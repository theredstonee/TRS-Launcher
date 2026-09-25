package dev.theredstonee.trsclient.core.online;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Freunde im Spiel (API.md §6): lädt die Liste nur, solange jemand sie braucht, und schickt Aktionen
 * (Anfrage, annehmen, ablehnen, zurückziehen, entfernen, blockieren, freigeben) – alles im API-Thread von
 * {@link TrsOnline}, nie im Render-Thread.
 *
 * <p>Abfrage-Takt wie im Launcher: 30 s, solange der Freunde-Bildschirm offen ist ({@link Interest#FOREGROUND}),
 * 90 s für Nebenanzeigen (Serverliste, Pausenmenü; {@link Interest#BACKGROUND}), sonst gar nicht. Ein
 * {@code 429} verschiebt die nächste Abfrage um {@code Retry-After}.
 */
public final class Friends {
	public static final long FOREGROUND_MS = 30_000L;
	public static final long BACKGROUND_MS = 90_000L;
	/** Frühestens so bald nach der letzten Abfrage erneut (auch bei „Aktualisieren“ oder nach Aktionen). */
	static final long MIN_GAP_MS = 2_000L;
	/** So lange gilt ein {@link #want}-Aufruf (Bildschirme melden sich jedes Bild). */
	static final long INTEREST_MS = 3_000L;
	/** Blockierliste höchstens so oft neu holen. */
	static final long BLOCKS_MS = 60_000L;
	/** Wartezeit nach einem Netzwerkfehler. */
	static final long ERROR_RETRY_MS = 15_000L;

	/** Wie dringend die Liste gebraucht wird. */
	public enum Interest {
		NONE, BACKGROUND, FOREGROUND
	}

	/** Aktionen mit Rückmeldung. */
	public enum Action {
		REQUEST, ACCEPT, DECLINE, CANCEL, REMOVE, BLOCK, UNBLOCK
	}

	/** Bekannte Fehlercodes der API mit eigener Meldung ({@code friends.error.<code>}). */
	static final Set<String> KNOWN_ERRORS = new HashSet<>(java.util.Arrays.asList(
			"player_not_found", "cannot_target_self", "blocked", "already_friends", "already_requested",
			"too_many_requests", "target_inbox_full", "friend_limit", "target_friend_limit", "request_not_found",
			"friend_not_found", "block_not_found", "rate_limited", "offline", "invalid_name", "busy"));

	/** Unveränderlicher Stand für die Oberfläche. */
	public static final class Snapshot {
		/** null = noch nie geladen. */
		public final FriendsView view;
		/** null = noch nicht geladen. */
		public final List<FriendsView.User> blocked;
		public final boolean loading;
		/** Fehler der letzten Abfrage ({@code friends.error.*}-Schlüssel) oder null. */
		public final String error;
		/** Laufende Aktion (Ziel-UUID bzw. Name) oder null. */
		public final String busy;
		/** Meldung der letzten Aktion (i18n-Schlüssel) oder null. */
		public final String message;
		public final Object[] args;
		public final boolean messageError;
		public final long messageAt;

		Snapshot(FriendsView view, List<FriendsView.User> blocked, boolean loading, String error, String busy,
				String message, Object[] args, boolean messageError, long messageAt) {
			this.view = view;
			this.blocked = blocked;
			this.loading = loading;
			this.error = error;
			this.busy = busy;
			this.message = message;
			this.args = args == null ? new Object[0] : args;
			this.messageError = messageError;
			this.messageAt = messageAt;
		}

		Snapshot with(FriendsView v, List<FriendsView.User> b, boolean l, String e) {
			return new Snapshot(v, b, l, e, busy, message, args, messageError, messageAt);
		}

		Snapshot busy(String target) {
			return new Snapshot(view, blocked, loading, error, target, message, args, messageError, messageAt);
		}

		Snapshot message(String key, Object[] a, boolean isError, long at) {
			return new Snapshot(view, blocked, loading, error, null, key, a, isError, at);
		}

		/** Offene eingehende Anfragen (0 = keine/unbekannt). */
		public int incoming() {
			return view == null ? 0 : view.incoming.size();
		}
	}

	static final Snapshot INITIAL = new Snapshot(null, null, false, null, null, null, null, false, 0);

	/** Anbindung an TrsOnline (API-Thread, Ergebnis-Warteschlange des Spiel-Threads, Neuanmeldung). */
	interface Backend {
		boolean submit(Runnable task);

		void post(Runnable onGameThread);

		void unauthorized(String rejectedToken);
	}

	private final TrsApi api;
	private final Backend backend;
	private volatile Snapshot snapshot = INITIAL;
	private volatile String token;

	// Aus dem Render-/Spiel-Thread (derselbe Thread in Minecraft):
	private volatile Interest interest = Interest.NONE;
	private volatile long interestUntil;
	private volatile boolean wantBlocks;
	private volatile boolean refreshSoon;

	// Nur Spiel-Thread:
	private long lastFetch = Long.MIN_VALUE / 2;
	private long lastBlocks = Long.MIN_VALUE / 2;
	private long notBefore;
	private boolean fetchInFlight;
	private int generation;

	Friends(TrsApi api, Backend backend) {
		this.api = api;
		this.backend = backend;
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	/** Die Liste wird gebraucht (jedes Bild bzw. jeden Tick melden; verfällt nach wenigen Sekunden). */
	public void want(Interest level, boolean blocks) {
		long now = System.currentTimeMillis();
		if (level == Interest.NONE) return;
		if (interest != Interest.FOREGROUND || now > interestUntil || level == Interest.FOREGROUND) interest = level;
		interestUntil = now + INTEREST_MS;
		if (blocks) wantBlocks = true;
	}

	/** Beim nächsten Tick neu laden (Knopf „Aktualisieren“, Bildschirm geöffnet). */
	public void refresh() {
		refreshSoon = true;
	}

	/** Konto gewechselt/abgemeldet: alles vergessen. */
	void reset() {
		generation++;
		snapshot = INITIAL;
		token = null;
		lastFetch = Long.MIN_VALUE / 2;
		lastBlocks = Long.MIN_VALUE / 2;
		notBefore = 0;
		fetchInFlight = false;
		wantBlocks = false;
	}

	/** Aus {@link TrsOnline#tick}, nur wenn angemeldet. */
	void tick(long now, String currentToken) {
		token = currentToken;
		if (currentToken == null || fetchInFlight) return;
		Interest level = now <= interestUntil ? interest : Interest.NONE;
		if (level == Interest.NONE && !refreshSoon) {
			wantBlocks = false;
			return;
		}
		if (now < notBefore) return;
		long gap = now - lastFetch;
		long period = level == Interest.FOREGROUND ? FOREGROUND_MS : BACKGROUND_MS;
		boolean due = refreshSoon || snapshot.view == null ? gap >= MIN_GAP_MS : gap >= period;
		if (!due) return;
		boolean blocks = wantBlocks && (snapshot.blocked == null || refreshSoon || now - lastBlocks >= BLOCKS_MS);
		fetch(now, currentToken, blocks);
	}

	private void fetch(long now, final String t, final boolean blocks) {
		refreshSoon = false;
		fetchInFlight = true;
		lastFetch = now;
		if (blocks) lastBlocks = now;
		final int gen = generation;
		snapshot = snapshot.with(snapshot.view, snapshot.blocked, true, snapshot.error);
		if (!backend.submit(new Runnable() {
			@Override
			public void run() {
				try {
					final FriendsView view = api.friends(t);
					final List<FriendsView.User> blocked = blocks ? api.blocks(t) : null;
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen != generation) return;
							fetchInFlight = false;
							Snapshot s = snapshot;
							snapshot = s.with(view, blocked != null ? Collections.unmodifiableList(blocked) : s.blocked, false, null);
						}
					});
				} catch (final ApiException e) {
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen != generation) return;
							fetchInFlight = false;
							if (e.unauthorized()) backend.unauthorized(t);
							if (e.rateLimited()) notBefore = System.currentTimeMillis() + Math.max(5_000L, e.retryAfterMs());
							else notBefore = System.currentTimeMillis() + ERROR_RETRY_MS;
							Snapshot s = snapshot;
							snapshot = s.with(s.view, s.blocked, false, errorKey(e.code(), e.status()));
						}
					});
				} catch (IOException | RuntimeException e) {
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen != generation) return;
							fetchInFlight = false;
							notBefore = System.currentTimeMillis() + ERROR_RETRY_MS;
							Snapshot s = snapshot;
							snapshot = s.with(s.view, s.blocked, false, "friends.error.offline");
						}
					});
				}
			}
		})) {
			fetchInFlight = false;
			snapshot = snapshot.with(snapshot.view, snapshot.blocked, false, snapshot.error);
		}
	}

	/**
	 * Aktion ausführen (Spiel-Thread). {@code arg} = UUID bzw. bei {@link Action#REQUEST}/{@link Action#BLOCK} Name
	 * oder UUID; {@code name} nur für die Meldung. false = läuft schon etwas / ungültig / offline (Meldung gesetzt).
	 */
	public boolean act(final Action action, String arg, final String name) {
		long now = System.currentTimeMillis();
		final String t = token;
		if (snapshot.busy != null) return false;
		final String target = action == Action.REQUEST || action == Action.BLOCK ? FriendsView.target(arg) : Uuids.normalize(arg);
		if (target == null) {
			snapshot = snapshot.message("friends.error.invalid_name", null, true, now);
			return false;
		}
		if (t == null) {
			snapshot = snapshot.message("friends.error.offline", null, true, now);
			return false;
		}
		final String shown = name == null || name.isEmpty() ? target : name;
		final int gen = generation;
		snapshot = snapshot.busy(target);
		if (!backend.submit(new Runnable() {
			@Override
			public void run() {
				String key;
				try {
					key = perform(action, t, target);
				} catch (final ApiException e) {
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen != generation) return;
							if (e.unauthorized()) backend.unauthorized(t);
							snapshot = snapshot.message(errorKey(e.code(), e.status()), new Object[]{shown}, true,
									System.currentTimeMillis());
						}
					});
					return;
				} catch (IOException | RuntimeException e) {
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen != generation) return;
							snapshot = snapshot.message("friends.error.offline", null, true, System.currentTimeMillis());
						}
					});
					return;
				}
				final String done = key;
				backend.post(new Runnable() {
					@Override
					public void run() {
						if (gen != generation) return;
						snapshot = snapshot.message(done, new Object[]{shown}, false, System.currentTimeMillis());
						if (action == Action.BLOCK || action == Action.UNBLOCK) wantBlocks = true;
						lastBlocks = Long.MIN_VALUE / 2;
						refreshSoon = true;
					}
				});
			}
		})) {
			snapshot = snapshot.message("friends.error.busy", null, true, now);
			return false;
		}
		return true;
	}

	/** Blockierend im API-Thread; Rückgabe = Erfolgsmeldung. */
	String perform(Action action, String t, String target) throws IOException, ApiException {
		switch (action) {
			case REQUEST:
				return "accepted".equals(api.requestFriend(t, target)) ? "friends.msg.nowFriends" : "friends.msg.sent";
			case ACCEPT:
				api.acceptFriend(t, target);
				return "friends.msg.nowFriends";
			case DECLINE:
				api.declineFriend(t, target);
				return "friends.msg.declined";
			case CANCEL:
				api.cancelRequest(t, target);
				return "friends.msg.cancelled";
			case REMOVE:
				api.removeFriend(t, target);
				return "friends.msg.removed";
			case BLOCK:
				api.block(t, target);
				return "friends.msg.blocked";
			case UNBLOCK:
				api.unblock(t, target);
				return "friends.msg.unblocked";
			default:
				throw new IllegalArgumentException(String.valueOf(action));
		}
	}

	/** API-Fehlercode → Meldungsschlüssel. */
	static String errorKey(String code, int status) {
		if (status == 429) return "friends.error.rate_limited";
		if (code != null && KNOWN_ERRORS.contains(code)) return "friends.error." + code;
		return "friends.error.generic";
	}
}

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
 * <p>Dazu Umhänge mit Freunden teilen (API.md §5.10): Angebote an mich kommen mit der Freundesliste (nur wenn
 * {@code GET /v1/friends} welche meldet), annehmen/ablehnen, eigenen Umhang anbieten, Inhaber laden und entziehen.
 *
 * <p>Abfrage-Takt wie im Launcher: 30 s, solange der Freunde-Bildschirm offen ist ({@link Interest#FOREGROUND}),
 * 90 s für Nebenanzeigen (Serverliste, Pausenmenü, Garderobe; {@link Interest#BACKGROUND}), sonst gar nicht. Ein
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
			"friend_not_found", "block_not_found", "rate_limited", "offline", "invalid_name", "busy",
			// Umhänge teilen
			"cape_not_found", "cape_not_approved", "cape_not_shareable", "already_shared", "share_limit",
			"offer_inbox_full", "offer_not_found", "holder_not_found"));

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
		/** Zuletzt geladene Inhaber eines Umhangs ({@link #loadHolders}) oder null. */
		public final CapeShare.Holders holders;
		/** Umhang-Angebote, die noch niemand angesehen hat ({@link #markOffersSeen}). */
		public final int unseenOffers;
		/** Zählt Meldungen „neues Angebot“ (nicht beim ersten Laden) – Oberflächen zeigen sie einmal. */
		public final int offerNotice;
		/** Anbieter und Umhang des neuesten Angebots für {@link #offerNotice}. */
		public final Object[] offerNoticeArgs;
		/** Zählt Änderungen an der eigenen Umhang-Sammlung (angenommen, zurückgegeben) – die Garderobe lädt neu. */
		public final int capesChanged;

		Snapshot(FriendsView view, List<FriendsView.User> blocked, boolean loading, String error, String busy,
				String message, Object[] args, boolean messageError, long messageAt, CapeShare.Holders holders,
				int unseenOffers, int offerNotice, Object[] offerNoticeArgs, int capesChanged) {
			this.view = view;
			this.blocked = blocked;
			this.loading = loading;
			this.error = error;
			this.busy = busy;
			this.message = message;
			this.args = args == null ? new Object[0] : args;
			this.messageError = messageError;
			this.messageAt = messageAt;
			this.holders = holders;
			this.unseenOffers = unseenOffers;
			this.offerNotice = offerNotice;
			this.offerNoticeArgs = offerNoticeArgs == null ? new Object[0] : offerNoticeArgs;
			this.capesChanged = capesChanged;
		}

		Snapshot with(FriendsView v, List<FriendsView.User> b, boolean l, String e) {
			return new Snapshot(v, b, l, e, busy, message, args, messageError, messageAt, holders, unseenOffers,
					offerNotice, offerNoticeArgs, capesChanged);
		}

		Snapshot busy(String target) {
			return new Snapshot(view, blocked, loading, error, target, message, args, messageError, messageAt, holders,
					unseenOffers, offerNotice, offerNoticeArgs, capesChanged);
		}

		Snapshot message(String key, Object[] a, boolean isError, long at) {
			return new Snapshot(view, blocked, loading, error, null, key, a, isError, at, holders, unseenOffers,
					offerNotice, offerNoticeArgs, capesChanged);
		}

		Snapshot holders(CapeShare.Holders h) {
			return new Snapshot(view, blocked, loading, error, busy, message, args, messageError, messageAt, h,
					unseenOffers, offerNotice, offerNoticeArgs, capesChanged);
		}

		Snapshot offers(int unseen, int notice, Object[] noticeArgs) {
			return new Snapshot(view, blocked, loading, error, busy, message, args, messageError, messageAt, holders,
					unseen, notice, noticeArgs, capesChanged);
		}

		Snapshot capesChanged() {
			return new Snapshot(view, blocked, loading, error, busy, message, args, messageError, messageAt, holders,
					unseenOffers, offerNotice, offerNoticeArgs, capesChanged + 1);
		}

		/** Was auf mich wartet: offene Freundschaftsanfragen + Umhang-Angebote (0 = nichts/unbekannt). */
		public int incoming() {
			return requests() + offers();
		}

		/** Offene Freundschaftsanfragen an mich. */
		public int requests() {
			return view == null ? 0 : view.incoming.size();
		}

		/** Offene Umhang-Angebote an mich. */
		public int offers() {
			return view == null ? 0 : view.offerCount;
		}

		/** Angebote aus der Liste (leer, solange nicht geladen). */
		public List<CapeShare.Offer> offerList() {
			return view == null ? Collections.<CapeShare.Offer>emptyList() : view.offers;
		}

		/** Inhaber genau dieses Umhangs (sonst null – noch nicht geladen oder ein anderer). */
		public CapeShare.Holders holdersOf(String capeId) {
			return holders != null && holders.capeId.equals(capeId) ? holders : null;
		}
	}

	static final Snapshot INITIAL = new Snapshot(null, null, false, null, null, null, null, false, 0, null, 0, 0, null, 0);

	/** Anbindung an TrsOnline (API-Thread, Ergebnis-Warteschlange des Spiel-Threads, Neuanmeldung). */
	interface Backend {
		boolean submit(Runnable task);

		void post(Runnable onGameThread);

		void unauthorized(String rejectedToken);
	}

	/** Eine Aktion im API-Thread; Rückgabe = Erfolgsmeldung (i18n-Schlüssel). */
	interface Job {
		String run(String token) throws IOException, ApiException;
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
	/** Angebote der letzten Abfrage (null = noch nie geladen → kein „neu“-Hinweis beim ersten Mal). */
	private Set<String> knownOffers;
	/** Schon angesehene Angebote (nur diese Sitzung). */
	private final Set<String> seenOffers = new HashSet<>();

	Friends(TrsApi api, Backend backend) {
		this.api = api;
		this.backend = backend;
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	/** Die Liste wird gebraucht (jedes Bild bzw. jeden Tick melden; verfällt nach wenigen Sekunden). */
	public void want(Interest level, boolean blocks) {
		want(level, blocks, System.currentTimeMillis());
	}

	void want(Interest level, boolean blocks, long now) {
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
		knownOffers = null;
		seenOffers.clear();
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
		boolean missing = snapshot.view == null || (wantBlocks && snapshot.blocked == null);
		boolean due = refreshSoon || missing ? gap >= MIN_GAP_MS : gap >= period;
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
		final List<CapeShare.Offer> previousOffers = snapshot.offerList();
		snapshot = snapshot.with(snapshot.view, snapshot.blocked, true, snapshot.error);
		if (!backend.submit(new Runnable() {
			@Override
			public void run() {
				try {
					FriendsView loaded = api.friends(t);
					// Angebote nur holen, wenn es welche gibt (eine Anfrage mehr – nur solange etwas offen ist).
					if (loaded.offerCount > 0) {
						List<CapeShare.Offer> offers;
						try {
							offers = api.capeOffers(t);
						} catch (ApiException | IOException | RuntimeException e) {
							offers = previousOffers;
						}
						loaded = offers.isEmpty() ? loaded : loaded.withOffers(offers);
					}
					final FriendsView view = loaded;
					final List<FriendsView.User> blocked = blocks ? api.blocks(t) : null;
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen != generation) return;
							fetchInFlight = false;
							Snapshot s = snapshot;
							snapshot = s.with(view, blocked != null ? Collections.unmodifiableList(blocked) : s.blocked, false, null);
							offersLoaded(view.offers);
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

	/** Spiel-Thread: neue Angebote zählen/melden (beim ersten Laden nur zählen, keine Meldung). */
	private void offersLoaded(List<CapeShare.Offer> offers) {
		Set<String> keys = new HashSet<>();
		CapeShare.Offer fresh = null;
		int unseen = 0;
		for (CapeShare.Offer o : offers) {
			keys.add(o.key());
			if (knownOffers != null && !knownOffers.contains(o.key()) && fresh == null) fresh = o;
			if (!seenOffers.contains(o.key())) unseen++;
		}
		seenOffers.retainAll(keys);
		knownOffers = keys;
		Snapshot s = snapshot;
		int notice = s.offerNotice;
		Object[] args = s.offerNoticeArgs;
		if (fresh != null) {
			notice++;
			args = new Object[]{fresh.fromName, fresh.capeName};
		}
		snapshot = s.offers(unseen, notice, args);
	}

	/** Die Angebote wurden angezeigt (Freunde → Anfragen, Garderobe → Umhänge): „neu“ fällt weg. */
	public void markOffersSeen() {
		Snapshot s = snapshot;
		if (s.unseenOffers == 0) return;
		for (CapeShare.Offer o : s.offerList()) seenOffers.add(o.key());
		snapshot = s.offers(0, s.offerNotice, s.offerNoticeArgs);
	}

	/** Ist dieses Angebot noch ungesehen? */
	public boolean unseen(CapeShare.Offer offer) {
		return !seenOffers.contains(offer.key());
	}

	/**
	 * Aktion ausführen (Spiel-Thread). {@code arg} = UUID bzw. bei {@link Action#REQUEST}/{@link Action#BLOCK} Name
	 * oder UUID; {@code name} nur für die Meldung. false = läuft schon etwas / ungültig / offline (Meldung gesetzt).
	 */
	public boolean act(final Action action, String arg, final String name) {
		final String target = action == Action.REQUEST || action == Action.BLOCK ? FriendsView.target(arg) : Uuids.normalize(arg);
		if (target == null) {
			if (snapshot.busy != null) return false;
			snapshot = snapshot.message("friends.error.invalid_name", null, true, System.currentTimeMillis());
			return false;
		}
		final String shown = name == null || name.isEmpty() ? target : name;
		return submit(target, new Object[]{shown}, new Job() {
			@Override
			public String run(String t) throws IOException, ApiException {
				return perform(action, t, target);
			}
		}, new Runnable() {
			@Override
			public void run() {
				if (action == Action.BLOCK || action == Action.UNBLOCK) wantBlocks = true;
				lastBlocks = Long.MIN_VALUE / 2;
			}
		});
	}

	// --- Umhänge teilen (API.md §5.10) ---

	/** Angebot annehmen: der Umhang kommt in die eigene Sammlung (Garderobe lädt neu). */
	public boolean acceptOffer(final CapeShare.Offer offer) {
		return submit("cape:" + offer.capeId, new Object[]{offer.capeName, offer.fromName}, new Job() {
			@Override
			public String run(String t) throws IOException, ApiException {
				api.acceptCapeOffer(t, offer.capeId);
				return "friends.msg.capeAccepted";
			}
		}, new Runnable() {
			@Override
			public void run() {
				snapshot = snapshot.capesChanged();
			}
		});
	}

	/** Angebot ablehnen (der Anbieter erfährt es nicht). */
	public boolean declineOffer(final CapeShare.Offer offer) {
		return submit("cape:" + offer.capeId, new Object[]{offer.capeName, offer.fromName}, new Job() {
			@Override
			public String run(String t) throws IOException, ApiException {
				api.declineCapeOffer(t, offer.capeId);
				return "friends.msg.capeDeclined";
			}
		}, null);
	}

	/** Eigenen (freigegebenen) oder angenommenen geteilten Umhang einem Freund anbieten; lädt danach die Inhaber. */
	public boolean offerCape(final String capeId, String capeName, String friendUuid, String friendName) {
		final String friend = Uuids.normalize(friendUuid);
		if (!CapeShare.validCapeId(capeId) || friend == null) return false;
		return submit("share:" + friend, new Object[]{friendName, capeName}, new Job() {
			@Override
			public String run(String t) throws IOException, ApiException {
				api.offerCape(t, capeId, friend);
				return "friends.msg.capeOffered";
			}
		}, new Runnable() {
			@Override
			public void run() {
				loadHolders(capeId);
			}
		});
	}

	/**
	 * Inhaber {@code holderUuid} den Umhang entziehen bzw. das Angebot zurückziehen (samt allem, was dieser Spieler
	 * weitergegeben hat). Mit der eigenen UUID ({@code self}) = geteilten Umhang zurückgeben.
	 */
	public boolean revokeShare(final String capeId, String holderUuid, String holderName, final boolean offered,
			final boolean self) {
		final String holder = Uuids.normalize(holderUuid);
		if (!CapeShare.validCapeId(capeId) || holder == null) return false;
		return submit("holder:" + holder, new Object[]{holderName}, new Job() {
			@Override
			public String run(String t) throws IOException, ApiException {
				api.revokeCapeShare(t, capeId, holder);
				if (self) return "friends.msg.capeGivenBack";
				return offered ? "friends.msg.capeWithdrawn" : "friends.msg.capeRevoked";
			}
		}, new Runnable() {
			@Override
			public void run() {
				if (self) snapshot = snapshot.capesChanged();
				else loadHolders(capeId);
			}
		});
	}

	/** Inhaber eines Umhangs laden ({@link Snapshot#holdersOf}); Fehler landen als Meldung. */
	public void loadHolders(final String capeId) {
		final String t = token;
		if (t == null || !CapeShare.validCapeId(capeId)) return;
		final int gen = generation;
		backend.submit(new Runnable() {
			@Override
			public void run() {
				try {
					final CapeShare.Holders h = api.capeHolders(t, capeId);
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen == generation) snapshot = snapshot.holders(h);
						}
					});
				} catch (final ApiException e) {
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen != generation) return;
							if (e.unauthorized()) backend.unauthorized(t);
							Snapshot prev = snapshot;
							Snapshot s = prev.message(errorKey(e.code(), e.status()), null, true, System.currentTimeMillis());
							snapshot = prev.busy == null ? s : s.busy(prev.busy);
						}
					});
				} catch (IOException | RuntimeException e) {
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen == generation) {
								snapshot = snapshot.message("friends.error.offline", null, true, System.currentTimeMillis());
							}
						}
					});
				}
			}
		});
	}

	// --- Ausführen ---

	/** Eine Aktion zur Zeit: im API-Thread ausführen, Meldung setzen, Liste neu laden, dann {@code after}. */
	private boolean submit(final String busyKey, final Object[] args, final Job job, final Runnable after) {
		long now = System.currentTimeMillis();
		final String t = token;
		if (snapshot.busy != null) return false;
		if (t == null) {
			snapshot = snapshot.message("friends.error.offline", null, true, now);
			return false;
		}
		final int gen = generation;
		snapshot = snapshot.busy(busyKey);
		if (!backend.submit(new Runnable() {
			@Override
			public void run() {
				String key;
				try {
					key = job.run(t);
				} catch (final ApiException e) {
					backend.post(new Runnable() {
						@Override
						public void run() {
							if (gen != generation) return;
							if (e.unauthorized()) backend.unauthorized(t);
							snapshot = snapshot.message(errorKey(e.code(), e.status()), args, true, System.currentTimeMillis());
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
						snapshot = snapshot.message(done, args, false, System.currentTimeMillis());
						refreshSoon = true;
						if (after != null) after.run();
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

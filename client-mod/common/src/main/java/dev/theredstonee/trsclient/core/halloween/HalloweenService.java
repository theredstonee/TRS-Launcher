package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsOnline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Halloween im Konto (API.md §36): Stand der Kürbis-Suche mit Rätsel, „Süßes oder Saures“ (einmal am Tag) und welche
 * eigenen Spuk-Effekte andere sehen dürfen. Lädt lazy (sobald ein Kürbis oder die Halloween-Seite gezeichnet wird),
 * folgt {@code halloween_updated} aus dem Ereignis-Stream und meldet Funde/Ergebnisse als {@link HalloweenToast}.
 *
 * <p>Alle Netzaufrufe laufen in einem eigenen Hintergrund-Thread; die Oberfläche liest nur die {@code volatile}-Felder.
 * Ohne TRS-Anmeldung (Offline-Konto, 1.7.10/1.13.2) bleibt alles leer – dann zeigt der Client keine Kürbisse.
 */
public final class HalloweenService {
	private static final HalloweenService INSTANCE = new HalloweenService();
	/** Zustand höchstens so alt (danach neu holen, wenn jemand hinsieht). */
	static final long MAX_AGE_MS = 10 * 60_000L;
	/** Nach einem Fehler so lange warten. */
	static final long RETRY_MS = 60_000L;
	/** „Klopf, klopf …“ dauert mindestens so lange (Eimer wackelt). */
	public static final long KNOCK_MS = 900L;

	/** Phase von „Süßes oder Saures“ in der Oberfläche. */
	public enum TrickPhase {
		/** Noch nicht geklopft (oder Zustand unbekannt). */
		IDLE,
		/** Anfrage läuft, Eimer wackelt. */
		KNOCKING,
		/** Ergebnis da ({@link #trickResult()}). */
		DONE,
		/** Heute schon geklopft – nächster Versuch ab {@link #trickNext()}. */
		USED,
		/** Fehler ({@link #trickError()} = i18n-Schlüssel). */
		ERROR
	}

	/** Ergebnis von „Süßes oder Saures“ für die Anzeige. */
	public static final class TrickOutcome {
		public final boolean sweet;
		/** Bei Süßem: Art ({@code cape}/{@code emote}/{@code sticker}), ID und Name (Sprache des Spiels). */
		public final String kind;
		public final String id;
		public final String name;

		TrickOutcome(boolean sweet, String kind, String id, String name) {
			this.sweet = sweet;
			this.kind = kind;
			this.id = id;
			this.name = name;
		}
	}

	private volatile Http http;
	private ThreadPoolExecutor worker;
	private volatile HalloweenApi.State state;
	private volatile long stateAt;
	private volatile long retryAt;
	private volatile boolean loading;
	/** Gerade gemeldete, noch nicht bestätigte Funde (werden schon nicht mehr gezeichnet). */
	private final Set<String> pending = Collections.synchronizedSet(new LinkedHashSet<String>());
	private volatile TrickPhase trickPhase = TrickPhase.IDLE;
	private volatile TrickOutcome trickResult;
	private volatile String trickNext;
	private volatile String trickError;
	private volatile long trickStarted;
	/** Steigt mit jeder Änderung des Zustands (Oberflächen bauen damit neu). */
	private volatile int revision;
	/** Flügel-Wahl: läuft gerade, Fehler (i18n-Schlüssel) oder null. */
	private volatile boolean wingsBusy;
	private volatile String wingsError;

	private HalloweenService() {
	}

	public static HalloweenService get() {
		return INSTANCE;
	}

	/** HTTP-Client (setzt {@code TrsOnline.create}; Tests setzen eine Attrappe). */
	public void http(Http http) {
		this.http = http;
	}

	// --- Lesen (Oberfläche) ---

	/** Letzter bekannter Zustand oder null. */
	public HalloweenApi.State state() {
		return state;
	}

	public int revision() {
		return revision;
	}

	/** Gefundene Kürbisse (leer, solange unbekannt). */
	public List<String> found() {
		HalloweenApi.State s = state;
		if (s == null || s.pumpkins == null || s.pumpkins.found == null) return Collections.emptyList();
		return Collections.unmodifiableList(s.pumpkins.found);
	}

	/** Anzahl gefundener Kürbisse (0..13). */
	public int foundCount() {
		int n = 0;
		for (String id : found()) if (Pumpkins.valid(id)) n++;
		return Math.min(Pumpkins.TOTAL, n);
	}

	/** Gesamtzahl (immer 13, außer die API sagt anderes). */
	public int total() {
		HalloweenApi.State s = state;
		Integer t = s == null || s.pumpkins == null ? null : s.pumpkins.total;
		return t == null || t < 1 || t > 99 ? Pumpkins.TOTAL : t;
	}

	/** Rätsel zum nächsten Kürbis in der Sprache des Spiels oder null (alle gefunden / unbekannt). */
	public String nextHint() {
		HalloweenApi.State s = state;
		if (s == null || s.pumpkins == null) return null;
		return HalloweenApi.name(s.pumpkins.nextHint, I18n.code(), null);
	}

	/** Laufen die Event-Funktionen (Suche, Süßes oder Saures) noch? (laut API; unbekannt = ja) */
	public boolean featuresActive() {
		HalloweenApi.State s = state;
		return s == null || s.active == null || s.active;
	}

	/** Ist der Kürbis {@code id} gerade zu zeigen (Zustand bekannt, nicht gefunden, nicht unterwegs)? */
	public boolean pumpkinVisible(String id) {
		HalloweenApi.State s = state;
		if (s == null || !featuresActive()) return false;
		if (pending.contains(id)) return false;
		return !found().contains(id);
	}

	/** Profil-Abzeichen des eigenen Kontos ({@code halloween2026}, {@code pumpkinhunter2026}). */
	public List<String> badges() {
		HalloweenApi.State s = state;
		return s == null || s.badges == null ? Collections.<String>emptyList() : Collections.unmodifiableList(s.badges);
	}

	public TrickPhase trickPhase() {
		TrickPhase p = trickPhase;
		if (p == TrickPhase.IDLE) {
			HalloweenApi.State s = state;
			if (s != null && s.trick != null && Boolean.TRUE.equals(s.trick.usedToday)) return TrickPhase.USED;
		}
		return p;
	}

	/** Zeit seit dem Anklopfen (Animation), sonst -1. */
	public long knockAge(long now) {
		return trickStarted == 0 ? -1 : now - trickStarted;
	}

	public TrickOutcome trickResult() {
		return trickResult;
	}

	/** Nächster Versuch (ISO) oder null. */
	public String trickNext() {
		String n = trickNext;
		if (n != null) return n;
		HalloweenApi.State s = state;
		return s == null || s.trick == null ? null : s.trick.next;
	}

	/** Ergebnis von heute aus dem Zustand (falls schon auf einem anderen Gerät geklopft). */
	public TrickOutcome todaysResult() {
		TrickOutcome own = trickResult;
		if (own != null) return own;
		HalloweenApi.State s = state;
		if (s == null || s.trick == null || !Boolean.TRUE.equals(s.trick.usedToday) || s.trick.result == null) return null;
		return outcome(s.trick.result, s.trick.reward);
	}

	public String trickError() {
		return trickError;
	}

	// --- Laden ---

	/** Zustand holen, falls er fehlt oder alt ist (billig – jedes Bild aufrufbar). */
	public void ensure() {
		if (!Halloween.active() || loading) return;
		long now = System.currentTimeMillis();
		if (now < retryAt) return;
		if (state != null && now - stateAt < MAX_AGE_MS) return;
		refresh();
	}

	/** Zustand jetzt neu holen (Seite geöffnet). */
	public void refresh() {
		final String token = token();
		final HalloweenApi api = api();
		if (token == null || api == null || loading) return;
		loading = true;
		if (!submit(new Runnable() {
			@Override
			public void run() {
				try {
					HalloweenApi.State s = api.state(token);
					if (s != null) setState(s);
					retryAt = 0;
				} catch (ApiException e) {
					retryAt = System.currentTimeMillis() + Math.max(RETRY_MS, e.retryAfterMs());
					if (e.unauthorized()) rejected(token);
				} catch (IOException | RuntimeException e) {
					retryAt = System.currentTimeMillis() + RETRY_MS;
				} finally {
					loading = false;
				}
			}
		})) loading = false;
	}

	/** {@code halloween_updated} (SSE): neuer Zustand (null/kaputt = einfach neu holen). */
	public void fromEvent(String json) {
		HalloweenApi.State s = HalloweenApi.parseState(json);
		if (s != null) {
			setState(s);
		} else {
			stateAt = 0;
			retryAt = 0;
		}
	}

	private void setState(HalloweenApi.State s) {
		state = s;
		stateAt = System.currentTimeMillis();
		if (s.pumpkins != null && s.pumpkins.found != null) {
			synchronized (pending) {
				pending.removeAll(s.pumpkins.found);
			}
		}
		revision++;
	}

	// --- Aktionen ---

	/** Kürbis gefunden (Klick): sofort ausblenden, beim Server melden, Toast mit „n/13“. */
	public void findPumpkin(final String id) {
		if (!Pumpkins.valid(id) || pending.contains(id)) return;
		final String token = token();
		final HalloweenApi api = api();
		if (token == null || api == null) return;
		pending.add(id);
		revision++;
		if (!submit(new Runnable() {
			@Override
			public void run() {
				try {
					HalloweenApi.FindResult r = api.find(token, id);
					applyFind(id, r);
				} catch (ApiException e) {
					pending.remove(id);
					revision++;
					if (e.unauthorized()) rejected(token);
					if ("event_inactive".equals(e.code())) {
						HalloweenToast.show(HalloweenToast.PUMPKIN, I18n.tr("halloween.toast.over.title"), I18n.tr("halloween.toast.over.text"));
					} else if (!e.rateLimited()) {
						HalloweenToast.show(HalloweenToast.PUMPKIN, I18n.tr("halloween.toast.error.title"), I18n.tr("halloween.error.network"));
					}
				} catch (IOException | RuntimeException e) {
					pending.remove(id);
					revision++;
					HalloweenToast.show(HalloweenToast.PUMPKIN, I18n.tr("halloween.toast.error.title"), I18n.tr("halloween.error.network"));
				}
			}
		})) pending.remove(id);
	}

	void applyFind(String id, HalloweenApi.FindResult r) {
		HalloweenApi.State s = state;
		HalloweenApi.State next = copy(s);
		if (next.pumpkins == null) next.pumpkins = new HalloweenApi.Pumpkins();
		List<String> found = r != null && r.found != null ? new ArrayList<String>(r.found)
				: new ArrayList<String>(next.pumpkins.found == null ? Collections.<String>emptyList() : next.pumpkins.found);
		if (!found.contains(id)) found.add(id);
		next.pumpkins.found = found;
		int count = r != null && r.count != null ? r.count : countValid(found);
		boolean complete = r != null && Boolean.TRUE.equals(r.complete);
		setState(next);
		pending.remove(id);
		if (complete) {
			HalloweenToast.show(HalloweenToast.HUNTER, I18n.tr("halloween.toast.found.title"), I18n.tr("halloween.toast.complete"));
		} else {
			HalloweenToast.show(HalloweenToast.PUMPKIN, I18n.tr("halloween.toast.found.title"),
					I18n.tr("halloween.toast.found.text", count, total()));
		}
		// Rätsel zum nächsten Kürbis kommt mit dem neuen Zustand.
		stateAt = 0;
		retryAt = 0;
	}

	/** „Anklopfen“: einmal am Tag; Ergebnis nach mindestens {@link #KNOCK_MS}. */
	public void trick() {
		if (trickPhase == TrickPhase.KNOCKING) return;
		final String token = token();
		final HalloweenApi api = api();
		if (token == null || api == null) {
			trickPhase = TrickPhase.ERROR;
			trickError = "halloween.error.offline";
			return;
		}
		final long started = System.currentTimeMillis();
		trickStarted = started;
		trickPhase = TrickPhase.KNOCKING;
		trickError = null;
		revision++;
		if (!submit(new Runnable() {
			@Override
			public void run() {
				TrickPhase phase;
				try {
					HalloweenApi.TrickResult r = api.trick(token);
					trickResult = outcome(r == null ? null : r.result, r == null ? null : r.reward);
					// Neuer Sticker: Liste der eigenen Sticker gleich neu holen.
					if (trickResult.sweet && "sticker".equals(trickResult.kind)) {
						dev.theredstonee.trsclient.core.social.Stickers s = dev.theredstonee.trsclient.core.social.Stickers.get();
						if (s != null) s.invalidate();
					}
					trickNext = r == null ? null : r.next;
					phase = TrickPhase.DONE;
				} catch (HalloweenApi.TrickUsed e) {
					trickNext = e.next;
					phase = TrickPhase.USED;
				} catch (ApiException e) {
					if (e.unauthorized()) rejected(token);
					trickError = "event_inactive".equals(e.code()) ? "halloween.toast.over.text" : "halloween.error.network";
					phase = TrickPhase.ERROR;
				} catch (IOException | RuntimeException e) {
					trickError = "halloween.error.network";
					phase = TrickPhase.ERROR;
				}
				long left = KNOCK_MS - (System.currentTimeMillis() - started);
				if (left > 0) {
					try {
						Thread.sleep(left);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
				}
				trickPhase = phase;
				revision++;
				stateAt = 0;
				retryAt = 0;
			}
		})) {
			trickPhase = TrickPhase.ERROR;
			trickError = "halloween.error.network";
		}
	}

	/** Eigene Spuk-Effekte für andere sichtbar ({@code PUT /v1/me/halloween/effects}). */
	// --- Flügel-Wahl (Nachtrag „Wings“) ---

	/** Gewählte Halloween-Flügel (ID) oder null. */
	public String wingsChoice() {
		HalloweenApi.State s = state;
		return s == null || s.wings == null ? null : s.wings.choice;
	}

	/** Darf jetzt gewählt werden (alle 13 Kürbisse, noch keine Wahl)? */
	public boolean canChooseWings() {
		HalloweenApi.State s = state;
		return s != null && s.wings != null && Boolean.TRUE.equals(s.wings.canChoose) && s.wings.choice == null;
	}

	/** Die vier Halloween-Flügel (IDs) laut API; leer, solange unbekannt. */
	public List<String> wingsOptions() {
		HalloweenApi.State s = state;
		if (s == null || s.wings == null || s.wings.options == null) return Collections.emptyList();
		return Collections.unmodifiableList(s.wings.options);
	}

	public boolean wingsBusy() {
		return wingsBusy;
	}

	/** Fehler der letzten Wahl (i18n-Schlüssel {@code halloween.wings.error.*}) oder null. */
	public String wingsError() {
		return wingsError;
	}

	/** Flügel endgültig wählen ({@code POST /v1/me/halloween/wings}); Erfolg → Toast, neuer Zustand. */
	public void chooseWings(final String id) {
		if (wingsBusy) return;
		final String token = token();
		final HalloweenApi api = api();
		if (token == null || api == null) {
			wingsError = "halloween.error.offline";
			return;
		}
		wingsBusy = true;
		wingsError = null;
		revision++;
		if (!submit(new Runnable() {
			@Override
			public void run() {
				try {
					HalloweenApi.State s = api.chooseWings(token, id);
					if (s != null) {
						setState(s);
					} else {
						HalloweenApi.State next = copy(state);
						if (next.wings == null) next.wings = new HalloweenApi.Wings();
						next.wings.choice = id;
						next.wings.canChoose = Boolean.FALSE;
						setState(next);
					}
					HalloweenToast.show(HalloweenToast.HUNTER, I18n.tr("halloween.wings.toast.title"), I18n.tr("halloween.wings.toast.text"));
					stateAt = 0;
					retryAt = 0;
				} catch (HalloweenApi.WingsRefused e) {
					wingsError = "wings_chosen".equals(e.code) ? "halloween.wings.error.chosen"
							: "hunt_incomplete".equals(e.code) ? "halloween.wings.error.incomplete" : "halloween.error.network";
					stateAt = 0;
					retryAt = 0;
				} catch (ApiException e) {
					if (e.unauthorized()) rejected(token);
					wingsError = "event_inactive".equals(e.code()) ? "halloween.toast.over.text" : "halloween.error.network";
				} catch (IOException | RuntimeException e) {
					wingsError = "halloween.error.network";
				} finally {
					wingsBusy = false;
					revision++;
				}
			}
		})) {
			wingsBusy = false;
			wingsError = "halloween.error.network";
		}
	}

	public void shareEffects(final boolean hits, final boolean trail) {
		final String token = token();
		final HalloweenApi api = api();
		if (token == null || api == null) return;
		submit(new Runnable() {
			@Override
			public void run() {
				try {
					api.effects(token, hits, trail);
				} catch (ApiException e) {
					if (e.unauthorized()) rejected(token);
				} catch (IOException | RuntimeException ignored) {
					// beim nächsten Umschalten erneut
				}
			}
		});
	}

	// --- Hilfen ---

	static TrickOutcome outcome(String result, HalloweenApi.Reward reward) {
		if (!"sweet".equals(result) || reward == null) return new TrickOutcome(false, null, null, null);
		String fallback = reward.id == null ? "?" : reward.id;
		return new TrickOutcome(true, reward.kind, reward.id, HalloweenApi.name(reward.name, I18n.code(), fallback));
	}

	private static int countValid(List<String> found) {
		int n = 0;
		for (String id : found) if (Pumpkins.valid(id)) n++;
		return n;
	}

	private static HalloweenApi.State copy(HalloweenApi.State s) {
		HalloweenApi.State n = new HalloweenApi.State();
		if (s == null) return n;
		n.active = s.active;
		n.endsAt = s.endsAt;
		n.trick = s.trick;
		n.badges = s.badges;
		n.effects = s.effects;
		n.wings = s.wings;
		if (s.pumpkins != null) {
			n.pumpkins = new HalloweenApi.Pumpkins();
			n.pumpkins.total = s.pumpkins.total;
			n.pumpkins.found = s.pumpkins.found;
			n.pumpkins.nextHint = s.pumpkins.nextHint;
		}
		return n;
	}

	private String token() {
		TrsOnline o = TrsOnline.current();
		return o == null ? null : o.token();
	}

	private void rejected(String token) {
		TrsOnline o = TrsOnline.current();
		if (o != null) o.tokenRejected(token);
	}

	private HalloweenApi api() {
		Http h = http;
		TrsOnline o = TrsOnline.current();
		if (h == null || o == null) return null;
		return new HalloweenApi(h, o.apiBase());
	}

	private synchronized boolean submit(Runnable task) {
		if (worker == null) {
			ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16), r -> {
				Thread t = new Thread(r, "TRS-Halloween");
				t.setDaemon(true);
				return t;
			});
			ex.allowCoreThreadTimeOut(true);
			worker = ex;
		}
		try {
			worker.execute(task);
			return true;
		} catch (RejectedExecutionException e) {
			return false;
		}
	}

	/** Nur Tests: alles vergessen. */
	void resetForTest() {
		state = null;
		stateAt = 0;
		retryAt = 0;
		loading = false;
		pending.clear();
		trickPhase = TrickPhase.IDLE;
		trickResult = null;
		trickNext = null;
		trickError = null;
		trickStarted = 0;
	}

	/** Nur Tests/Autotest: Zustand direkt setzen. */
	public void setStateForTest(HalloweenApi.State s) {
		if (s == null) {
			state = null;
		} else {
			setState(s);
		}
	}

	/** Autotest: Zustand ohne Server (Suche 7/13, Rätsel), damit die Seite und die Kürbisse etwas zeigen. */
	public void demoState() {
		HalloweenApi.State s = new HalloweenApi.State();
		s.active = Boolean.TRUE;
		s.pumpkins = new HalloweenApi.Pumpkins();
		s.pumpkins.total = Pumpkins.TOTAL;
		s.pumpkins.found = new ArrayList<String>(Pumpkins.ALL.subList(0, 7));
		Map<String, String> hint = new java.util.LinkedHashMap<String, String>();
		hint.put("en", "Where all the features end.");
		hint.put("de", "Wo alle Funktionen enden.");
		hint.put("es", "Donde terminan todas las funciones.");
		s.pumpkins.nextHint = hint;
		s.trick = new HalloweenApi.Trick();
		s.trick.usedToday = Boolean.FALSE;
		s.badges = new ArrayList<String>(Collections.singletonList("halloween2026"));
		setState(s);
	}
}

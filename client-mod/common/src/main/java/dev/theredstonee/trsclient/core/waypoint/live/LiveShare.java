package dev.theredstonee.trsclient.core.waypoint.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.social.MeEvent;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Live-Wegpunkte mit Freunden (API.md §41): meldet die eigene Sitzung (Welt + Dimension), schickt die eigene Position,
 * solange Freunde mit Freigabe in derselben Welt sind, lädt die eigene geteilte Liste nach Änderungen hoch und hält die
 * geteilten Wegpunkte/Positionen der Freunde aktuell (Ereignisse über den Konto-Stream, {@code wpshare_*}).
 *
 * <p>Nur Spiel-Thread ({@link #tick}, {@link #event}, Lesen); Netz im eigenen Thread „TRS-Waypoints“. Offline, 401,
 * 429 und unbekannte Routen führen still zu Pausen (Backoff) – das Spiel wartet nie.
 */
public final class LiveShare {
	static final long SESSION_REFRESH_MS = 60_000L;
	static final long LIST_DEBOUNCE_MS = 1_000L;
	static final long LIST_CHECK_MS = 250L;
	static final long GRANTS_TTL_MS = 30_000L;
	/** Positionen älter als das gelten als „nicht mehr live“ (verblassen) bzw. verschwinden. */
	public static final long POSITION_FADE_MS = 20_000L;
	public static final long POSITION_MAX_AGE_MS = 60_000L;
	/** Kennt der Server §41 nicht, erst nach dieser Pause wieder fragen (eine einzige Anfrage, ohne Meldung). */
	static final long UNSUPPORTED_RETRY_MS = 60 * 60_000L;

	/** Zugriff aufs Spiel (Welt, Position, Wegpunkt-Speicher) – Spiel-Thread. */
	public interface Env {
		boolean inWorld();

		/** Schlüssel des Wegpunkt-Speichers ({@code mp:<adresse>} / {@code sp:<welt>}), "" = keiner. */
		String storeKey();

		/** Dimension in der Schreibweise der Version. */
		String dimension();

		double x();

		double y();

		double z();

		float yaw();

		WaypointStore store();

		void waypointsChanged();

		/** Gehostete TRS-Welt (als Host oder Gast) oder null. */
		LiveWorld hostedWorld(String self);
	}

	/** Anbindung an TrsOnline. */
	public interface Backend {
		/** 401 für dieses Token. */
		void unauthorized(String token);
	}

	private static volatile LiveShare current;
	/** Nur Autotest: feste Welt statt der echten (z. B. Einzelspieler-Testwelt als „Server“). */
	private static volatile LiveWorld testWorld;

	private final LiveApi api;
	/** Einstellungen (kommen mit der Karte; null = noch nicht da → aus). */
	private final java.util.function.Supplier<TrsModules> modules;
	private final Env env;
	private final Executor executor;
	private final Backend backend;
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<Runnable>();

	// --- Spiel-Thread ---
	private String token;
	private String self;
	/** Uhrzeit des letzten Takts (ms) – auch für Ergebnisse aus dem Netz-Thread. */
	private long clock;
	private LiveWorld world;
	private String storeKey = "";
	private String dimension = "";
	private boolean unsupported;
	private long unsupportedUntil;
	/** Der Server hat §41 zuletzt nicht gekannt (bis zur ersten erfolgreichen Antwort): keine Oberfläche dafür zeigen. */
	private volatile boolean apiMissing;

	// Sitzung.
	private boolean sessionOpen;
	private boolean sessionInFlight;
	private long sessionAt;
	/** Sitzung bald neu melden (Peers neu holen: nach Stream-Lücke, Freigabe geändert, Sitzung verloren). */
	private boolean sessionDue;
	private String sessionDim = "";
	/** Erste Sitzungs-Antwort für diese Welt da (vorher: Ereignisse alter Welten ignorieren). */
	private boolean confirmed;
	private final Backoff sessionBackoff = new Backoff();
	private int peersSend;
	private Set<String> peersReceive = Collections.emptySet();

	// Position.
	private final PositionGate gate = new PositionGate();
	private boolean positionInFlight;
	private final Backoff positionBackoff = new Backoff();
	private int positionsSent;

	// Eigene geteilte Liste.
	private boolean ownLoaded;
	private boolean ownInFlight;
	private long ownRev = -1;
	private String syncedFp;
	private String pendingFp;
	private long dirtyAt;
	private long lastListCheck;
	private boolean putInFlight;
	private boolean putRetried;
	private final Backoff listBackoff = new Backoff();
	private int listsSent;

	// Freunde.
	private final Map<String, Owner> owners = new LinkedHashMap<String, Owner>();
	private List<Owner> ownersView = Collections.emptyList();
	private final Set<String> hiddenOwners = new HashSet<String>();
	private boolean friendsWanted;
	private boolean friendsInFlight;
	/** Steigt bei jeder entzogenen Freigabe: eine vorher gestartete Abfrage ist dann veraltet (würde ihn zurückholen). */
	private int revokeSerial;
	private final Backoff friendsBackoff = new Backoff();
	private int friendsFetches;

	// Freigaben.
	private Set<String> grants = new LinkedHashSet<String>();
	private Set<String> grantedToMe = new LinkedHashSet<String>();
	private boolean grantsLoaded;
	private boolean grantsInFlight;
	private long grantsAt;
	private boolean grantsStale;
	private final Set<String> grantBusy = new HashSet<String>();
	private final Backoff grantsBackoff = new Backoff();
	private String notice;

	public LiveShare(LiveApi api, java.util.function.Supplier<TrsModules> modules, Env env, Executor executor, Backend backend) {
		this.api = api;
		this.modules = modules;
		this.env = env;
		this.executor = executor;
		this.backend = backend;
	}

	/** Standard im Spiel: Netz im Thread „TRS-Waypoints“, Spielzugriff über die Karte ({@link GameEnv}). */
	public static LiveShare create(Http http, String apiBase, Backend backend) {
		ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16), r -> {
			Thread t = new Thread(r, "TRS-Waypoints");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		});
		ex.allowCoreThreadTimeOut(true);
		LiveShare s = new LiveShare(new LiveApi(http, apiBase), () -> {
			dev.theredstonee.trsclient.core.map.MapEngine e = dev.theredstonee.trsclient.core.map.MapEngine.get();
			return e == null ? null : e.modules();
		}, new GameEnv(), ex, backend);
		current = s;
		return s;
	}

	/** Der Dienst des laufenden Spiels oder null (ohne TRS-Online, 1.7.10/1.13.2, Tests). */
	public static LiveShare get() {
		return current;
	}

	/**
	 * Wie {@link #get()}, aber für die Oberfläche: null auch dann, wenn der Server die API (noch) nicht kennt – Reiter,
	 * Schalter und Freigaben erscheinen erst, wenn es die Funktion wirklich gibt.
	 */
	public static LiveShare ui() {
		LiveShare s = current;
		return s == null || s.apiMissing ? null : s;
	}

	/** Hat der Server die API zuletzt nicht gekannt? */
	public boolean apiMissing() {
		return apiMissing;
	}

	/** Nur Autotest/Tests: diese Welt statt der echten verwenden (null = aus). */
	public static void testWorld(LiveWorld w) {
		testWorld = w;
	}

	// --- Takt ---

	/** Funktion grundsätzlich an (ein Kartenmodul oder die Wegpunkte an, und etwas zu teilen/zeigen)? */
	boolean featureOn() {
		TrsModules m = modules.get();
		if (m == null) return false;
		boolean module = m.waypoints.isEnabled() || m.minimap.isEnabled() || m.worldMap.isEnabled();
		// Ohne eigene Sitzung gibt die API auch die Wegpunkte der Freunde nicht heraus (409 not_in_world).
		return module && sharing();
	}

	private TrsModules m() {
		return modules.get();
	}

	private boolean sharing() {
		TrsModules m = modules.get();
		return m != null && m.waypointShareLive.get();
	}

	private boolean showing() {
		TrsModules m = modules.get();
		return m != null && (m.waypointFriendWaypoints.get() || m.waypointFriendPositions.get());
	}

	/** Braucht der Dienst gerade den Konto-Stream (in einer teilbaren Welt)? */
	public boolean wantsStream() {
		// Kennt der Server die API nicht, wird der Konto-Stream dafür auch nicht offen gehalten (Worker-Tageslimit).
		return world != null && token != null && !unsupported;
	}

	/**
	 * Einmal je Client-Tick (aus TrsOnline). {@code token}/{@code self} null = nicht angemeldet.
	 */
	public void tick(long now, String token, String self) {
		clock = now;
		Runnable r;
		while ((r = results.poll()) != null) r.run();
		if (token == null || self == null) {
			// Abgemeldet: Sitzung läuft serverseitig nach 3 min ab; lokal alles vergessen.
			if (world != null) leave(false);
			this.token = null;
			return;
		}
		if (!self.equals(this.self)) {
			if (world != null) leave(this.token != null);
			this.self = self;
			grants = new LinkedHashSet<String>();
			grantedToMe = new LinkedHashSet<String>();
			grantsLoaded = false;
			grantBusy.clear();
			hiddenOwners.clear();
		}
		this.token = token;
		if (unsupported && now < unsupportedUntil) return;
		unsupported = false;

		LiveWorld w = null;
		String key = "";
		String dim = "";
		if (featureOn() && env.inWorld()) {
			key = env.storeKey() == null ? "" : env.storeKey();
			dim = WaypointShare.canonicalDimension(env.dimension());
			w = testWorld != null ? testWorld : currentWorld(key, self);
			if (dim == null) w = null;
		}
		if (w == null ? world != null : !w.equals(world)) {
			if (world != null) leave(true);
			if (w != null) enter(w, key);
		}
		if (world == null) {
			tickGrants(now);
			return;
		}
		storeKey = key;
		dimension = dim;
		boolean share = sharing();

		// Sitzung: beim Betreten, Dimensionswechsel und alle 60 s.
		if (share) {
			boolean due = !sessionOpen || sessionDue || now - sessionAt >= SESSION_REFRESH_MS || !dim.equals(sessionDim);
			if (due && !sessionInFlight && sessionBackoff.ready(now)) putSession(now, dim);
		} else if (sessionOpen) {
			endSession();
		}

		// Position nur, wenn jemand zusieht (send > 0) und die eigene Position geteilt wird.
		if (share && m().waypointSharePosition.get() && confirmed && sessionOpen && peersSend > 0 && !positionInFlight
				&& positionBackoff.ready(now)) {
			double x = env.x(), y = env.y(), z = env.z();
			float yaw = env.yaw();
			if (gate.due(now, x, y, z, dim, yaw)) postPosition(now, x, y, z, dim, yaw);
		}

		// Eigene geteilte Liste: Änderungen erkennen (4×/s), 1 s entprellt hochladen.
		if (share) tickOwnList(now);

		// Wegpunkte/Positionen der Freunde.
		if (showing() && friendsWanted && !friendsInFlight && friendsBackoff.ready(now) && confirmed && sessionOpen
				&& !sessionDue && !sessionInFlight) {
			fetchFriends();
		}
		tickGrants(now);
	}

	/** Welt fürs Teilen: gehostete TRS-Welt vor Server; Einzelspieler ohne Hosting → null. */
	LiveWorld currentWorld(String key, String self) {
		LiveWorld hosted = env.hostedWorld(self);
		if (hosted != null) return hosted;
		return LiveWorld.fromStoreKey(key);
	}

	private void enter(LiveWorld w, String key) {
		world = w;
		storeKey = key;
		confirmed = false;
		sessionOpen = false;
		sessionDue = true;
		sessionDim = "";
		peersSend = 0;
		peersReceive = Collections.emptySet();
		gate.reset();
		ownLoaded = false;
		ownRev = -1;
		syncedFp = null;
		pendingFp = null;
		putRetried = false;
		owners.clear();
		rebuildView();
		friendsWanted = true;
		sessionBackoff.ok();
		positionBackoff.ok();
		listBackoff.ok();
		friendsBackoff.ok();
	}

	/** Welt verlassen: Sitzung beenden (wenn offen), Freunde vergessen. */
	private void leave(boolean deleteSession) {
		if (deleteSession && sessionOpen) endSession();
		sessionOpen = false;
		world = null;
		confirmed = false;
		peersSend = 0;
		peersReceive = Collections.emptySet();
		owners.clear();
		rebuildView();
		friendsWanted = false;
	}

	/** Spiel wird beendet: Sitzung kurz beenden (höchstens 1,5 s warten). */
	public void shutdown() {
		final String t = token;
		if (!sessionOpen || t == null) return;
		sessionOpen = false;
		Thread thread = new Thread(() -> {
			try {
				api.endSession(t);
			} catch (IOException | ApiException | RuntimeException ignored) {
				// läuft nach 3 min ab
			}
		}, "TRS-Waypoints-End");
		thread.setDaemon(true);
		thread.start();
		try {
			thread.join(1500L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// --- Sitzung ---

	private void putSession(final long now, final String dim) {
		final String t = token;
		final LiveWorld w = world;
		sessionInFlight = true;
		if (!submit(() -> {
			try {
				final String body = api.session(t, w, dim);
				post(() -> {
					sessionInFlight = false;
					apiMissing = false;
					if (!w.equals(world)) return;
					sessionBackoff.ok();
					sessionOpen = true;
					sessionAt = clock;
					sessionDue = false;
					sessionDim = dim;
					confirmed = true;
					applyPeers(LiveJson.peers(LiveJson.object(body)));
				});
			} catch (final ApiException e) {
				post(() -> {
					sessionInFlight = false;
					failed(e, sessionBackoff, t);
				});
			} catch (IOException | RuntimeException e) {
				post(() -> {
					sessionInFlight = false;
					sessionBackoff.fail(clock, 0);
				});
			}
		})) sessionInFlight = false;
	}

	private void endSession() {
		final String t = token;
		sessionOpen = false;
		confirmed = false;
		peersSend = 0;
		if (t == null) return;
		submit(() -> {
			try {
				api.endSession(t);
			} catch (IOException | ApiException | RuntimeException ignored) {
				// läuft nach 3 min ab
			}
		});
	}

	private void applyPeers(LiveJson.Peers p) {
		if (p == null) return;
		peersSend = p.send;
		Set<String> before = peersReceive;
		peersReceive = Collections.unmodifiableSet(new LinkedHashSet<String>(p.receive));
		for (String u : peersReceive) {
			if (!owners.containsKey(u) || !before.contains(u)) friendsWanted = true;
		}
		// Wer gegangen ist, hat keine Live-Position mehr (seine Wegpunkte bleiben – Orte ändern sich nicht).
		for (Owner o : owners.values()) {
			if (!peersReceive.contains(o.uuid)) o.position = null;
		}
		if (p.send == 0) gate.reset();
	}

	// --- Position ---

	private void postPosition(long now, double x, double y, double z, String dim, float yaw) {
		final String t = token;
		final String body = LiveJson.positionBody(x, y, z, dim, yaw);
		gate.sent(now, x, y, z, dim, yaw);
		positionInFlight = true;
		positionsSent++;
		if (!submit(() -> {
			try {
				api.position(t, body);
				post(() -> {
					positionInFlight = false;
					positionBackoff.ok();
				});
			} catch (final ApiException e) {
				post(() -> {
					positionInFlight = false;
					// Keine Sitzung mehr (z. B. nach Neustart des Servers): neu melden.
					if (e.status() == 409 || "no_session".equals(e.code())) sessionDue = true;
					failed(e, positionBackoff, t);
				});
			} catch (IOException | RuntimeException e) {
				post(() -> {
					positionInFlight = false;
					positionBackoff.fail(clock, 0);
				});
			}
		})) positionInFlight = false;
	}

	// --- Eigene geteilte Liste ---

	private List<Waypoint> ownWaypoints() {
		WaypointStore store = env.store();
		return store == null || storeKey.isEmpty() ? Collections.<Waypoint>emptyList() : store.all(storeKey);
	}

	private String fingerprint() {
		return LiveJson.sharedList(ownWaypoints(), m().waypointShareDeath.get()).toString();
	}

	private void tickOwnList(long now) {
		if (!ownLoaded) {
			if (!ownInFlight && listBackoff.ready(now)) loadOwn(false);
			return;
		}
		if (now - lastListCheck < LIST_CHECK_MS && now >= lastListCheck) return;
		lastListCheck = now;
		String fp = fingerprint();
		if (fp.equals(syncedFp)) {
			pendingFp = null;
			return;
		}
		if (!fp.equals(pendingFp)) {
			pendingFp = fp;
			dirtyAt = now;
		}
		if (now - dirtyAt >= LIST_DEBOUNCE_MS && !putInFlight && listBackoff.ready(now)) putList();
	}

	/** {@code GET /waypoints}: Stand und rev der eigenen Liste; nach einer Neuinstallation zurückholen. */
	private void loadOwn(final boolean thenPut) {
		final String t = token;
		final LiveWorld w = world;
		ownInFlight = true;
		if (!submit(() -> {
			try {
				final String body = api.ownWaypoints(t, w);
				post(() -> {
					ownInFlight = false;
					if (!w.equals(world)) return;
					listBackoff.ok();
					ownRev = LiveJson.rev(body);
					List<Waypoint> server = LiveJson.ownList(body);
					if (!ownLoaded) {
						ownLoaded = true;
						WaypointStore store = env.store();
						if (store != null && !storeKey.isEmpty() && !store.hasWorld(storeKey) && !server.isEmpty()) {
							// Neuinstallation/anderer PC: geteilte Wegpunkte zurückholen (sonst gewinnt die lokale Liste).
							String cur = env.dimension();
							for (Waypoint wp : server) {
								wp.dimension = WaypointShare.localDimension(wp.dimension, cur);
								store.add(storeKey, wp);
							}
							env.waypointsChanged();
						}
						syncedFp = LiveJson.sharedList(server, true).toString();
					}
					if (thenPut) putList();
				});
			} catch (final ApiException e) {
				post(() -> {
					ownInFlight = false;
					if (e.status() == 404 && !"unsupported".equals(e.code())) {
						// Noch nichts geteilt in dieser Welt.
						ownLoaded = true;
						ownRev = -1;
						syncedFp = "[]";
						if (thenPut) putList();
						return;
					}
					failed(e, listBackoff, t);
				});
			} catch (IOException | RuntimeException e) {
				post(() -> {
					ownInFlight = false;
					listBackoff.fail(clock, 0);
				});
			}
		})) ownInFlight = false;
	}

	private void putList() {
		final String t = token;
		final LiveWorld w = world;
		final JsonArray list = LiveJson.sharedList(ownWaypoints(), m().waypointShareDeath.get());
		final String fp = list.toString();
		final String body = LiveJson.putBody(w, ownRev, list);
		putInFlight = true;
		listsSent++;
		if (!submit(() -> {
			try {
				final String res = api.putWaypoints(t, body);
				post(() -> {
					putInFlight = false;
					putRetried = false;
					listBackoff.ok();
					if (!w.equals(world)) return;
					long rev = LiveJson.rev(res);
					ownRev = rev >= 0 ? rev : ownRev;
					syncedFp = fp;
					if (fp.equals(pendingFp)) pendingFp = null;
				});
			} catch (final ApiException e) {
				post(() -> {
					putInFlight = false;
					if (!w.equals(world)) return;
					if (e.status() == 409 && "rev_mismatch".equals(e.code()) && !putRetried) {
						// Anderswo geändert (zweiter PC): mit dem aktuellen rev einmal erneut senden – die lokale Liste gewinnt.
						putRetried = true;
						long cur = LiveJson.errorRev(e.body());
						if (cur >= 0) {
							ownRev = cur;
							putList();
						} else {
							loadOwn(true);
						}
						return;
					}
					putRetried = false;
					if (e.status() == 400 || e.status() == 413 || e.status() == 422 || "too_many_worlds".equals(e.code())) {
						// Inhalt abgelehnt (z. B. Namensfilter): erst nach der nächsten Änderung wieder versuchen.
						syncedFp = fp;
						pendingFp = null;
						notice = "waypoint.live.rejected";
						return;
					}
					failed(e, listBackoff, t);
				});
			} catch (IOException | RuntimeException e) {
				post(() -> {
					putInFlight = false;
					listBackoff.fail(clock, 0);
				});
			}
		})) putInFlight = false;
	}

	// --- Freunde ---

	private void fetchFriends() {
		final String t = token;
		final LiveWorld w = world;
		final int serial = revokeSerial;
		friendsWanted = false;
		friendsInFlight = true;
		friendsFetches++;
		if (!submit(() -> {
			try {
				final String body = api.friends(t, w);
				post(() -> {
					friendsInFlight = false;
					if (!w.equals(world)) return;
					friendsBackoff.ok();
					if (serial != revokeSerial) {
						// Während der Abfrage wurde eine Freigabe entzogen – Antwort verwerfen, neu holen.
						friendsWanted = true;
						return;
					}
					applyOwners(LiveJson.owners(body, System.currentTimeMillis()));
				});
			} catch (final ApiException e) {
				post(() -> {
					friendsInFlight = false;
					friendsWanted = true;
					// Sitzung abgelaufen/anderswo: neu melden, danach erneut holen.
					if ("not_in_world".equals(e.code())) {
						sessionDue = true;
						sessionOpen = false;
						confirmed = false;
						friendsBackoff.fail(clock, 0);
						return;
					}
					failed(e, friendsBackoff, t);
				});
			} catch (IOException | RuntimeException e) {
				post(() -> {
					friendsInFlight = false;
					friendsWanted = true;
					friendsBackoff.fail(clock, 0);
				});
			}
		})) {
			friendsInFlight = false;
			friendsWanted = true;
		}
	}

	void applyOwners(List<Owner> fresh) {
		Map<String, Owner> before = new LinkedHashMap<String, Owner>(owners);
		owners.clear();
		for (Owner o : fresh) {
			Owner old = before.get(o.uuid);
			// Neuere Live-Position aus Ereignissen behalten.
			if (old != null && old.position != null && (o.position == null || old.position.at > o.position.at)) o.position = old.position;
			if (o.name == null && old != null) o.name = old.name;
			o.hidden = hiddenOwners.contains(o.uuid);
			owners.put(o.uuid, o);
		}
		rebuildView();
	}

	private void rebuildView() {
		ownersView = Collections.unmodifiableList(new ArrayList<Owner>(owners.values()));
		version++;
	}

	/** Steigt bei jeder Änderung der Freunde-Daten (Listen, Ein-/Ausblenden) – für Zwischenspeicher der Anzeige. */
	private int version;
	private int cachedVersion = -1;
	private String cachedDim;
	private List<FriendWaypoint> cachedWaypoints = Collections.emptyList();

	public int version() {
		return version;
	}

	/**
	 * Geteilte Wegpunkte aller eingeblendeten Freunde in dieser Dimension (Namensraum-Kennung, null = alle) –
	 * zwischengespeichert, je Bild billig.
	 */
	public List<FriendWaypoint> friendWaypoints(String canonicalDim) {
		if (cachedVersion == version && (canonicalDim == null ? cachedDim == null : canonicalDim.equals(cachedDim))) {
			return cachedWaypoints;
		}
		List<FriendWaypoint> out = new ArrayList<FriendWaypoint>();
		for (Owner o : ownersView) {
			if (o.hidden) continue;
			for (FriendWaypoint w : o.waypoints) {
				if (canonicalDim == null || canonicalDim.equals(w.dimension)) out.add(w);
			}
		}
		cachedWaypoints = Collections.unmodifiableList(out);
		cachedVersion = version;
		cachedDim = canonicalDim;
		return cachedWaypoints;
	}

	// --- Ereignisse ---

	/** {@code wpshare_*} sowie {@code hello}/{@code resync} des Konto-Streams (Spiel-Thread). */
	public void event(MeEvent e) {
		if (e == null) return;
		String t = e.type;
		if (t.equals("hello")) {
			if (!e.resumed) resync();
			return;
		}
		if (t.equals("resync")) {
			resync();
			return;
		}
		if (!t.startsWith("wpshare_")) return;
		JsonObject d = LiveJson.object(e.wpshareData);
		if (t.equals("wpshare_revoked")) {
			String u = Uuids.normalize(LiveJson.str(d, "uuid"));
			if (u == null) return;
			if (owners.remove(u) != null) rebuildView();
			revokeSerial++;
			grantedToMe.remove(u);
			grantsStale = true;
			return;
		}
		if (world == null) return;
		if (t.equals("wpshare_peers")) {
			if (confirmed) applyPeers(LiveJson.peers(d));
			return;
		}
		if (t.equals("wpshare_position")) {
			// Vor der ersten Sitzungs-Antwort könnte es noch eine Position aus der vorigen Welt sein.
			if (!confirmed) return;
			String u = Uuids.normalize(LiveJson.str(d, "uuid"));
			FriendPosition p = LiveJson.position(d, System.currentTimeMillis());
			if (u == null || p == null || u.equals(self)) return;
			Owner o = owners.get(u);
			if (o == null) {
				o = new Owner(u, null);
				o.hidden = hiddenOwners.contains(u);
				owners.put(u, o);
				rebuildView();
				friendsWanted = true;
			}
			o.position = p;
			return;
		}
		if (t.equals("wpshare_changed")) {
			String u = Uuids.normalize(LiveJson.str(d, "uuid"));
			double rev = LiveJson.num(d, "rev");
			Owner o = u == null ? null : owners.get(u);
			if (u != null && (o == null || Double.isNaN(rev) || (long) rev != o.rev)) friendsWanted = true;
		}
	}

	/** Stream-Lücke: Stand neu holen (Peers über die Sitzung, Wegpunkte über GET /friends, Freigaben bei Bedarf). */
	private void resync() {
		grantsStale = true;
		if (world == null) return;
		friendsWanted = true;
		sessionDue = true;
	}

	// --- Freigaben ---

	/** Freigaben gleich neu laden (Freunde-Bildschirm geöffnet – jemand hat mir evtl. inzwischen freigegeben). */
	public void refreshGrants() {
		grantsStale = true;
	}

	/** Die Oberfläche (Freunde) braucht die Freigaben: laden, wenn unbekannt oder älter als 30 s. */
	public void wantGrants() {
		if (!grantsLoaded || clock - grantsAt > GRANTS_TTL_MS) grantsStale = true;
	}

	private void tickGrants(long now) {
		if (!grantsStale || grantsInFlight || token == null || !grantsBackoff.ready(now)) return;
		grantsStale = false;
		final String t = token;
		grantsInFlight = true;
		if (!submit(() -> {
			try {
				final String body = api.grants(t);
				post(() -> {
					grantsInFlight = false;
					apiMissing = false;
					grantsBackoff.ok();
					LiveJson.Grants g = LiveJson.grants(body);
					if (g == null) return;
					grants = new LinkedHashSet<String>(g.grants);
					grantedToMe = new LinkedHashSet<String>(g.grantedToMe);
					// Gerade umgeschaltete behalten ihren neuen Stand (Antwort kann älter sein).
					grantsLoaded = true;
					grantsAt = clock;
				});
			} catch (final ApiException e) {
				post(() -> {
					grantsInFlight = false;
					failed(e, grantsBackoff, t);
				});
			} catch (IOException | RuntimeException e) {
				post(() -> {
					grantsInFlight = false;
					grantsBackoff.fail(clock, 0);
				});
			}
		})) grantsInFlight = false;
	}

	/** Freigabe für einen Freund an/aus (sofort sichtbar, bei Fehler zurück). */
	public void setGrant(String friend, final boolean on) {
		final String u = Uuids.normalize(friend);
		final String t = token;
		if (u == null) return;
		if (t == null) {
			notice = "waypoint.live.offline";
			return;
		}
		if (grantBusy.contains(u)) return;
		final boolean was = grants.contains(u);
		if (was == on) return;
		if (on) grants.add(u);
		else grants.remove(u);
		grantBusy.add(u);
		if (!submit(() -> {
			try {
				api.grant(t, u, on);
				post(() -> {
					grantBusy.remove(u);
					// Wer jetzt (nicht mehr) zusieht, steht in den Peers der nächsten Sitzungs-Antwort.
					sessionDue = true;
				});
			} catch (final ApiException e) {
				post(() -> {
					grantBusy.remove(u);
					if (was) grants.add(u);
					else grants.remove(u);
					if ("unsupported".equals(e.code())) {
						// Server ohne §41: still zurücknehmen – es gibt die Funktion dort noch nicht.
						failed(e, grantsBackoff, t);
						return;
					}
					notice = "not_friends".equals(e.code()) ? "waypoint.live.notFriends"
							: "blocked".equals(e.code()) ? "waypoint.live.blocked"
							: "too_many_grants".equals(e.code()) || e.status() == 409 ? "waypoint.live.limit"
							: e.rateLimited() ? "waypoint.live.slowDown" : "waypoint.live.failed";
					if (e.unauthorized()) backend.unauthorized(t);
				});
			} catch (IOException | RuntimeException e) {
				post(() -> {
					grantBusy.remove(u);
					if (was) grants.add(u);
					else grants.remove(u);
					notice = "waypoint.live.offline";
				});
			}
		})) {
			grantBusy.remove(u);
			if (was) grants.add(u);
			else grants.remove(u);
			notice = "waypoint.live.failed";
		}
	}

	public boolean granted(String uuid) {
		String u = Uuids.normalize(uuid);
		return u != null && grants.contains(u);
	}

	public boolean grantedToMe(String uuid) {
		String u = Uuids.normalize(uuid);
		return u != null && grantedToMe.contains(u);
	}

	public boolean grantBusy(String uuid) {
		String u = Uuids.normalize(uuid);
		return u != null && grantBusy.contains(u);
	}

	public boolean grantsLoaded() {
		return grantsLoaded;
	}

	/** Hinweis für die Oberfläche (i18n-Schlüssel) – einmal. */
	public String takeNotice() {
		String n = notice;
		notice = null;
		return n;
	}

	// --- Lesen (Spiel-/Render-Thread) ---

	/** Teilbare Welt gerade aktiv? */
	public boolean active() {
		return world != null;
	}

	public LiveWorld world() {
		return world;
	}

	/** Freunde, die mir in dieser Welt freigegeben haben (Reihenfolge der API). */
	public List<Owner> owners() {
		return ownersView;
	}

	public Owner owner(String uuid) {
		return uuid == null ? null : owners.get(uuid);
	}

	/** Freund lokal aus-/einblenden (bleibt bis zum Neustart). */
	public void setHidden(String uuid, boolean hidden) {
		Owner o = owners.get(uuid);
		if (hidden) hiddenOwners.add(uuid);
		else hiddenOwners.remove(uuid);
		if (o != null) o.hidden = hidden;
		version++;
	}

	public int peersSend() {
		return peersSend;
	}

	public Set<String> peersReceive() {
		return peersReceive;
	}

	/** Live-Position noch frisch genug zum Anzeigen? */
	public static boolean live(FriendPosition p, long now) {
		return p != null && now - p.at <= POSITION_MAX_AGE_MS;
	}

	/** Zähler für den Selbsttest: Positionen, Listen-Uploads, Freunde-Abrufe. */
	public int[] testCounters() {
		return new int[]{positionsSent, listsSent, friendsFetches};
	}

	// --- Hilfen ---

	private void failed(ApiException e, Backoff b, String t) {
		long now = clock;
		if (e.unauthorized()) {
			backend.unauthorized(t);
			b.fail(now, 0);
			return;
		}
		if ("unsupported".equals(e.code())) {
			// Server kennt §41 noch nicht: alles länger ruhen lassen.
			unsupported = true;
			apiMissing = true;
			unsupportedUntil = now + UNSUPPORTED_RETRY_MS;
			return;
		}
		b.fail(now, e.retryAfterMs());
	}

	private boolean submit(Runnable task) {
		try {
			executor.execute(task);
			return true;
		} catch (RejectedExecutionException e) {
			return false;
		}
	}

	private void post(Runnable onGameThread) {
		results.add(onGameThread);
	}

	/** Wartezeiten nach Fehlern: 5 s, 15 s, 30 s, 1 min, 2 min, 5 min (oder Retry-After, wenn länger). */
	static final class Backoff {
		static final long[] STEPS = {5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L};
		int failures;
		long until;

		boolean ready(long now) {
			return now >= until;
		}

		void ok() {
			failures = 0;
			until = 0;
		}

		void fail(long now, long retryAfterMs) {
			long wait = STEPS[Math.min(failures, STEPS.length - 1)];
			failures++;
			until = now + Math.max(wait, retryAfterMs);
		}
	}
}

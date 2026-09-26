package dev.theredstonee.trsclient.core.hosting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import dev.theredstonee.trsclient.core.hosting.net.Relay;
import dev.theredstonee.trsclient.core.hosting.net.RelayControl;
import dev.theredstonee.trsclient.core.hosting.net.RelayStream;
import dev.theredstonee.trsclient.core.hosting.net.UdpLink;
import dev.theredstonee.trsclient.core.hosting.netty.LoopbackBridge;
import dev.theredstonee.trsclient.core.hosting.netty.ServerAttach;
import dev.theredstonee.trsclient.core.hosting.netty.TrsChannel;
import dev.theredstonee.trsclient.core.hosting.netty.TrsConnect;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.social.MeEvent;
import dev.theredstonee.trsclient.core.social.Toasts;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Welt-Hosting im TRS Client: eine Einzelspielerwelt für Freunde öffnen (Host) und fremden Welten beitreten (Gast).
 *
 * <p>Zugang und Signalisierung laufen über die TRS API (API.md §21), die Spieldaten nie: zuerst direkt (UDP-Lochstanzen
 * + {@link dev.theredstonee.trsclient.core.hosting.net.Rudp}), sonst über das TRS Relay (TCP). Auf dem integrierten
 * Server hängt jeder Gast als eigener Netty-Kanal ({@link ServerAttach}); der Gast verbindet sich über
 * {@link TrsConnect} (Mixin) bzw. {@link LoopbackBridge} (Legacy).
 *
 * <p>Threads: alles Öffentliche aus dem Spiel-Thread; Netz nur in den Hintergrund-Threads „TRS-Hosting“ (nie im
 * Render-Thread). Ergebnisse kommen über {@link #tick} zurück.
 */
public final class Hosting {
	public static final long HEARTBEAT_MS = 30_000L;
	public static final long FRIENDS_ROOMS_TTL_MS = 30_000L;
	static final long NOTICE_MS = 7_000L;

	/** Zustand der eigenen, gehosteten Welt. */
	public enum HostState {
		IDLE, BACKUP, OPENING, OPEN, CLOSING
	}

	/** Zustand als Gast. */
	public enum GuestState {
		IDLE, JOINING, WAITING, CONNECTING, CONNECTED
	}

	/** Anbindung an TrsOnline/Social. */
	public interface Backend {
		/** Toasts (null = keine). */
		Toasts toasts();

		/** 401 für dieses Token: neu anmelden. */
		void unauthorized(String token);

		/** Freunde (für „Einladen“), online zuerst; null = unbekannt. */
		List<Friend> friends();

		/** Freunde laden (Bildschirm offen). */
		void wantFriends();
	}

	/** Ein Freund für die Einladeliste. */
	public static final class Friend {
		public final String uuid;
		public final String name;
		public final boolean online;
		public final boolean inGame;

		public Friend(String uuid, String name, boolean online, boolean inGame) {
			this.uuid = uuid;
			this.name = name;
			this.online = online;
			this.inGame = inGame;
		}
	}

	/** Meldung für die Oberfläche. */
	public static final class Notice {
		public final String key;
		public final Object[] args;
		public final boolean error;
		public final long at;

		Notice(String key, Object[] args, boolean error, long at) {
			this.key = key;
			this.args = args == null ? new Object[0] : args;
			this.error = error;
			this.at = at;
		}

		public String text() {
			return I18n.tr(key, args);
		}
	}

	/** Ein Spieler in der gehosteten Welt (für die Spielerliste). */
	public static final class Guest {
		public final String uuid;
		public final String name;
		/** Weg der Verbindung; null = Host selbst oder unbekannt. */
		public final PeerStream.Path path;
		public final PlayerRights rights;
		public final boolean host;

		Guest(String uuid, String name, PeerStream.Path path, PlayerRights rights, boolean host) {
			this.uuid = uuid;
			this.name = name;
			this.path = path;
			this.rights = rights;
			this.host = host;
		}
	}

	/** Anfrage „Welt hosten“. */
	public static final class Request {
		public String name;
		public HostingPlatform.Options options = new HostingPlatform.Options();
		/** friends | invited */
		public String visibility = "friends";
		public boolean backup = true;
	}

	/** Eine angehängte Gast-Verbindung. */
	static final class Attached {
		final String uuid;
		final String name;
		final TrsChannel channel;
		final PeerStream.Path path;

		Attached(String uuid, String name, TrsChannel channel, PeerStream.Path path) {
			this.uuid = uuid;
			this.name = name;
			this.channel = channel;
			this.path = path;
		}
	}

	/** Die offene Welt. */
	final class HostSession {
		final String worldKey;
		final HostingPlatform.Options options;
		String visibility;
		String name;
		volatile Rooms.Room room;
		volatile Rooms.ConnectInfo connect;
		volatile RelayControl control;
		volatile Object listener;
		boolean controlBusy;
		long controlRetryAt;
		int controlFailures;
		volatile String relayProblem;
		final Map<String, PlayerRights> rights = new HashMap<String, PlayerRights>();
		/** Welche Rechte gerade im Spiel gelten (nur für Spieler, die schon angewendet wurden). */
		final Map<String, PlayerRights> applied = new HashMap<String, PlayerRights>();
		final Set<String> opGranted = new HashSet<String>();
		final List<Attached> attached = new CopyOnWriteArrayList<Attached>();
		final Set<String> blocked = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
		long lastHeartbeat;
		long lastControlTick;
		boolean heartbeatBusy;
		int lastPlayers = -1;
		volatile boolean closed;
		boolean recreated;

		HostSession(String worldKey, HostingPlatform.Options options) {
			this.worldKey = worldKey;
			this.options = options;
		}

		boolean acceptedMember(String uuid) {
			Rooms.Room r = room;
			if (r == null || uuid == null) return false;
			Rooms.Member m = r.member(uuid);
			return m != null && "accepted".equals(m.state);
		}

		String memberName(String uuid) {
			Rooms.Room r = room;
			Rooms.Member m = r == null ? null : r.member(uuid);
			return m == null ? null : m.name;
		}

		int openGuests() {
			Set<String> ids = new HashSet<String>();
			for (Attached a : attached) if (a.channel.isOpen()) ids.add(a.uuid == null ? "link:" + a.name : a.uuid);
			return ids.size();
		}
	}

	private static volatile HostingPlatform platform;
	private static volatile Hosting current;

	private final HostingApi api;
	private final Backend backend;
	private final ThreadPoolExecutor worker;
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<Runnable>();
	private final SignalBox signals = new SignalBox();
	private final PublicLink publicLink;

	// Nur Spiel-Thread:
	private String token;
	private String self;
	private String selfName;
	private HostState hostState = HostState.IDLE;
	private HostSession session;
	private Notice notice;
	private int generation;

	private GuestState guestState = GuestState.IDLE;
	private Rooms.Room guestRoom;
	private PeerStream guestStream;
	private PeerStream.Path guestPath;
	private String guestAddress;
	private int guestAttempt;
	private final Map<String, Rooms.Room> friendsRooms = new LinkedHashMap<String, Rooms.Room>();
	private long friendsRoomsAt;
	private boolean friendsRoomsBusy;
	private boolean mineChecked;
	private boolean wantStream;
	/** Welt-Beitritt vom Launcher (TRS Link), wartet auf Anmeldung/Menü. */
	private volatile Rooms.Room launcherJoin;
	private boolean linkHooked;

	public Hosting(HostingApi api, Backend backend) {
		this.api = api;
		this.backend = backend;
		this.worker = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(128),
				new java.util.concurrent.ThreadFactory() {
					private int n;

					@Override
					public synchronized Thread newThread(Runnable r) {
						Thread t = new Thread(r, "TRS-Hosting-" + (++n));
						t.setDaemon(true);
						return t;
					}
				});
		this.worker.allowCoreThreadTimeOut(true);
		this.publicLink = new PublicLink(this);
		this.signals.offers(new SignalBox.OfferHandler() {
			@Override
			public void offer(String roomId, SignalBox.Signal s) {
				onOffer(roomId, s);
			}
		});
		current = this;
	}

	/** Anbindung des Spiels (je Loader beim Start). */
	public static void install(final HostingPlatform p) {
		platform = p;
		TrsChannel.log = DirectConnect.log = new java.util.function.Consumer<String>() {
			@Override
			public void accept(String m) {
				try {
					p.log(m);
				} catch (RuntimeException ignored) {
					// egal
				}
			}
		};
	}

	public static HostingPlatform platform() {
		return platform;
	}

	/** Dienst der laufenden Online-Verbindung oder null. */
	public static Hosting current() {
		return current;
	}

	// --- Lesen (Spiel-Thread) ---

	public HostState hostState() {
		return hostState;
	}

	public Rooms.Room room() {
		HostSession s = session;
		return s == null ? null : s.room;
	}

	public HostingPlatform.Options options() {
		HostSession s = session;
		return s == null ? null : s.options.copy();
	}

	public String visibility() {
		HostSession s = session;
		return s == null ? "friends" : s.visibility;
	}

	/** Relay nicht erreichbar (nur Direktverbindungen)? i18n-Schlüssel oder null. */
	public String relayProblem() {
		HostSession s = session;
		return s == null ? null : s.relayProblem;
	}

	public GuestState guestState() {
		return guestState;
	}

	public Rooms.Room guestRoom() {
		return guestRoom;
	}

	public PeerStream.Path guestPath() {
		return guestPath;
	}

	public PublicLink publicLink() {
		return publicLink;
	}

	public boolean signedIn() {
		return token != null;
	}

	public String self() {
		return self;
	}

	/** Kann man gerade hosten (Einzelspielerwelt, angemeldet, Anbindung vorhanden)? */
	public boolean canHost() {
		HostingPlatform p = platform;
		try {
			return p != null && p.canHost() && ServerAttach.supported(p.connectionListener());
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	public int generation() {
		return generation;
	}

	public Notice notice(long now) {
		Notice n = notice;
		return n != null && now - n.at < NOTICE_MS ? n : null;
	}

	public void clearNotice() {
		notice = null;
	}

	/** Offene Welten von Freunden + Einladungen + eigene Anfragen (neueste zuerst). */
	public List<Rooms.Room> friendsRooms() {
		return new ArrayList<Rooms.Room>(friendsRooms.values());
	}

	/** Freunde zum Einladen (online zuerst) oder leer. */
	public List<Friend> friendsForInvite() {
		List<Friend> all = backend.friends();
		if (all == null) return Collections.emptyList();
		List<Friend> out = new ArrayList<Friend>(all);
		Collections.sort(out, new java.util.Comparator<Friend>() {
			@Override
			public int compare(Friend a, Friend b) {
				int oa = a.inGame ? 0 : a.online ? 1 : 2;
				int ob = b.inGame ? 0 : b.online ? 1 : 2;
				if (oa != ob) return oa - ob;
				return a.name.compareToIgnoreCase(b.name);
			}
		});
		return out;
	}

	/** Spieler der gehosteten Welt mit Verbindungsweg und Rechten (Host zuerst). */
	public List<Guest> guests() {
		HostSession s = session;
		HostingPlatform p = platform;
		if (s == null || p == null) return Collections.emptyList();
		List<Guest> out = new ArrayList<Guest>();
		List<HostingPlatform.Player> players;
		try {
			players = p.players();
		} catch (RuntimeException e) {
			players = Collections.emptyList();
		}
		for (HostingPlatform.Player pl : players) {
			boolean host = pl.uuid != null && pl.uuid.equals(self);
			PeerStream.Path path = host ? null : pathOf(s, pl);
			PlayerRights r = s.rights.get(pl.uuid);
			Guest g = new Guest(pl.uuid, pl.name, path, r == null ? PlayerRights.DEFAULT : r, host);
			if (host) out.add(0, g);
			else out.add(g);
		}
		return out;
	}

	private static PeerStream.Path pathOf(HostSession s, HostingPlatform.Player pl) {
		for (Attached a : s.attached) {
			if (!a.channel.isOpen()) continue;
			if ((a.uuid != null && a.uuid.equals(pl.uuid)) || (a.name != null && a.name.equalsIgnoreCase(pl.name))) return a.path;
		}
		return null;
	}

	/** Rechte eines Gasts. */
	public PlayerRights rights(String uuid) {
		HostSession s = session;
		PlayerRights r = s == null ? null : s.rights.get(uuid);
		return r == null ? PlayerRights.DEFAULT : r;
	}

	// --- Takt (Spiel-Thread) ---

	/** Einmal je Client-Tick (aus TrsOnline). */
	public void tick(long now, String currentToken, String uuid, String name) {
		Runnable r;
		while ((r = results.poll()) != null) {
			try {
				r.run();
			} catch (RuntimeException e) {
				log("TRS Hosting: " + e);
			}
		}
		if (uuid != null && !uuid.equals(self)) {
			// Kontowechsel: alles beenden.
			if (session != null) stopHosting("account");
			resetGuest();
			friendsRooms.clear();
			mineChecked = false;
			self = uuid;
		}
		selfName = name;
		token = currentToken;
		HostSession s = session;
		if (s != null) tickHost(s, now);
		tickGuest(now);
		publicLink.tick(now);
		tickLauncherJoin();
		wantStream = s != null || guestState == GuestState.WAITING || guestState == GuestState.CONNECTING
				|| guestState == GuestState.JOINING;
		if (token != null && !mineChecked) {
			mineChecked = true;
			closeLeftoverRoom();
		}
	}

	// --- Beitritt vom Launcher (docs/hosting-link.md) ---

	private void tickLauncherJoin() {
		if (!linkHooked) hookLink();
		Rooms.Room r = launcherJoin;
		HostingPlatform p = platform;
		if (r == null || token == null || p == null) return;
		boolean ready;
		boolean playing;
		try {
			ready = p.readyForJoin();
			playing = p.inWorld();
		} catch (RuntimeException | LinkageError e) {
			return;
		}
		if (!ready) return;
		launcherJoin = null;
		if (playing) {
			// Schon im Spiel: nicht einfach herausreißen – Toast mit „Beitreten“ (Schnelltaste).
			friendsRooms.put(r.id, r);
			Toasts toasts = backend.toasts();
			if (toasts != null) {
				toasts.add(Toasts.Kind.WORLD_INVITE, "world:" + r.id, r.hostName, I18n.tr("hosting.toast.accepted"), r.hostUuid,
						r.hostName, r.id, null, System.currentTimeMillis());
			}
			generation++;
			return;
		}
		joinAccepted(r);
	}

	private void hookLink() {
		dev.theredstonee.trsclient.core.link.TrsLink link = dev.theredstonee.trsclient.core.link.TrsLink.shared();
		if (link == null) return;
		linkHooked = true;
		link.addListener(new dev.theredstonee.trsclient.core.link.TrsLink.Listener() {
			@Override
			public void onConnected(dev.theredstonee.trsclient.core.link.TrsLink l, boolean accounts) {
				if (!l.status().has(dev.theredstonee.trsclient.core.link.TrsLink.FEATURE_HOSTING_JOIN)) return;
				// Abholen, falls der Push schon vor unserer Anmeldung kam (docs/hosting-link.md §3).
				l.request("hosting.join", null, 5000L, new dev.theredstonee.trsclient.core.link.TrsLink.Callback() {
					@Override
					public void done(dev.theredstonee.trsclient.core.link.TrsLink.Line response) {
						offerLauncherJoin(response.join);
					}

					@Override
					public void failed(String code) {
						// nichts da / alter Launcher
					}
				});
			}

			@Override
			public void onLine(dev.theredstonee.trsclient.core.link.TrsLink l,
					dev.theredstonee.trsclient.core.link.TrsLink.Line line) {
				if ("hostingJoin".equals(line.type)) offerLauncherJoin(line.join);
			}

			@Override
			public void onDisconnected(dev.theredstonee.trsclient.core.link.TrsLink l) {
			}
		});
	}

	/** Anweisung vom Launcher (Netz-Thread): prüfen und für den nächsten Tick merken. */
	void offerLauncherJoin(dev.theredstonee.trsclient.core.link.TrsLink.JoinDto j) {
		Rooms.Room r = fromLauncher(j);
		if (r == null) return;
		log("TRS Hosting: Beitritt vom Launcher: " + r.name);
		launcherJoin = r;
	}

	/** Felder erneut prüfen (docs/hosting-link.md §2); ungültig → null. */
	static Rooms.Room fromLauncher(dev.theredstonee.trsclient.core.link.TrsLink.JoinDto j) {
		if (j == null || !Rooms.validRoomId(j.roomId)) return null;
		String code = j.code == null ? null : Rooms.normalizeCode(j.code);
		String hostUuid = j.host == null ? null : Uuids.normalize(j.host.uuid);
		String hostName = j.host == null ? "?" : Rooms.player(j.host.name);
		String name = j.name == null || j.name.trim().isEmpty() ? "?" : Rooms.name(j.name);
		return new Rooms.Room(j.roomId, code, name, hostUuid, hostName, Rooms.version(j.mcVersion), Rooms.loader(j.loader),
				Rooms.MAX_PLAYERS, "survival", true, false, true, null, 1, 0, null, "accepted");
	}

	/** Schon angenommen (Launcher/Einladung): frisches Relay-Token holen und verbinden. */
	void joinAccepted(final Rooms.Room r) {
		if (guestState == GuestState.CONNECTING || guestState == GuestState.JOINING) return;
		resetGuest();
		guestRoom = r;
		guestState = GuestState.JOINING;
		final int attempt = ++guestAttempt;
		generation++;
		call(new ApiCall<Rooms.ConnectInfo>() {
			@Override
			public Rooms.ConnectInfo run(String t) throws IOException, ApiException {
				return api.connect(t, r.id);
			}

			@Override
			public void done(Rooms.ConnectInfo value, String error) {
				if (attempt != guestAttempt) return;
				if (value == null) {
					resetGuest();
					notice("hosting.error.room_not_found".equals(error) ? "hosting.error.world_closed"
							: "hosting.error.not_accepted".equals(error) ? "hosting.error.kicked"
							: error == null ? "hosting.error.generic" : error, true);
					return;
				}
				connectTo(r, value);
			}
		});
	}

	/** Braucht das Hosting gerade den Echtzeit-Stream (Signale/Anfragen)? */
	public boolean wantsStream() {
		return wantStream;
	}

	private void tickHost(final HostSession s, long now) {
		HostingPlatform p = platform;
		if (hostState != HostState.OPEN || s.closed) return;
		String key = null;
		boolean can;
		try {
			can = p != null && p.canHost();
			key = p == null ? null : p.worldKey();
		} catch (RuntimeException e) {
			can = false;
		}
		if (!can || key == null || !key.equals(s.worldKey)) {
			stopHosting("world_closed");
			return;
		}
		// Geschlossene Verbindungen vergessen.
		for (Attached a : s.attached) if (!a.channel.isOpen()) s.attached.remove(a);
		// Rechte auf neue/wiederkehrende Spieler anwenden.
		List<HostingPlatform.Player> players;
		try {
			players = p.players();
		} catch (RuntimeException e) {
			players = Collections.emptyList();
		}
		Set<String> present = new HashSet<String>();
		for (HostingPlatform.Player pl : players) {
			if (pl.uuid == null || pl.uuid.equals(self)) continue;
			present.add(pl.uuid);
			PlayerRights want = s.rights.get(pl.uuid);
			if (want == null) want = PlayerRights.DEFAULT;
			PlayerRights have = s.applied.get(pl.uuid);
			// Beim ersten Sehen immer anwenden: Spielmodus der Welt bzw. Rechte (Vanilla-LAN-Vorgabe spielt keine Rolle).
			if (!want.equals(have)) applyRights(s, pl.uuid, want);
		}
		s.applied.keySet().retainAll(present);
		int count = players.size();
		// Herzschlag (alle 30 s; bei geänderter Spielerzahl frühestens nach 5 s).
		boolean due = now - s.lastHeartbeat >= HEARTBEAT_MS || (count != s.lastPlayers && now - s.lastHeartbeat >= 5_000L);
		if (due && !s.heartbeatBusy && token != null && s.room != null) heartbeat(s, count, now);
		// Relay-Kontrollverbindung halten.
		RelayControl c = s.control;
		if (c != null && c.isOpen()) {
			final RelayControl cc = c;
			if (now - s.lastControlTick >= 5_000L) {
				s.lastControlTick = now;
				submit(new Runnable() {
				@Override
				public void run() {
						cc.tick(System.currentTimeMillis());
					}
				});
			}
		} else if (!s.controlBusy && now >= s.controlRetryAt && s.room != null) {
			connectControl(s, s.controlFailures > 0);
		}
	}

	private void tickGuest(long now) {
		if (guestState == GuestState.CONNECTED) {
			PeerStream st = guestStream;
			if (st == null || !st.isOpen()) resetGuest();
		}
	}

	// --- Host: öffnen/schließen ---

	/** „Hosten“: Backup (optional) → Raum anlegen → Welt öffnen → Relay. */
	public void host(final Request req) {
		if (hostState != HostState.IDLE) return;
		final HostingPlatform p = platform;
		if (token == null) {
			notice("hosting.error.offline", true);
			return;
		}
		if (p == null || !canHost()) {
			notice("hosting.error.no_world", true);
			return;
		}
		final HostSession s = new HostSession(p.worldKey(), req.options.copy());
		s.visibility = req.visibility;
		s.name = req.name;
		s.listener = p.connectionListener();
		session = s;
		generation++;
		if (req.backup) {
			hostState = HostState.BACKUP;
			p.backup(new HostingPlatform.Done() {
				@Override
				public void done(java.nio.file.Path result, String error) {
					if (session != s || s.closed) return;
					if (error != null) {
						hostState = HostState.IDLE;
						session = null;
						generation++;
						notice(error, true);
						return;
					}
					log("TRS Hosting: Welt gesichert: " + (result == null ? "?" : result.getFileName()));
					createRoom(s);
				}
			});
		} else {
			createRoom(s);
		}
	}

	private void createRoom(final HostSession s) {
		final HostingPlatform p = platform;
		hostState = HostState.OPENING;
		generation++;
		final String t = token;
		final HostingApi.Settings set = settings(s, p);
		submit(new Runnable() {
			@Override
			public void run() {
				try {
					final HostingApi.Opened o = api.create(t, set);
					post(new Runnable() {
						@Override
						public void run() {
							if (session != s || s.closed) {
								closeRoomQuietly(o.room.id);
								return;
							}
							s.room = o.room;
							s.connect = o.connect;
							try {
								p.publish(s.options);
							} catch (RuntimeException | LinkageError e) {
								log("TRS Hosting: Öffnen fehlgeschlagen: " + e);
								closeRoomQuietly(o.room.id);
								session = null;
								hostState = HostState.IDLE;
								generation++;
								notice("hosting.error.publish_failed", true);
								return;
							}
							hostState = HostState.OPEN;
							s.lastHeartbeat = System.currentTimeMillis();
							generation++;
							notice("hosting.notice.open", false, o.room.prettyCode());
							connectControl(s, false);
						}
					});
				} catch (final ApiException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (e.unauthorized()) backend.unauthorized(t);
							if (session == s) {
								session = null;
								hostState = HostState.IDLE;
								generation++;
							}
							notice(errorKey(e.code()), true);
						}
					});
				} catch (IOException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (session == s) {
								session = null;
								hostState = HostState.IDLE;
								generation++;
							}
							notice("hosting.error.network", true);
						}
					});
				}
			}
		});
	}

	private HostingApi.Settings settings(HostSession s, HostingPlatform p) {
		HostingApi.Settings set = new HostingApi.Settings();
		set.name = s.name == null || s.name.trim().isEmpty() ? (p.worldName() == null ? "World" : p.worldName()) : s.name.trim();
		if (set.name.length() > Rooms.MAX_NAME) set.name = set.name.substring(0, Rooms.MAX_NAME);
		set.mcVersion = p.minecraftVersion();
		set.loader = p.loader();
		set.maxPlayers = Math.max(Rooms.MIN_PLAYERS, Math.min(Rooms.MAX_PLAYERS, s.options.maxPlayers));
		set.gameMode = s.options.gameMode;
		set.pvp = s.options.pvp;
		set.cheats = s.options.cheats;
		set.open = true;
		set.visibility = s.visibility;
		return set;
	}

	/** Einstellungen der offenen Welt ändern (Spielmodus, Cheats, PvP, max. Spieler, Sichtbarkeit, Name). */
	public void update(String name, HostingPlatform.Options o, String visibility) {
		final HostSession s = session;
		if (s == null || hostState != HostState.OPEN) return;
		s.options.gameMode = o.gameMode;
		s.options.cheats = o.cheats;
		s.options.pvp = o.pvp;
		s.options.maxPlayers = Math.max(Math.max(Rooms.MIN_PLAYERS, s.openGuests() + 1), Math.min(Rooms.MAX_PLAYERS, o.maxPlayers));
		if (visibility != null) s.visibility = visibility;
		if (name != null) s.name = name;
		HostingPlatform p = platform;
		try {
			if (p != null) p.apply(s.options);
		} catch (RuntimeException | LinkageError e) {
			log("TRS Hosting: Einstellungen: " + e);
		}
		// Rechte neu anwenden (Spielmodus der Welt kann sich geändert haben).
		s.applied.clear();
		generation++;
		final HostingApi.Settings set = settings(s, p);
		set.mcVersion = null;
		set.loader = null;
		final String roomId = s.room == null ? null : s.room.id;
		if (roomId == null) return;
		call(new ApiCall<Rooms.Room>() {
			@Override
			public Rooms.Room run(String t) throws IOException, ApiException {
				return api.update(t, roomId, set);
			}

			@Override
			public void done(Rooms.Room value, String error) {
				if (value != null && session == s) s.room = value;
				if (error != null) notice(error, true);
				generation++;
			}
		});
	}

	/** Hosting beenden: Raum schließen, Gäste trennen, Welt wieder „privat“. */
	public void stopHosting(String reason) {
		final HostSession s = session;
		if (s == null) return;
		s.closed = true;
		session = null;
		hostState = HostState.IDLE;
		generation++;
		signals.clear();
		publicLink.stop();
		final RelayControl c = s.control;
		s.control = null;
		for (Attached a : s.attached) a.channel.close();
		HostingPlatform p = platform;
		if (p != null) {
			for (String uuid : s.opGranted) {
				try {
					p.applyRights(uuid, null, Boolean.FALSE);
				} catch (RuntimeException | LinkageError ignored) {
					// Welt evtl. schon zu
				}
			}
			try {
				p.unpublish();
			} catch (RuntimeException | LinkageError e) {
				log("TRS Hosting: Schließen: " + e);
			}
		}
		final String roomId = s.room == null ? null : s.room.id;
		final String t = token;
		submit(new Runnable() {
			@Override
			public void run() {
				if (c != null) c.close();
				if (roomId != null && t != null) {
					try {
						api.close(t, roomId);
					} catch (IOException | ApiException ignored) {
						// läuft nach 90 s ohne Herzschlag ohnehin ab
					}
				}
			}
		});
		if (!"account".equals(reason) && !"quit".equals(reason)) {
			notice("world_closed".equals(reason) ? "hosting.notice.closedWorld" : "hosting.notice.closed", false);
		}
	}

	/** Beim Beenden des Spiels (Shutdown-Hook): Raum sofort schließen, höchstens ~2 s. */
	public void shutdown() {
		HostSession s = session;
		if (s == null || s.room == null || token == null) return;
		s.closed = true;
		RelayControl c = s.control;
		if (c != null) c.close();
		try {
			api.close(token, s.room.id);
		} catch (IOException | ApiException | RuntimeException ignored) {
			// egal
		}
	}

	/** Nach Neustart: ein übrig gebliebener eigener Raum wird geschlossen (die Welt läuft ja nicht mehr). */
	private void closeLeftoverRoom() {
		call(new ApiCall<Rooms.Room>() {
			@Override
			public Rooms.Room run(String t) throws IOException, ApiException {
				Rooms.Room r = api.mine(t);
				if (r != null && session == null) api.close(t, r.id);
				return r;
			}

			@Override
			public void done(Rooms.Room value, String error) {
				// still
			}
		});
	}

	private void closeRoomQuietly(final String roomId) {
		final String t = token;
		submit(new Runnable() {
			@Override
			public void run() {
				try {
					api.close(t, roomId);
				} catch (IOException | ApiException ignored) {
					// egal
				}
			}
		});
	}

	private void heartbeat(final HostSession s, final int players, long now) {
		s.heartbeatBusy = true;
		s.lastHeartbeat = now;
		s.lastPlayers = players;
		final String roomId = s.room.id;
		final String t = token;
		submit(new Runnable() {
			@Override
			public void run() {
				String err = null;
				boolean gone = false;
				try {
					api.heartbeat(t, roomId, players);
				} catch (ApiException e) {
					if (e.unauthorized()) backend.unauthorized(t);
					gone = e.status() == 404;
					err = e.code();
				} catch (IOException e) {
					err = "network";
				}
				final boolean roomGone = gone;
				post(new Runnable() {
					@Override
					public void run() {
						s.heartbeatBusy = false;
						if (roomGone && session == s && !s.closed) recreateRoom(s);
					}
				});
			}
		});
	}

	/** Raum ist serverseitig abgelaufen (z. B. Netz weg): einmal neu anlegen, Gäste bleiben verbunden. */
	private void recreateRoom(final HostSession s) {
		if (s.recreated) {
			stopHosting("expired");
			return;
		}
		s.recreated = true;
		final HostingPlatform p = platform;
		final HostingApi.Settings set = settings(s, p);
		final RelayControl old = s.control;
		s.control = null;
		call(new ApiCall<HostingApi.Opened>() {
			@Override
			public HostingApi.Opened run(String t) throws IOException, ApiException {
				if (old != null) old.close();
				return api.create(t, set);
			}

			@Override
			public void done(HostingApi.Opened value, String error) {
				if (session != s || s.closed) return;
				if (value == null) {
					stopHosting("expired");
					return;
				}
				s.room = value.room;
				s.connect = value.connect;
				s.controlFailures = 0;
				s.controlRetryAt = 0;
				generation++;
				notice("hosting.notice.recreated", false, value.room.prettyCode());
			}
		});
	}

	// --- Relay (Host) ---

	private void connectControl(final HostSession s, final boolean freshToken) {
		if (s.controlBusy || s.closed) return;
		s.controlBusy = true;
		final String t = token;
		final String roomId = s.room.id;
		final Rooms.ConnectInfo first = s.connect;
		submit(new Runnable() {
			@Override
			public void run() {
				RelayControl c = null;
				String problem = null;
				try {
					Rooms.ConnectInfo ci = freshToken || first == null || first.expiresAt < System.currentTimeMillis() + 5000
							? api.connect(t, roomId) : first;
					s.connect = ci;
					c = RelayControl.connect(ci.relayHost, ci.tcpPort, ci.token, new ControlListener(s));
				} catch (Relay.RelayException e) {
					problem = e.code;
				} catch (ApiException e) {
					if (e.unauthorized()) backend.unauthorized(t);
					problem = e.code();
				} catch (IOException | RuntimeException e) {
					problem = "unreachable";
				}
				final RelayControl ok = c;
				final String prob = problem;
				post(new Runnable() {
					@Override
					public void run() {
						s.controlBusy = false;
						if (s.closed || session != s) {
							if (ok != null) ok.close();
							return;
						}
						if (ok != null) {
							s.control = ok;
							s.controlFailures = 0;
							if (s.relayProblem != null) generation++;
							s.relayProblem = null;
						} else {
							s.controlFailures++;
							long[] backoff = { 5_000L, 15_000L, 30_000L, 60_000L };
							s.controlRetryAt = System.currentTimeMillis() + backoff[Math.min(backoff.length - 1, s.controlFailures - 1)];
							if (s.relayProblem == null) generation++;
							s.relayProblem = "hosting.relay.problem";
							log("TRS Hosting: Relay nicht erreichbar (" + prob + ")");
						}
					}
				});
			}
		});
	}

	/** Rückrufe der Kontrollverbindung (Lese-Thread des Relay). */
	private final class ControlListener implements RelayControl.Listener {
		private final HostSession s;

		ControlListener(HostSession s) {
			this.s = s;
		}

		@Override
		public void guestOpen(final byte[] pairId, final String uuid) {
			final RelayControl c = s.control;
			if (s.closed || c == null) return;
			if (!allowGuest(s, uuid)) {
				c.closeGuest(pairId);
				return;
			}
			final Rooms.ConnectInfo ci = s.connect;
			submit(new Runnable() {
				@Override
				public void run() {
					try {
						RelayStream st = Relay.pair(ci.relayHost, ci.tcpPort, pairId);
						attach(s, st, uuid);
					} catch (IOException | RuntimeException e) {
						log("TRS Hosting: Relay-Gast nicht verbunden (" + e.getMessage() + ")");
					}
				}
			});
		}

		@Override
		public void guestClosed(byte[] pairId) {
			// Der Strom meldet sein Ende selbst.
		}

		@Override
		public void closed(final String code) {
			post(new Runnable() {
				@Override
				public void run() {
					if (s.closed || session != s) return;
					if (s.control != null && !s.control.isOpen()) s.control = null;
					s.controlRetryAt = System.currentTimeMillis() + ("replaced".equals(code) ? 15_000L : 3_000L);
					s.controlFailures = Math.max(1, s.controlFailures);
				}
			});
		}
	}

	/** Darf dieser Gast jetzt verbinden (angenommen, nicht gesperrt, Platz frei)? Beliebiger Thread. */
	boolean allowGuest(HostSession s, String uuid) {
		if (uuid == null || s.closed || s.blocked.contains(uuid)) return false;
		if (!s.acceptedMember(uuid)) return false;
		Rooms.Room r = s.room;
		int max = r == null ? s.options.maxPlayers : r.maxPlayers;
		boolean already = false;
		for (Attached a : s.attached) if (uuid.equals(a.uuid) && a.channel.isOpen()) already = true;
		return already || s.openGuests() < max - 1;
	}

	/** Gast-Strom an den integrierten Server hängen. Beliebiger Thread. */
	void attach(final HostSession s, PeerStream stream, String uuid) {
		if (s.closed || !allowGuest(s, uuid)) {
			stream.close("not allowed");
			return;
		}
		final String expected = s.memberName(uuid);
		final String host = selfName;
		try {
			TrsChannel ch = ServerAttach.attach(s.listener, stream, new LoginSniffer.Policy() {
				@Override
				public boolean allow(String name) {
					return expected != null && name.equalsIgnoreCase(expected) && (host == null || !name.equalsIgnoreCase(host));
				}
			});
			s.attached.add(new Attached(uuid, expected, ch, stream.path()));
			log("TRS Hosting: Gast " + expected + " verbunden (" + stream.path() + ")");
			post(new Runnable() {
				@Override
				public void run() {
					generation++;
				}
			});
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			stream.close("attach failed");
			log("TRS Hosting: Gast konnte nicht angehängt werden: " + e);
		}
	}

	/** Öffentlicher Link: einen fremden Strom anhängen (nur Namensprüfung gegen den Host). */
	boolean attachPublic(PeerStream stream) {
		final HostSession s = session;
		if (s == null || s.closed) {
			stream.close("not hosting");
			return false;
		}
		Rooms.Room r = s.room;
		int max = r == null ? s.options.maxPlayers : r.maxPlayers;
		if (s.openGuests() >= max - 1) {
			stream.close("full");
			return false;
		}
		final String host = selfName;
		final Set<String> blockedNames = s.blocked;
		try {
			final Attached[] holder = new Attached[1];
			TrsChannel ch = ServerAttach.attach(s.listener, stream, new LoginSniffer.Policy() {
				@Override
				public boolean allow(String name) {
					return (host == null || !name.equalsIgnoreCase(host)) && !blockedNames.contains("name:" + name.toLowerCase(Locale.ROOT));
				}
			});
			holder[0] = new Attached(null, null, ch, PeerStream.Path.PUBLIC);
			s.attached.add(holder[0]);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			stream.close("attach failed");
			return false;
		}
	}

	// --- Direkt (Host) ---

	private void onOffer(final String roomId, final SignalBox.Signal offer) {
		final HostSession s = session;
		final HostingPlatform p = platform;
		boolean ok = s != null && !s.closed && s.room != null && s.room.id.equals(roomId) && allowGuest(s, offer.from)
				&& directAllowed();
		final String t = token;
		if (!ok) {
			log("TRS Hosting P2P: Angebot abgelehnt (Gast nicht angenommen/gesperrt, Direkt aus oder keine Welt)");
			if (s != null && t != null && s.room != null && s.room.id.equals(roomId)) {
				submit(new Runnable() {
					@Override
					public void run() {
						try {
							api.signal(t, roomId, offer.from, "bye", offer.sid, "");
						} catch (IOException | ApiException | RuntimeException ignored) {
							// Gast fällt nach seinem Budget aufs Relay zurück
						}
					}
				});
			}
			return;
		}
		final Rooms.ConnectInfo ci = s.connect;
		submit(new Runnable() {
			@Override
			public void run() {
				List<InetSocketAddress> stun = DirectConnect.resolve(ci == null ? null : ci.stun);
				UdpLink link = DirectConnect.host(signals, signaller(roomId), offer, stun, loopback(), DirectConnect.BUDGET_MS,
						p.minecraftVersion(), p.loader());
				if (link == null) return;
				link.begin("TRS-P2P-Host");
				attach(s, link, offer.from);
			}
		});
	}

	private DirectConnect.Signaller signaller(final String roomId) {
		return new DirectConnect.Signaller() {
			@Override
			public void send(String to, String kind, String sid, String data) throws Exception {
				String t = token;
				if (t == null) throw new IOException("offline");
				api.signal(t, roomId, to, kind, sid, data);
			}
		};
	}

	static boolean loopback() {
		return Boolean.getBoolean("trsclient.hosting.loopback");
	}

	/** Direktverbindungen erlaubt (Einstellung)? Ohne: nur Relay – die IP bleibt verborgen. */
	static volatile boolean directEnabled = true;

	public static void directEnabled(boolean v) {
		directEnabled = v;
	}

	static boolean directAllowed() {
		return directEnabled && !Boolean.getBoolean("trsclient.hosting.relayOnly");
	}

	// --- Host: Mitglieder ---

	public void invite(final String uuid) {
		final Rooms.Room r = room();
		if (r == null || Uuids.normalize(uuid) == null) return;
		call(new ApiCall<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.invite(t, r.id, uuid, true);
				return Boolean.TRUE;
			}

			@Override
			public void done(Boolean value, String error) {
				if (error != null) notice(error, true);
				else notice("hosting.notice.invited", false);
			}
		});
	}

	public void revokeInvite(final String uuid) {
		final Rooms.Room r = room();
		if (r == null) return;
		call(new ApiCall<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.revokeInvite(t, r.id, uuid);
				return Boolean.TRUE;
			}

			@Override
			public void done(Boolean value, String error) {
				if (error != null) notice(error, true);
			}
		});
	}

	public void accept(final String uuid) {
		final Rooms.Room r = room();
		if (r == null) return;
		call(new ApiCall<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.accept(t, r.id, uuid);
				return Boolean.TRUE;
			}

			@Override
			public void done(Boolean value, String error) {
				if (error != null) notice(error, true);
				dismissRequestToast(uuid);
			}
		});
	}

	public void decline(final String uuid) {
		final Rooms.Room r = room();
		if (r == null) return;
		call(new ApiCall<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.decline(t, r.id, uuid);
				return Boolean.TRUE;
			}

			@Override
			public void done(Boolean value, String error) {
				if (error != null) notice(error, true);
				dismissRequestToast(uuid);
			}
		});
	}

	/** Spieler entfernen: API (+ Sperre / dauerhaft merken), Relay-KICK, lokal trennen. */
	public void kick(final String uuid, final boolean ban, final boolean remember) {
		final HostSession s = session;
		if (s == null || uuid == null) return;
		s.blocked.add(uuid);
		RelayControl c = s.control;
		final String name = nameOf(s, uuid);
		disconnectLocal(s, uuid, name);
		if (c != null && uuid.matches("[0-9a-f]{32}")) {
			final RelayControl cc = c;
			submit(new Runnable() {
				@Override
				public void run() {
					cc.kick(uuid);
				}
			});
		}
		final Rooms.Room r = s.room;
		if (r == null) return;
		call(new ApiCall<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.kick(t, r.id, uuid, ban || remember, remember);
				return Boolean.TRUE;
			}

			@Override
			public void done(Boolean value, String error) {
				if (error != null && !error.equals("hosting.error.generic")) notice(error, true);
				else notice(ban || remember ? "hosting.notice.banned" : "hosting.notice.kicked", false, name == null ? "?" : name);
				if (!ban && !remember) s.blocked.remove(uuid);
			}
		});
	}

	/** Spieler über den öffentlichen Link (ohne TRS-Konto) trennen und für diese Sitzung sperren. */
	public void kickPublic(String name) {
		HostSession s = session;
		if (s == null || name == null) return;
		s.blocked.add("name:" + name.toLowerCase(Locale.ROOT));
		disconnectLocal(s, null, name);
	}

	private static String nameOf(HostSession s, String uuid) {
		String n = s.memberName(uuid);
		if (n != null) return n;
		HostingPlatform p = platform;
		if (p != null) {
			for (HostingPlatform.Player pl : p.players()) if (uuid.equals(pl.uuid)) return pl.name;
		}
		return null;
	}

	private void disconnectLocal(HostSession s, String uuid, String name) {
		HostingPlatform p = platform;
		boolean done = false;
		try {
			if (p != null && uuid != null) done = p.disconnect(uuid, I18n.tr("hosting.kickMessage"));
		} catch (RuntimeException | LinkageError ignored) {
			// dann über den Kanal
		}
		for (Attached a : s.attached) {
			boolean match = (uuid != null && uuid.equals(a.uuid)) || (name != null && a.name != null && name.equalsIgnoreCase(a.name));
			if (match && (!done || a.channel.isOpen())) a.channel.close();
		}
		generation++;
	}

	/** Rechte setzen (OP / Zuschauer / Bauen). */
	public void setRights(String uuid, PlayerRights r) {
		HostSession s = session;
		if (s == null || uuid == null || r == null) return;
		if (r.isDefault()) s.rights.remove(uuid);
		else s.rights.put(uuid, r);
		applyRights(s, uuid, r);
		generation++;
	}

	private void applyRights(HostSession s, String uuid, PlayerRights r) {
		HostingPlatform p = platform;
		if (p == null) return;
		// „Cheats (für alle)“ = jeder Gast darf Befehle (OP); sonst nur, wer das Recht OP hat.
		boolean op = r.op || s.options.cheats;
		// OP nur zurücknehmen, wenn wir es vergeben haben (fremde /op-Einträge bleiben).
		Boolean opChange = op ? Boolean.TRUE : s.opGranted.contains(uuid) ? Boolean.FALSE : null;
		try {
			p.applyRights(uuid, r.gameMode(s.options.gameMode), opChange);
			s.applied.put(uuid, r);
			if (op) s.opGranted.add(uuid);
			else s.opGranted.remove(uuid);
		} catch (RuntimeException | LinkageError e) {
			log("TRS Hosting: Rechte: " + e);
		}
	}

	/** Sperre in dieser Welt aufheben. */
	public void unban(final String uuid) {
		final HostSession s = session;
		if (s == null || s.room == null) return;
		s.blocked.remove(uuid);
		final String roomId = s.room.id;
		call(new ApiCall<Boolean>() {
			@Override
			public Boolean run(String t) throws IOException, ApiException {
				api.unban(t, roomId, uuid);
				return Boolean.TRUE;
			}

			@Override
			public void done(Boolean value, String error) {
				if (error != null) notice(error, true);
			}
		});
	}

	private void dismissRequestToast(String uuid) {
		// Toasts verschwinden von selbst; die Liste aktualisiert das hosting_room-Ereignis.
		generation++;
	}

	// --- Gast ---

	/** Einer Welt beitreten (Einladung, Weltkarte, Freundesliste). */
	public void joinRoom(String roomId) {
		if (!Rooms.validRoomId(roomId)) return;
		join(roomId, null);
	}

	/** „Mit Code beitreten“. false = Code ungültig (Oberfläche zeigt das). */
	public boolean joinCode(String code) {
		String c = Rooms.normalizeCode(code);
		if (c == null) return false;
		join(null, c);
		return true;
	}

	private void join(final String roomId, final String code) {
		if (token == null) {
			notice("hosting.error.offline", true);
			return;
		}
		if (guestState == GuestState.CONNECTING || guestState == GuestState.JOINING) return;
		resetGuest();
		guestState = GuestState.JOINING;
		final int attempt = ++guestAttempt;
		generation++;
		final String t = token;
		submit(new Runnable() {
			@Override
			public void run() {
				try {
					final HostingApi.Joined j = api.join(t, roomId, code);
					post(new Runnable() {
						@Override
						public void run() {
							if (attempt != guestAttempt) return;
							guestRoom = j.room;
							if (j.room != null) friendsRooms.put(j.room.id, j.room);
							if (!j.accepted) {
								guestState = GuestState.WAITING;
								generation++;
								notice("hosting.notice.requested", false, j.room == null ? "?" : j.room.hostName);
								return;
							}
							connectTo(j.room, j.connect);
						}
					});
				} catch (final ApiException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (e.unauthorized()) backend.unauthorized(t);
							if (attempt != guestAttempt) return;
							resetGuest();
							notice(errorKey(e.code()), true);
						}
					});
				} catch (IOException e) {
					post(new Runnable() {
						@Override
						public void run() {
							if (attempt != guestAttempt) return;
							resetGuest();
							notice("hosting.error.network", true);
						}
					});
				}
			}
		});
	}

	/** Prüft Version/Loader: null = passt; sonst i18n-Schlüssel (Version = blockiert). */
	public String compatibility(Rooms.Room room) {
		HostingPlatform p = platform;
		if (p == null || room == null) return null;
		if (!room.mcVersion.equals(p.minecraftVersion())) return "hosting.error.version";
		if (!room.loader.equals(p.loader()) && !room.loader.equals("vanilla")) return "hosting.warn.loader";
		return null;
	}

	private void connectTo(final Rooms.Room room, final Rooms.ConnectInfo first) {
		final HostingPlatform p = platform;
		if (room == null || first == null || p == null) {
			resetGuest();
			notice("hosting.error.generic", true);
			return;
		}
		String compat = compatibility(room);
		if ("hosting.error.version".equals(compat)) {
			resetGuest();
			notice(compat, true, room.mcVersion, loaderName(room.loader), p.minecraftVersion(), loaderName(p.loader()));
			return;
		}
		if (compat != null) notice(compat, false, loaderName(room.loader), loaderName(p.loader()));
		guestRoom = room;
		guestState = GuestState.CONNECTING;
		final int attempt = guestAttempt;
		generation++;
		final String t = token;
		final boolean direct = directAllowed();
		submit(new Runnable() {
			@Override
			public void run() {
				PeerStream stream = null;
				String error = null;
				if (direct) {
					List<InetSocketAddress> stun = DirectConnect.resolve(first.stun);
					UdpLink link = DirectConnect.guest(signals, signaller(room.id), room.hostUuid, stun, loopback(),
							DirectConnect.BUDGET_MS, p.minecraftVersion(), p.loader());
					if (link != null) {
						link.begin("TRS-P2P-Gast");
						stream = link;
					}
				}
				if (stream == null && attempt == guestAttempt) {
					Rooms.ConnectInfo ci = first;
					for (int i = 0; i < 3 && stream == null; i++) {
						try {
							if (i > 0 || ci.expiresAt < System.currentTimeMillis() + 5000) ci = api.connect(t, room.id);
							stream = Relay.guest(ci.relayHost, ci.tcpPort, ci.token);
						} catch (Relay.RelayException e) {
							error = "hosting.error." + e.code;
							if (e.tokenProblem()) continue;
							if (e.backOff()) {
								sleep(5000L * (i + 1));
								continue;
							}
							break;
						} catch (ApiException e) {
							if (e.unauthorized()) backend.unauthorized(t);
							error = errorKey(e.code());
							break;
						} catch (IOException | RuntimeException e) {
							error = "hosting.error.relay_failed";
							break;
						}
					}
				}
				final PeerStream ready = stream;
				final String err = error;
				post(new Runnable() {
					@Override
					public void run() {
						if (attempt != guestAttempt || guestState != GuestState.CONNECTING) {
							if (ready != null) ready.close("cancelled");
							return;
						}
						if (ready == null) {
							resetGuest();
							notice(err == null ? "hosting.error.relay_failed" : knownOr(err), true);
							return;
						}
						String address;
						try {
							address = p.channelConnect() ? TrsConnect.offer(ready) : LoopbackBridge.open(ready);
						} catch (IOException | RuntimeException e) {
							ready.close("bridge");
							resetGuest();
							notice("hosting.error.generic", true);
							return;
						}
						guestStream = ready;
						guestPath = ready.path();
						guestAddress = address;
						guestState = GuestState.CONNECTED;
						generation++;
						log("TRS Hosting: verbinde mit " + room.hostName + " (" + ready.path() + ")");
						p.connect(address, room.name);
					}
				});
			}
		});
	}

	/** Beitritt abbrechen (Warten auf Annahme, Verbinden). Beim Warten wird die Anfrage zurückgezogen. */
	public void cancelJoin() {
		final Rooms.Room r = guestRoom;
		GuestState st = guestState;
		guestAttempt++;
		resetGuest();
		if (st == GuestState.WAITING && r != null) {
			call(new ApiCall<Boolean>() {
				@Override
				public Boolean run(String t) throws IOException, ApiException {
					api.leave(t, r.id);
					return Boolean.TRUE;
				}

				@Override
				public void done(Boolean value, String error) {
					// still
				}
			});
		}
	}

	private void resetGuest() {
		if (guestAddress != null && guestState != GuestState.CONNECTED) TrsConnect.cancel(guestAddress);
		guestState = GuestState.IDLE;
		guestStream = null;
		guestPath = null;
		guestAddress = null;
		guestRoom = null;
		generation++;
	}

	/** Welten der Freunde neu laden (Bildschirm offen), höchstens alle 30 s. */
	public void refreshFriendsRooms(boolean force) {
		long now = System.currentTimeMillis();
		if (friendsRoomsBusy || token == null || (!force && now - friendsRoomsAt < FRIENDS_ROOMS_TTL_MS)) return;
		friendsRoomsBusy = true;
		friendsRoomsAt = now;
		call(new ApiCall<List<Rooms.Room>>() {
			@Override
			public List<Rooms.Room> run(String t) throws IOException, ApiException {
				return api.friendsRooms(t);
			}

			@Override
			public void done(List<Rooms.Room> value, String error) {
				friendsRoomsBusy = false;
				if (value != null) {
					friendsRooms.clear();
					for (Rooms.Room r : value) friendsRooms.put(r.id, r);
					generation++;
				}
			}
		});
		backend.wantFriends();
	}

	// --- Ereignisse (Spiel-Thread, aus Social) ---

	/** {@code hosting_*}, {@code hello}, {@code resync} aus {@code /v1/events/me}. */
	@SuppressWarnings("deprecation")
	public void event(MeEvent e) {
		String type = e.type;
		if (type.equals("hello") || type.equals("resync")) {
			if (type.equals("resync") || !e.resumed) friendsRoomsAt = 0;
			return;
		}
		JsonObject d;
		try {
			JsonElement el = new JsonParser().parse(e.hostingData == null ? "{}" : e.hostingData);
			if (!el.isJsonObject()) return;
			d = el.getAsJsonObject();
		} catch (RuntimeException ex) {
			return;
		}
		Rooms.Room room = d.has("room") ? HostingApi.room(gson(d.get("room"))) : null;
		String roomId = str(d, "roomId");
		if (roomId != null && !Rooms.validRoomId(roomId)) return;
		long now = System.currentTimeMillis();
		Toasts toasts = backend.toasts();
		HostSession s = session;
		if (type.equals("hosting_signal")) {
			String from = Uuids.normalize(str(d, "from"));
			String kind = str(d, "kind");
			String sid = str(d, "sid");
			String data = str(d, "data");
			if (from == null || kind == null || !kind.matches("offer|answer|candidate|bye")) return;
			if (sid != null && !sid.matches("[A-Za-z0-9_-]{1,32}")) return;
			signals.deliver(roomId, new SignalBox.Signal(from, kind, sid, data));
			return;
		}
		if (type.equals("hosting_room")) {
			if (s != null && room != null && s.room != null && room.id.equals(s.room.id)) {
				s.room = room;
				generation++;
			}
			return;
		}
		if (type.equals("hosting_join_request")) {
			String[] from = user(d);
			if (from == null || s == null || s.room == null || !s.room.id.equals(roomId)) return;
			if (toasts != null) {
				toasts.add(Toasts.Kind.JOIN_REQUEST, "joinreq:" + from[0], from[1], I18n.tr("hosting.toast.request"), from[0],
						from[1], roomId, null, now);
			}
			generation++;
			return;
		}
		if (type.equals("hosting_invite")) {
			String[] from = user(d);
			if (room == null) return;
			friendsRooms.put(room.id, room);
			generation++;
			if (toasts != null && from != null) {
				toasts.add(Toasts.Kind.WORLD_INVITE, "world:" + room.id, from[1], I18n.tr("hosting.toast.invite", from[1]),
						from[0], from[1], room.id, null, now);
			}
			return;
		}
		if (type.equals("hosting_join_accepted")) {
			if (room == null) return;
			friendsRooms.put(room.id, room);
			generation++;
			if (guestState == GuestState.WAITING && guestRoom != null && guestRoom.id.equals(room.id)) {
				final Rooms.Room r = room;
				final int attempt = guestAttempt;
				guestState = GuestState.JOINING;
				call(new ApiCall<Rooms.ConnectInfo>() {
					@Override
					public Rooms.ConnectInfo run(String t) throws IOException, ApiException {
						return api.connect(t, r.id);
					}

					@Override
					public void done(Rooms.ConnectInfo value, String error) {
						if (attempt != guestAttempt) return;
						if (value == null) {
							resetGuest();
							notice(error == null ? "hosting.error.generic" : error, true);
							return;
						}
						connectTo(r, value);
					}
				});
			} else if (toasts != null) {
				toasts.add(Toasts.Kind.WORLD_INVITE, "world:" + room.id, room.hostName, I18n.tr("hosting.toast.accepted"),
						room.hostUuid, room.hostName, room.id, null, now);
			}
			return;
		}
		if (type.equals("hosting_join_declined")) {
			Rooms.Room r = roomId == null ? null : friendsRooms.remove(roomId);
			if (guestState == GuestState.WAITING && guestRoom != null && guestRoom.id.equals(roomId)) {
				r = guestRoom;
				resetGuest();
				notice("hosting.notice.declined", true, r.hostName);
			}
			if (toasts != null && r != null) {
				toasts.add(Toasts.Kind.WORLD, "worldinfo:" + roomId, r.hostName, I18n.tr("hosting.toast.declined"), r.hostUuid,
						r.hostName, roomId, null, now);
			}
			generation++;
			return;
		}
		if (type.equals("hosting_kicked")) {
			Rooms.Room r = roomId == null ? null : friendsRooms.remove(roomId);
			boolean banned = d.has("banned") && d.get("banned").isJsonPrimitive() && d.get("banned").getAsBoolean();
			if (guestRoom != null && guestRoom.id.equals(roomId)) {
				if (r == null) r = guestRoom;
				guestAttempt++;
				resetGuest();
			}
			if (toasts != null) {
				toasts.add(Toasts.Kind.WORLD, "worldinfo:" + roomId, r == null ? I18n.tr("hosting.title") : r.hostName,
						I18n.tr(banned ? "hosting.toast.banned" : "hosting.toast.kicked"), r == null ? null : r.hostUuid,
						r == null ? null : r.hostName, roomId, null, now);
			}
			generation++;
			return;
		}
		if (type.equals("hosting_room_updated")) {
			if (room != null && (friendsRooms.containsKey(room.id) || room.open)) {
				friendsRooms.put(room.id, room);
				if (guestRoom != null && guestRoom.id.equals(room.id)) guestRoom = room;
				generation++;
			}
			return;
		}
		if (type.equals("hosting_invite_revoked")) {
			if (roomId != null) {
				Rooms.Room r = friendsRooms.get(roomId);
				if (r != null && "invited".equals(r.myState)) friendsRooms.remove(roomId);
				generation++;
			}
			return;
		}
		if (type.equals("hosting_room_closed")) {
			String reason = str(d, "reason");
			if (roomId == null) return;
			friendsRooms.remove(roomId);
			if (s != null && s.room != null && s.room.id.equals(roomId) && !s.closed) {
				if ("expired".equals(reason)) recreateRoom(s);
				else if ("replaced".equals(reason) || "host_unavailable".equals(reason)) stopHosting("replaced");
			}
			if (guestRoom != null && guestRoom.id.equals(roomId) && guestState != GuestState.CONNECTED) {
				guestAttempt++;
				resetGuest();
				notice("hosting.error.world_closed", true);
			}
			generation++;
		}
	}

	private static HostingApi.RoomDto gson(JsonElement e) {
		try {
			return HostingApi.GSON.fromJson(e, HostingApi.RoomDto.class);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	/** {from:{uuid,name}} → [uuid, name] oder null. */
	private static String[] user(JsonObject d) {
		JsonElement f = d.get("from");
		if (f == null || !f.isJsonObject()) return null;
		String u = Uuids.normalize(str(f.getAsJsonObject(), "uuid"));
		if (u == null) return null;
		return new String[] { u, Rooms.player(str(f.getAsJsonObject(), "name")) };
	}

	// --- Hilfen ---

	/** Bekannte Fehlercodes mit eigener Meldung. */
	static final Set<String> KNOWN_ERRORS = new HashSet<String>(Arrays.asList("hosting_unavailable", "room_not_found",
			"banned_from_world", "world_closed", "room_full", "too_many_requests", "cannot_join_own_world", "not_friends",
			"player_banned", "too_many_invites", "invalid_name", "message_blocked", "room_too_small", "not_accepted",
			"rate_limited", "invalid_code", "host_offline", "host_timeout", "kicked", "relay_failed", "no_world",
			"backup_failed", "publish_failed", "network", "offline", "version", "ban_limit", "too_many_connections",
			"server_full", "shutting_down", "timeout"));

	static String errorKey(String code) {
		return KNOWN_ERRORS.contains(code) ? "hosting.error." + code : "hosting.error.generic";
	}

	static String knownOr(String key) {
		String code = key.startsWith("hosting.error.") ? key.substring("hosting.error.".length()) : key;
		return errorKey(code);
	}

	public static String loaderName(String loader) {
		if (loader == null) return "?";
		if (loader.equals("neoforge")) return "NeoForge";
		return loader.isEmpty() ? loader : Character.toUpperCase(loader.charAt(0)) + loader.substring(1);
	}

	void notice(String key, boolean error, Object... args) {
		notice = new Notice(key, args, error, System.currentTimeMillis());
		generation++;
	}

	void log(String msg) {
		HostingPlatform p = platform;
		if (p != null) {
			try {
				p.log(msg);
			} catch (RuntimeException ignored) {
				// egal
			}
		}
	}

	void post(Runnable r) {
		results.add(r);
	}

	void submit(Runnable r) {
		try {
			worker.execute(r);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			log("TRS Hosting: zu viele Aufgaben");
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Eine API-Aktion im Hintergrund mit Ergebnis im Spiel-Thread. */
	interface ApiCall<T> {
		T run(String token) throws IOException, ApiException;

		void done(T value, String error);
	}

	<T> void call(final ApiCall<T> c) {
		final String t = token;
		if (t == null) {
			c.done(null, "hosting.error.offline");
			return;
		}
		submit(new Runnable() {
			@Override
			public void run() {
				T value = null;
				String error = null;
				try {
					value = c.run(t);
				} catch (ApiException e) {
					if (e.unauthorized()) backend.unauthorized(t);
					error = errorKey(e.code());
				} catch (IOException | RuntimeException e) {
					error = "hosting.error.network";
				}
				final T v = value;
				final String err = error;
				post(new Runnable() {
					@Override
					public void run() {
						c.done(v, err);
					}
				});
			}
		});
	}

	/** Für Tests. */
	SignalBox signals() {
		return signals;
	}

	HostSession session() {
		return session;
	}
}

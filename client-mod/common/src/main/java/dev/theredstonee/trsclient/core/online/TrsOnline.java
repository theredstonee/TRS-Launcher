package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.cape.CapeDiskCache;
import dev.theredstonee.trsclient.core.cape.CapeFrames;
import dev.theredstonee.trsclient.core.cape.PngDecoder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Verbindung des TRS Clients zur TRS API: Anmeldung über die Spiel-Sitzung (Mojang-Join), Lookup der
 * sichtbaren Spieler (Abzeichen/Umhang), Presence-Herzschlag und das Laden der Umhang-Texturen.
 *
 * <p>Alles Netzwerk läuft in zwei Hintergrund-Threads (API, Texturen); der Spiel-Thread ruft nur
 * {@link #tick} (billig, nie blockierend) und liest Ergebnisse. Das Token lebt nur im Speicher; bei 401
 * wird neu angemeldet. Ist die API im Launcher abgeschaltet ({@code trs-api.json}) oder das Modul aus,
 * passiert gar nichts.
 */
public final class TrsOnline {
	public static final long PRESENCE_INTERVAL_MS = 60_000L;
	static final long ME_INTERVAL_MS = 10 * 60_000L;
	/** Liste der freigeschalteten Emotes: so oft neu holen (bzw. bei Fehlern erneut versuchen). */
	static final long EMOTES_INTERVAL_MS = 10 * 60_000L;
	static final long EMOTES_RETRY_MS = 30_000L;
	/** Frühestens so bald nach dem letzten Abruf auf Wunsch (Rad geöffnet, Emote gesperrt) erneut. */
	static final long EMOTES_MIN_REFRESH_MS = 5_000L;
	static final long[] LOGIN_BACKOFF_MS = {30_000L, 2 * 60_000L, 10 * 60_000L, 30 * 60_000L};

	/** Zustand für das Menü. */
	public enum Status {
		OFF("Aus"), LAUNCHER_OFF("Im Launcher abgeschaltet"), NO_ACCOUNT("Kein Online-Konto"),
		CONNECTING("Verbinde …"), ONLINE("Verbunden"), RETRY("Nicht erreichbar – neuer Versuch später"),
		BANNED("Konto gesperrt");

		private final String label;

		Status(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	private final OnlineConfig config;
	private final OnlinePlatform platform;
	private final TrsApi api;
	private final CapeDiskCache capeCache;
	private final PlayerDirectory directory = new PlayerDirectory();
	private final ThreadPoolExecutor apiWorker;
	private final ThreadPoolExecutor capeWorker;
	/** Ergebnisse der Hintergrund-Threads, abgearbeitet im nächsten {@link #tick}. */
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
	/** Live-Ereignisse über sichtbare Spieler (Emotes), nur solange Emotes sie brauchen. */
	private final PlayerEventStream events;
	private final List<PlayerEvent> eventBuffer = new ArrayList<>();
	private final List<PlayerEvent> emoteEvents = new ArrayList<>();

	// Nur Spiel-Thread:
	private String sessionUuid;
	private boolean loginInFlight;
	private long nextLoginAt;
	private int loginFailures;
	private long lastPresence = Long.MIN_VALUE / 2;
	private boolean presenceInFlight;
	private long lastMe;
	private boolean banned;
	private long lastPrune;
	private boolean active;
	private boolean wantEmotes;
	private boolean wantEvents;
	private long lastEmoteFetch = Long.MIN_VALUE / 2;
	private boolean emoteFetchInFlight;
	private boolean emoteFetchFailed;
	private long lastEventsUpdate = Long.MIN_VALUE / 2;
	private boolean observedOnce;

	// Aus beiden Threads gelesen:
	private volatile String token;
	private volatile Boolean shareServer;
	private volatile Status status = Status.OFF;
	private volatile String ownUuid;
	/** Freigeschaltete Emotes laut API (null = noch unbekannt). */
	private volatile List<String> unlockedEmotes;

	public TrsOnline(OnlineConfig config, OnlinePlatform platform, Http http, Path capeDir) {
		this(config, platform, http, capeDir, new PlayerEventStream.UrlOpener("TRS-Client"));
	}

	public TrsOnline(OnlineConfig config, OnlinePlatform platform, Http http, Path capeDir,
			PlayerEventStream.Opener eventOpener) {
		this.config = config;
		this.platform = platform;
		this.api = new TrsApi(http, config);
		this.capeCache = new CapeDiskCache(capeDir);
		this.apiWorker = worker("TRS-Online");
		this.capeWorker = worker("TRS-Umhaenge");
		this.events = new PlayerEventStream(eventOpener, config.apiBase());
	}

	/** Standard: HttpURLConnection, Umhang-Cache unter {@code <configDir>/trsclient/capes}. */
	public static TrsOnline create(Path configDir, OnlinePlatform platform, String modVersion) {
		OnlineConfig config = OnlineConfig.load(configDir);
		String userAgent = "TRS-Client/" + modVersion + " (Minecraft " + platform.minecraftVersion() + "; "
				+ platform.loader() + ")";
		Http http = new Http.UrlConnection(userAgent);
		return new TrsOnline(config, platform, http, configDir.resolve("trsclient").resolve("capes"),
				new PlayerEventStream.UrlOpener(userAgent));
	}

	private static ThreadPoolExecutor worker(String name) {
		ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32),
				r -> {
					Thread t = new Thread(r, name);
					t.setDaemon(true);
					t.setPriority(Thread.MIN_PRIORITY + 1);
					return t;
				});
		ex.allowCoreThreadTimeOut(true);
		return ex;
	}

	/**
	 * Aus dem Client-Tick (Spiel-Thread): Ergebnisse übernehmen, bei Bedarf anmelden, Lookup-Stapel und
	 * Presence verschicken. {@code visible} = UUIDs aus Tabliste und Sichtweite, {@code null} = unverändert
	 * seit dem letzten Aufruf (der Loader sammelt sie nur einmal je Sekunde).
	 */
	public void tick(long now, Collection<UUID> visible, boolean moduleEnabled) {
		Runnable r;
		while ((r = results.poll()) != null) r.run();
		if (!config.launcherEnabled()) {
			// Keine Einwilligung im Launcher: kein einziger Aufruf.
			status = Status.LAUNCHER_OFF;
			events.stop();
			return;
		}
		if (!moduleEnabled) {
			status = Status.OFF;
			active = false;
			events.stop();
			return;
		}
		if (banned) {
			status = Status.BANNED;
			events.stop();
			return;
		}
		GameSession session = platform.session();
		if (session != null && session.uuid == null && session.name != null && devMock()) {
			// Nur gegen die lokale Test-Attrappe: Entwicklungsstarts ohne UUID bekommen die Offline-UUID des Namens.
			session = new GameSession(Uuids.of(UUID.nameUUIDFromBytes(("OfflinePlayer:" + session.name)
					.getBytes(java.nio.charset.StandardCharsets.UTF_8))), session.name, session.accessToken);
		}
		if (session == null || session.uuid == null || !(session.usable() || devMock())) {
			status = Status.NO_ACCOUNT;
			events.stop();
			return;
		}
		if (!session.uuid.equals(sessionUuid)) {
			// Anderes Konto (oder erster Tick): alles neu.
			sessionUuid = session.uuid;
			ownUuid = session.uuid;
			token = null;
			shareServer = null;
			directory.clear();
			loginFailures = 0;
			nextLoginAt = 0;
			unlockedEmotes = null;
			lastEmoteFetch = Long.MIN_VALUE / 2;
			lastEventsUpdate = Long.MIN_VALUE / 2;
			observedOnce = false;
			events.stop();
		}
		active = true;
		if (token == null) {
			if (!loginInFlight && now >= nextLoginAt) login(session);
			if (status != Status.RETRY) status = Status.CONNECTING;
			events.stop();
			return;
		}
		status = Status.ONLINE;

		// Sichtbare Spieler meldet der Loader höchstens einmal je Sekunde (null = diesmal nicht).
		if (visible != null) {
			List<String> uuids = new ArrayList<>(visible.size() + 1);
			uuids.add(session.uuid);
			for (UUID u : visible) {
				String s = Uuids.of(u);
				if (s != null) uuids.add(s);
			}
			directory.observe(uuids, now);
		} else if (!observedOnce) {
			directory.observe(java.util.Collections.singletonList(session.uuid), now);
		}
		observedOnce = true;
		List<String> batch = directory.nextBatch(now);
		if (!batch.isEmpty()) lookup(batch);
		if (!presenceInFlight && now - lastPresence >= PRESENCE_INTERVAL_MS) presence(now);
		if (now - lastMe >= ME_INTERVAL_MS) refreshMe(now);
		if (now - lastPrune > 60_000L) {
			lastPrune = now;
			directory.prune(now);
		}
		tickEmotes(now, session.uuid);
	}

	// --- Emotes (API.md §12, §13) ---

	/**
	 * Emote-Liste bei Bedarf holen und den Ereignis-Stream (eigene UUID + sichtbare TRS-Spieler) pflegen –
	 * beides nur, solange das Emote-Modul es verlangt ({@link #wantEmotes}).
	 */
	private void tickEmotes(long now, String self) {
		if (wantEmotes && !emoteFetchInFlight) {
			long age = now - lastEmoteFetch;
			boolean due = unlockedEmotes == null || emoteFetchFailed ? age >= EMOTES_RETRY_MS : age >= EMOTES_INTERVAL_MS;
			if (due) fetchEmotes(now);
		}
		if (!wantEvents) {
			events.stop();
			return;
		}
		// Stream-Verwaltung (Spielerliste, Neuverbinden) einmal je Sekunde; Ereignisse jeden Tick abholen.
		if (now - lastEventsUpdate >= 1000L || now < lastEventsUpdate) {
			lastEventsUpdate = now;
			List<String> watch = new ArrayList<>();
			watch.add(self);
			watch.addAll(directory.visibleUsers());
			events.update(now, token, watch);
			if (events.takeUnauthorized()) relogin(token);
		}
		eventBuffer.clear();
		events.drain(eventBuffer);
		for (PlayerEvent e : eventBuffer) {
			if (e.type.equals("emote")) {
				if (emoteEvents.size() < 256) emoteEvents.add(e);
			} else if (e.uuid != null) {
				// Umhang/Kosmetik/Skin geändert → beim nächsten Stapel neu nachschlagen.
				directory.invalidate(e.uuid);
			}
		}
	}

	private void fetchEmotes(long now) {
		String t = token;
		emoteFetchInFlight = true;
		lastEmoteFetch = now;
		if (!submit(apiWorker, () -> {
			try {
				List<String> ids = api.emotes(t);
				results.add(() -> {
					emoteFetchInFlight = false;
					emoteFetchFailed = false;
					unlockedEmotes = java.util.Collections.unmodifiableList(ids);
				});
			} catch (ApiException e) {
				results.add(() -> {
					emoteFetchInFlight = false;
					emoteFetchFailed = true;
					if (e.unauthorized()) relogin(t);
					else if (e.rateLimited()) lastEmoteFetch = System.currentTimeMillis() + e.retryAfterMs() - EMOTES_RETRY_MS;
				});
			} catch (IOException | RuntimeException e) {
				results.add(() -> {
					emoteFetchInFlight = false;
					emoteFetchFailed = true;
				});
			}
		})) emoteFetchInFlight = false;
	}

	/**
	 * Vom Emote-Modul in jedem Tick: {@code list} = freigeschaltete Emotes gebraucht, {@code stream} = Ereignisse
	 * anderer Spieler gebraucht. Ohne beides macht TrsOnline keine Emote-Anfragen.
	 */
	public void wantEmotes(boolean list, boolean stream) {
		wantEmotes = list;
		wantEvents = stream;
	}

	/** Freigeschaltete Emote-IDs laut API (null = noch unbekannt). */
	public List<String> unlockedEmotes() {
		return unlockedEmotes;
	}

	/**
	 * Liste bald neu holen (Rad geöffnet, Emote war gesperrt), wenn der letzte Abruf älter als {@code maxAgeMs}
	 * ist – nie öfter als alle {@link #EMOTES_MIN_REFRESH_MS}.
	 */
	public void refreshEmotes(long now, long maxAgeMs) {
		if (now - lastEmoteFetch < Math.max(EMOTES_MIN_REFRESH_MS, maxAgeMs)) return;
		lastEmoteFetch = Long.MIN_VALUE / 2;
	}

	/** Ergebnis von {@link #playEmote} (im Spiel-Thread). */
	public interface PlayCallback {
		/** {@code error} null = gespielt ({@code durationMs} laut Server, 0 = keine Angabe). */
		void done(int durationMs, ApiException error);
	}

	/**
	 * {@code POST /v1/emotes/play} im Hintergrund. Ohne Anmeldung sofort Fehler {@code offline}. Die Wartezeit
	 * zwischen zwei Emotes (1 / 2 s) hält der Aufrufer ein.
	 */
	public void playEmote(String emote, PlayCallback callback) {
		String t = token;
		if (t == null || !active || !config.launcherEnabled()) {
			callback.done(0, new ApiException(0, "offline", 0));
			return;
		}
		if (!submit(apiWorker, () -> {
			try {
				int duration = api.playEmote(t, emote);
				results.add(() -> callback.done(duration, null));
			} catch (ApiException e) {
				results.add(() -> {
					if (e.unauthorized()) relogin(t);
					callback.done(0, e);
				});
			} catch (IOException | RuntimeException e) {
				results.add(() -> callback.done(0, new ApiException(0, "offline", 0)));
			}
		})) callback.done(0, new ApiException(0, "busy", 0));
	}

	/** Seit dem letzten Aufruf empfangene Emote-Ereignisse (älteste zuerst); leert die Liste. */
	public List<PlayerEvent> pollEmoteEvents() {
		if (emoteEvents.isEmpty()) return java.util.Collections.emptyList();
		List<PlayerEvent> out = new ArrayList<>(emoteEvents);
		emoteEvents.clear();
		return out;
	}

	/** Steht der Ereignis-Stream? */
	public boolean eventsConnected() {
		return events.connected();
	}

	/** Hat der Launcher die TRS API erlaubt (Einwilligung)? */
	public boolean launcherEnabled() {
		return config.launcherEnabled();
	}

	private boolean devMock() {
		return config.apiBase().startsWith("http://");
	}

	// --- Hintergrund-Aufgaben ---

	private void login(GameSession session) {
		loginInFlight = true;
		if (!submit(apiWorker, () -> {
			try {
				TrsApi.Session s = api.login(session);
				results.add(() -> {
					loginInFlight = false;
					loginFailures = 0;
					token = s.token;
					applyMe(s.me);
					lastMe = System.currentTimeMillis();
					lastPresence = Long.MIN_VALUE / 2;
					lastEmoteFetch = Long.MIN_VALUE / 2;
					directory.invalidate(session.uuid);
					platform.log("TRS API: angemeldet");
				});
			} catch (ApiException e) {
				results.add(() -> loginFailed(e.banned(), e.retryAfterMs(), "HTTP " + e.status() + " " + e.code()));
			} catch (IOException | RuntimeException e) {
				results.add(() -> loginFailed(false, 0, e.getClass().getSimpleName()));
			}
		})) loginInFlight = false;
	}

	private void loginFailed(boolean isBanned, long retryAfterMs, String reason) {
		loginInFlight = false;
		if (isBanned) {
			banned = true;
			platform.log("TRS API: Konto gesperrt – Online-Funktionen aus");
			return;
		}
		long wait = LOGIN_BACKOFF_MS[Math.min(loginFailures, LOGIN_BACKOFF_MS.length - 1)];
		loginFailures++;
		nextLoginAt = System.currentTimeMillis() + Math.max(wait, retryAfterMs);
		status = Status.RETRY;
		platform.log("TRS API: Anmeldung fehlgeschlagen (" + reason + "), neuer Versuch in " + wait / 1000 + " s");
	}

	private void lookup(List<String> batch) {
		String t = token;
		if (!submit(apiWorker, () -> {
			long done;
			try {
				Map<String, PlayerInfo> found = api.lookup(t, batch);
				done = System.currentTimeMillis();
				long at = done;
				results.add(() -> directory.complete(batch, found, at));
			} catch (ApiException e) {
				done = System.currentTimeMillis();
				long at = done;
				if (e.unauthorized()) {
					results.add(() -> {
						directory.aborted();
						relogin(t);
					});
				} else {
					results.add(() -> directory.failed(at, e.retryAfterMs()));
				}
			} catch (IOException | RuntimeException e) {
				long at = System.currentTimeMillis();
				results.add(() -> directory.failed(at, 0));
			}
		})) directory.aborted();
	}

	private void presence(long now) {
		String t = token;
		String server = Boolean.TRUE.equals(shareServer) ? cleanServer(platform.serverAddress()) : null;
		String version = cleanVersion(platform.minecraftVersion());
		String loader = platform.loader();
		presenceInFlight = true;
		lastPresence = now;
		if (!submit(apiWorker, () -> {
			try {
				api.presence(t, version, loader, server);
				results.add(() -> presenceInFlight = false);
			} catch (ApiException e) {
				results.add(() -> {
					presenceInFlight = false;
					if (e.unauthorized()) relogin(t);
					else if (e.rateLimited()) lastPresence = System.currentTimeMillis() - PRESENCE_INTERVAL_MS
							+ Math.max(10_000L, e.retryAfterMs());
				});
			} catch (IOException | RuntimeException e) {
				// Nächster Versuch beim nächsten Intervall (Presence läuft nach 180 s ab – kein Drama).
				results.add(() -> presenceInFlight = false);
			}
		})) presenceInFlight = false;
	}

	private void refreshMe(long now) {
		String t = token;
		lastMe = now;
		submit(apiWorker, () -> {
			try {
				TrsApi.Me me = api.me(t);
				results.add(() -> applyMe(me));
			} catch (ApiException e) {
				if (e.unauthorized()) results.add(() -> relogin(t));
			} catch (IOException | RuntimeException ignored) {
				// beim nächsten Intervall erneut
			}
		});
	}

	private void applyMe(TrsApi.Me me) {
		if (me == null || me.settings == null) return;
		shareServer = me.settings.shareServer;
	}

	/** 401: Token verwerfen und (einmal sofort) neu anmelden. */
	private void relogin(String rejected) {
		if (token != null && token.equals(rejected)) {
			token = null;
			nextLoginAt = 0;
		}
	}

	private boolean submit(ThreadPoolExecutor ex, Runnable task) {
		try {
			ex.execute(task);
			return true;
		} catch (RejectedExecutionException e) {
			return false;
		}
	}

	/**
	 * Lädt einen Umhang (Platte oder Netz) und zerlegt ihn in Bilder – im Textur-Thread. Genau einer der
	 * Rückrufe läuft danach im Spiel-Thread (nächster Tick).
	 */
	public void loadCape(CapeInfo cape, java.util.function.Consumer<CapeFrames> done, Runnable failed) {
		String t = token;
		if (!submit(capeWorker, () -> {
			try {
				byte[] png = capeCache.load(cape, api, t);
				CapeFrames frames = CapeFrames.split(PngDecoder.decode(png), cape);
				results.add(() -> done.accept(frames));
			} catch (IOException | ApiException | RuntimeException e) {
				platform.log("TRS API: Umhang '" + cape.id + "' nicht ladbar (" + e.getMessage() + ")");
				results.add(failed);
			}
		})) failed.run();
	}

	// --- Lesen (jeder Thread) ---

	/** Lookup-Ergebnis eines Spielers ({@link PlayerInfo#NONE} = unbekannt/kein TRS). */
	public PlayerInfo info(UUID uuid) {
		if (!active || uuid == null) return PlayerInfo.NONE;
		return directory.get(uuid);
	}

	public Status status() {
		return status;
	}

	/** Eigene UUID (32 Hex) des angemeldeten Kontos, sonst null. */
	public String ownUuid() {
		return ownUuid;
	}

	public boolean online() {
		return token != null && active;
	}

	/**
	 * Token der aktuellen TRS-Anmeldung für weitere Dienste im Hintergrund (Garderobe/Sync) oder null. Nie loggen,
	 * nie auf Platte schreiben.
	 */
	public String token() {
		return active && config.launcherEnabled() ? token : null;
	}

	/** Adresse der TRS API (z. B. {@code https://trs-launcher.theredstonee.de}). */
	public String apiBase() {
		return config.apiBase();
	}

	/** 401 eines anderen Dienstes: Token verwerfen und neu anmelden. */
	public void tokenRejected(String rejected) {
		results.add(() -> relogin(rejected));
	}

	/** Eigenen Eintrag (Umhang/Abzeichen) beim nächsten Stapel neu nachschlagen (z. B. nach Umhangwechsel). */
	public void refreshSelf() {
		results.add(() -> {
			String self = ownUuid;
			if (self != null) directory.invalidate(self);
		});
	}

	OnlineConfig config() {
		return config;
	}

	PlayerDirectory directory() {
		return directory;
	}

	private long lastError;

	/** Fehler aus dem Tick (nie ans Spiel weiterreichen), höchstens einmal pro Minute ins Log. */
	public void reportError(RuntimeException e) {
		long now = System.currentTimeMillis();
		if (now - lastError < 60_000L) return;
		lastError = now;
		StackTraceElement[] st = e.getStackTrace();
		platform.log("TRS Client: Fehler in den Online-Funktionen ignoriert: " + e
				+ (st.length > 0 ? " bei " + st[0] : ""));
	}

	// --- Validierung der Presence-Felder (die API prüft ebenso streng) ---

	static String cleanVersion(String v) {
		if (v == null) return "unknown";
		String t = v.trim();
		if (t.length() > 32) t = t.substring(0, 32);
		return t.matches("[0-9A-Za-z._+ -]{1,32}") ? t : "unknown";
	}

	/** "Play.Example.net:25565" → "play.example.net:25565"; alles andere (Schema, Pfad, Leerzeichen) → null. */
	static String cleanServer(String address) {
		if (address == null) return null;
		String t = address.trim().toLowerCase(Locale.ROOT);
		if (t.isEmpty() || t.length() > 253) return null;
		return t.matches("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*(:[0-9]{1,5})?") ? t : null;
	}
}

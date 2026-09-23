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

	// Aus beiden Threads gelesen:
	private volatile String token;
	private volatile Boolean shareServer;
	private volatile Status status = Status.OFF;
	private volatile String ownUuid;

	public TrsOnline(OnlineConfig config, OnlinePlatform platform, Http http, Path capeDir) {
		this.config = config;
		this.platform = platform;
		this.api = new TrsApi(http, config);
		this.capeCache = new CapeDiskCache(capeDir);
		this.apiWorker = worker("TRS-Online");
		this.capeWorker = worker("TRS-Umhaenge");
	}

	/** Standard: HttpURLConnection, Umhang-Cache unter {@code <configDir>/trsclient/capes}. */
	public static TrsOnline create(Path configDir, OnlinePlatform platform, String modVersion) {
		OnlineConfig config = OnlineConfig.load(configDir);
		Http http = new Http.UrlConnection("TRS-Client/" + modVersion + " (Minecraft " + platform.minecraftVersion()
				+ "; " + platform.loader() + ")");
		return new TrsOnline(config, platform, http, configDir.resolve("trsclient").resolve("capes"));
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
	 * Presence verschicken. {@code visible} = UUIDs aus Tabliste und Sichtweite.
	 */
	public void tick(long now, Collection<UUID> visible, boolean moduleEnabled) {
		Runnable r;
		while ((r = results.poll()) != null) r.run();
		if (!config.launcherEnabled()) {
			status = Status.LAUNCHER_OFF;
			return;
		}
		if (!moduleEnabled) {
			status = Status.OFF;
			active = false;
			return;
		}
		if (banned) {
			status = Status.BANNED;
			return;
		}
		GameSession session = platform.session();
		if (session == null || !(session.usable() || devMock())) {
			status = Status.NO_ACCOUNT;
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
		}
		active = true;
		if (token == null) {
			if (!loginInFlight && now >= nextLoginAt) login(session);
			if (status != Status.RETRY) status = Status.CONNECTING;
			return;
		}
		status = Status.ONLINE;

		List<String> uuids = new ArrayList<>(visible.size() + 1);
		uuids.add(session.uuid);
		for (UUID u : visible) {
			String s = Uuids.of(u);
			if (s != null) uuids.add(s);
		}
		directory.observe(uuids, now);
		List<String> batch = directory.nextBatch(now);
		if (!batch.isEmpty()) lookup(batch);
		if (!presenceInFlight && now - lastPresence >= PRESENCE_INTERVAL_MS) presence(now);
		if (now - lastMe >= ME_INTERVAL_MS) refreshMe(now);
		if (now - lastPrune > 60_000L) {
			lastPrune = now;
			directory.prune(now);
		}
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
		return directory.get(Uuids.of(uuid));
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

	OnlineConfig config() {
		return config;
	}

	PlayerDirectory directory() {
		return directory;
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

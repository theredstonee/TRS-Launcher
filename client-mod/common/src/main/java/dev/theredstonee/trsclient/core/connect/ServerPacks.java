package dev.theredstonee.trsclient.core.connect;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-Ressourcenpakete für „Schnell verbinden“, in jeder Minecraft-Version gleich:
 * <ul>
 *   <li><b>Merken:</b> Welches Paket (URL, SHA-1, ID) ein Server schickte – nur wenn der Spieler es annahm.</li>
 *   <li><b>Vorladen:</b> Wird der Server in der Liste ausgewählt (oder vom Launcher als Ziel gemeldet), lädt der TRS
 *   Client das Paket gedrosselt im Hintergrund in Minecrafts eigenen Paket-Speicher – genau dorthin und unter dem
 *   Namen, unter dem Minecraft es beim Beitreten sucht ({@link Era}). Minecraft prüft den SHA-1 dort selbst noch einmal
 *   und lädt dann nichts mehr herunter.</li>
 *   <li><b>Gleiches Paket beim Serverwechsel:</b> Schickt der Server (z. B. nach einem Wechsel im Proxy-Netzwerk)
 *   genau das Paket, das schon aktiv ist (gleiche ID, gleicher SHA-1), meldet der Client sofort „angenommen –
 *   geladen“, statt alles neu zu laden. Was der Server sieht, ist dasselbe wie bei Vanilla.</li>
 * </ul>
 * Die Annahme-Abfrage von Minecraft bleibt unverändert: vorgeladen wird nur, was schon einmal angenommen wurde.
 */
public final class ServerPacks {
	/** Wo und unter welchem Namen Minecraft Server-Pakete zwischenspeichert. */
	public enum Era {
		/** 1.20.3+: {@code downloads/<Paket-ID>/<sha1>}. */
		DOWNLOADS_UUID,
		/** 1.14.4–1.18.2: {@code server-resource-packs/<sha1(URL-Text)>}. */
		URL_SHA1_RAW,
		/** 1.19–1.20.2: {@code server-resource-packs/<sha1(URL.toString())>}. */
		URL_SHA1_URL,
		/** 1.8.9–1.12.2: {@code server-resource-packs/<sha1 des Pakets>}. */
		HASH
	}

	/** Gesamtgrenze für selbst vorgeladene Dateien (ältere werden gelöscht). */
	static final long CACHE_LIMIT = 512L * 1024 * 1024;
	/** Gedrosselt beim Vorladen aus der Serverliste (Spiel und Ping sollen flüssig bleiben). */
	static final long THROTTLE_BYTES_PER_SECOND = 8L * 1024 * 1024;

	private static volatile Era era;
	private static volatile Path gameDir;
	private static volatile long maxBytes = 52428800L;
	private static volatile Map<String, String> headers = Collections.emptyMap();
	private static volatile PackMemory memory;
	private static volatile FastConnect.Switch fastSwitch;
	private static volatile FastConnect.Switch preload;

	private ServerPacks() {
	}

	/** Beim Start (Loader): Speicher-Ort der Version, Größengrenze von Vanilla, Kopfzeilen (User-Agent). */
	public static void setup(Path configDir, Era e, long vanillaMaxBytes, Map<String, String> requestHeaders,
			FastConnect.Switch fastSwitchSwitch, FastConnect.Switch preloadSwitch) {
		era = e;
		gameDir = configDir == null ? null : configDir.toAbsolutePath().getParent();
		maxBytes = vanillaMaxBytes;
		headers = requestHeaders == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(requestHeaders);
		memory = new PackMemory(configDir);
		fastSwitch = fastSwitchSwitch;
		preload = preloadSwitch;
	}

	static boolean on(FastConnect.Switch s) {
		try {
			return s != null && FastConnect.active() && s.on();
		} catch (RuntimeException ex) {
			return false;
		}
	}

	public static boolean fastSwitchOn() {
		return on(fastSwitch);
	}

	public static boolean preloadOn() {
		return on(preload);
	}

	/** Pfad, unter dem Minecraft dieses Paket sucht, oder null (z. B. ohne Paket-ID ab 1.20.3). */
	static Path cachePath(String uuid, String url, String sha1) {
		Path g = gameDir;
		Era e = era;
		if (g == null || e == null || !PackMemory.validSha1(sha1)) return null;
		String h = sha1.toLowerCase(Locale.ROOT);
		switch (e) {
			case DOWNLOADS_UUID:
				if (uuid == null || !uuidText(uuid)) return null;
				return g.resolve("downloads").resolve(uuid.toLowerCase(Locale.ROOT)).resolve(h);
			case URL_SHA1_RAW:
				return url == null ? null : g.resolve("server-resource-packs").resolve(sha1Hex(url));
			case URL_SHA1_URL:
				try {
					return url == null ? null : g.resolve("server-resource-packs").resolve(sha1Hex(new URL(url).toString()));
				} catch (IOException ex) {
					return null;
				}
			case HASH:
			default:
				return g.resolve("server-resource-packs").resolve(h);
		}
	}

	static boolean uuidText(String s) {
		if (s.length() != 36) return false;
		for (int i = 0; i < 36; i++) {
			char c = s.charAt(i);
			boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
			if (dash ? c != '-' : !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
		}
		return true;
	}

	static String sha1Hex(String text) {
		try {
			return PackDownloader.hex(java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// --- laufende Verbindung ---

	private static final class Push {
		final String server;
		final String url;
		final String sha1;
		final String uuid;

		Push(String server, String url, String sha1, String uuid) {
			this.server = server;
			this.url = url;
			this.sha1 = sha1;
			this.uuid = uuid;
		}
	}

	/** Schlüssel = Paket-ID (ab 1.20.3) bzw. "" (ältere Versionen: ein Paket). */
	private static final Map<String, Push> PENDING = new HashMap<String, Push>();
	private static final Map<String, Push> ACTIVE = new HashMap<String, Push>();

	private static String slot(String uuid) {
		return uuid == null ? "" : uuid.toLowerCase(Locale.ROOT);
	}

	static String normalizedSha1(String hash) {
		return PackMemory.validSha1(hash) ? hash.toLowerCase(Locale.ROOT) : null;
	}

	/**
	 * Server schickt ein Paket (Hauptthread bzw. vor Vanillas Handler). true = genau dieses Paket ist schon aktiv:
	 * Aufrufer meldet dem Server „angenommen“ (+ „heruntergeladen“ ab 1.20.3) + „erfolgreich geladen“ und lässt Vanilla
	 * nichts tun. Sonst wird – falls vorgeladen – die Datei an Vanillas Platz gelegt.
	 */
	public static boolean onPush(String server, String uuid, String url, String hash) {
		try {
			String h = normalizedSha1(hash);
			Push p = new Push(server, url, h, uuid);
			synchronized (ServerPacks.class) {
				Push active = ACTIVE.get(slot(uuid));
				if (h != null && active != null && h.equals(active.sha1) && fastSwitchOn()
						&& (uuid != null || (url != null && url.equals(active.url)))) {
					FastConnect.info("Server resource pack " + h + " is already active - skipping the reload");
					return true;
				}
				PENDING.put(slot(uuid), p);
			}
			if (h != null && preloadOn()) ensureInPlace(uuid, url, h);
			return false;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Client meldet dem Server einen Paket-Status ("ACCEPTED", "SUCCESSFULLY_LOADED", "DECLINED" …). */
	public static void onStatus(String uuid, String action) {
		if (action == null) return;
		Push p;
		synchronized (ServerPacks.class) {
			p = PENDING.get(slot(uuid));
			if (p == null && "SUCCESSFULLY_LOADED".equals(action)) p = ACTIVE.get(slot(uuid));
			if ("SUCCESSFULLY_LOADED".equals(action)) {
				if (p != null) ACTIVE.put(slot(uuid), p);
			} else if (!"ACCEPTED".equals(action) && !"DOWNLOADED".equals(action)) {
				ACTIVE.remove(slot(uuid));
			}
		}
		if (p == null || p.sha1 == null || p.server == null) return;
		PackMemory m = memory;
		if (m == null) return;
		if ("ACCEPTED".equals(action) || "SUCCESSFULLY_LOADED".equals(action)) m.remember(p.server, p.url, p.sha1, p.uuid, System.currentTimeMillis());
		else if ("DECLINED".equals(action)) m.forget(p.server, p.sha1);
	}

	/** Server entfernt Paket(e): {@code uuid} null = alle. */
	public static synchronized void onPop(String uuid) {
		if (uuid == null) {
			ACTIVE.clear();
			PENDING.clear();
		} else {
			ACTIVE.remove(slot(uuid));
			PENDING.remove(slot(uuid));
		}
	}

	/** Neue Verbindung / getrennt: Vanilla entfernt Server-Pakete dann ohnehin. */
	public static synchronized void onDisconnect() {
		ACTIVE.clear();
		PENDING.clear();
	}

	/** Vorgeladene Datei mit diesem SHA-1 an Vanillas Platz kopieren (z. B. andere Paket-ID). */
	static void ensureInPlace(String uuid, String url, String sha1) {
		Path target = cachePath(uuid, url, sha1);
		PackMemory m = memory;
		if (target == null || m == null || Files.exists(target)) return;
		Path source = m.ownFileWithHash(sha1);
		if (source == null || source.equals(target)) return;
		try {
			Files.createDirectories(target.getParent());
			Path tmp = target.resolveSibling(target.getFileName() + ".trs-part");
			Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			m.ownFile(target, sha1, Files.size(target), System.currentTimeMillis());
			FastConnect.info("Server resource pack " + sha1 + " taken from the preload cache");
		} catch (IOException | RuntimeException e) {
			FastConnect.warn("Could not place preloaded resource pack", e);
		}
	}

	// --- Vorladen ---

	private static final ThreadPoolExecutor POOL = pool();
	private static final Set<String> QUEUED = new HashSet<String>();
	/** In dieser Sitzung geprüfte Dateien (nicht jedes Mal neu hashen). */
	private static final Set<String> VERIFIED = Collections.synchronizedSet(new HashSet<String>());
	private static final List<AtomicBoolean> CANCELS = new ArrayList<AtomicBoolean>();

	private static ThreadPoolExecutor pool() {
		ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16),
				new java.util.concurrent.ThreadFactory() {
					@Override
					public Thread newThread(Runnable r) {
						Thread t = new Thread(r, "TRS-PackPreload");
						t.setDaemon(true);
						t.setPriority(Thread.MIN_PRIORITY);
						return t;
					}
				}, new ThreadPoolExecutor.DiscardPolicy());
		ex.allowCoreThreadTimeOut(true);
		return ex;
	}

	/**
	 * Gemerkte Pakete eines Servers vorladen (im Hintergrund, einmal je Datei). {@code throttled}: gedrosselt
	 * (Serverliste); ungedrosselt, wenn der Launcher meldet, dass gleich beigetreten wird.
	 */
	public static void preload(final String address, final boolean throttled) {
		PackMemory m = memory;
		if (!preloadOn() || m == null || address == null) return;
		final boolean privateServer = privateServer(address);
		for (final PackMemory.Pack p : m.packs(address)) {
			final Path target = cachePath(p.uuid, p.url, p.sha1);
			if (target == null) continue;
			final String key = target.toString();
			if (VERIFIED.contains(key)) continue;
			synchronized (QUEUED) {
				if (!QUEUED.add(key)) continue;
			}
			final AtomicBoolean cancel = new AtomicBoolean();
			if (throttled) {
				synchronized (CANCELS) {
					CANCELS.add(cancel);
				}
			}
			POOL.execute(new Runnable() {
				@Override
				public void run() {
					try {
						preloadNow(address, p, target, throttled, privateServer, cancel);
					} finally {
						synchronized (QUEUED) {
							QUEUED.remove(key);
						}
						synchronized (CANCELS) {
							CANCELS.remove(cancel);
						}
					}
				}
			});
		}
	}

	/** Spieler hat die Serverliste verlassen: gedrosselte Downloads abbrechen. */
	public static void cancelPreloads() {
		synchronized (CANCELS) {
			for (AtomicBoolean c : CANCELS) c.set(true);
			CANCELS.clear();
		}
	}

	static void preloadNow(String address, PackMemory.Pack p, Path target, boolean throttled, boolean privateServer, AtomicBoolean cancel) {
		if (cancel.get()) return;
		if (Files.isRegularFile(target)) {
			String have = PackDownloader.sha1(target);
			if (p.sha1.equalsIgnoreCase(have)) {
				VERIFIED.add(target.toString());
				return;
			}
		}
		PackMemory m = memory;
		if (m == null) return;
		long limit = Math.min(maxBytes, CACHE_LIMIT);
		m.makeRoom(p.size == null ? 0 : p.size, CACHE_LIMIT);
		PackDownloader.Result r = PackDownloader.download(p.url, target, p.sha1, limit, headers,
				throttled ? THROTTLE_BYTES_PER_SECOND : 0, cancel, privateServer);
		if (r.ok) {
			VERIFIED.add(target.toString());
			m.ownFile(target, p.sha1, r.bytes, System.currentTimeMillis());
			LAST_PRELOAD = address + ": " + (r.bytes / 1024) + " KiB in " + r.ms + " ms";
			FastConnect.info("Preloaded server resource pack " + p.sha1 + " for " + address + " (" + r + ")");
		} else if (!"cancelled".equals(r.error)) {
			FastConnect.info("Preloading server resource pack for " + address + " failed: " + r.error);
		}
	}

	/** Für die Modulseite: letzter erfolgreicher Vorlade-Vorgang oder null. */
	static volatile String LAST_PRELOAD;

	/** Ist der Server selbst im privaten Netz (LAN)? Dann dürfen auch seine Pakete von dort kommen. */
	static boolean privateServer(String address) {
		String[] hp = FastConnect.parse(address);
		if (hp == null) return false;
		byte[] lit = IpLiteral.parse(hp[0]);
		try {
			if (lit != null) return PackDownloader.isPrivate(java.net.InetAddress.getByAddress(lit));
		} catch (java.net.UnknownHostException e) {
			return false;
		}
		if ("localhost".equalsIgnoreCase(hp[0])) return true;
		List<java.net.InetAddress> l = FastConnect.CACHE.inetAddresses(hp[0]);
		if (l == null || l.isEmpty()) return false;
		for (java.net.InetAddress a : l) if (!PackDownloader.isPrivate(a)) return false;
		return true;
	}

	/** Für Tests. */
	static synchronized void resetForTests() {
		ACTIVE.clear();
		PENDING.clear();
		VERIFIED.clear();
	}

	static PackMemory memory() {
		return memory;
	}
}

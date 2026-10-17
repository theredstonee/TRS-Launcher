package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.online.Uuids;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Persönlicher Gag NUR für MiyakoMC (Vertrag Part A; UUID {@value #UUID}, einvernehmlich – er hat darum gebeten).
 * Betrifft KEINEN anderen Spieler: für jeden anderen liefert die API 404 und hier passiert nichts.
 *
 * <ol>
 *   <li>Beim allerersten Start (Server-Flag {@code introDone}, lokal als Rückfall): ein unüberspringbares Video
 *   (nur moderner Fabric-Pfad, 1.20+); kann es die MC-Version nicht, wird still übersprungen.</li>
 *   <li>Bei jedem Spielstart und Server-Beitritt: ein Zufalls-Timer (60–300 s). Läuft er ab (und die Schutzregeln
 *   passen), erscheint {@code jumpscare.png} ~1,3 s mit einem Schreck-Ton (immer, unabhängig vom Ton-Schalter).</li>
 * </ol>
 *
 * <p>Die Medien kommen EINMAL als geschütztes Paket vom Server und liegen unter {@code <config>/trsclient/miyako/};
 * sie sind NIE in der Mod/den JARs. Netz läuft im Hintergrund-Thread; die Oberfläche liest nur {@code volatile}-Felder.
 */
public final class Miyako {
	/** MiyakoMCs UUID (32 Hex, ohne Bindestriche). */
	public static final String UUID = "7f5eb4a770b24574ad99382d42163623";
	/** Timer: frühestens nach … */
	public static final long TIMER_MIN_MS = 60_000L;
	/** … und standardmäßig spätestens nach … (Server kann es über {@code timerMaxMs} ändern). */
	public static final long TIMER_MAX_DEFAULT_MS = 300_000L;
	/** Dauer des Bild-Schrecks. */
	public static final long IMAGE_MS = 1300L;
	/** Schutz: kein Schreck in den letzten 10 s nach Schaden. */
	static final long DAMAGE_PAUSE_MS = 10_000L;
	/** Schutz: nie unter 6 Herzen. */
	static final float MIN_HEALTH = 12f;
	/** Testschalter {@code -Dtrsclient.miyako.test=true}: so tun, als wäre dies MiyakoMC (gegen die Attrappe). */
	private static final boolean TEST = "true".equalsIgnoreCase(System.getProperty("trsclient.miyako.test"));
	static final long RETRY_MS = 60_000L;

	private static final Miyako INSTANCE = new Miyako();

	private final Random random = new Random();
	private volatile Http http;
	private volatile Path dir;
	private ThreadPoolExecutor worker;

	/** Nur ein moderner Pfad (Fabric 1.20+) kann das Video abspielen; sonst wird es still übersprungen. */
	private volatile boolean videoSupported;

	// Netz-Zustand.
	private volatile boolean metaLoaded;
	private volatile boolean bundleReady;
	private volatile boolean notMine;
	private volatile boolean loading;
	private volatile long retryAt;
	private volatile long timerMaxMs = TIMER_MAX_DEFAULT_MS;
	private volatile boolean introDoneServer;

	// Jumpscare-Bild (ARGB), einmal entschlüsselt.
	private volatile int[] imageArgb;
	private volatile int imageW;
	private volatile int imageH;
	private volatile boolean imageTried;

	// Sitzung / Timer (nur Spiel-Thread bzw. SSE-Thread, synchronisiert).
	private long lastDamage = Long.MIN_VALUE / 2;
	private float health = 20f;
	private boolean inWorld;
	private long fireAt = -1;
	private long imageStart = -1;
	private boolean imageSoundPending;
	private boolean introAttempted;
	private volatile boolean introPlaying;

	private Miyako() {
	}

	public static Miyako get() {
		return INSTANCE;
	}

	// --- Einrichtung (aus TrsOnline.create bzw. den Bäumen) ---

	public void http(Http http) {
		this.http = http;
	}

	public void dir(Path dir) {
		this.dir = dir;
	}

	/** Der Baum meldet, dass er das Video abspielen kann (moderner Fabric-Pfad 1.20+). */
	public void setVideoSupported(boolean supported) {
		this.videoSupported = supported;
	}

	public boolean videoSupported() {
		return videoSupported;
	}

	// --- Freischaltung ---

	/** Ist dies MiyakoMC (oder der Testschalter)? */
	public boolean gated() {
		if (TEST) return true;
		TrsOnline o = TrsOnline.current();
		String uuid = o == null ? null : Uuids.normalize(o.ownUuid());
		return UUID.equals(uuid);
	}

	// --- Tick (einmal je Client-Tick aus den Bäumen) ---

	/** Spielzustand melden und, wenn fällig, den Bild-Schreck auslösen. */
	public synchronized void tick(long now, boolean inWorld, float health, boolean hurt) {
		if (!Halloween.active() || !gated()) {
			fireAt = -1;
			return;
		}
		this.inWorld = inWorld;
		this.health = health;
		if (hurt) lastDamage = now;
		ensure();
		if (fireAt >= 0 && now >= fireAt && imageStart < 0 && bundleReady && imageReady() && safe(now)) {
			imageStart = now;
			imageSoundPending = true;
			fireAt = -1;
		}
	}

	/** Schutzregeln für den Bild-Schreck: im Spiel, mindestens 6 Herzen, kein Schaden in den letzten 10 s. */
	private boolean safe(long now) {
		return inWorld && health >= MIN_HEALTH && now - lastDamage >= DAMAGE_PAUSE_MS;
	}

	/** Timer bewaffnen (bei jedem Spielstart und Server-Beitritt): Zufalls-Verzögerung 60–{@code timerMaxMs}. */
	public synchronized void armTimer(long now) {
		if (!gated()) return;
		long span = Math.max(0, timerMaxMs - TIMER_MIN_MS);
		fireAt = now + TIMER_MIN_MS + (span > 0 ? random.nextInt((int) Math.min(Integer.MAX_VALUE, span)) : 0);
	}

	/** Nur Autotest: den Timer gleich auslösen lassen (nächster sicherer Tick). */
	public synchronized void forceTimerForTest(long now) {
		fireAt = now;
	}

	// --- Bild-Schreck (Zeichnen/Ton) ---

	/** Deckkraft des Bild-Schrecks jetzt (0 = nichts, blendet ein/aus) – beendet sich selbst. */
	public synchronized float imageAlpha(long now) {
		if (imageStart < 0) return 0f;
		long age = now - imageStart;
		if (age >= IMAGE_MS) {
			imageStart = -1;
			return 0f;
		}
		if (age < 90) return age / 90f;
		long outAt = IMAGE_MS - 220;
		return age > outAt ? Math.max(0f, (IMAGE_MS - age) / 220f) : 1f;
	}

	public synchronized boolean imageActive(long now) {
		return imageStart >= 0 && now - imageStart < IMAGE_MS;
	}

	/** Schreck-Ton zum Bild (einmal, immer – unabhängig vom „Grusel-Töne“-Schalter). */
	public synchronized String takeImageSound() {
		if (!imageSoundPending) return null;
		imageSoundPending = false;
		return Jumpscares.SOUND_SCREAM;
	}

	public boolean imageReady() {
		return imageArgb != null && imageW > 0 && imageH > 0;
	}

	public int[] imageArgb() {
		return imageArgb;
	}

	public int imageWidth() {
		return imageW;
	}

	public int imageHeight() {
		return imageH;
	}

	// --- Intro-Video ---

	/** Soll das Intro jetzt gespielt werden? (gated, Event an, Paket da, noch nicht gesehen/versucht) */
	public synchronized boolean wantsIntro() {
		return gated() && Halloween.active() && bundleReady && !introDone() && !introAttempted && !introPlaying;
	}

	public boolean introDone() {
		return introDoneServer || localIntroDone();
	}

	/** Der Baum hat das Video geöffnet. */
	public synchronized void introStarted() {
		introPlaying = true;
		introAttempted = true;
	}

	/** Das Video lief durch: lokal und am Server als gesehen vermerken. */
	public synchronized void introFinished() {
		introPlaying = false;
		markLocalIntroDone();
		postIntroDone();
	}

	/** Kann auf dieser Version nicht abgespielt werden – diese Sitzung nicht mehr versuchen, nichts am Server ändern. */
	public synchronized void introSkipped() {
		introAttempted = true;
	}

	public boolean introPlaying() {
		return introPlaying;
	}

	// --- Dateien ---

	public Path dir() {
		return dir;
	}

	public Path framesDir() {
		Path d = dir;
		return d == null ? null : d.resolve("frames");
	}

	public Path audioFile() {
		Path d = dir;
		return d == null ? null : d.resolve("intro.ogg");
	}

	private boolean localIntroDone() {
		Path d = dir;
		return d != null && Files.exists(d.resolve("intro-done"));
	}

	private void markLocalIntroDone() {
		Path d = dir;
		if (d == null) return;
		try {
			Files.createDirectories(d);
			Files.write(d.resolve("intro-done"), new byte[0]);
		} catch (IOException ignored) {
			// Rückfall ist nur ein Zusatz zum Server-Flag.
		}
	}

	// --- Laden (Hintergrund) ---

	private void ensure() {
		if (loading || notMine || http == null || dir == null) return;
		if (metaLoaded && bundleReady && imageReady()) return;
		long now = System.currentTimeMillis();
		if (now < retryAt) return;
		final String token = token();
		final MiyakoApi api = api();
		if (token == null || api == null) return;
		loading = true;
		if (!submit(new Runnable() {
			@Override
			public void run() {
				try {
					load(api, token);
					retryAt = 0;
				} catch (ApiException e) {
					if (e.status() == 404) {
						// Server sagt: nicht MiyakoMC – nichts tun, nie wieder fragen.
						notMine = true;
					} else {
						retryAt = System.currentTimeMillis() + Math.max(RETRY_MS, e.retryAfterMs());
					}
				} catch (IOException | RuntimeException e) {
					retryAt = System.currentTimeMillis() + RETRY_MS;
				} finally {
					loading = false;
				}
			}
		})) {
			loading = false;
		}
	}

	private void load(MiyakoApi api, String token) throws IOException, ApiException {
		if (!metaLoaded) {
			MiyakoApi.Meta m = api.meta(token);
			if (m.introDone != null) introDoneServer = m.introDone;
			if (m.timerMaxMs != null && m.timerMaxMs >= TIMER_MIN_MS && m.timerMaxMs <= 3_600_000L) timerMaxMs = m.timerMaxMs;
			metaLoaded = true;
		}
		if (!bundleReady) {
			if (!cached()) {
				byte[] zip = api.bundle(token);
				extract(zip, dir);
			}
			bundleReady = cached();
		}
		if (bundleReady && !imageTried) decodeImage();
	}

	/** Liegt das Paket schon vollständig auf der Platte? */
	private boolean cached() {
		Path d = dir;
		if (d == null) return false;
		return Files.isRegularFile(d.resolve("jumpscare.png")) && Files.isRegularFile(d.resolve("intro.ogg"))
				&& Files.isRegularFile(d.resolve("frames").resolve("0001.jpg"));
	}

	private void decodeImage() {
		imageTried = true;
		Path d = dir;
		if (d == null) return;
		try {
			byte[] png = Files.readAllBytes(d.resolve("jumpscare.png"));
			PngDecoder.Image img = PngDecoder.decode(png);
			imageArgb = img.argb;
			imageW = img.width;
			imageH = img.height;
		} catch (IOException | RuntimeException e) {
			imageArgb = null;
		}
	}

	/** ZIP ins Zielverzeichnis entpacken; nur erlaubte, flache Namen (keine Pfad-Tricks). */
	static void extract(byte[] zip, Path dir) throws IOException {
		Files.createDirectories(dir);
		Files.createDirectories(dir.resolve("frames"));
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			byte[] buf = new byte[8192];
			while ((entry = in.getNextEntry()) != null) {
				String name = entry.getName().replace('\\', '/');
				if (entry.isDirectory() || !safeName(name)) {
					in.closeEntry();
					continue;
				}
				Path target = dir.resolve(name).normalize();
				if (!target.startsWith(dir)) {
					in.closeEntry();
					continue;
				}
				Path parent = target.getParent();
				if (parent != null) Files.createDirectories(parent);
				try (java.io.OutputStream out = Files.newOutputStream(target)) {
					int n;
					while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
				}
				in.closeEntry();
			}
		}
	}

	/** Erlaubt: {@code jumpscare.png}, {@code intro.ogg}, {@code frames/NNNN.jpg}. */
	private static boolean safeName(String name) {
		if (name.contains("..") || name.startsWith("/")) return false;
		String n = name.toLowerCase(Locale.ROOT);
		if (n.equals("jumpscare.png") || n.equals("intro.ogg")) return true;
		return n.matches("frames/[0-9]{1,6}\\.jpg");
	}

	// --- Hilfen ---

	private String token() {
		TrsOnline o = TrsOnline.current();
		return o == null ? null : o.token();
	}

	private MiyakoApi api() {
		Http h = http;
		TrsOnline o = TrsOnline.current();
		if (h == null || o == null) return null;
		return new MiyakoApi(h, o.apiBase());
	}

	private void postIntroDone() {
		final String token = token();
		final MiyakoApi api = api();
		if (token == null || api == null) return;
		submit(new Runnable() {
			@Override
			public void run() {
				try {
					api.introDone(token);
				} catch (IOException | ApiException | RuntimeException ignored) {
					// Lokales Flag genügt, falls das Netz klemmt.
				}
			}
		});
	}

	private synchronized boolean submit(Runnable task) {
		if (worker == null) {
			ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8), r -> {
				Thread t = new Thread(r, "TRS-Miyako");
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

	/** Nur Tests: alles zurücksetzen. */
	public synchronized void resetForTest() {
		metaLoaded = false;
		bundleReady = false;
		notMine = false;
		loading = false;
		retryAt = 0;
		timerMaxMs = TIMER_MAX_DEFAULT_MS;
		introDoneServer = false;
		imageArgb = null;
		imageW = 0;
		imageH = 0;
		imageTried = false;
		lastDamage = Long.MIN_VALUE / 2;
		health = 20f;
		inWorld = false;
		fireAt = -1;
		imageStart = -1;
		imageSoundPending = false;
		introAttempted = false;
		introPlaying = false;
	}

	/** Nur Autotest/Tests: ein Jumpscare-Bild direkt einsetzen (ohne Netz). */
	public synchronized void setImageForTest(int[] argb, int w, int h) {
		imageArgb = argb;
		imageW = w;
		imageH = h;
		imageTried = true;
		bundleReady = true;
	}

	/** Nur Autotest: den Bild-Schreck sofort zeigen (ohne Gating/Schutz/Ton). */
	public synchronized void forceImageForTest(long now) {
		imageStart = now;
		imageSoundPending = false;
	}
}

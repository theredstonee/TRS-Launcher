package dev.theredstonee.trsclient.core.bugreport;

import dev.theredstonee.trsclient.core.account.AccountManager;
import dev.theredstonee.trsclient.core.account.GameAccount;
import dev.theredstonee.trsclient.core.account.SessionData;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.GameSession;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import dev.theredstonee.trsclient.core.online.TrsApi;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.social.ChatImages;
import dev.theredstonee.trsclient.core.ui.TextInput;
import dev.theredstonee.trsclient.core.ui.social.ChatInput;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * „Bug melden“ im TRS Client: Entwurf (bleibt erhalten, wenn das Menü zwischendurch zu ist), Sammeln der Anhänge im
 * Hintergrund (Mod-Liste aus {@code mods/}, Log-Ausschnitt aus {@code logs/latest.log} bzw. dem neuesten
 * Absturzbericht, Bildschirmfotos) – alles schon gesäubert ({@link LogScrubber}) –, und das Senden über die TRS API
 * mit dem Konto des Spielers.
 *
 * <p>Konto: läuft {@link TrsOnline} (moderne Versionen, Legacy 1.8.9–1.12.2), kommt dessen Token; sonst
 * (Forge 1.7.10/1.13.2) meldet sich der Dienst einmalig selbst an (Mojang-Join wie TrsOnline, Token nur im
 * Speicher). Ohne Einwilligung im Launcher ({@code trs-api.json}) geht kein einziger Aufruf raus.
 *
 * <p>Threads: Entwurf nur im Render-Thread; Sammeln und Senden im Thread „TRS-Bugreport“; die Oberfläche liest
 * die Ergebnisse über volatile Felder.
 */
public final class BugReports {
	/** So viele Zeilen des Logs gehen höchstens mit. */
	public static final int LOG_LINES = 300;
	/** So viel vom Ende des Logs wird gelesen (danach auf {@link #LOG_LINES} gekürzt). */
	static final int LOG_READ_BYTES = 256 * 1024;
	/** Absturzberichte, die älter sind, werden nicht angeboten. */
	static final long CRASH_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
	static final int MAX_SCREENSHOTS = 24;
	/** Größte Kante des Bildes (Server verkleinert ohnehin). */
	static final int SCREENSHOT_MAX_SIDE = 4096;
	private static final Pattern SCREENSHOT_NAME = Pattern.compile("[A-Za-z0-9 _.()\\[\\]-]{1,120}\\.(?i:png|jpe?g)");
	private static final Pattern VERSION_DIR = Pattern.compile("[0-9][0-9.]{0,15}");

	/** Kann gesendet werden – und wenn nicht, warum nicht. */
	public enum Access {
		READY, LAUNCHER_OFF, MODULE_OFF, NO_ACCOUNT, CONNECTING, RETRY, BANNED
	}

	public enum Phase {
		IDLE, UPLOADING, SENDING, DONE, FAILED
	}

	/** Zugang zum Token (austauschbar für Tests). */
	public interface Auth {
		Access access();

		/** Token der TRS-Anmeldung (darf blockieren – läuft im Hintergrund) oder null. */
		String token() throws IOException, ApiException;

		/** Der Server hat {@code token} abgelehnt (401). */
		void rejected(String token);
	}

	/** Ein Bildschirmfoto unter {@code screenshots/}. */
	public static final class Screenshot {
		public final Path path;
		public final String name;
		public final long modified;
		public final long size;

		Screenshot(Path path, String name, long modified, long size) {
			this.path = path;
			this.name = name;
			this.modified = modified;
			this.size = size;
		}
	}

	/** Gesammelte, schon gesäuberte Anhänge (unveränderlich). */
	public static final class Data {
		public final long collectedAt;
		public final List<String> mods;
		/** Anzahl Mod-Dateien vor dem Kürzen auf {@link BugReportBody#MAX_MODS}. */
		public final int modsTotal;
		/** Log-Ausschnitt (gesäubert) oder null (keine latest.log). */
		public final String log;
		public final LogScrubber.Result logScrub;
		public final int logLines;
		/** Neuester Absturzbericht (Anfang, gesäubert) oder null. */
		public final String crash;
		public final LogScrubber.Result crashScrub;
		public final String crashName;
		public final long crashModified;
		/** Neueste zuerst. */
		public final List<Screenshot> screenshots;

		Data(long collectedAt, List<String> mods, int modsTotal, String log, LogScrubber.Result logScrub, String crash,
				LogScrubber.Result crashScrub, String crashName, long crashModified, List<Screenshot> screenshots) {
			this.collectedAt = collectedAt;
			this.mods = Collections.unmodifiableList(new ArrayList<String>(mods));
			this.modsTotal = modsTotal;
			this.log = log;
			this.logScrub = logScrub;
			this.logLines = log == null || log.isEmpty() ? 0 : count(log, '\n') + 1;
			this.crash = crash;
			this.crashScrub = crashScrub;
			this.crashName = crashName;
			this.crashModified = crashModified;
			this.screenshots = Collections.unmodifiableList(new ArrayList<Screenshot>(screenshots));
		}
	}

	private static volatile BugReports instance;

	private final Path configDir;
	private final Path gameDir;
	private final String modVersion;
	private final String mcVersion;
	private final String loader;
	private final String userAgent;
	private final Executor worker;
	private volatile Auth auth;
	private volatile Http http;

	// --- Entwurf (Render-Thread) ---
	public final TextInput title = new TextInput(BugReportBody.TITLE_MAX);
	public final ChatInput description = new ChatInput(BugReportBody.DESCRIPTION_MAX);
	/**
	 * Häkchen: Versionen und Mod-Liste an (klein, nichts Persönliches, fürs Nachstellen fast immer nötig); Log und
	 * Bildschirmfoto aus (können trotz Säuberung Serveradressen, Koordinaten oder andere Spieler zeigen – bewusst
	 * einschalten).
	 */
	public boolean includeModVersion = true;
	public boolean includeGame = true;
	public boolean includeMods = true;
	public boolean includeLog;
	public boolean includeScreenshot;
	/** Statt latest.log den neuesten Absturzbericht anhängen. */
	public boolean useCrashReport;
	/** Gewähltes Bildschirmfoto (Pfad) oder null = das neueste. */
	public Path screenshot;
	/** „Neu aufnehmen“: ab diesem Zeitpunkt das nächste neue Bildschirmfoto nehmen (0 = aus). */
	public long awaitScreenshotSince;
	private volatile boolean openRequest;

	// --- Hintergrund ---
	private volatile Data data;
	private volatile boolean collecting;
	private volatile long lastCollect;
	private volatile Phase phase = Phase.IDLE;
	private volatile BugReportBody.Created created;
	private volatile String error;
	private volatile long retryAfterMs;
	/** Eigene Anmeldung (nur ohne TrsOnline). */
	private volatile String ownToken;

	BugReports(Path configDir, String modVersion, String mcVersion, String loader, Executor worker) {
		this.configDir = configDir;
		this.gameDir = configDir == null ? null : configDir.toAbsolutePath().normalize().getParent();
		this.modVersion = modVersion;
		this.mcVersion = mcVersion;
		this.loader = loader;
		this.userAgent = "TRS-Client/" + modVersion + " (Minecraft " + mcVersion + "; " + loader + ")";
		this.worker = worker;
		this.auth = new DefaultAuth();
	}

	/** Beim Start je Loader (nach {@code AccountManager.init}); {@code configDir} = {@code config}-Ordner der Instanz. */
	public static synchronized BugReports init(Path configDir, String modVersion, String mcVersion, String loader) {
		if (instance == null) {
			ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16), r -> {
				Thread t = new Thread(r, "TRS-Bugreport");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY + 1);
				return t;
			});
			ex.allowCoreThreadTimeOut(true);
			instance = new BugReports(configDir, modVersion, mcVersion, loader, ex);
		}
		return instance;
	}

	/** null, solange der Loader {@link #init} nicht aufgerufen hat (dann gibt es keinen Menüpunkt). */
	public static BugReports get() {
		return instance;
	}

	/** Nur Tests. */
	static void set(BugReports b) {
		instance = b;
	}

	public Executor worker() {
		return worker;
	}

	public String modVersion() {
		return modVersion;
	}

	public String mcVersion() {
		return mcVersion;
	}

	public String loader() {
		return loader;
	}

	/** Anderer Token-Zugang bzw. HTTP (Tests). */
	void auth(Auth a) {
		auth = a;
	}

	void http(Http h) {
		http = h;
	}

	private Http httpClient() {
		Http h = http;
		if (h == null) {
			h = new Http.UrlConnection(userAgent);
			http = h;
		}
		return h;
	}

	String apiBase() {
		TrsOnline o = TrsOnline.current();
		return o != null ? o.apiBase() : OnlineConfig.load(configDir).apiBase();
	}

	// --- Öffnen (Autotest, „Neu aufnehmen“) ---

	/** Das TRS-Menü soll beim nächsten Öffnen direkt „Bug melden“ zeigen. */
	public void requestOpen() {
		openRequest = true;
	}

	/** Liest und löscht die Anfrage (TRS-Menü beim Öffnen). */
	public boolean takeOpenRequest() {
		boolean r = openRequest;
		openRequest = false;
		return r;
	}

	// --- Zustand ---

	public Access access() {
		try {
			return auth.access();
		} catch (RuntimeException e) {
			return Access.RETRY;
		}
	}

	/** Name des Kontos, mit dem gesendet wird (null = unbekannt). */
	public String accountName() {
		AccountManager am = AccountManager.get();
		SessionData s = am == null ? null : am.currentSession();
		return s == null ? null : s.name;
	}

	public Data data() {
		return data;
	}

	public boolean collecting() {
		return collecting;
	}

	public Phase phase() {
		return phase;
	}

	public BugReportBody.Created created() {
		return created;
	}

	/** Fehlercode des letzten Sendeversuchs (Server-Code, {@code offline}, {@code unauthorized} …). */
	public String error() {
		return error;
	}

	public long retryAfterMs() {
		return retryAfterMs;
	}

	public boolean busy() {
		Phase p = phase;
		return p == Phase.UPLOADING || p == Phase.SENDING;
	}

	/** Neue Meldung: Entwurf und Ergebnis leeren (Häkchen bleiben, wie der Spieler sie gesetzt hat). */
	public void reset() {
		if (busy()) return;
		title.clear();
		description.clear();
		screenshot = null;
		awaitScreenshotSince = 0;
		created = null;
		error = null;
		phase = Phase.IDLE;
	}

	/** Fehlermeldung wegklicken. */
	public void clearError() {
		if (phase == Phase.FAILED) phase = Phase.IDLE;
		error = null;
	}

	/** Titel und Beschreibung passen (Grenzen wie der Server)? */
	public boolean textValid() {
		return BugReportBody.titleError(title.text()) == null && BugReportBody.descriptionError(description.text()) == null;
	}

	/** Das Bildschirmfoto, das mitgehen würde (gewähltes, sonst das neueste) oder null. */
	public Screenshot selectedScreenshot() {
		Data d = data;
		if (d == null || d.screenshots.isEmpty()) return null;
		if (screenshot != null) {
			for (Screenshot s : d.screenshots) {
				if (s.path.equals(screenshot)) return s;
			}
		}
		return d.screenshots.get(0);
	}

	/** Ein Bildschirmfoto weiter ({@code +1} = älter) bzw. zurück. */
	public void stepScreenshot(int delta) {
		Data d = data;
		if (d == null || d.screenshots.isEmpty()) return;
		Screenshot cur = selectedScreenshot();
		int i = Math.max(0, d.screenshots.indexOf(cur));
		int n = d.screenshots.size();
		screenshot = d.screenshots.get(((i + delta) % n + n) % n).path;
	}

	/** Log-Text, der mitgehen würde (Absturzbericht oder latest.log), oder null. */
	public String selectedLog() {
		Data d = data;
		if (d == null) return null;
		return useCrashReport && d.crash != null ? d.crash : d.log;
	}

	public LogScrubber.Result selectedLogScrub() {
		Data d = data;
		if (d == null) return null;
		return useCrashReport && d.crash != null ? d.crashScrub : d.logScrub;
	}

	/** Technische Angaben nach den Häkchen (für Vorschau und Senden). */
	public BugReportBody.Meta meta() {
		BugReportBody.Meta m = new BugReportBody.Meta();
		if (includeModVersion) m.modVersion = modVersion;
		if (includeGame) {
			m.mcVersion = mcVersion;
			m.loader = loader;
		}
		Data d = data;
		if (includeMods && d != null) m.mods = d.mods;
		if (includeLog) m.log = selectedLog();
		return m;
	}

	// --- Sammeln ---

	/** Anhänge neu sammeln (im Hintergrund); {@code force} = auch wenn gerade erst gesammelt wurde. */
	public void collectAsync(boolean force) {
		if (collecting) return;
		long now = System.currentTimeMillis();
		if (!force && data != null && now - lastCollect < 3000) return;
		collecting = true;
		lastCollect = now;
		if (!submit(new Runnable() {
			@Override
			public void run() {
				try {
					data = collect(gameDir, names(), System.getProperty("user.name"), System.getProperty("user.home"));
				} catch (RuntimeException e) {
					// Sammeln darf nie das Spiel stören – dann eben ohne Anhänge.
					data = new Data(System.currentTimeMillis(), Collections.<String>emptyList(), 0, null, null, null, null, null,
							0, Collections.<Screenshot>emptyList());
				} finally {
					collecting = false;
				}
			}
		})) {
			collecting = false;
		}
	}

	/** Spieler- und Kontonamen dieses Spiels (für die Säuberung). */
	static List<String> names() {
		Set<String> out = new LinkedHashSet<String>();
		AccountManager am = AccountManager.get();
		if (am != null) {
			SessionData s = am.currentSession();
			if (s != null && s.name != null) out.add(s.name);
			try {
				for (GameAccount a : am.state().accounts) {
					if (a != null && a.name != null) out.add(a.name);
				}
			} catch (RuntimeException ignored) {
				// ohne Kontoliste
			}
		}
		return new ArrayList<String>(out);
	}

	/** Alles einsammeln und säubern (Hintergrund; auch für Tests). */
	static Data collect(Path gameDir, List<String> names, String osUser, String home) {
		long now = System.currentTimeMillis();
		// Mods
		List<String> rawMods = modFiles(gameDir);
		List<String> mods = new ArrayList<String>();
		for (String m : rawMods) mods.add(LogScrubber.scrub(m, names, osUser, home).text);
		mods = BugReportBody.cleanMods(mods);
		// latest.log: Ende lesen, Namen auch aus dem Anfang (dort steht „Setting user: …“) mitnehmen.
		String log = null;
		LogScrubber.Result logScrub = null;
		Path latest = gameDir == null ? null : gameDir.resolve("logs").resolve("latest.log");
		if (latest != null && Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS)) {
			try {
				List<String> all = new ArrayList<String>(names);
				all.addAll(LogScrubber.namesIn(readHead(latest, 64 * 1024)));
				String raw = LogScrubber.tail(readTail(latest, LOG_READ_BYTES), LOG_LINES, Integer.MAX_VALUE);
				logScrub = LogScrubber.scrub(raw, all, osUser, home);
				log = LogScrubber.tail(logScrub.text, LOG_LINES, BugReportBody.MAX_LOG);
			} catch (IOException | RuntimeException e) {
				log = null;
			}
		}
		// Absturzbericht: der Anfang ist wichtig (Beschreibung, Stack, Mod-Liste).
		String crash = null;
		LogScrubber.Result crashScrub = null;
		String crashName = null;
		long crashModified = 0;
		Path crashFile = newestCrash(gameDir, now);
		if (crashFile != null) {
			try {
				String raw = LogScrubber.head(readHead(crashFile, LOG_READ_BYTES), LOG_LINES, Integer.MAX_VALUE);
				crashScrub = LogScrubber.scrub(raw, names, osUser, home);
				crash = LogScrubber.head(crashScrub.text, LOG_LINES, BugReportBody.MAX_LOG);
				crashName = crashFile.getFileName().toString();
				crashModified = Files.getLastModifiedTime(crashFile).toMillis();
			} catch (IOException | RuntimeException e) {
				crash = null;
			}
		}
		return new Data(now, mods, rawMods.size(), log, logScrub, crash, crashScrub, crashName, crashModified, screenshots(gameDir));
	}

	/** Dateinamen unter {@code mods/} (+ Versions-Unterordner wie {@code mods/1.8.9/} der alten Forge-Versionen). */
	static List<String> modFiles(Path gameDir) {
		List<String> out = new ArrayList<String>();
		if (gameDir == null) return out;
		Path mods = gameDir.resolve("mods");
		if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) return out;
		try (DirectoryStream<Path> dir = Files.newDirectoryStream(mods)) {
			for (Path p : dir) {
				String name = p.getFileName().toString();
				if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
					if (!VERSION_DIR.matcher(name).matches()) continue;
					try (DirectoryStream<Path> sub = Files.newDirectoryStream(p)) {
						for (Path q : sub) {
							String n = q.getFileName().toString();
							if (modFile(n) && Files.isRegularFile(q, LinkOption.NOFOLLOW_LINKS)) out.add(name + "/" + n);
							if (out.size() > 2000) break;
						}
					}
				} else if (modFile(name) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
					out.add(name);
				}
				if (out.size() > 2000) break;
			}
		} catch (IOException | RuntimeException e) {
			// was da ist, reicht
		}
		return out;
	}

	private static boolean modFile(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return n.endsWith(".jar") || n.endsWith(".zip") || n.endsWith(".litemod");
	}

	private static Path newestCrash(Path gameDir, long now) {
		if (gameDir == null) return null;
		Path dir = gameDir.resolve("crash-reports");
		if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return null;
		Path best = null;
		long bestTime = 0;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.txt")) {
			for (Path p : ds) {
				if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
				long t = Files.getLastModifiedTime(p).toMillis();
				if (now - t > CRASH_MAX_AGE_MS) continue;
				if (best == null || t > bestTime) {
					best = p;
					bestTime = t;
				}
			}
		} catch (IOException | RuntimeException e) {
			return best;
		}
		return best;
	}

	/** Bildschirmfotos (neueste zuerst), nur harmlose Dateinamen, keine Verknüpfungen. */
	static List<Screenshot> screenshots(Path gameDir) {
		List<Screenshot> out = new ArrayList<Screenshot>();
		if (gameDir == null) return out;
		Path dir = gameDir.resolve("screenshots");
		if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return out;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				String name = p.getFileName().toString();
				if (!SCREENSHOT_NAME.matcher(name).matches() || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
				out.add(new Screenshot(p, name, Files.getLastModifiedTime(p).toMillis(), Files.size(p)));
				if (out.size() > 4000) break;
			}
		} catch (IOException | RuntimeException e) {
			// was da ist, reicht
		}
		Collections.sort(out, new Comparator<Screenshot>() {
			@Override
			public int compare(Screenshot a, Screenshot b) {
				return Long.compare(b.modified, a.modified);
			}
		});
		return out.size() > MAX_SCREENSHOTS ? new ArrayList<Screenshot>(out.subList(0, MAX_SCREENSHOTS)) : out;
	}

	/** Die letzten {@code max} Bytes einer Datei als Text (erste, angeschnittene Zeile fällt weg). */
	static String readTail(Path file, int max) throws IOException {
		try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
			long len = f.length();
			long start = Math.max(0, len - max);
			byte[] buf = new byte[(int) (len - start)];
			f.seek(start);
			f.readFully(buf);
			String s = new String(buf, StandardCharsets.UTF_8);
			if (start > 0) {
				int nl = s.indexOf('\n');
				s = nl < 0 ? "" : s.substring(nl + 1);
			}
			return s;
		}
	}

	/** Die ersten {@code max} Bytes einer Datei als Text. */
	static String readHead(Path file, int max) throws IOException {
		try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
			byte[] buf = new byte[(int) Math.min(max, f.length())];
			f.readFully(buf);
			return new String(buf, StandardCharsets.UTF_8);
		}
	}

	// --- Senden ---

	/**
	 * Im Hintergrund senden: Token holen, Bildschirmfoto hochladen (falls angehakt), Issue anlegen. Nimmt die
	 * Daten, die gerade in der Vorschau stehen. false = nicht möglich (Eingaben, schon unterwegs, keine Daten).
	 */
	public boolean sendAsync() {
		if (busy() || !textValid() || data == null) return false;
		final String t = title.text().trim();
		final String d = description.text().trim();
		final BugReportBody.Meta meta = meta();
		final Screenshot shot = includeScreenshot ? selectedScreenshot() : null;
		error = null;
		created = null;
		retryAfterMs = 0;
		phase = shot != null ? Phase.UPLOADING : Phase.SENDING;
		if (!submit(new Runnable() {
			@Override
			public void run() {
				send(t, d, meta, shot);
			}
		})) {
			fail("busy", 0);
			return false;
		}
		return true;
	}

	/** Senden (synchron, Hintergrund). */
	void send(String t, String d, BugReportBody.Meta meta, Screenshot shot) {
		String token = null;
		try {
			Access a = access();
			if (a != Access.READY && a != Access.CONNECTING) {
				fail(accessError(a), 0);
				return;
			}
			token = auth.token();
			if (token == null) {
				fail(accessError(access()), 0);
				return;
			}
			BugReportApi api = new BugReportApi(httpClient(), apiBase());
			byte[] image = null;
			String mime = null;
			if (shot != null) {
				phase = Phase.UPLOADING;
				try {
					if (Files.size(shot.path) > 64L * 1024 * 1024) throw new IOException("zu groß");
					ChatImages.Upload u = ChatImages.prepareLimited(Files.readAllBytes(shot.path), BugReportApi.MAX_UPLOAD_BYTES,
							SCREENSHOT_MAX_SIDE);
					image = u.bytes;
					mime = u.mime;
				} catch (IOException | RuntimeException | OutOfMemoryError e) {
					fail("image_unreadable", 0);
					return;
				}
			}
			for (int attempt = 0; ; attempt++) {
				List<String> ids = new ArrayList<String>();
				if (image != null) {
					phase = Phase.UPLOADING;
					ids.add(api.upload(token, image, mime));
				}
				phase = Phase.SENDING;
				try {
					BugReportBody.Created c = api.create(token, BugReportBody.build(t, d, ids, meta));
					created = c;
					phase = Phase.DONE;
					return;
				} catch (ApiException e) {
					// Hochgeladenes Bild schon abgelaufen/weg: einmal neu hochladen.
					if ("upload_not_found".equals(e.code()) && image != null && attempt == 0) continue;
					throw e;
				}
			}
		} catch (ApiException e) {
			if (e.unauthorized() && token != null) auth.rejected(token);
			fail(e.unauthorized() ? "unauthorized" : e.banned() ? "banned" : e.code(), e.retryAfterMs());
		} catch (IOException e) {
			fail("offline", 0);
		} catch (RuntimeException e) {
			fail("invalid_response", 0);
		}
	}

	private void fail(String code, long retryAfter) {
		error = code == null || code.isEmpty() ? "unknown" : code;
		retryAfterMs = retryAfter;
		phase = Phase.FAILED;
	}

	static String accessError(Access a) {
		switch (a) {
			case LAUNCHER_OFF:
				return "launcher_off";
			case MODULE_OFF:
				return "module_off";
			case NO_ACCOUNT:
				return "no_account";
			case BANNED:
				return "banned";
			case CONNECTING:
			case RETRY:
				return "offline";
			default:
				return "unauthorized";
		}
	}

	private boolean submit(Runnable r) {
		try {
			worker.execute(r);
			return true;
		} catch (RejectedExecutionException e) {
			return false;
		}
	}

	private static int count(String s, char c) {
		int n = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == c) n++;
		}
		return n;
	}

	// --- Anmeldung ---

	/** TrsOnline, falls es läuft; sonst eigene Anmeldung mit der Spielsitzung (Forge 1.7.10/1.13.2). */
	final class DefaultAuth implements Auth {
		@Override
		public Access access() {
			TrsOnline o = TrsOnline.current();
			if (o != null) {
				switch (o.status()) {
					case ONLINE:
						return o.token() != null ? Access.READY : Access.CONNECTING;
					case LAUNCHER_OFF:
						return Access.LAUNCHER_OFF;
					case OFF:
						return Access.MODULE_OFF;
					case NO_ACCOUNT:
						return Access.NO_ACCOUNT;
					case BANNED:
						return Access.BANNED;
					case RETRY:
						return Access.RETRY;
					default:
						return Access.CONNECTING;
				}
			}
			OnlineConfig config = config();
			if (!config.launcherEnabled()) return Access.LAUNCHER_OFF;
			return session(config) == null ? Access.NO_ACCOUNT : Access.READY;
		}

		private volatile OnlineConfig config;
		private volatile long configAt;

		/** {@code trs-api.json} höchstens alle 5 s neu lesen (access() läuft je Bild). */
		private OnlineConfig config() {
			long now = System.currentTimeMillis();
			OnlineConfig c = config;
			if (c == null || now - configAt > 5000) {
				c = OnlineConfig.load(configDir);
				config = c;
				configAt = now;
			}
			return c;
		}

		@Override
		public String token() throws IOException, ApiException {
			TrsOnline o = TrsOnline.current();
			if (o != null) return o.token();
			String t = ownToken;
			if (t != null) return t;
			OnlineConfig config = config();
			if (!config.launcherEnabled()) return null;
			GameSession s = session(config);
			if (s == null) return null;
			t = new TrsApi(httpClient(), config).login(s).token;
			ownToken = t;
			return t;
		}

		@Override
		public void rejected(String token) {
			TrsOnline o = TrsOnline.current();
			if (o != null) o.tokenRejected(token);
			if (token != null && token.equals(ownToken)) ownToken = null;
		}

		/** Spielsitzung zum Anmelden oder null (Offline-/Demo-Konto). */
		private GameSession session(OnlineConfig config) {
			AccountManager am = AccountManager.get();
			SessionData d = am == null ? null : am.currentSession();
			if (d == null || d.name == null) return null;
			boolean devMock = config.apiBase().startsWith("http://");
			String uuid = d.uuid;
			if ((uuid == null || Uuids.normalize(uuid) == null) && devMock) {
				// Nur gegen die lokale Test-Attrappe: Entwicklungsstarts ohne UUID bekommen die Offline-UUID.
				uuid = Uuids.of(UUID.nameUUIDFromBytes(("OfflinePlayer:" + d.name).getBytes(StandardCharsets.UTF_8)));
			}
			GameSession s = new GameSession(uuid, d.name, d.accessToken == null && devMock ? "dev-token-0000" : d.accessToken);
			return s.uuid != null && (s.usable() || devMock) ? s : null;
		}
	}
}

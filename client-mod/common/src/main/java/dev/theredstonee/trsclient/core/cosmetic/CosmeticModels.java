package dev.theredstonee.trsclient.core.cosmetic;

import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.OnlineConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Die Kopf-Vorlagen, die diese Mod zeichnet. Bewusst eine feste Liste: andere Kopf-Kosmetik der API bleibt
 * unsichtbar, bis sie hier freigegeben wird.
 *
 * <p>Eine Vorlage kann noch im Jar liegen ({@code assets/trsclient/cosmetics/<vorlage>.json}). Fehlt sie, lädt
 * {@link #get(HatInfo)} sie im Hintergrund von {@code templateUrl} (sonst {@code /v1/cosmetics/<id>/template.json}),
 * prüft sie mit {@link CosmeticModel#parse} und legt sie unter
 * {@code config/trsclient/cosmetics/templates/<id>.json} ab – daneben {@code <id>.hash} mit dem {@code ?v=}-Hash.
 * Gleicher Hash kommt ohne Netz aus dem Cache (auch offline). Der Render-Thread wartet nie: bis die Vorlage da ist,
 * wird das Teil einfach nicht gezeichnet.
 */
public final class CosmeticModels {
	/** Höchstens so groß darf eine Vorlage sein (Netz und Platte). */
	public static final int MAX_BYTES = 256 * 1024;
	private static final long RETRY_MS = 30_000L;

	private static final String[] SUPPORTED = { "duck" };

	/** Lädt den Körper einer Vorlage. Wirft oder liefert null, wenn das Netz nicht erreichbar ist. */
	public interface Source {
		byte[] get(String url) throws IOException;
	}

	private static final class Entry {
		final CosmeticModel model;
		/** Hash aus {@code ?v=}, sonst der gespeicherte Cache-Hash (kann leer sein). */
		final String hash;

		Entry(CosmeticModel model, String hash) {
			this.model = model;
			this.hash = hash == null ? "" : hash;
		}
	}

	private static final class Loaded {
		final CosmeticModel model;
		final String hash;
		/** false = nur alter Cache, Netz gerade nicht erreichbar. */
		final boolean current;

		Loaded(CosmeticModel model, String hash, boolean current) {
			this.model = model;
			this.hash = hash == null ? "" : hash;
			this.current = current;
		}
	}

	private static final Map<String, Entry> MEMORY = new HashMap<String, Entry>();
	private static final Map<String, CosmeticModel> BUNDLED = new HashMap<String, CosmeticModel>();
	private static final Set<String> BUNDLED_TRIED = new HashSet<String>();
	private static final Set<String> INFLIGHT = new HashSet<String>();
	private static final Map<String, Long> RETRY_AT = new HashMap<String, Long>();

	private static Path dir;
	private static OnlineConfig config;
	private static Source source;
	private static Consumer<String> log;
	/** Wird beim Zurücksetzen erhöht, damit ein noch laufender Abruf nichts mehr einträgt. */
	private static int generation;

	private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<Runnable>(16), r -> {
				Thread t = new Thread(r, "TRS-Vorlagen");
				t.setDaemon(true);
				return t;
			});

	static {
		WORKER.allowCoreThreadTimeOut(true);
	}

	private CosmeticModels() {
	}

	public static boolean supported(String template) {
		for (String s : SUPPORTED) if (s.equals(template)) return true;
		return false;
	}

	/**
	 * Wohin geladen wird und woher. {@code configDir} ist {@code config/trsclient/cosmetics/templates}.
	 * Nur aus dem Start heraus aufrufen, nie aus dem Render-Thread mit einem blockierenden {@code source}.
	 */
	public static synchronized void install(Path templatesDir, OnlineConfig online, Source fetch, Consumer<String> logger) {
		dir = templatesDir;
		config = online;
		source = fetch;
		log = logger;
	}

	/**
	 * Schon geladene oder mitgelieferte Vorlage, ohne Netz. Unbekannt → null.
	 * Startet keinen Abruf – dafür {@link #get(HatInfo)}.
	 */
	public static CosmeticModel get(String template) {
		if (!supported(template)) return null;
		synchronized (CosmeticModels.class) {
			Entry e = MEMORY.get(template);
			if (e != null) return e.model;
		}
		return bundled(template);
	}

	/**
	 * Vorlage dieses Teils. Liegt sie noch nicht vor, läuft der Abruf im Hintergrund an und die Methode gibt sofort
	 * null zurück (nichts zeichnen). Niemals blockierend.
	 */
	public static CosmeticModel get(HatInfo hat) {
		if (hat == null || hat.v2() || !supported(hat.template)) return null;
		Path useDir;
		OnlineConfig useConfig;
		Source useSource;
		Consumer<String> useLog;
		int gen;
		synchronized (CosmeticModels.class) {
			useDir = dir;
			useConfig = config;
			useSource = source;
			useLog = log;
			gen = generation;
		}
		if (useDir == null || useConfig == null || useSource == null) return get(hat.template);
		String url = resolveUrl(hat.id, hat.templateUrl, useConfig);
		if (url == null) return get(hat.template);
		String want = hashOf(url);
		String flight = hat.id + "|" + (want == null ? "-" : want);
		synchronized (CosmeticModels.class) {
			if (gen != generation) return get(hat.template);
			Entry e = MEMORY.get(hat.template);
			if (e != null && (want == null || want.equals(e.hash))) return e.model;
			if (INFLIGHT.contains(flight) || !retryReady(flight)) return e == null ? null : e.model;
			CosmeticModel packed = bundled(hat.template);
			if (packed != null) {
				MEMORY.put(hat.template, new Entry(packed, want == null ? "" : want));
				return packed;
			}
			INFLIGHT.add(flight);
			try {
				WORKER.execute(() -> finish(useDir, hat, useConfig, useSource, useLog, flight, gen));
			} catch (RejectedExecutionException ex) {
				INFLIGHT.remove(flight);
			}
			return e == null ? null : e.model;
		}
	}

	/**
	 * Blockierend laden (Garderobe, Tests): Platte, sonst Netz, bei Netzfehler der alte Cache. null = nichts zu zeichnen.
	 */
	public static CosmeticModel load(Path templatesDir, String itemId, String template, String templateUrl,
			OnlineConfig online, Source fetch) {
		Loaded loaded = fetch(templatesDir, itemId, template, templateUrl, online, fetch);
		if (loaded == null) return null;
		synchronized (CosmeticModels.class) {
			MEMORY.put(loaded.model.id, new Entry(loaded.model, loaded.hash));
		}
		return loaded.model;
	}

	private static void finish(Path useDir, HatInfo hat, OnlineConfig useConfig, Source useSource, Consumer<String> useLog,
			String flight, int gen) {
		Loaded loaded = null;
		try {
			loaded = fetch(useDir, hat.id, hat.template, hat.templateUrl, useConfig, useSource);
		} catch (RuntimeException e) {
			loaded = null;
		}
		boolean tell = false;
		synchronized (CosmeticModels.class) {
			INFLIGHT.remove(flight);
			if (gen != generation) return;
			if (loaded != null) MEMORY.put(loaded.model.id, new Entry(loaded.model, loaded.hash));
			if (loaded == null || !loaded.current) {
				RETRY_AT.put(flight, System.currentTimeMillis() + RETRY_MS);
				tell = loaded == null;
			} else {
				RETRY_AT.remove(flight);
			}
		}
		if (tell && useLog != null) useLog.accept("TRS API: Vorlage '" + hat.id + "' nicht ladbar");
	}

	/** Mitgelieferte Vorlage oder null (einmal nachgesehen). */
	static CosmeticModel bundled(String template) {
		if (!supported(template)) return null;
		synchronized (CosmeticModels.class) {
			if (BUNDLED_TRIED.contains(template)) return BUNDLED.get(template);
			BUNDLED_TRIED.add(template);
		}
		CosmeticModel model = readBundled(template);
		if (model == null) return null;
		synchronized (CosmeticModels.class) {
			BUNDLED.put(template, model);
		}
		return model;
	}

	/** Tests: Speicher und laufende Abrufe verwerfen (der Cache auf der Platte bleibt). */
	static synchronized void reset() {
		generation++;
		MEMORY.clear();
		INFLIGHT.clear();
		RETRY_AT.clear();
		BUNDLED.clear();
		BUNDLED_TRIED.clear();
		dir = null;
		config = null;
		source = null;
		log = null;
	}

	/** Tests: nur den Speicher leeren, Installation und Platte behalten. */
	static synchronized void clearMemory() {
		MEMORY.clear();
	}

	private static boolean retryReady(String flight) {
		Long at = RETRY_AT.get(flight);
		return at == null || System.currentTimeMillis() >= at;
	}

	private static Loaded fetch(Path templatesDir, String itemId, String template, String templateUrl, OnlineConfig online,
			Source fetch) {
		if (!supported(template) || templatesDir == null) return null;
		String url = resolveUrl(itemId, templateUrl, online);
		if (url == null) return null;
		String hash = hashOf(url);
		Path file = child(templatesDir, itemId, ".json");
		Path hashFile = child(templatesDir, itemId, ".hash");
		if (file == null) return null;
		byte[] cached = read(file);
		String cachedHash = readHash(hashFile);
		if (cached != null && (hash == null || hash.equals(cachedHash))) {
			CosmeticModel model = tryParse(cached, template);
			if (model != null) return new Loaded(model, hash != null ? hash : cachedHash, true);
		}
		byte[] body = null;
		if (fetch != null) {
			try {
				body = fetch.get(url);
			} catch (IOException e) {
				body = null;
			}
		}
		if (body != null) {
			CosmeticModel model = tryParse(body, template);
			if (model != null) {
				String store = hash != null ? hash : sha12(body);
				write(templatesDir, file, hashFile, body, store);
				return new Loaded(model, store, true);
			}
		}
		if (cached != null) {
			CosmeticModel model = tryParse(cached, template);
			if (model != null) return new Loaded(model, cachedHash, false);
		}
		return null;
	}

	/** {@code templateUrl}, sonst {@code <api>/v1/cosmetics/<id>/template.json}. Fremde Adressen → null. */
	static String resolveUrl(String itemId, String templateUrl, OnlineConfig online) {
		if (templateUrl != null && allowed(templateUrl, online)) return templateUrl;
		if (online == null || itemId == null || !itemId.matches("[a-z0-9][a-z0-9_-]{0,35}")) return null;
		String fallback = online.apiBase() + "/v1/cosmetics/" + itemId + "/template.json";
		return allowed(fallback, online) ? fallback : null;
	}

	/** https, oder http nur für localhost/127.0.0.1 – und nur die TRS API. */
	static boolean allowed(String url, OnlineConfig online) {
		if (online == null || !online.isApiUrl(url)) return false;
		try {
			URI uri = new URI(url);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			if (scheme.equals("https")) return true;
			return scheme.equals("http") && (host.equals("localhost") || host.equals("127.0.0.1"));
		} catch (Exception e) {
			return false;
		}
	}

	/** {@code ?v=} (6–64 Hex) oder null. */
	static String hashOf(String url) {
		if (url == null) return null;
		int q = url.indexOf('?');
		if (q < 0 || q + 1 >= url.length()) return null;
		String[] parts = url.substring(q + 1).split("&");
		for (String part : parts) {
			if (!part.startsWith("v=") || part.length() < 3) continue;
			String v = part.substring(2).toLowerCase(Locale.ROOT);
			if (v.matches("[0-9a-f]{6,64}")) return v;
		}
		return null;
	}

	private static CosmeticModel readBundled(String template) {
		if (!template.matches("[a-z0-9][a-z0-9_-]{0,35}")) return null;
		try (InputStream in = CosmeticModels.class.getResourceAsStream("/assets/trsclient/cosmetics/" + template + ".json")) {
			if (in == null) return null;
			byte[] body = readLimited(in, MAX_BYTES);
			return tryParse(body, template);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static CosmeticModel tryParse(byte[] body, String template) {
		if (body == null || body.length == 0 || body.length > MAX_BYTES) return null;
		try {
			CosmeticModel model = CosmeticModel.parse(new String(body, StandardCharsets.UTF_8));
			if (model == null || !template.equals(model.id)) return null;
			return model;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Path child(Path templatesDir, String itemId, String ext) {
		if (itemId == null || !itemId.matches("[a-z0-9][a-z0-9_-]{0,35}")) return null;
		return templatesDir.resolve(itemId + ext);
	}

	private static byte[] read(Path file) {
		try {
			if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return null;
			return Files.readAllBytes(file);
		} catch (IOException e) {
			return null;
		}
	}

	private static String readHash(Path file) {
		try {
			if (file == null || !Files.isRegularFile(file) || Files.size(file) > 80) return null;
			String s = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
			return s.matches("[0-9a-f]{6,64}") ? s : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static void write(Path templatesDir, Path file, Path hashFile, byte[] body, String hash) {
		try {
			Files.createDirectories(templatesDir);
			writeAtomic(file, body);
			if (hash != null && hash.matches("[0-9a-f]{6,64}")) {
				writeAtomic(hashFile, hash.getBytes(StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			// Cache ist nur eine Beschleunigung
		}
	}

	private static void writeAtomic(Path file, byte[] data) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
		Files.write(tmp, data);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
	}

	private static String sha12(byte[] body) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(body);
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i] & 0xFF));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			return "000000000000";
		}
	}

	private static byte[] readLimited(InputStream in, int max) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		while ((n = in.read(buf)) > 0) {
			if (out.size() + n > max) throw new IOException("zu groß");
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}
}

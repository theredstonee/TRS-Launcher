package dev.theredstonee.trsclient.core.partner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Partner-Server für die Mehrspieler-Liste (API.md §44.3/§44.10): holt {@code GET /v1/partners/servers?mc=…} höchstens
 * alle {@code ttl} Sekunden (ETag/304), hält die Liste im Speicher und in {@code config/trsclient/partner-servers.json}
 * (Start ohne Netz), meldet Beitritte. Ist {@link Env#enabled()} aus, wird nichts geladen und nichts gezeigt.
 */
public final class PartnerServers {
	public static final String FILE = "partner-servers.json";
	static final int DEFAULT_TTL = 600;
	static final int MIN_TTL = 60;
	static final int MAX_TTL = 86_400;
	static final long RETRY_MS = 120_000;
	static final long MAX_CACHE_AGE_MS = 7L * 24 * 3600 * 1000;
	static final int MAX_BODY = 64 * 1024;

	/** Was der Dienst vom Spiel braucht (im Spiel aus Modulen und TrsOnline, in Tests eine Attrappe). */
	public interface Env {
		/** Einstellung an, Online-Funktionen an, Launcher erlaubt die TRS API? */
		boolean enabled();

		String apiBase();

		/** TRS-Token oder null (Beitritt wird dann ohne Anmeldung gemeldet). */
		String token();
	}

	private static final PartnerServers INSTANCE = new PartnerServers();

	public static PartnerServers get() {
		return INSTANCE;
	}

	private volatile Env env;
	private volatile Http http;
	private volatile Path file;
	private volatile String mc;
	private volatile Executor executor;
	private PartnerLogos logos;
	private final PartnerPings pings = new PartnerPings();

	private List<PartnerServer> servers = Collections.emptyList();
	/** Antwort der API wie erhalten (für die Platte). */
	private JsonArray raw = new JsonArray();
	private String etag;
	private long fetchedAt;
	private int ttl = DEFAULT_TTL;
	private boolean diskLoaded;
	private boolean inFlight;
	private long retryAt;

	PartnerServers() {
	}

	/** Einmal beim Start (aus {@code TrsOnline.create}). */
	public void init(Path configDir, Http http, String mcVersion, Env env) {
		init(configDir == null ? null : configDir.resolve("trsclient"), http, mcVersion, env, null);
	}

	synchronized void init(Path dir, Http http, String mcVersion, Env env, Executor executor) {
		this.http = http;
		this.env = env;
		this.mc = mcVersion != null && mcVersion.matches("[0-9]{1,3}(\\.[0-9]{1,3}){0,3}") ? mcVersion : null;
		this.file = dir == null ? null : dir.resolve(FILE);
		this.executor = executor != null ? executor : worker();
		this.logos = new PartnerLogos(dir == null ? null : dir.resolve("partner-logos"));
		servers = Collections.emptyList();
		raw = new JsonArray();
		etag = null;
		fetchedAt = 0;
		ttl = DEFAULT_TTL;
		diskLoaded = false;
		inFlight = false;
		retryAt = 0;
	}

	private static Executor worker() {
		ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(64),
				r -> {
					Thread t = new Thread(r, "TRS-Partners");
					t.setDaemon(true);
					return t;
				});
		ex.allowCoreThreadTimeOut(true);
		return ex;
	}

	public boolean enabled() {
		Env e = env;
		try {
			return e != null && http != null && e.enabled() && e.apiBase() != null;
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/**
	 * Aktuelle Partner-Server für die gespielte Version (leer, wenn aus). Stößt bei Bedarf im Hintergrund ein Neuladen an
	 * – billig, darf jedes Bild aufgerufen werden.
	 */
	public List<PartnerServer> servers(long now) {
		if (!enabled()) return Collections.emptyList();
		boolean load;
		synchronized (this) {
			if (!diskLoaded) {
				diskLoaded = true;
				readDisk(now);
			}
			load = !inFlight && now >= retryAt && (fetchedAt == 0 || now - fetchedAt >= ttl * 1000L);
			if (load) inFlight = true;
		}
		if (load) {
			try {
				executor.execute(new Runnable() {
					@Override
					public void run() {
						fetch(now);
					}
				});
			} catch (RuntimeException e) {
				synchronized (this) {
					inFlight = false;
					retryAt = now + RETRY_MS;
				}
			}
		}
		synchronized (this) {
			return servers;
		}
	}

	/** Ein Abruf (blockierend, im Hintergrund bzw. in Tests). */
	void fetch(long now) {
		String base;
		String tag;
		boolean ok = false;
		try {
			base = env.apiBase();
			synchronized (this) {
				tag = servers.isEmpty() && fetchedAt == 0 ? null : etag;
			}
			Http.Request req = new Http.Request("GET", base + "/v1/partners/servers" + (mc == null ? "" : "?mc=" + enc(mc)))
					.header("Accept", "application/json");
			if (tag != null) req.header("If-None-Match", tag);
			req.maxBytes = MAX_BODY;
			Http.Response res = http.send(req);
			if (res.status == 304) {
				synchronized (this) {
					fetchedAt = now;
				}
				ok = true;
				writeDisk();
			} else if (res.status == 200) {
				JsonElement root = new JsonParser().parse(res.text());
				if (root != null && root.isJsonObject()) {
					JsonObject o = root.getAsJsonObject();
					List<PartnerServer> list = PartnerServer.parseList(o.get("servers"));
					String newTag = res.header("ETag");
					synchronized (this) {
						servers = filter(list);
						raw = o.get("servers") != null && o.get("servers").isJsonArray() ? o.getAsJsonArray("servers") : new JsonArray();
						etag = newTag != null && newTag.length() <= 200 ? newTag : null;
						ttl = ttl(o.get("ttl"));
						fetchedAt = now;
					}
					ok = true;
					writeDisk();
				}
			}
		} catch (IOException | RuntimeException e) {
			ok = false;
		} finally {
			synchronized (this) {
				inFlight = false;
				if (!ok) retryAt = now + RETRY_MS;
			}
		}
	}

	private List<PartnerServer> filter(List<PartnerServer> list) {
		List<PartnerServer> out = new ArrayList<PartnerServer>();
		for (PartnerServer s : list) {
			if (s.supports(mc)) out.add(s);
		}
		return Collections.unmodifiableList(out);
	}

	static int ttl(JsonElement e) {
		try {
			if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return DEFAULT_TTL;
			return Math.max(MIN_TTL, Math.min(MAX_TTL, e.getAsInt()));
		} catch (RuntimeException ex) {
			return DEFAULT_TTL;
		}
	}

	private void readDisk(long now) {
		Path f = file;
		if (f == null) return;
		try {
			if (!Files.isRegularFile(f) || Files.size(f) > MAX_BODY * 2L) return;
			JsonElement root = new JsonParser().parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
			if (root == null || !root.isJsonObject()) return;
			JsonObject o = root.getAsJsonObject();
			String cachedMc = o.has("mc") && o.get("mc").isJsonPrimitive() ? o.get("mc").getAsString() : null;
			long at = o.has("fetchedAt") && o.get("fetchedAt").isJsonPrimitive() ? o.get("fetchedAt").getAsLong() : 0;
			if (mc != null && !mc.equals(cachedMc)) return;
			if (at <= 0 || at > now + 60_000 || now - at > MAX_CACHE_AGE_MS) return;
			JsonArray arr = o.has("servers") && o.get("servers").isJsonArray() ? o.getAsJsonArray("servers") : new JsonArray();
			servers = filter(PartnerServer.parseList(arr));
			raw = arr;
			String tag = o.has("etag") && o.get("etag").isJsonPrimitive() ? o.get("etag").getAsString() : null;
			etag = tag != null && tag.length() <= 200 ? tag : null;
			ttl = ttl(o.get("ttl"));
			fetchedAt = at;
		} catch (IOException | RuntimeException e) {
			servers = Collections.emptyList();
		}
	}

	private void writeDisk() {
		Path f = file;
		if (f == null) return;
		JsonObject o = new JsonObject();
		synchronized (this) {
			o.addProperty("version", 1);
			o.addProperty("mc", mc);
			if (etag != null) o.addProperty("etag", etag);
			o.addProperty("ttl", ttl);
			o.addProperty("fetchedAt", fetchedAt);
			o.add("servers", raw);
		}
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(FILE + ".tmp");
			Files.write(tmp, o.toString().getBytes(StandardCharsets.UTF_8));
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException ignored) {
			// nur der Start ohne Netz fehlt dann
		}
	}

	/** „ausblenden ✕“: Einstellung aus (gespeichert und mit dem Konto abgeglichen wie jede andere). */
	public void hide() {
		dev.theredstonee.trsclient.core.module.TrsModules m = dev.theredstonee.trsclient.core.intro.IntroGate.modules();
		if (m == null) return;
		m.showPartnerServers.set(false);
		dev.theredstonee.trsclient.core.intro.IntroGate.save(m);
	}

	/** Beitritt über die Liste melden (im Hintergrund, Fehler egal). */
	public void reportJoin(final PartnerServer s) {
		final Env e = env;
		final Http h = http;
		if (s == null || e == null || h == null) return;
		final String base;
		final String token;
		try {
			base = e.apiBase();
			token = e.token();
		} catch (RuntimeException ex) {
			return;
		}
		if (base == null) return;
		try {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						Http.Request req = new Http.Request("POST", base + "/v1/partners/servers/" + enc(s.id) + "/join");
						req.body = new byte[0];
						req.maxBytes = 4096;
						if (token != null) req.header("Authorization", "Bearer " + token);
						h.send(req);
					} catch (IOException | RuntimeException ignored) {
						// zählt dann eben nicht
					}
				}
			});
		} catch (RuntimeException ignored) {
			// Warteschlange voll
		}
	}

	/** Logo des Servers als Textur (nur im Render-Thread) oder null, solange es lädt bzw. keins da ist. */
	public dev.theredstonee.trsclient.core.ui.TextureRef logo(PartnerServer s, long now) {
		PartnerLogos l = logos;
		Env e = env;
		if (l == null || e == null || s == null || s.icon == null) return null;
		String base;
		try {
			base = e.apiBase();
		} catch (RuntimeException ex) {
			return null;
		}
		return base == null ? null : l.texture(s.icon, base + s.icon, http, executor, now);
	}

	public PartnerPings pings() {
		return pings;
	}

	private static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			return s;
		}
	}
}

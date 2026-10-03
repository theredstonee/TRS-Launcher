package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.cosmetic.CosmeticModel;
import dev.theredstonee.trsclient.core.cosmetic.CosmeticModels;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Cache;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import dev.theredstonee.trsclient.core.online.TrsApi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kopf-Kosmetik für die Garderobe (Reiter „Kosmetik“): Katalog {@code GET /v1/cosmetics} (nur Slot {@code hat}; Format 2
 * und – wenn besessen – die Quietscheente), Vorschaubilder der Karten (Tag/Nacht), Modell + Texturen zum Anprobieren
 * und Aufsetzen/Absetzen über {@code PUT /v1/me/cosmetics}. Alles im eigenen Hintergrund-Thread „TRS-Kosmetik“; die
 * Oberfläche liest nur den unveränderlichen {@link State}.
 */
public final class CosmeticCatalog {
	/** Kartenbilder werden auf höchstens diese Kante verkleinert (Speicher, Upload). */
	static final int CARD_EDGE = 160;
	static final int MAX_CARD_BYTES = 2 * 1024 * 1024;
	static final int MAX_ITEMS = 64;

	/** Ein Teil im Katalog. */
	public static final class Item {
		public final String id;
		public final String name;
		/** 1 (Vorlage, z. B. Ente) oder 2 (Studio-Modell). */
		public final int format;
		/** {@code free}, {@code code}, {@code admin}, {@code achievement} … */
		public final String unlock;
		public final boolean owned;
		public final boolean hidden;
		/** Format 2: Adressen für die Vorschau; Format 1: null. */
		public final HatInfo hat;
		/** Format 1: Vorlage + Textur. */
		public final String template;
		public final String textureUrl;
		/** Format 1: Adresse von {@code template.json} (absolut) oder null → fester Pfad. */
		public final String templateUrl;
		public final String cardUrl;
		public final String cardNightUrl;

		Item(String id, String name, int format, String unlock, boolean owned, boolean hidden, HatInfo hat, String template,
				String textureUrl, String templateUrl, String cardUrl, String cardNightUrl) {
			this.id = id;
			this.name = name;
			this.format = format;
			this.unlock = unlock;
			this.owned = owned;
			this.hidden = hidden;
			this.hat = hat;
			this.template = template;
			this.textureUrl = textureUrl;
			this.templateUrl = templateUrl;
			this.cardUrl = cardUrl;
			this.cardNightUrl = cardNightUrl;
		}

		/** Gesperrt (nicht besessen)? */
		public boolean locked() {
			return !owned;
		}
	}

	/** Ein Bild (ARGB). */
	public static final class Art {
		public final int[] argb;
		public final int width;
		public final int height;

		Art(int[] argb, int width, int height) {
			this.argb = argb;
			this.width = width;
			this.height = height;
		}
	}

	/** Was die 3D-Vorschau braucht. */
	public static final class Preview {
		/** Format 2 (sonst null). */
		public final CosmeticV2Cache.Loaded v2;
		/** Format 1: Vorlage + erstes Texturbild. */
		public final CosmeticModel v1;
		public final int[] v1Pixels;
		public final int v1Width;
		public final int v1Height;

		Preview(CosmeticV2Cache.Loaded v2, CosmeticModel v1, int[] v1Pixels, int v1Width, int v1Height) {
			this.v2 = v2;
			this.v1 = v1;
			this.v1Pixels = v1Pixels;
			this.v1Width = v1Width;
			this.v1Height = v1Height;
		}
	}

	public static final class State {
		public final List<Item> items;
		/** Getragene Kopf-Kosmetik (ID) oder null. */
		public final String equipped;
		public final boolean loaded;
		public final boolean loading;
		/** Mit der TRS API verbunden (angemeldet)? */
		public final boolean trs;
		/** Gerade aufsetzen/absetzen (ID bzw. „-“ fürs Absetzen) oder null. */
		public final String busy;
		/** i18n-Schlüssel der letzten Meldung oder null. */
		public final String message;
		public final boolean error;
		public final int version;
		public final Map<String, Art> cards;
		public final Map<String, Preview> previews;
		/** IDs, deren Vorschau nicht ladbar war. */
		public final Set<String> failed;

		State(List<Item> items, String equipped, boolean loaded, boolean loading, boolean trs, String busy, String message,
				boolean error, int version, Map<String, Art> cards, Map<String, Preview> previews, Set<String> failed) {
			this.items = items;
			this.equipped = equipped;
			this.loaded = loaded;
			this.loading = loading;
			this.trs = trs;
			this.busy = busy;
			this.message = message;
			this.error = error;
			this.version = version;
			this.cards = cards;
			this.previews = previews;
			this.failed = failed;
		}

		public Item item(String id) {
			if (id == null) return null;
			for (Item i : items) if (i.id.equals(id)) return i;
			return null;
		}

		/** Kartenbild (Tag oder Nacht) oder null. */
		public Art card(String id, boolean night) {
			Art a = cards.get(id + (night ? "#n" : "#d"));
			return a != null ? a : cards.get(id + "#d");
		}
	}

	private static volatile CosmeticCatalog instance;

	private final WardrobeService.Platform platform;
	private final Http http;
	private final ExecutorService worker;
	private final AtomicReference<State> state = new AtomicReference<State>();

	// --- nur Hintergrund-Thread ---
	private List<Item> items = Collections.emptyList();
	private String equipped;
	private boolean loaded;
	private boolean loadingFlag;
	private boolean trs;
	private String busy;
	private String message;
	private boolean error;
	private int version;
	private final Map<String, Art> cards = new LinkedHashMap<String, Art>();
	private final Map<String, Preview> previews = new LinkedHashMap<String, Preview>();
	private final Set<String> failed = new HashSet<String>();
	/** Angefragte Vorschauen (Render-Thread schreibt, Hintergrund liest – nur über submit). */
	private final Set<String> previewRequested = Collections.synchronizedSet(new HashSet<String>());
	private volatile long lastRefresh;

	CosmeticCatalog(WardrobeService.Platform platform, Http http, ExecutorService worker) {
		this.platform = platform;
		this.http = http;
		this.worker = worker;
		publish();
	}

	/** Gemeinsame Instanz (beim ersten Öffnen des Reiters angelegt). */
	public static CosmeticCatalog shared(WardrobeService.Platform platform) {
		CosmeticCatalog c = instance;
		if (c != null) return c;
		synchronized (CosmeticCatalog.class) {
			if (instance == null) {
				ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "TRS-Kosmetik");
					t.setDaemon(true);
					t.setPriority(Thread.MIN_PRIORITY + 1);
					return t;
				});
				instance = new CosmeticCatalog(platform, new Http.UrlConnection(platform.userAgent()), worker);
			}
			return instance;
		}
	}

	/** Nur für Tests. */
	static CosmeticCatalog create(WardrobeService.Platform platform, Http http, ExecutorService worker) {
		return new CosmeticCatalog(platform, http, worker);
	}

	public State state() {
		return state.get();
	}

	// ====================================================================================================
	// Aufrufe aus der Oberfläche
	// ====================================================================================================

	/** Katalog (neu) laden – höchstens alle 5 s, außer {@code force}. */
	public void refresh(boolean force) {
		long now = System.currentTimeMillis();
		if (!force && now - lastRefresh < 5000) return;
		lastRefresh = now;
		submit(() -> {
			loadingFlag = true;
			publish();
			try {
				load();
			} finally {
				loadingFlag = false;
				publish();
			}
		});
	}

	/** Modell + Texturen eines Teils für die Vorschau laden (einmal). */
	public void wantPreview(final String id) {
		if (id == null || !previewRequested.add(id)) return;
		submit(() -> loadPreview(id));
	}

	/** Aufsetzen ({@code id}) oder absetzen (null). */
	public void wear(final String id) {
		State s = state.get();
		if (s.busy != null) return;
		submit(() -> {
			String token = platform.trsToken();
			if (token == null) {
				fail("wardrobe.cosmetics.error.offline");
				return;
			}
			busy = id == null ? "-" : id;
			message = null;
			publish();
			try {
				api().setHat(token, id);
				equipped = id;
				message = id == null ? "wardrobe.cosmetics.takenOff" : "wardrobe.cosmetics.putOn";
				error = false;
				// eigenen Lookup auffrischen, damit man es im Spiel sofort trägt
				platform.trsCapeChanged();
			} catch (ApiException e) {
				if (e.unauthorized()) platform.trsTokenRejected(token);
				message = "cosmetic_locked".equals(e.code()) ? "wardrobe.cosmetics.error.locked"
						: "cosmetic_not_found".equals(e.code()) ? "wardrobe.cosmetics.error.notFound"
						: "wardrobe.cosmetics.error.generic";
				error = true;
			} catch (IOException e) {
				message = "wardrobe.cosmetics.error.offline";
				error = true;
			} finally {
				busy = null;
				version++;
				publish();
			}
		});
	}

	// ====================================================================================================
	// Hintergrund
	// ====================================================================================================

	private WardrobeApi api() {
		return new WardrobeApi(http, platform.apiBase());
	}

	private TrsApi trsApi() {
		return new TrsApi(http, config());
	}

	private OnlineConfig config() {
		return new OnlineConfig(true, platform.apiBase(), OnlineConfig.DEFAULT_SESSION);
	}

	private void load() {
		String token = platform.trsToken();
		trs = token != null;
		if (token == null) {
			publish();
			return;
		}
		String raw;
		try {
			raw = api().cosmetics(token);
		} catch (ApiException e) {
			if (e.unauthorized()) platform.trsTokenRejected(token);
			fail("wardrobe.cosmetics.error.generic");
			return;
		} catch (IOException e) {
			fail("wardrobe.cosmetics.error.offline");
			return;
		}
		List<Item> parsed = new ArrayList<Item>();
		String eq = null;
		try {
			JsonElement root = new JsonParser().parse(raw);
			JsonArray list = root.isJsonObject() && root.getAsJsonObject().get("cosmetics") != null
					&& root.getAsJsonObject().get("cosmetics").isJsonArray() ? root.getAsJsonObject().getAsJsonArray("cosmetics") : null;
			if (list != null) {
				OnlineConfig cfg = config();
				for (JsonElement e : list) {
					if (!e.isJsonObject() || parsed.size() >= MAX_ITEMS) continue;
					Item it = parse(e.getAsJsonObject(), cfg);
					if (it == null) continue;
					parsed.add(it);
					if (bool(e.getAsJsonObject(), "equipped")) eq = it.id;
				}
			}
		} catch (RuntimeException e) {
			fail("wardrobe.cosmetics.error.generic");
			return;
		}
		items = Collections.unmodifiableList(parsed);
		equipped = eq;
		loaded = true;
		publish();
		// Kartenbilder nachladen (Tag + Nacht)
		TrsApi api = trsApi();
		for (Item it : parsed) {
			loadCard(api, it.id + "#d", it.cardUrl, token);
			loadCard(api, it.id + "#n", it.cardNightUrl, token);
		}
	}

	/** Ein Katalog-Eintrag; nur Kopf-Kosmetik, die diese Mod zeichnen kann. */
	static Item parse(JsonObject o, OnlineConfig cfg) {
		String id = str(o, "id");
		if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,39}")) return null;
		if (!"hat".equals(str(o, "slot"))) return null;
		String name = SkinFiles.cleanName(str(o, "name"), id);
		String unlock = str(o, "unlock");
		if (unlock == null || !unlock.matches("[a-z_]{1,24}")) unlock = "code";
		boolean owned = bool(o, "owned");
		boolean hidden = bool(o, "hidden");
		// Versteckte nur, wenn besessen (die API liefert sie sonst ohnehin nicht).
		if (hidden && !owned) return null;
		int format = num(o, "format", 1);
		String textureUrl = null;
		JsonElement tex = o.get("texture");
		if (tex != null && tex.isJsonPrimitive()) textureUrl = tex.getAsString();
		else if (tex != null && tex.isJsonObject()) textureUrl = str(tex.getAsJsonObject(), "url");
		String card = resolve(str(o, "card"), cfg);
		String cardNight = resolve(str(o, "cardNight"), cfg);
		if (format == 2) {
			HatInfo hat = HatInfo.v2(id, str(o, "model"), textureUrl, str(o, "glow"), str(o, "hash"),
					integer(o, "frames"), integer(o, "glowFrames"), cfg);
			if (hat == null) return null;
			return new Item(id, name, 2, unlock, owned, hidden, hat, null, null, null, card, cardNight);
		}
		String template = str(o, "template");
		if (template == null || !CosmeticModels.supported(template) || !owned) return null;
		String url = resolve(textureUrl, cfg);
		if (url == null) return null;
		return new Item(id, name, 1, unlock, owned, hidden, null, template, url, resolve(str(o, "templateUrl"), cfg), card,
				cardNight);
	}

	private static String resolve(String url, OnlineConfig cfg) {
		if (url == null || url.isEmpty() || url.length() >= 512) return null;
		String abs = url.startsWith("/v1/") ? cfg.apiBase() + url : url;
		return cfg.isApiUrl(abs) ? abs : null;
	}

	private void loadCard(TrsApi api, String key, String url, String token) {
		if (url == null || cards.containsKey(key)) return;
		try {
			PngDecoder.Image img = PngDecoder.decode(api.asset(url, "image/png", MAX_CARD_BYTES, token));
			cards.put(key, shrink(img));
			version++;
			publish();
		} catch (Exception ignored) {
			// ohne Bild (Karte zeigt ein Symbol)
		}
	}

	/** Auf höchstens {@link #CARD_EDGE} verkleinern (Mittelwert je Block, Alpha-gewichtet). */
	static Art shrink(PngDecoder.Image img) {
		int w = img.width;
		int h = img.height;
		int f = 1;
		while (w / f > CARD_EDGE || h / f > CARD_EDGE) f++;
		if (f == 1) return new Art(img.argb, w, h);
		int nw = w / f;
		int nh = h / f;
		int[] out = new int[nw * nh];
		for (int y = 0; y < nh; y++) {
			for (int x = 0; x < nw; x++) {
				long a = 0, r = 0, g = 0, b = 0;
				for (int dy = 0; dy < f; dy++) {
					for (int dx = 0; dx < f; dx++) {
						int p = img.argb[(y * f + dy) * w + x * f + dx];
						int pa = p >>> 24;
						a += pa;
						r += ((p >> 16) & 0xFF) * pa;
						g += ((p >> 8) & 0xFF) * pa;
						b += (p & 0xFF) * pa;
					}
				}
				int n = f * f;
				int oa = (int) (a / n);
				out[y * nw + x] = a == 0 ? 0 : (oa << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
			}
		}
		return new Art(out, nw, nh);
	}

	private void loadPreview(String id) {
		State s = state.get();
		Item it = s.item(id);
		if (it == null) {
			previewRequested.remove(id);
			return;
		}
		String token = platform.trsToken();
		try {
			Preview p;
			if (it.format == 2) {
				CosmeticV2Cache cache = new CosmeticV2Cache(platform.configDir().resolve("trsclient").resolve("cosmetics"));
				p = new Preview(cache.load(it.hat, trsApi(), token), null, null, 0, 0);
			} else {
				final String tokenFinal = token;
				CosmeticModel model = CosmeticModels.load(
						platform.configDir().resolve("trsclient").resolve("cosmetics").resolve("templates"), it.id,
						it.template, it.templateUrl, config(), url -> {
							try {
								return trsApi().asset(url, "application/json", CosmeticModels.MAX_BYTES, tokenFinal);
							} catch (ApiException e) {
								throw new IOException(e.code());
							}
						});
				if (model == null) throw new IOException("Vorlage");
				PngDecoder.Image img = PngDecoder.decode(trsApi().asset(it.textureUrl, "image/png", TrsApi.MAX_TEXTURE_BYTES, token));
				int fw = img.width;
				int fh = Math.min(img.height, Math.max(1, fw / 2));
				int[] px = new int[fw * fh];
				System.arraycopy(img.argb, 0, px, 0, px.length);
				p = new Preview(null, model, px, fw, fh);
			}
			previews.put(id, p);
		} catch (Exception e) {
			platform.log("Garderobe: Kosmetik-Vorschau '" + id + "' nicht ladbar (" + e.getMessage() + ")");
			failed.add(id);
		}
		version++;
		publish();
	}

	private void fail(String key) {
		message = key;
		error = true;
		version++;
		publish();
	}

	private void publish() {
		state.set(new State(items, equipped, loaded, loadingFlag, trs, busy, message, error, version,
				Collections.unmodifiableMap(new LinkedHashMap<String, Art>(cards)),
				Collections.unmodifiableMap(new LinkedHashMap<String, Preview>(previews)),
				Collections.unmodifiableSet(new HashSet<String>(failed))));
	}

	private void submit(Runnable r) {
		try {
			worker.execute(() -> {
				try {
					r.run();
				} catch (RuntimeException e) {
					platform.log("Garderobe: Kosmetik-Fehler " + e);
				}
			});
		} catch (RejectedExecutionException ignored) {
			// beendet
		}
	}

	// --- JSON ---

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	private static boolean bool(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	private static int num(JsonObject o, String k, int fallback) {
		Integer i = integer(o, k);
		return i == null ? fallback : i;
	}

	private static Integer integer(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
		double d = e.getAsDouble();
		return d == Math.rint(d) && Math.abs(d) < 1e6 ? (int) d : null;
	}
}

package dev.theredstonee.trsclient.core.social;

import com.google.gson.Gson;
import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.halloween.HalloweenApi;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.core.ui.halloween.Sprites;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Chat-Sticker (Halloween 2026, API.md §36): Katalog ({@code GET /v1/stickers}, Namen), eigene Sticker
 * ({@code GET /v1/me/cosmetics → stickers}) und die Bilder ({@code /v1/stickers/{id}.png}, 12×12 ×8) als Texturen. Bis ein
 * Bild geladen ist (oder wenn es fehlt), zeigt der Client das eingebaute Pixel-Bild ({@link Sprites#forSticker}).
 *
 * <p>Laden im eigenen Hintergrund-Thread; Texturen nur im Render-Thread anlegen ({@link #texture}).
 */
public final class Stickers {
	private static final Stickers INSTANCE = new Stickers();
	private static final Gson GSON = new Gson();
	static final long MAX_AGE_MS = 5 * 60_000L;
	static final long RETRY_MS = 60_000L;
	/** Größtes Bild der API (Kante). */
	static final int MAX_SIDE = 256;

	static final class MeCosmetics {
		List<String> stickers;
	}

	/** Ein geladenes Bild (vor dem Hochladen). */
	private static final class Image {
		final int w;
		final int h;
		final int[] argb;

		Image(int w, int h, int[] argb) {
			this.w = w;
			this.h = h;
			this.argb = argb;
		}
	}

	private volatile Http http;
	private ThreadPoolExecutor worker;
	private volatile List<String> owned;
	private volatile long ownedAt;
	private volatile long retryAt;
	private volatile boolean loading;
	private final Map<String, Map<String, String>> names = Collections.synchronizedMap(new HashMap<String, Map<String, String>>());
	private final Map<String, String> urls = Collections.synchronizedMap(new HashMap<String, String>());
	/** Geladene Bilder, die noch hochgeladen werden müssen (Render-Thread). */
	private final Map<String, Image> pending = Collections.synchronizedMap(new HashMap<String, Image>());
	/** Texturen der API-Bilder je Sticker (Render-Thread). */
	private final Map<String, TextureRef> textures = new HashMap<String, TextureRef>();
	private final Map<String, Long> imageRetry = new HashMap<String, Long>();
	private final Map<String, Boolean> imageLoading = Collections.synchronizedMap(new HashMap<String, Boolean>());

	private Stickers() {
	}

	public static Stickers get() {
		return INSTANCE;
	}

	/** Eigener HTTP-Client (Tests). */
	public void http(Http h) {
		http = h;
	}

	// --- Eigene Sticker + Katalog ---

	/** Sticker, die dieses Konto besitzt (leer, solange unbekannt). Lädt bei Bedarf nach. */
	public List<String> owned() {
		ensure();
		List<String> o = owned;
		return o == null ? Collections.<String>emptyList() : o;
	}

	/** Ist der Stand bekannt (für „Lädt …“)? */
	public boolean loaded() {
		return owned != null;
	}

	/** Name in der Sprache des Spiels (Katalog), sonst die ID. */
	public String name(String id) {
		return HalloweenApi.name(names.get(id), I18n.code(), id);
	}

	/** Nach dem Senden eines Stickers, den der Server ablehnt, oder nach Süßes oder Saures: neu holen. */
	public void invalidate() {
		ownedAt = 0;
		retryAt = 0;
	}

	private void ensure() {
		long now = System.currentTimeMillis();
		if (loading || now < retryAt || (owned != null && now - ownedAt < MAX_AGE_MS)) return;
		TrsOnline o = TrsOnline.current();
		final String token = o == null ? null : o.token();
		if (token == null) return;
		final String base = o.apiBase();
		loading = true;
		if (!submit(new Runnable() {
			@Override
			public void run() {
				try {
					List<String> mine = fetchOwned(base, token);
					try {
						List<HalloweenApi.Sticker> cat = new HalloweenApi(client(), base).stickers(token);
						if (cat != null) {
							for (HalloweenApi.Sticker s : cat) {
								if (s == null || !Chat.validSticker(s.id)) continue;
								if (s.name != null) names.put(s.id, s.name);
								if (s.url != null) urls.put(s.id, s.url);
							}
						}
					} catch (IOException | ApiException | RuntimeException ignored) {
						// Namen sind nur Zierde – IDs reichen.
					}
					owned = mine;
					ownedAt = System.currentTimeMillis();
					retryAt = 0;
				} catch (ApiException e) {
					retryAt = System.currentTimeMillis() + Math.max(RETRY_MS, e.retryAfterMs());
				} catch (IOException | RuntimeException e) {
					retryAt = System.currentTimeMillis() + RETRY_MS;
				} finally {
					loading = false;
				}
			}
		})) loading = false;
	}

	List<String> fetchOwned(String base, String token) throws IOException, ApiException {
		Http.Request r = new Http.Request("GET", base + "/v1/me/cosmetics").header("Accept", "application/json")
				.header("Authorization", "Bearer " + token);
		r.maxBytes = 256 * 1024;
		Http.Response res = client().send(r);
		if (res.status != 200) throw new ApiException(res.status, "http_" + res.status, 0);
		return parseOwned(new String(res.body, StandardCharsets.UTF_8));
	}

	/** {@code stickers} aus {@code GET /v1/me/cosmetics}: gültige IDs, ohne Dubletten, höchstens 64. */
	static List<String> parseOwned(String json) {
		List<String> out = new ArrayList<String>();
		MeCosmetics m;
		try {
			m = GSON.fromJson(json, MeCosmetics.class);
		} catch (RuntimeException e) {
			return out;
		}
		if (m == null || m.stickers == null) return out;
		for (String id : m.stickers) {
			if (Chat.validSticker(id) && !out.contains(id) && out.size() < 64) out.add(id);
		}
		return Collections.unmodifiableList(out);
	}

	// --- Bilder ---

	/**
	 * Textur des Stickers (Bild der API, sonst eingebautes Pixel-Bild) oder null (unbekannt und noch nicht geladen, keine
	 * Texturen möglich). Nur Render-Thread.
	 */
	public TextureRef texture(String id) {
		if (!Chat.validSticker(id)) return null;
		Textures.Store store = Textures.store();
		if (store == null) return null;
		Image img = pending.remove(id);
		if (img != null) {
			TextureRef up = store.upload("sticker/" + id, img.w, img.h, img.argb);
			if (up != null) textures.put(id, up);
		}
		TextureRef remote = textures.get(id);
		if (remote != null) return remote;
		requestImage(id);
		TextureRef b = builtins.get(id);
		if (b != null) return b;
		Sprites.Sprite s = Sprites.forSticker(id);
		if (s == null) return null;
		b = store.upload("sticker/" + id + "_builtin", s.w * 8, s.h * 8, Sprites.image(s, 8));
		if (b != null) builtins.put(id, b);
		return b;
	}

	/** Eingebaute Bilder (Rückfall), Render-Thread. */
	private final Map<String, TextureRef> builtins = new HashMap<String, TextureRef>();

	private void requestImage(final String id) {
		if (Boolean.TRUE.equals(imageLoading.get(id))) return;
		Long retry = imageRetry.get(id);
		long now = System.currentTimeMillis();
		if (retry != null && now < retry) return;
		TrsOnline o = TrsOnline.current();
		if (o == null) return;
		final String token = o.token();
		final String base = o.apiBase();
		final String url = urls.containsKey(id) ? urls.get(id) : "/v1/stickers/" + id + ".png";
		if (!url.startsWith("/v1/") && !url.startsWith(base + "/")) {
			imageRetry.put(id, Long.MAX_VALUE);
			return;
		}
		imageLoading.put(id, Boolean.TRUE);
		imageRetry.put(id, now + RETRY_MS);
		if (!submit(new Runnable() {
			@Override
			public void run() {
				try {
					byte[] png = new HalloweenApi(client(), base).png(token, url);
					PngDecoder.Image img = PngDecoder.decode(png, (long) MAX_SIDE * MAX_SIDE);
					if (img.width > 0 && img.height > 0 && img.width <= MAX_SIDE && img.height <= MAX_SIDE) {
						pending.put(id, new Image(img.width, img.height, img.argb));
					}
				} catch (IOException | ApiException | RuntimeException ignored) {
					// eingebautes Bild bleibt
				} finally {
					imageLoading.remove(id);
				}
			}
		})) imageLoading.remove(id);
	}

	private Http client() {
		Http h = http;
		if (h == null) {
			h = new Http.UrlConnection("TRS-Client");
			http = h;
		}
		return h;
	}

	private synchronized boolean submit(Runnable task) {
		if (worker == null) {
			ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32), r -> {
				Thread t = new Thread(r, "TRS-Sticker");
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

	/** Nur Tests/Autotest: eigene Sticker direkt setzen. */
	public void setOwnedForTest(List<String> ids) {
		owned = ids == null ? null : Collections.unmodifiableList(new ArrayList<String>(ids));
		ownedAt = ids == null ? 0 : Long.MAX_VALUE / 2;
	}

	/** Für Tests: Namen aus dem Katalog setzen. */
	void putName(String id, Map<String, String> n) {
		names.put(id, n == null ? new LinkedHashMap<String, String>() : n);
	}
}

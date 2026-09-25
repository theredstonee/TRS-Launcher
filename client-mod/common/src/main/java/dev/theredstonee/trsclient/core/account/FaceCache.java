package dev.theredstonee.trsclient.core.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.online.Http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Gesichter (8×8 + Hut-Ebene) der Konten für die Liste – aus dem Skin geschnitten, im Hintergrund geladen,
 * nur im Speicher. Ohne Skin-Adresse wird sie über den öffentlichen Profil-Dienst von Mojang nachgeschlagen.
 * Gezeichnet wird Pixel für Pixel über den Canvas (keine Texturen nötig).
 */
public final class FaceCache {
	static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
	/** Ergebnis „kein Gesicht“ (Fehler) – nicht erneut versuchen. */
	private static final int[] NONE = new int[0];

	private final Http http;
	private final Map<String, int[]> faces = new ConcurrentHashMap<String, int[]>();
	private final java.util.Set<String> loading = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	private final ThreadPoolExecutor worker;

	public FaceCache(Http http) {
		this.http = http;
		this.worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(64), r -> {
			Thread t = new Thread(r, "TRS-Faces");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		worker.allowCoreThreadTimeOut(true);
	}

	private static volatile FaceCache shared;

	public static FaceCache shared(String userAgent) {
		if (shared == null) {
			synchronized (FaceCache.class) {
				if (shared == null) shared = new FaceCache(new Http.UrlConnection(userAgent));
			}
		}
		return shared;
	}

	/**
	 * 128 ARGB-Werte (64 Gesicht, 64 Hut) oder null, solange noch geladen wird bzw. es keins gibt. Stößt das
	 * Laden beim ersten Aufruf an (Spiel-Thread, blockiert nie).
	 */
	public int[] face(final String uuid, final String skinUrl) {
		if (uuid == null) return null;
		int[] f = faces.get(uuid);
		if (f != null) return f.length == 0 ? null : f;
		faces.put(uuid, NONE);
		loading.add(uuid);
		try {
			worker.execute(() -> {
				int[] loaded = load(uuid, skinUrl);
				faces.put(uuid, loaded == null ? NONE : loaded);
				loading.remove(uuid);
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			faces.remove(uuid);
			loading.remove(uuid);
		}
		return null;
	}

	int[] load(String uuid, String skinUrl) {
		try {
			String url = MsAuth.safeSkinUrl(skinUrl);
			if (url == null) url = lookup(uuid);
			if (url == null) return null;
			Http.Request r = new Http.Request("GET", url);
			r.maxBytes = 256 * 1024;
			Http.Response res = http.send(r);
			if (res.status != 200) return null;
			return extract(PngDecoder.decode(res.body));
		} catch (Exception e) {
			if (Boolean.getBoolean("trsclient.autotest")) System.out.println("[TRS Faces] " + uuid + ": " + e);
			return null;
		}
	}

	/** Schon fertig geladen (mit oder ohne Ergebnis)? Für den Autotest. */
	public boolean settled(String uuid) {
		int[] f = faces.get(uuid);
		return f != null && (f.length > 0 || !loading.contains(uuid));
	}

	/** Skin-Adresse aus dem öffentlichen Profil (Base64-JSON in {@code properties[textures]}). */
	String lookup(String uuid) throws java.io.IOException {
		if (uuid == null || !uuid.matches("[0-9a-f]{32}")) return null;
		Http.Request r = new Http.Request("GET", PROFILE_URL + uuid);
		Http.Response res = http.send(r);
		if (res.status != 200) return null;
		try {
			JsonObject o = new JsonParser().parse(res.text()).getAsJsonObject();
			for (JsonElement p : o.getAsJsonArray("properties")) {
				JsonObject prop = p.getAsJsonObject();
				if (!"textures".equals(MsAuth.str(prop, "name"))) continue;
				String json = new String(Base64.getDecoder().decode(MsAuth.str(prop, "value")), StandardCharsets.UTF_8);
				JsonObject tex = new JsonParser().parse(json).getAsJsonObject().getAsJsonObject("textures");
				return MsAuth.safeSkinUrl(MsAuth.str(tex.getAsJsonObject("SKIN"), "url"));
			}
		} catch (RuntimeException e) {
			return null;
		}
		return null;
	}

	/** Gesicht (8,8)–(16,16) und Hut (40,8)–(48,16) aus einem 64×64- oder 64×32-Skin. */
	static int[] extract(PngDecoder.Image img) {
		if (img == null || img.width != 64 || (img.height != 64 && img.height != 32)) return null;
		int[] out = new int[128];
		boolean opaque = true;
		boolean uniform = true;
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				out[y * 8 + x] = img.argb[(8 + y) * 64 + 8 + x] | 0xFF000000;
				int hat = img.argb[(8 + y) * 64 + 40 + x];
				out[64 + y * 8 + x] = hat;
				opaque &= (hat >>> 24) == 0xFF;
				uniform &= hat == img.argb[8 * 64 + 40];
			}
		}
		// Wie Minecraft bei alten 64×32-Skins: eine komplett deckende Hut-Ebene gilt als leer (sonst schwarzes Gesicht).
		if (opaque && (img.height == 32 || uniform)) {
			for (int i = 64; i < 128; i++) out[i] = 0;
		}
		return out;
	}
}

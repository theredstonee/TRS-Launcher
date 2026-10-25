package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.cosmetic.CosmeticModels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Kopf-Kosmetik aus der Lookup-Antwort ({@code cosmetics.hat}, API.md §11).
 *
 * <p><b>Format 1</b> (Vorlage, z. B. die Quietscheente): Vorlage + Textur. Die Vorlage kommt aus dem Jar oder über
 * {@code templateUrl} ({@link dev.theredstonee.trsclient.core.cosmetic.CosmeticModels}). Die Textur hat dasselbe
 * Format wie ein Umhang (Bilder senkrecht übereinander, je 2:1) und läuft deshalb über dieselben Umhang-Texturen –
 * mit eigener Kennung {@code cos-<id>}, damit sich Cache-Dateien nie mit Umhängen mischen.
 *
 * <p><b>Format 2</b> (Studio-Modell, {@code cosmetic-format.md}): Modell ({@code model.json}), Textur und optional
 * Leucht-Streifen – geladen über {@code CosmeticAssets} mit Platten-Cache nach {@link #hash}. Bildmaße/Frames kommen aus
 * dem Modell; die Frame-Angaben des Lookups sind nur Hinweise.
 */
public final class HatInfo {
	public final String id;
	/** Vorlage (Format 1) oder null. */
	public final String template;
	/** Textur als Umhang-Streifen (Format 1) oder null. */
	public final CapeInfo texture;
	/**
	 * Format 1: Adresse der Modell-Vorlage ({@code template.json}, absolut, nur TRS API) oder null.
	 * null → {@code /v1/cosmetics/<id>/template.json}.
	 */
	public final String templateUrl;
	/** 1 oder 2. */
	public final int format;
	/** Format 2: Adressen (geprüft, absolut) und Inhalts-Kennung. */
	public final String modelUrl;
	public final String textureUrl;
	public final String glowUrl;
	public final String hash;
	/** Format 2: vom Lookup versprochene Frames (0 = unbekannt). */
	public final int frames;
	public final int glowFrames;
	/**
	 * Wahl des Spielers für die abschaltbaren Teile des Modells ({@code cosmetics.<slot>.options}, Anhang D:
	 * {@code optionId → an}); nie null, unveränderlich, sortiert. Fehlt ein Eintrag, gilt {@code default} des Modells.
	 */
	public final java.util.Map<String, Boolean> options;
	/** Begleiter am Boden: wie viele (1…{@link #MAX_COUNT}, Lookup {@code cosmetics.pet.count}); sonst 1. */
	public final int count;
	/**
	 * Begleiter am Boden: Größe im Spiel (Lookup {@code petScale}; Standard 1,
	 * {@link #MIN_PET_SCALE}…{@link #MAX_PET_SCALE}) – z. B. Kaiserpinguin 1,25.
	 */
	public final double petScale;
	/**
	 * Begleiter am Boden: zusätzliches Gefolge ({@code cosmetics.pet.extras}, Creator-Begleiter, je einmal gezeichnet,
	 * höchstens {@link #MAX_EXTRAS}); nie null, unveränderlich. Jedes Extra ist ein eigenes Format-2-Teil mit eigener
	 * {@link #petScale}, ohne eigene Extras.
	 */
	public final java.util.List<HatInfo> extras;
	/** Höchstens so oft wird das Haupt-Teil am Boden gezeichnet ({@code count}). */
	public static final int MAX_COUNT = 4;
	/** Höchstens so viele zusätzliche Begleiter je Spieler. */
	public static final int MAX_EXTRAS = 4;
	/** Höchstens so viele Begleiter am Boden je Spieler (Haupt-Teil × {@code count} + Extras). */
	public static final int MAX_PETS = 8;
	/** Grenzen der Größe eines Begleiters am Boden. */
	public static final double MIN_PET_SCALE = 0.5, MAX_PET_SCALE = 2.0;
	/** Höchstens so viele Optionen werden übernommen (Modell-Grenze). */
	static final int MAX_OPTIONS = 4;
	private final String key;
	private final String assetKey;

	HatInfo(String id, String template, CapeInfo texture, String templateUrl) {
		this.id = id;
		this.template = template;
		this.texture = texture;
		this.templateUrl = templateUrl;
		this.format = 1;
		this.modelUrl = null;
		this.textureUrl = null;
		this.glowUrl = null;
		this.hash = null;
		this.frames = 0;
		this.glowFrames = 0;
		this.options = java.util.Collections.emptyMap();
		this.count = 1;
		this.petScale = 1.0;
		this.extras = java.util.Collections.emptyList();
		this.key = "v1|" + id + "|" + (texture == null ? "" : texture.key());
		this.assetKey = key;
	}

	private HatInfo(HatInfo base, java.util.Map<String, Boolean> options, int count, double petScale,
			java.util.List<HatInfo> extras) {
		this.id = base.id;
		this.template = base.template;
		this.texture = base.texture;
		this.templateUrl = base.templateUrl;
		this.format = base.format;
		this.modelUrl = base.modelUrl;
		this.textureUrl = base.textureUrl;
		this.glowUrl = base.glowUrl;
		this.hash = base.hash;
		this.frames = base.frames;
		this.glowFrames = base.glowFrames;
		this.options = options;
		this.count = count;
		this.petScale = petScale;
		this.extras = extras;
		this.assetKey = base.assetKey;
		StringBuilder k = new StringBuilder(base.assetKey);
		if (count != 1) k.append("|x").append(count);
		if (petScale != 1.0) k.append("|s").append(petScale);
		for (java.util.Map.Entry<String, Boolean> e : options.entrySet()) {
			k.append('|').append(e.getKey()).append('=').append(e.getValue().booleanValue() ? '1' : '0');
		}
		for (HatInfo e : extras) k.append("|+[").append(e.key).append(']');
		this.key = k.toString();
	}

	private HatInfo(String id, String modelUrl, String textureUrl, String glowUrl, String hash, int frames, int glowFrames) {
		this.id = id;
		this.template = null;
		this.texture = null;
		this.templateUrl = null;
		this.format = 2;
		this.modelUrl = modelUrl;
		this.textureUrl = textureUrl;
		this.glowUrl = glowUrl;
		this.hash = hash;
		this.frames = frames;
		this.glowFrames = glowFrames;
		this.options = java.util.Collections.emptyMap();
		this.count = 1;
		this.petScale = 1.0;
		this.extras = java.util.Collections.emptyList();
		this.key = "v2|" + id + "|" + hash;
		this.assetKey = key;
	}

	public boolean v2() {
		return format == 2;
	}

	/**
	 * Gleiches Teil in gleichem Stand mit gleicher Wahl (Optionen, Anzahl) → gleicher Schlüssel. Für Texturen/Cache gilt
	 * {@link #assetKey()} (die Wahl ändert keine Datei).
	 */
	public String key() {
		return key;
	}

	/** Gleiches Teil in gleichem Stand → gleicher Schlüssel für Modell, Texturen und Platten-Cache (ohne Optionen/Anzahl). */
	public String assetKey() {
		return assetKey;
	}

	/** Ist die Option {@code optionId} an? {@code fallback} (der {@code default} des Modells), wenn der Spieler nichts wählte. */
	public boolean option(String optionId, boolean fallback) {
		Boolean b = options.get(optionId);
		return b == null ? fallback : b.booleanValue();
	}

	/**
	 * Gleiches Teil mit der Wahl des Spielers ({@code options}, nur gültige IDs, höchstens 4) und der Anzahl (Begleiter am
	 * Boden, auf 1…{@link #MAX_COUNT} begrenzt; null = 1).
	 */
	public HatInfo with(java.util.Map<String, Boolean> chosen, Integer wantedCount) {
		java.util.TreeMap<String, Boolean> o = new java.util.TreeMap<String, Boolean>();
		if (chosen != null) {
			for (java.util.Map.Entry<String, Boolean> e : chosen.entrySet()) {
				String k = e.getKey();
				if (k == null || e.getValue() == null || !k.matches("[a-z][a-z0-9_]{0,19}")) continue;
				if (o.size() >= MAX_OPTIONS) break;
				o.put(k, e.getValue());
			}
		}
		int c = wantedCount == null ? 1 : Math.max(1, Math.min(MAX_COUNT, wantedCount));
		if (o.isEmpty() && c == 1 && options.isEmpty() && count == 1) return this;
		return new HatInfo(this, o.isEmpty() ? java.util.Collections.<String, Boolean>emptyMap()
				: java.util.Collections.unmodifiableMap(o), c, petScale, extras);
	}

	/**
	 * Gleiches Teil als Begleiter am Boden mit Größe {@code scale} (auf {@link #MIN_PET_SCALE}…{@link #MAX_PET_SCALE}
	 * begrenzt, kein endlicher Wert = 1) und Gefolge {@code extraPets} (nur Format 2, ohne eigene Extras, Dubletten
	 * erlaubt, höchstens {@link #MAX_EXTRAS}; null = keins).
	 */
	public HatInfo withPet(double scale, java.util.List<HatInfo> extraPets) {
		double sc = clampScale(scale);
		java.util.List<HatInfo> ex = new java.util.ArrayList<HatInfo>();
		if (extraPets != null) {
			for (HatInfo e : extraPets) {
				if (e == null || !e.v2() || ex.size() >= MAX_EXTRAS) continue;
				ex.add(e.extras.isEmpty() ? e : new HatInfo(e, e.options, e.count, e.petScale,
						java.util.Collections.<HatInfo>emptyList()));
			}
		}
		if (sc == petScale && ex.equals(extras)) return this;
		return new HatInfo(this, options, count, sc,
				ex.isEmpty() ? java.util.Collections.<HatInfo>emptyList() : java.util.Collections.unmodifiableList(ex));
	}

	/** Größe eines Begleiters am Boden begrenzen (kein endlicher Wert = 1). */
	public static double clampScale(double scale) {
		if (Double.isNaN(scale) || Double.isInfinite(scale)) return 1.0;
		double s = Math.max(MIN_PET_SCALE, Math.min(MAX_PET_SCALE, scale));
		// auf Hundertstel runden: gleicher Wert → gleicher Schlüssel
		return Math.round(s * 100.0) / 100.0;
	}

	/** Wie viele Begleiter am Boden insgesamt gezeichnet werden ({@code count} + Extras, höchstens {@link #MAX_PETS}). */
	public int petTotal() {
		return Math.min(MAX_PETS, count + extras.size());
	}

	/** Teil des {@code i}-ten Begleiters am Boden (0 … {@link #petTotal()}−1): erst {@code count} × dieses, dann die Extras. */
	public HatInfo petPiece(int i) {
		if (i < count) return this;
		int e = i - count;
		return e >= 0 && e < extras.size() ? extras.get(e) : null;
	}

	/** Prüft streng; unbekannte Vorlage (neuere API), fremde URL oder kaputte Werte → null (nichts zeichnen). */
	public static HatInfo of(String id, String template, String url, Integer scale, Integer frames, Integer frameTimeMs,
			OnlineConfig config) {
		return of(id, template, url, scale, frames, frameTimeMs, null, config);
	}

	/**
	 * Wie {@link #of(String, String, String, Integer, Integer, Integer, OnlineConfig)} plus {@code templateUrl}
	 * (absolut oder {@code /v1/…}). Eine fremde Vorlagen-Adresse wird verworfen (dann gilt der feste Pfad
	 * {@code /v1/cosmetics/<id>/template.json}), das Teil selbst bleibt gültig.
	 */
	public static HatInfo of(String id, String template, String url, Integer scale, Integer frames, Integer frameTimeMs,
			String templateUrl, OnlineConfig config) {
		if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,35}")) return null;
		if (template == null || !CosmeticModels.supported(template)) return null;
		// Adresse wie bei Format 2 auch API-relativ erlaubt (nur TRS-Hosts)
		String abs = resolve(url, config);
		if (abs == null) return null;
		CapeInfo tex = CapeInfo.of("cos-" + id, abs, scale, frames, frameTimeMs, config);
		if (tex == null) return null;
		String tpl = templateUrl == null || templateUrl.isEmpty() ? null : resolve(templateUrl, config);
		return new HatInfo(id, template, tex, tpl);
	}

	/**
	 * Format 2 aus dem Lookup (oder dem Katalog). Adressen dürfen absolut oder API-relativ ({@code /v1/…}) sein, müssen
	 * aber zur TRS API gehören. Ohne {@code hash} wird einer aus den Adressen gebildet (die {@code ?v=} ändern sich mit
	 * dem Inhalt). Ungültig → null.
	 */
	public static HatInfo v2(String id, String model, String texture, String glow, String hash, Integer frames,
			Integer glowFrames, OnlineConfig config) {
		if (id == null || !id.matches("[a-z][a-z0-9_]{0,39}")) return null;
		String m = resolve(model, config);
		String t = resolve(texture, config);
		if (m == null || t == null) return null;
		String g = null;
		if (glow != null && !glow.isEmpty()) {
			g = resolve(glow, config);
			if (g == null) return null;
		}
		String h = hash;
		if (h == null || !h.matches("[0-9a-f]{6,64}")) h = digest(m + "\n" + t + "\n" + (g == null ? "" : g));
		int f = frames == null ? 0 : frames;
		int gf = glowFrames == null ? 0 : glowFrames;
		if (f < 0 || f > 64 || gf < 0 || gf > 64) return null;
		return new HatInfo(id, m, t, g, h, f, gf);
	}

	/** API-relative Adresse ergänzen und prüfen (nur TRS API, nie beliebige Hosts). */
	static String resolve(String url, OnlineConfig config) {
		if (url == null || url.isEmpty() || url.length() >= 512) return null;
		String abs = url.startsWith("/v1/") ? config.apiBase() + url : url;
		return config.isApiUrl(abs) ? abs : null;
	}

	private static String digest(String s) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i] & 0xFF));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(s.hashCode());
		}
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof HatInfo && ((HatInfo) o).key.equals(key);
	}

	@Override
	public int hashCode() {
		return key.hashCode();
	}
}

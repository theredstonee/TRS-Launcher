package dev.theredstonee.trsclient.core.online;

/**
 * Ein TRS-Umhang aus der Lookup-Antwort: Textur-URL plus Aufbau der PNG
 * (Bilder senkrecht übereinander, jedes {@code 64·scale × 32·scale}).
 */
public final class CapeInfo {
	public final String id;
	public final String url;
	/** Größter Faktor gegenüber 64×32 (mitgelieferte HD-Umhänge bis 512×256; Uploads der API bleiben bei 1–4). */
	public static final int MAX_SCALE = 8;

	public final int scale;
	public final int frames;
	/** Dauer je Bild in ms; 0 = statisch. */
	public final int frameTimeMs;
	/** Einmal gebaut: wird je Bild und Spieler nachgeschlagen. */
	private final String key;

	public CapeInfo(String id, String url, int scale, int frames, int frameTimeMs) {
		this.id = id;
		this.url = url;
		this.scale = scale;
		this.frames = frames;
		this.frameTimeMs = frameTimeMs;
		this.key = id + "|" + url;
	}

	/** Prüft die Werte der API streng; ungültig → null (dann Vanilla). */
	public static CapeInfo of(String id, String url, Integer scale, Integer frames, Integer frameTimeMs,
			OnlineConfig config) {
		if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,39}")) return null;
		if (!config.isApiUrl(url)) return null;
		int s = scale == null ? 1 : scale;
		int f = frames == null ? 1 : frames;
		if (s < 1 || s > MAX_SCALE || f < 1 || f > 64) return null;
		int t = 0;
		if (f > 1) {
			if (frameTimeMs == null || frameTimeMs < 20 || frameTimeMs > 10000) return null;
			t = frameTimeMs;
		}
		return new CapeInfo(id, url, s, f, t);
	}

	public boolean animated() {
		return frames > 1 && frameTimeMs > 0;
	}

	/** Welches Bild gerade dran ist: Wanduhr, damit alle Spieler dasselbe Bild sehen. */
	public int frameAt(long nowMillis) {
		if (!animated()) return 0;
		return (int) Math.floorMod(nowMillis / frameTimeMs, (long) frames);
	}

	/** Schlüssel für Cache/Texturen: gleicher Umhang + gleicher Inhalt (?v=…) → gleicher Schlüssel. */
	public String key() {
		return key;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof CapeInfo)) return false;
		CapeInfo c = (CapeInfo) o;
		return id.equals(c.id) && url.equals(c.url) && scale == c.scale && frames == c.frames
				&& frameTimeMs == c.frameTimeMs;
	}

	@Override
	public int hashCode() {
		return key().hashCode();
	}
}

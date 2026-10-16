package dev.theredstonee.trsclient.core.online;

/**
 * Fan-Symbol eines Creators aus der Lookup-Antwort ({@code fan.symbol}, API.md §35.4/§35.5): quadratische Bilder
 * {@code size × size}, {@code frames} Stück senkrecht übereinander, je {@code frameMs} lang. Die Datei ist unveränderlich
 * (Adresse und {@link #sha256} ändern sich mit dem Inhalt).
 */
public final class SymbolInfo {
	public static final int MIN_SIZE = 16;
	public static final int MAX_SIZE = 64;
	public static final int MAX_FRAMES = 8;
	public static final int MIN_FRAME_MS = 50;
	public static final int MAX_FRAME_MS = 1000;
	public static final int DEFAULT_FRAME_MS = 150;

	/** Absolute Adresse (nur TRS API). */
	public final String url;
	public final int size;
	public final int frames;
	public final int frameMs;
	/** 64 Hex-Ziffern (klein) – Schlüssel für Texturen und Platten-Cache. */
	public final String sha256;

	SymbolInfo(String url, int size, int frames, int frameMs, String sha256) {
		this.url = url;
		this.size = size;
		this.frames = frames;
		this.frameMs = frameMs;
		this.sha256 = sha256;
	}

	/**
	 * Prüft die Werte der API streng; {@code url} absolut oder API-relativ ({@code /v1/…}). Ungültig → null (dann kein
	 * Symbol). Fehlende {@code frames}/{@code frameMs} → 1 Bild bzw. Standard-Dauer.
	 */
	public static SymbolInfo of(String url, Integer size, Integer frames, Integer frameMs, String sha256, OnlineConfig config) {
		if (sha256 == null) return null;
		String sha = sha256.toLowerCase(java.util.Locale.ROOT);
		if (!sha.matches("[0-9a-f]{64}")) return null;
		String abs = HatInfo.resolve(url, config);
		if (abs == null) return null;
		int s = size == null ? 0 : size;
		if (s < MIN_SIZE || s > MAX_SIZE) return null;
		int f = frames == null ? 1 : frames;
		if (f < 1 || f > MAX_FRAMES) return null;
		int t = frameMs == null ? DEFAULT_FRAME_MS : frameMs;
		if (t < MIN_FRAME_MS || t > MAX_FRAME_MS) {
			if (f > 1) return null;
			t = DEFAULT_FRAME_MS;
		}
		return new SymbolInfo(abs, s, f, t, sha);
	}

	public boolean animated() {
		return frames > 1;
	}

	/** Welches Bild gerade dran ist (Wanduhr – alle sehen dasselbe Bild). */
	public int frameAt(long nowMillis, int available) {
		int n = Math.max(1, Math.min(frames, available));
		if (n == 1) return 0;
		return (int) Math.floorMod(nowMillis / frameMs, (long) n);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof SymbolInfo)) return false;
		SymbolInfo s = (SymbolInfo) o;
		return sha256.equals(s.sha256) && url.equals(s.url) && size == s.size && frames == s.frames && frameMs == s.frameMs;
	}

	@Override
	public int hashCode() {
		return sha256.hashCode();
	}
}

package dev.theredstonee.trsclient.core.ui;

/** Farbmathematik für Menü, Farbwähler und Chroma (alles ARGB als int, ohne Minecraft). */
public final class ColorMath {
	/** Dauer eines Chroma-Durchlaufs in Millisekunden. */
	public static final int CHROMA_PERIOD_MS = 4000;

	private ColorMath() {
	}

	/** HSV → 0xRRGGBB. h in Umdrehungen (0..1, wird umgebrochen), s/v in 0..1. */
	public static int hsvToRgb(float h, float s, float v) {
		float hh = h - (float) Math.floor(h);
		float ss = clamp01(s);
		float vv = clamp01(v);
		int i = (int) (hh * 6) % 6;
		float f = hh * 6 - (float) Math.floor(hh * 6);
		int p = to255(vv * (1 - ss));
		int q = to255(vv * (1 - f * ss));
		int t = to255(vv * (1 - (1 - f) * ss));
		int b = to255(vv);
		switch (i) {
			case 0: return rgb(b, t, p);
			case 1: return rgb(q, b, p);
			case 2: return rgb(p, b, t);
			case 3: return rgb(p, q, b);
			case 4: return rgb(t, p, b);
			default: return rgb(b, p, q);
		}
	}

	/** 0xRRGGBB → {h, s, v} (jeweils 0..1). */
	public static float[] rgbToHsv(int rgb) {
		float r = ((rgb >> 16) & 0xFF) / 255f;
		float g = ((rgb >> 8) & 0xFF) / 255f;
		float b = (rgb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h = 0;
		if (d > 0.00001f) {
			if (max == r) h = ((g - b) / d) / 6f;
			else if (max == g) h = (2 + (b - r) / d) / 6f;
			else h = (4 + (r - g) / d) / 6f;
			if (h < 0) h += 1f;
		}
		float s = max <= 0 ? 0 : d / max;
		return new float[]{h, s, max};
	}

	/** Regenbogenfarbe zum Zeitpunkt {@code timeMs} (0xRRGGBB), {@code offsetMs} verschiebt den Farbton. */
	public static int chroma(long timeMs, long offsetMs) {
		float h = ((timeMs + offsetMs) % CHROMA_PERIOD_MS) / (float) CHROMA_PERIOD_MS;
		return hsvToRgb(h, 0.85f, 1f);
	}

	public static int rgb(int r, int g, int b) {
		return (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
	}

	public static int argb(int a, int rgb) {
		return (clampByte(a) << 24) | (rgb & 0xFFFFFF);
	}

	/** Setzt die Deckkraft (0..255) einer Farbe neu. */
	public static int withAlpha(int argb, int alpha) {
		return (clampByte(alpha) << 24) | (argb & 0xFFFFFF);
	}

	/** Multipliziert die Deckkraft mit {@code factor} (0..1) – für Ein-/Ausblenden. */
	public static int fade(int argb, float factor) {
		int a = (int) Math.round(((argb >>> 24) & 0xFF) * clamp01(factor));
		return withAlpha(argb, a);
	}

	/** Mischt zwei Farben (inkl. Alpha); {@code t} = 0 → a, 1 → b. */
	public static int lerp(int a, int b, float t) {
		float f = clamp01(t);
		int ar = (a >>> 24) & 0xFF, ag = (a >> 16) & 0xFF, ab = (a >> 8) & 0xFF, aa = a & 0xFF;
		int br = (b >>> 24) & 0xFF, bg = (b >> 16) & 0xFF, bb = (b >> 8) & 0xFF, ba = b & 0xFF;
		return (lerpByte(ar, br, f) << 24) | (lerpByte(ag, bg, f) << 16) | (lerpByte(ab, bb, f) << 8) | lerpByte(aa, ba, f);
	}

	/** Hellere/dunklere Variante (factor &gt; 1 heller, &lt; 1 dunkler); Alpha bleibt. */
	public static int scaleRgb(int argb, float factor) {
		int r = (int) (((argb >> 16) & 0xFF) * factor);
		int g = (int) (((argb >> 8) & 0xFF) * factor);
		int b = (int) ((argb & 0xFF) * factor);
		return (argb & 0xFF000000) | rgb(r, g, b);
	}

	/** Gut lesbare Textfarbe (Schwarz oder Weiß) auf der angegebenen Fläche. */
	public static int contrastText(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		return (r * 299 + g * 587 + b * 114) / 1000 > 140 ? 0xFF10101A : 0xFFFFFFFF;
	}

	public static float clamp01(float v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	private static int lerpByte(int a, int b, float t) {
		return clampByte(Math.round(a + (b - a) * t));
	}

	private static int to255(float v) {
		return Math.round(clamp01(v) * 255);
	}

	private static int clampByte(int v) {
		return v < 0 ? 0 : Math.min(v, 255);
	}
}

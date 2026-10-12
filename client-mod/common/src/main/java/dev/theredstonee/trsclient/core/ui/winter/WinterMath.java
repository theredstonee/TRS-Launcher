package dev.theredstonee.trsclient.core.ui.winter;

/**
 * Kleine Rechenhelfer der Winterszene – Port der Launcher-Funktionen ({@code app/utils/redstone/paint.ts} noise,
 * {@code scene.ts} rng, {@code winter/landscape.ts} smooth, {@code winter/paint.ts} bayer). Gleiche Eingaben ergeben
 * dieselben Zahlen wie im Launcher, die Landschaft ist also aus dem Seed stabil.
 */
public final class WinterMath {
	private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

	private WinterMath() {
	}

	/** Hash-Rauschen 0…1 je Gitterpunkt (wie {@code noise} im Launcher). */
	public static double noise(int x, int y, int seed) {
		int h = (int) ((long) x * 374761393L + (long) y * 668265263L + (long) seed * 2246822519L);
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
	}

	/** Glattes 1D-Rauschen 0…1. */
	public static double smooth(double x, int seed) {
		int i = (int) Math.floor(x);
		double f = x - i;
		double a = noise(i, 0, seed);
		double b = noise(i + 1, 0, seed);
		double t = f * f * (3 - 2 * f);
		return a + (b - a) * t;
	}

	/** Schwelle 0…1 des 4 × 4-Rasters an einem Texel. */
	public static double bayer(int x, int y) {
		return (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
	}

	public static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	public static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Deckendes RGB aus "#rrggbb" (ohne Prüfung – nur für feste Werte). */
	public static int hex(String h) {
		return 0xFF000000 | Integer.parseInt(h.substring(1), 16);
	}

	/** Mischung zweier deckender Farben (Alpha bleibt deckend). */
	public static int mix(int a, int b, double t) {
		int ar = (a >> 16) & 255;
		int ag = (a >> 8) & 255;
		int ab = a & 255;
		int r = (int) Math.round(ar + (((b >> 16) & 255) - ar) * t);
		int g = (int) Math.round(ag + (((b >> 8) & 255) - ag) * t);
		int bl = (int) Math.round(ab + ((b & 255) - ab) * t);
		return 0xFF000000 | (clamp(r, 0, 255) << 16) | (clamp(g, 0, 255) << 8) | clamp(bl, 0, 255);
	}

	/** Zufallszahlen wie {@code rng} (mulberry32) im Launcher. */
	public static final class Rng {
		private int a;

		public Rng(int seed) {
			a = seed;
		}

		public double next() {
			a += 0x6d2b79f5;
			int t = a;
			t = (t ^ (t >>> 15)) * (t | 1);
			t ^= t + (t ^ (t >>> 7)) * (t | 61);
			return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
		}
	}
}

package dev.theredstonee.trsclient.core.ui.winter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.clamp;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.noise;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.smooth;

/**
 * Aufbau der Winterlandschaft (Port von {@code app/utils/winter/landscape.ts}, nur Daten, testbar): eine weite
 * verschneite Ebene, hinten am Horizont eine Bergkette mit Gipfeln, Graten und Schnee über einer Schneegrenze, davor
 * ein schmaler Waldstreifen, vorn rechts die Blockhütte (mit dem Pinguin), Sterne und Mond. Alles in Texeln, aus dem
 * Seed stabil (gleiche Größe + Seed = gleiche Landschaft).
 */
public final class Landscape {
	/** Tanne: {@code layer} 0 = fern (Silhouette), 1 = Ebene, 2 = vorn. */
	public static final class Pine {
		public final int x;
		public final int base;
		public final int h;
		public final int layer;
		public final int seed;

		Pine(int x, int base, int h, int layer, int seed) {
			this.x = x;
			this.base = base;
			this.h = h;
			this.layer = layer;
			this.seed = seed;
		}
	}

	public static final class Star {
		public final int x;
		public final int y;
		public final boolean bright;
		public final boolean big;
		public final boolean twinkle;

		Star(int x, int y, boolean bright, boolean big, boolean twinkle) {
			this.x = x;
			this.y = y;
			this.bright = bright;
			this.big = big;
			this.twinkle = twinkle;
		}
	}

	public static final class Cloud {
		public final int x;
		public final int y;
		public final int w;
		public final int h;
		public final int seed;
		/** Texel je Takt. */
		public final double speed;

		Cloud(int x, int y, int w, int h, int seed, double speed) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.seed = seed;
			this.speed = speed;
		}
	}

	/** Rechteck in Hütten-Koordinaten (inklusive Ränder). */
	public static final class Rect {
		public final int x0;
		public final int y0;
		public final int x1;
		public final int y1;

		Rect(int x0, int y0, int x1, int y1) {
			this.x0 = x0;
			this.y0 = y0;
			this.x1 = x1;
			this.y1 = y1;
		}
	}

	/** Große Hütte vorn (46 × 36): Schornstein, Tür, zwei Fenster, Laterne. */
	public static final int CABIN_W = 46;
	public static final int CABIN_H = 36;
	public static final int CHIMNEY_X = 33;
	public static final int CHIMNEY_Y = 0;
	public static final Rect DOOR = new Rect(27, 22, 34, 35);
	public static final Rect[] WINDOWS = {new Rect(9, 21, 17, 27), new Rect(38, 21, 40, 24)};
	public static final int LANTERN_X = 22;
	public static final int LANTERN_Y = 22;

	public final int w;
	public final int h;
	public final int seed;
	/** Fuß der Berge. */
	public final int horizon;
	/** Höhe des höchsten Gipfels über dem Horizont. */
	public final int mountainAmp;
	public final short[] mountainsBack;
	public final short[] mountains;
	public final short[] forest;
	/** Oberkante der weiten Ebene. */
	public final short[] hills;
	/** Vordere Schneewehe (Hütte steht darauf). */
	public final short[] ground;
	public final List<Pine> pines = new ArrayList<Pine>();
	public final List<Star> stars = new ArrayList<Star>();
	public final int moonX;
	public final int moonY;
	public final int moonR;
	/** Hütte (linke Spalte, Bodenzeile) oder {@code cabinX < 0} = keine. */
	public final int cabinX;
	public final int cabinY;
	/** Glitzernde Schneepunkte {x, y}. */
	public final int[][] sparkles;
	/** Halme {x, y, h}. */
	public final List<int[]> tufts = new ArrayList<int[]>();
	public final List<Cloud> clouds = new ArrayList<Cloud>();

	private Landscape(int w, int h, int seed, double cabinPos, boolean allowCabin) {
		this.w = w;
		this.h = h;
		this.seed = seed;
		WinterMath.Rng rand = new WinterMath.Rng(seed ^ 0x5eed);
		int groundBase = (int) Math.round(h * 0.86);
		horizon = Math.max(4, (int) Math.round(h * 0.56));
		mountainAmp = Math.max(4, (int) Math.round(h * 0.15));
		int forestBase = horizon + Math.max(2, (int) Math.round(h * 0.012));
		int hillBase = forestBase + Math.max(3, (int) Math.round(h * 0.03));
		int s = seed & 0xffff;
		double[] back = peaks(w, mountainAmp, rand, 1.25, s);
		double[] front = peaks(w, mountainAmp * 0.62, rand, 1, s + 13);
		mountainsBack = new short[w];
		mountains = new short[w];
		forest = new short[w];
		hills = new short[w];
		ground = new short[w];
		for (int x = 0; x < w; x++) {
			mountainsBack[x] = (short) (horizon - Math.round(back[x]));
			mountains[x] = (short) (horizon - Math.round(front[x]));
			forest[x] = (short) Math.round(forestBase - 1.4 * smooth(x / 7.0, s + 5));
			hills[x] = (short) Math.round(hillBase - h * 0.012 * smooth(x / 80.0 + 2, s + 6));
			double drift = h * 0.02 * smooth(x / 60.0, s + 9) + 1.6 * smooth(x / 16.0, s + 10);
			ground[x] = (short) Math.round(groundBase - drift);
		}

		// Blockhütte vorn rechts (nur die große – mit Pinguin).
		int cx = -1;
		int cy = 0;
		if (allowCabin && w >= 160 && h >= 90) {
			int margin = Math.max(4, (int) Math.round(w * 0.05));
			cx = cabinPos < 0 ? w - CABIN_W - margin : (int) Math.round(clamp(w * cabinPos, 4, w - CABIN_W - 4));
			int top = h;
			for (int k = cx; k < cx + CABIN_W; k++) top = Math.min(top, ground[clamp(k, 0, w - 1)]);
			cy = top + 1;
			// Boden unter der Hütte einebnen.
			for (int k = cx - 2; k < cx + CABIN_W + 2; k++) if (k >= 0 && k < w) ground[k] = (short) top;
		}
		cabinX = cx;
		cabinY = cy;

		// Schmaler Waldstreifen vor den Bergen: kleine Tannen in Gruppen.
		int tiny = Math.max(3, (int) Math.round(h * 0.022));
		for (int x = 0; x < w; ) {
			double patch = smooth(x / 26.0, s + 11);
			if (patch > 0.3) pines.add(new Pine(x, forest[x] + 1, tiny + (int) Math.round(rand.next() * tiny * 0.9 + patch * 2), 0, pineSeed(rand)));
			x += 2 + (int) Math.floor(rand.next() * 3);
		}
		// Ein paar Tannengruppen in der Ebene, eher rechts (links stehen Schrift und Knöpfe).
		int groups = Math.max(1, (int) Math.round(w / 140.0));
		for (int g = 0; g < groups; g++) {
			int gx = (int) Math.round(w * (0.38 + rand.next() * 0.55));
			int depth = (int) Math.round(rand.next() * Math.max(1, h * 0.04));
			int n = 2 + (int) Math.floor(rand.next() * 3);
			for (int k = 0; k < n; k++) {
				int x = gx + (int) Math.round((k - n / 2.0) * (4 + rand.next() * 4));
				if (x < 0 || x >= w || nearCabin(x, 6)) continue;
				pines.add(new Pine(x, hills[x] + 1 + depth, (int) Math.round(clamp(h * (0.05 + rand.next() * 0.04), 6, 22)), 1, pineSeed(rand)));
			}
		}
		// Große Tannen an der Hütte und eine am linken Rand.
		if (cabinX >= 0) {
			int big = (int) Math.round(clamp(CABIN_H * 1.35, 14, h * 0.55));
			int right = cabinX + CABIN_W + 3;
			if (right < w + 4) pines.add(new Pine(right, cabinY + 1, big, 2, pineSeed(rand)));
			pines.add(new Pine(cabinX - 4, cabinY - (int) Math.round(CABIN_H * 0.1), (int) Math.round(big * 0.8), 2, pineSeed(rand)));
		}
		if (w > 200) pines.add(new Pine(-2, ground[0] + 1, (int) Math.round(h * 0.42), 2, pineSeed(rand)));
		Collections.sort(pines, new Comparator<Pine>() {
			@Override
			public int compare(Pine a, Pine b) {
				return a.layer != b.layer ? a.layer - b.layer : a.base - b.base;
			}
		});

		// Mond oben rechts, Sterne mehr nach oben hin.
		int r = (int) Math.round(clamp(Math.min(w, h) * 0.035, 5, 10));
		int skyTop = horizon - mountainAmp;
		moonR = r;
		moonX = (int) Math.round(w * 0.8);
		moonY = (int) Math.round(clamp(h * 0.15, r + 3, Math.max(r + 3, skyTop - r - 2)));
		int skyBottom = horizon - (int) Math.round(mountainAmp * 0.6);
		int count = Math.max(0, (int) Math.round((w * (double) skyBottom) / 260.0));
		for (int i = 0; i < count; i++) {
			int x = (int) Math.floor(rand.next() * w);
			int y = (int) Math.floor(Math.pow(rand.next(), 1.5) * skyBottom);
			if ((x - moonX) * (x - moonX) + (y - moonY) * (y - moonY) < (r * 3) * (r * 3)) continue;
			boolean big = rand.next() < 0.06;
			boolean bright = big || rand.next() < 0.35;
			stars.add(new Star(x, y, bright, big, rand.next() < 0.22));
		}

		int n = (int) Math.round(w * 0.4);
		List<int[]> sp = new ArrayList<int[]>();
		for (int i = 0; i < n; i++) {
			int x = (int) Math.floor(rand.next() * w);
			int top = hills[x];
			int y = top + 1 + (int) Math.floor(rand.next() * Math.max(1, h - top - 2));
			if (y < h) sp.add(new int[]{x, y});
		}
		sparkles = sp.toArray(new int[0][]);

		for (int x = 3; x < w; x += 4 + (int) Math.floor(rand.next() * 9)) {
			if (smooth(x / 18.0, s + 13) > 0.62 && !nearCabin(x, 2)) {
				tufts.add(new int[]{x, ground[x], 2 + (int) Math.floor(rand.next() * 3)});
			}
		}

		int nc = Math.max(2, (int) Math.round(w / 110.0));
		for (int i = 0; i < nc; i++) {
			int cw = (int) Math.round(22 + rand.next() * 30);
			int x = (int) Math.round(rand.next() * (w + cw)) - cw;
			int y = (int) Math.round(h * 0.05 + rand.next() * Math.max(4, skyTop - h * 0.14));
			int ch = (int) Math.round(6 + rand.next() * 5);
			clouds.add(new Cloud(x, y, cw, ch, pineSeed(rand), 0.02 + rand.next() * 0.03));
		}
	}

	private static int pineSeed(WinterMath.Rng rand) {
		return (int) (long) (rand.next() * 1e9);
	}

	private boolean nearCabin(int x, int pad) {
		return cabinX >= 0 && x > cabinX - pad && x < cabinX + CABIN_W + pad;
	}

	/** Hat die Landschaft die große Hütte (und damit den Pinguin)? */
	public boolean hasCabin() {
		return cabinX >= 0;
	}

	/** Oberste Zeile der Hütte. */
	public int cabinTop() {
		return cabinY - CABIN_H + 1;
	}

	/** Schornstein-Mündung in Texeln oder null. */
	public int[] chimney() {
		return cabinX < 0 ? null : new int[]{cabinX + CHIMNEY_X, cabinY - CABIN_H + CHIMNEY_Y};
	}

	/**
	 * Landschaft für {@code cols} × {@code rows} Texel.
	 *
	 * @param cabinPos waagrechte Lage der Hütte 0…1, &lt; 0 = ganz rechts
	 */
	public static Landscape build(int cols, int rows, int seed, double cabinPos, boolean cabin) {
		return new Landscape(Math.max(8, cols), Math.max(8, rows), seed, cabinPos, cabin);
	}

	/**
	 * Bergkette wie echte Berge: Gipfel mit ungleich steilen Flanken, Nebengipfeln und gezackten Graten – ohne Nadeln
	 * (höchstens 1,6 Texel Sprung je Spalte).
	 */
	static double[] peaks(int w, double amp, WinterMath.Rng rand, double spread, int seed) {
		double[] out = new double[w];
		double c = -rand.next() * amp * 2;
		while (c < w + amp * 3) {
			double top = amp * (0.55 + rand.next() * 0.45);
			double left = Math.max(5, top * (1.2 + rand.next() * 0.9) * spread);
			double right = Math.max(5, top * (1.2 + rand.next() * 0.9) * spread);
			boolean hasSub = rand.next() < 0.7;
			double subAt = 0;
			double subH = 0;
			if (hasSub) {
				double sign = rand.next() < 0.5 ? -1 : 1;
				subAt = sign * (0.35 + rand.next() * 0.25);
				subH = 0.55 + rand.next() * 0.25;
			}
			int from = Math.max(0, (int) Math.floor(c - left));
			int to = Math.min(w, (int) Math.ceil(c + right));
			for (int x = from; x < to; x++) {
				double dx = x - c;
				double r = dx < 0 ? left : right;
				double u = Math.abs(dx) / r;
				double v = top * Math.pow(1 - u, 1.12);
				if (hasSub) {
					double su = Math.abs(dx / r - subAt) / 0.22;
					if (su < 1 && Math.signum(dx) == Math.signum(subAt)) v = Math.max(v, top * subH * (1 - su));
				}
				double ridge = (smooth(x / 3.5, seed) - 0.5) * 0.09 + (smooth(x / 1.4, seed + 7) - 0.5) * 0.035;
				v += top * ridge * (1 - u * 0.6);
				if (v > out[x]) out[x] = v;
			}
			c += (left + right) * (0.45 + rand.next() * 0.35);
		}
		for (int pass = 0; pass < 2; pass++) {
			for (int x = 1; x < w; x++) {
				if (out[x] > out[x - 1] + 1.6) out[x] = out[x - 1] + 1.6;
				if (out[x - 1] > out[x] + 1.6) out[x - 1] = out[x] + 1.6;
			}
		}
		return out;
	}

	/** Länge eines Polarlicht-Durchgangs (Sekunden). */
	public static final double AURORA_PERIOD = 120;

	/** Polarlicht-Stärke 0…1 über die Zeit: etwa alle zwei Minuten für 34–56 s, sanft ein- und ausgeblendet. */
	public static double auroraEnvelope(double seconds, int seed) {
		double t0 = seconds - 2;
		if (t0 < 0) return 0;
		int k = (int) Math.floor(t0 / AURORA_PERIOD);
		double delay = k == 0 ? 0 : noise(k, 2, seed) * 30;
		double on = 34 + noise(k, 1, seed) * 22;
		double t = t0 - k * AURORA_PERIOD - delay;
		if (t < 0 || t > on) return 0;
		double fade = 7;
		if (t < fade) return ease(t / fade);
		if (t > on - fade) return ease((on - t) / fade);
		return 1;
	}

	private static double ease(double t) {
		double c = clamp(t, 0, 1);
		return c * c * (3 - 2 * c);
	}
}

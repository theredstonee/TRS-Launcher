package dev.theredstonee.trsclient.core.ui.fox;

import dev.theredstonee.trsclient.core.ui.winter.WinterMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.clamp;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.noise;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.smooth;

/**
 * Aufbau der Fuchsbau-Landschaft (Port von {@code app/utils/fox/landscape.ts}, nur Daten, testbar): ein Minecraft-Wald
 * in der Abenddämmerung von der Seite. Hinten blockige Hügel im rosa Dunst mit kleinen Fichten, davor ein dichtes
 * Waldband (Taiga-Fichten, ab und zu ein Kirschbaum), eine Wiese im Abendlicht und vorn eine Reihe Grasblöcke. Rechts
 * liegt der Fuchsbau: ein kleiner Grashügel mit dunklem Loch zwischen Wurzeln, eine Fichte darauf, davor das
 * Lagerfeuer – und daneben schläft ein einzelner Fuchs. Alles in Texeln, aus dem Seed stabil: gleiche Größe + Seed =
 * dieselbe Landschaft wie im Launcher.
 */
public final class FoxLandscape {
	public static final int SPRUCE = 0;
	public static final int CHERRY = 1;

	/** Baum: {@code layer} 0 = fernes Waldband, 1 = Gruppen auf der Wiese, 2 = vorn (am Bau, Bildrand). */
	public static final class Tree {
		public final int kind;
		/** Linke Kante des Stamm-Blocks. */
		public final int x;
		/** Unterkante (Boden, auf dem der Stamm steht). */
		public final int base;
		/** Kantenlänge eines Blocks in Texeln. */
		public final int bs;
		/** Nadel-Stufen (Fichte) bzw. Stammhöhe in Blöcken (Kirsche). */
		public final int tiers;
		public final int layer;
		public final int seed;
		/** Kirschbaum: Stamm neigt sich nach links. */
		public final boolean flip;

		Tree(int kind, int x, int base, int bs, int tiers, int layer, int seed, boolean flip) {
			this.kind = kind;
			this.x = x;
			this.base = base;
			this.bs = bs;
			this.tiers = tiers;
			this.layer = layer;
			this.seed = seed;
			this.flip = flip;
		}
	}

	public static final class Star {
		public final int x;
		public final int y;
		public final boolean bright;
		public final boolean twinkle;

		Star(int x, int y, boolean bright, boolean twinkle) {
			this.x = x;
			this.y = y;
			this.bright = bright;
			this.twinkle = twinkle;
		}
	}

	/** Flache Minecraft-Wolke aus Zellen (je 6 × 2 Texel). */
	public static final class Cloud {
		public final int x;
		public final int y;
		public final int cols;
		public final int rows;
		public final int seed;
		/** Texel je Takt. */
		public final double speed;

		Cloud(int x, int y, int cols, int rows, int seed, double speed) {
			this.x = x;
			this.y = y;
			this.cols = cols;
			this.rows = rows;
			this.seed = seed;
			this.speed = speed;
		}
	}

	/** Der Bau: linke Kante, Bodenzeile, groß (Blöcke à 8 Texel) oder klein (3), mit Fuchs? */
	public static final class Den {
		public final int x;
		public final int y;
		public final boolean large;
		public final int bs;
		public final boolean fox;

		Den(int x, int y, boolean large, int bs, boolean fox) {
			this.x = x;
			this.y = y;
			this.large = large;
			this.bs = bs;
			this.fox = fox;
		}
	}

	/** Lage der Teile des Baus (Texel, absolut) – Maler, Licht und Tests rechnen damit. */
	public static final class Parts {
		/** Lagerfeuer: linke Kante, Bodenzeile, Breite (= ein Block). */
		public final int fireX;
		public final int fireY;
		public final int fireW;
		/** Schlafender Fuchs (nur großer Bau): Fußpunkt links unten; {@code hasFox} false = keiner. */
		public final boolean hasFox;
		public final int foxX;
		public final int foxY;
		/** Hügel: linke Kante, Höhe je Blockspalte (in Blöcken). */
		public final int hillX;
		public final int[] heights;
		/** Loch im Hügel (Bogen). */
		public final int holeX0;
		public final int holeX1;
		public final int holeTop;
		public final int holeY;
		/** Laterne auf dem Zaunpfahl (Mitte des Glases). */
		public final int lanternX;
		public final int lanternY;
		/** Süßbeerenstrauch: linke Kante, Bodenzeile, Größe. */
		public final int bushX;
		public final int bushY;
		public final int bushSize;

		Parts(Den d) {
			int b = d.bs;
			heights = new int[]{1, 2, 2, 2, 2, 2, 1};
			int hx = d.x + 4 * b;
			int top2 = d.y - 2 * b;
			fireX = d.x + (int) Math.round(0.6 * b);
			fireY = d.y;
			fireW = b;
			hasFox = d.fox && d.large;
			foxX = d.x + (int) Math.round(1.75 * b);
			foxY = d.y;
			hillX = hx;
			holeX0 = hx + (int) Math.round(1.35 * b);
			holeX1 = hx + (int) Math.round(3.3 * b);
			holeTop = d.y - (int) Math.round(1.45 * b);
			holeY = d.y;
			lanternX = hx + (int) Math.round(1.1 * b);
			lanternY = top2 - Math.max(4, (int) Math.round(b * 0.95));
			bushX = d.x + 10 * b + (int) Math.round(b * 0.3);
			bushY = d.y;
			bushSize = (int) Math.round(b * 1.6);
		}
	}

	/** Breite des Baus in Blöcken. */
	public static final int DEN_BLOCKS = 12;
	public static final int DEN_BS_LARGE = 8;
	public static final int DEN_BS_SMALL = 3;

	public final int w;
	public final int h;
	public final int seed;
	/** Fuß der fernen Hügel. */
	public final int horizon;
	/** Höhe der höchsten fernen Hügel. */
	public final int hillAmp;
	public final short[] hillsBack;
	public final short[] hills;
	/** Boden des fernen Waldbands. */
	public final short[] forest;
	/** Oberkante der Wiese. */
	public final short[] meadow;
	/** Vordere Reihe Grasblöcke (der Bau steht darauf). */
	public final short[] ground;
	/** Blockgröße der vorderen Reihe. */
	public final int bs;
	public final List<Tree> trees = new ArrayList<Tree>();
	public final List<Star> stars = new ArrayList<Star>();
	/** Eckige Minecraft-Sonne: Mitte und halbe Kantenlänge. */
	public final int sunX;
	public final int sunY;
	public final int sunHalf;
	/** Der Bau oder null. */
	public final Den den;
	/** Blumen auf der Wiese {x, y, Farbe 0 rosa / 1 gelb / 2 weiß}. */
	public final List<int[]> flowers = new ArrayList<int[]>();
	/** Grashalme vorn {x, y, h}. */
	public final List<int[]> tufts = new ArrayList<int[]>();
	/** Büsche und Farn auf der Wiese {x, y, Größe} (Mitte unten). */
	public final List<int[]> shrubs = new ArrayList<int[]>();
	public final List<Cloud> clouds = new ArrayList<Cloud>();

	/** Blockige Hügelkette: Höhe je Blockspalte (Stufen in Blockgröße), zwei Wellen + etwas Rauschen. */
	static double[] blockHills(int w, int bs, double amp, int seed) {
		double[] out = new double[w];
		int s = seed & 0xffff;
		for (int bx = 0; bx < w; bx += bs) {
			double u = bx / (double) Math.max(1, w);
			double wave = 0.55 + 0.3 * Math.sin(u * 7.1 + s * 0.37) * Math.sin(u * 15.3 + s * 0.11) + 0.25 * (smooth(bx / 40.0, s + 3) - 0.5);
			double hgt = Math.max(bs, Math.round((amp * clamp(wave, 0.12, 1)) / bs) * (double) bs);
			for (int x = bx; x < Math.min(w, bx + bs); x++) out[x] = hgt;
		}
		return out;
	}

	private static int round(double v) {
		return (int) Math.round(v);
	}

	/**
	 * @param cols Breite in Texeln
	 * @param rows Höhe in Texeln
	 * @param seed Seed (wie im Launcher)
	 * @param withDen Bau zeigen (wenn Platz ist)
	 */
	public static FoxLandscape build(int cols, int rows, int seed, boolean withDen) {
		return new FoxLandscape(cols, rows, seed, withDen, Double.NaN);
	}

	/**
	 * Wie {@link #build(int, int, int, boolean)} mit eigener Lage der Sonne ({@code sunX} 0…1, wie die Option des
	 * Launchers); NaN = Standard (0,56).
	 */
	public static FoxLandscape build(int cols, int rows, int seed, boolean withDen, double sunX) {
		return new FoxLandscape(cols, rows, seed, withDen, sunX);
	}

	private FoxLandscape(int cols, int rows, int seedIn, boolean withDen, double sunPos) {
		w = Math.max(8, cols);
		h = Math.max(8, rows);
		seed = seedIn;
		final WinterMath.Rng rand = new WinterMath.Rng(seed ^ 0xf0c5);
		int s = seed & 0xffff;

		int size = w >= 160 && h >= 90 ? 2 : (w >= 100 && h >= 44 ? 1 : 0);
		bs = size == 2 ? DEN_BS_LARGE : (size == 1 ? DEN_BS_SMALL : 2);

		int groundBase = round(h * 0.86);
		horizon = Math.max(4, round(h * 0.55));
		hillAmp = Math.max(4, round(h * 0.12));
		int forestBase = horizon + Math.max(2, round(h * 0.014));
		int meadowBase = forestBase + Math.max(2, round(h * 0.02));

		int farBs = h >= 90 ? 4 : 2;
		double[] back = blockHills(w, farBs, hillAmp, s + 1);
		double[] front = blockHills(w, Math.max(2, farBs - 1), hillAmp * 0.55, s + 17);
		hillsBack = new short[w];
		hills = new short[w];
		forest = new short[w];
		meadow = new short[w];
		ground = new short[w];
		for (int x = 0; x < w; x++) {
			hillsBack[x] = (short) (horizon - round(back[x]));
			hills[x] = (short) (horizon + 1 - round(front[x]));
			forest[x] = (short) round(forestBase - 1.2 * smooth(x / 9.0, s + 5));
			meadow[x] = (short) round(meadowBase - h * 0.01 * smooth(x / 70.0 + 2, s + 6));
		}
		// Vorn: Blockstufen (eine Blockhöhe hoch oder runter), unter dem Bau eben.
		int step = Math.max(2, round(bs / 2.0));
		for (int bx = 0; bx < w; bx += bs) {
			int lift = smooth(bx / 90.0, s + 9) > 0.62 ? step : (smooth(bx / 50.0, s + 10) < 0.28 ? -step : 0);
			for (int x = bx; x < Math.min(w, bx + bs); x++) ground[x] = (short) (groundBase - lift);
		}

		// Fuchsbau rechts.
		Den d = null;
		if (withDen && size > 0) {
			int dw = DEN_BLOCKS * bs;
			int margin = Math.max(4, round(w * 0.05));
			int x = w - dw - margin;
			int top = h;
			for (int k = x; k < x + dw; k++) top = Math.min(top, ground[clamp(k, 0, w - 1)]);
			// Boden unter dem Bau einebnen.
			for (int k = x - bs; k < x + dw + bs; k++) if (k >= 0 && k < w) ground[k] = (short) top;
			d = new Den(x, top, size == 2, bs, size == 2);
		}
		den = d;

		// Sonne: tief über den Hügeln, hält Abstand zum Bau.
		int half = round(clamp(Math.min(w, h) * 0.038, 4, 10));
		int sx = round(w * (Double.isNaN(sunPos) ? 0.56 : sunPos));
		if (d != null && Math.abs(sx - (d.x + DEN_BLOCKS * d.bs * 0.6)) < half * 4) sx = round(d.x - half * 3);
		sx = clamp(sx, half + 2, w - half - 2);
		// Zu zwei Dritteln über den Hügeln an ihrer Stelle – sie geht gerade unter.
		int ridge = horizon;
		for (int x = sx - half; x <= sx + half; x++) ridge = Math.min(ridge, horizon - round(back[clamp(x, 0, w - 1)]));
		sunX = sx;
		sunY = round(ridge - half * 0.45);
		sunHalf = half;

		// Fernes Waldband: kleine Fichten (2er-Blöcke) dicht an dicht, ab und zu ein Kirschbaum; um die Sonne frei.
		int fb = h >= 90 ? 2 : 1;
		for (int x = 0; x < w; ) {
			double patch = smooth(x / 30.0, s + 11);
			boolean clear = Math.abs(x - sunX) < half * 1.6;
			if (patch > 0.26 && !clear) {
				boolean cherry = rand.next() < 0.1;
				int tiers = cherry ? 3 : 3 + (int) Math.floor(rand.next() * 3 + patch * 2);
				int ts = treeSeed(rand);
				boolean flip = rand.next() < 0.5;
				trees.add(new Tree(cherry ? CHERRY : SPRUCE, x, forest[x] + 1, fb, tiers, 0, ts, flip));
			}
			x += fb * (2 + (int) Math.floor(rand.next() * 2));
		}
		// Baumgruppen auf der Wiese.
		int mb = h >= 90 ? 4 : 2;
		int groups = Math.max(1, round(w / 170.0));
		for (int g = 0; g < groups; g++) {
			int gx = round(w * (0.08 + rand.next() * 0.86));
			int depth = round(rand.next() * Math.max(1, h * 0.03));
			for (int k = 0, n = 2 + (int) Math.floor(rand.next() * 3); k < n; k++) {
				int x = gx + round((k - n / 2.0) * mb * (2.5 + rand.next() * 1.5));
				// Die Sonne bleibt frei, der Bau auch.
				if (x < 0 || x >= w || nearDen(d, x, mb * 3) || Math.abs(x - sunX) < half + mb * 5) continue;
				boolean cherry = k == 1 && rand.next() < 0.45;
				int tiers = cherry ? 4 : 5 + (int) Math.floor(rand.next() * 4);
				int ts = treeSeed(rand);
				boolean flip = rand.next() < 0.5;
				trees.add(new Tree(cherry ? CHERRY : SPRUCE, x, meadow[x] + 1 + depth, mb, tiers, 1, ts, flip));
			}
		}
		// Am Bau: eine Fichte auf dem Hügel, ein Kirschbaum hinter dem Feuer; groß dazu eine Fichte am Bildrand.
		if (d != null) {
			Parts p = new Parts(d);
			int b = d.bs;
			boolean big = d.large;
			int cb = big ? 5 : 2;
			trees.add(new Tree(CHERRY, d.x - round(b * 0.4), d.y - (big ? 3 : 1), cb, 4, 1, treeSeed(rand), true));
			trees.add(new Tree(SPRUCE, p.hillX + 5 * b, d.y - 2 * b, b, 6, 2, treeSeed(rand), false));
			int edge = d.x + DEN_BLOCKS * b + round(b * 1.2);
			if (big && edge < w) trees.add(new Tree(SPRUCE, edge, ground[clamp(edge, 0, w - 1)], b, 9, 2, treeSeed(rand), false));
		}
		if (w > 220 && size == 2) trees.add(new Tree(SPRUCE, -round(bs * 0.5), ground[0], bs, 10, 2, treeSeed(rand), false));
		// Stabil sortiert wie Array.sort im Launcher: Ebene, dann Fußpunkt.
		Collections.sort(trees, new Comparator<Tree>() {
			@Override
			public int compare(Tree a, Tree b) {
				return a.layer != b.layer ? a.layer - b.layer : a.base - b.base;
			}
		});

		// Erste Sterne oben im Violett.
		int skyBottom = round((horizon - hillAmp) * 0.55);
		int count = Math.max(0, round((w * (double) skyBottom) / 1100));
		for (int i = 0; i < count; i++) {
			int x = (int) Math.floor(rand.next() * w);
			int y = (int) Math.floor(Math.pow(rand.next(), 2.2) * skyBottom);
			boolean bright = rand.next() < 0.25;
			boolean tw = rand.next() < 0.3;
			stars.add(new Star(x, y, bright, tw));
		}

		for (int i = 0, n = round(w * 0.08); i < n; i++) {
			int x = (int) Math.floor(rand.next() * w);
			int top = meadow[x];
			int y = top + 2 + (int) Math.floor(Math.pow(rand.next(), 0.7) * Math.max(1, ground[x] - top - 4));
			if (y < ground[x] - 1 && noise(x >> 3, 0, s + 21) > 0.35) {
				int c = rand.next() < 0.5 ? 0 : (rand.next() < 0.6 ? 1 : 2);
				flowers.add(new int[]{x, y, c});
			}
		}

		for (int x = 2; x < w; x += 2 + (int) Math.floor(rand.next() * 5)) {
			if (smooth(x / 14.0, s + 13) > 0.45 && !nearDen(d, x, 0)) {
				tufts.add(new int[]{x, ground[x], 1 + (int) Math.floor(rand.next() * 3)});
			}
		}

		// Büsche auf der Wiese: hinten klein, vorn größer.
		for (int i = 0, n = round(w / 26.0); i < n; i++) {
			int x = (int) Math.floor(rand.next() * w);
			int top = meadow[x];
			int room = ground[x] - top - 3;
			if (room < 4 || nearDen(d, x, 4)) continue;
			double depth = Math.pow(rand.next(), 0.8);
			int y = top + 2 + round(depth * room);
			shrubs.add(new int[]{x, y, Math.max(2, round((2 + depth * 5) * (h >= 90 ? 1 : 0.6)))});
		}
		Collections.sort(shrubs, new Comparator<int[]>() {
			@Override
			public int compare(int[] a, int[] b) {
				return a[1] - b[1];
			}
		});

		int skyTop = horizon - hillAmp;
		for (int i = 0, n = Math.max(2, round(w / 120.0)); i < n; i++) {
			int cols2 = 5 + (int) Math.floor(rand.next() * 8);
			int cx = round(rand.next() * (w + cols2 * 6)) - cols2 * 6;
			int cy = round(h * 0.05 + rand.next() * Math.max(4, skyTop * 0.5));
			int rows2 = 2 + (int) Math.floor(rand.next() * 2);
			int cs = treeSeed(rand);
			double speed = 0.015 + rand.next() * 0.025;
			clouds.add(new Cloud(cx, cy, cols2, rows2, cs, speed));
		}
	}

	private static int treeSeed(WinterMath.Rng rand) {
		return (int) (rand.next() * 1e9);
	}

	private static boolean nearDen(Den d, int x, int pad) {
		return d != null && x > d.x - pad && x < d.x + DEN_BLOCKS * d.bs + pad;
	}

	/** Teile des Baus oder null (kein Bau). */
	public Parts parts() {
		return den == null ? null : new Parts(den);
	}

	/** Oberste Zeile der Wiese. */
	public int meadowTop() {
		int top = h;
		for (short v : meadow) top = Math.min(top, v);
		return top;
	}

	/** Fuß der Flammen {x, y, w} (für Rauch und Funken) oder null. */
	public static int[] fireTop(Parts p) {
		if (p == null) return null;
		boolean big = p.fireW >= 6;
		return new int[]{p.fireX + 1, p.fireY - (big ? 3 : 2) - (int) Math.round(p.fireW * 0.9), p.fireW - 2};
	}
}

package dev.theredstonee.trsclient.core.ui.fox;

import dev.theredstonee.trsclient.core.ui.winter.Snow;
import dev.theredstonee.trsclient.core.ui.winter.WinterMath;

import java.util.ArrayList;
import java.util.List;

import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.bayer;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.clamp;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.mix;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.noise;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.smooth;

/**
 * Malt die Fuchsbau-Landschaft in Texeln (Port von {@code app/utils/fox/paint.ts}). Himmel und Landschaft entstehen
 * einmal je Aufbau/Farbschema als fertige Ebenen; je Takt setzt {@link #compose} daraus das Bild zusammen und malt nur
 * Wolken, Sterne, Feuer, Rauch, Funken, die Laterne am Bau, Glühwürmchen, Blätter, den atmenden Fuchs und das weiche
 * Licht neu. Gleiche Regeln wie die Winternacht: flächige Bänder mit schmalem 4 × 4-Bayer-Übergang, Dunst auf fernen
 * Ebenen, Licht von der tiefen Sonne. Bäume und Boden sind Minecraft-Blöcke (16 × 16-Texturen, je Block gleich).
 */
public final class FoxPainter {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int BLACK = 0xFF000000;

	/** ARGB-Puffer in Texeln; 0 = leer. */
	static final class Raster {
		final int w;
		final int h;
		final int[] px;

		Raster(int w, int h) {
			this.w = w;
			this.h = h;
			this.px = new int[w * h];
		}

		void set(int x, int y, int c) {
			if (x < 0 || y < 0 || x >= w || y >= h) return;
			px[y * w + x] = c;
		}

		/** Farbe an (x, y) oder 0 (leer bzw. außerhalb). */
		int get(int x, int y) {
			if (x < 0 || y < 0 || x >= w || y >= h) return 0;
			return px[y * w + x];
		}
	}

	// --- Der schlafende Fuchs (handgepixelt nach dem Minecraft-Fuchs, Port von sprites.ts) ---

	/** 18 × 9, ausgeatmet; Kopf links (zum Feuer), der Schwanz deckt mit der weißen Spitze die Schnauze zu. */
	static final String[] SLEEP = {
			"..K..K............",
			".OO.OO....LLLLL...",
			".LLLLLLD.LOOOOOLL.",
			".ODDOOOODOOOOOOOOS",
			"KOOOOOOODOOOOOOOOS",
			".WWWOOOSSSSSSSSSOS",
			"WWWLLLLLLLLLLLLLSD",
			"WWVOOOOOOOOOOOOSD.",
			".VVSSSSSSSSSSSSD..",
	};
	public static final int FOX_W = 18;
	public static final int FOX_H = 9;

	/** Farben des Minecraft-Fuchses (Tageslicht; die Szene tönt sie). */
	static int foxColor(char c) {
		switch (c) {
			case 'O': return 0xFFE47C3A;
			case 'S': return 0xFFB25422;
			case 'D': return 0xFF7E381A;
			case 'L': return 0xFFFCB46A;
			case 'W': return 0xFFF4EFE8;
			case 'V': return 0xFFD6CABE;
			case 'K': return 0xFF2A1A16;
			case 'N': return 0xFF141010;
			default: return 0;
		}
	}

	/** Pixel des schlafenden Fuchses {x, y, Farbe} relativ zur linken oberen Ecke. */
	public static List<int[]> foxPixels(boolean inhale, boolean twitch) {
		List<int[]> out = new ArrayList<int[]>();
		for (int y = 0; y < SLEEP.length; y++) {
			String row = SLEEP[y];
			for (int x = 0; x < row.length(); x++) {
				char c = row.charAt(x);
				if (c == '.') continue;
				// Ohrzucken: das hintere Ohr klappt kurz flach.
				if (twitch && y == 0 && x == 5) continue;
				if (twitch && y == 1 && x == 5) c = 'K';
				// Einatmen: über dem Rücken eine neue Lichtkante, die alte wird Fell.
				if (inhale && y == 1 && x >= 10 && x <= 14) c = 'O';
				out.add(new int[]{x, y, foxColor(c)});
			}
		}
		if (inhale) for (int x = 10; x <= 14; x++) out.add(new int[]{x, 0, foxColor('L')});
		return out;
	}

	public final FoxLandscape L;
	public final FoxPalette P;
	final int[] sky;
	final int[] land;
	private final Raster[] clouds;
	public final FoxParticles.Fireflies flies;
	public final FoxLandscape.Parts parts;
	/** Maske der Licht-Ebene je Spalte (links hinter der Schrift schwächer) mal Deckkraft. */
	private final float[] lightMask;
	private final List<int[]>[] foxCache;

	@SuppressWarnings("unchecked")
	public FoxPainter(FoxLandscape L, FoxPalette P) {
		this.L = L;
		this.P = P;
		this.sky = paintSky(L, P).px;
		this.land = paintLand(L, P).px;
		this.clouds = new Raster[L.clouds.size()];
		for (int i = 0; i < clouds.length; i++) clouds[i] = paintCloud(L.clouds.get(i), P);
		this.parts = L.parts();
		boolean day = P.variant == FoxPalette.LIGHT;
		// Glühwürmchen über der Wiese, dichter am Bau; am Tag nur wenige.
		int n = (int) Math.round((L.w / (day ? 70.0 : 18.0)) * (L.h >= 90 ? 1 : 0.6));
		int top = L.meadowTop();
		FoxParticles.Fireflies all = new FoxParticles.Fireflies(new WinterMath.Rng(L.seed ^ 0xf1), n, L.w * 0.25, L.w, top + 2,
				Math.max(top + 6, L.h * 0.92));
		if (parts != null && L.den.large) {
			FoxParticles.Fireflies near = new FoxParticles.Fireflies(new WinterMath.Rng(L.seed ^ 0xf2), Math.max(3, (int) Math.round(n / 3.0)),
					L.den.x - L.den.bs * 2, L.den.x + FoxLandscape.DEN_BLOCKS * L.den.bs, L.den.y - L.den.bs * 5, L.den.y - 2);
			all = merge(all, near);
		}
		this.flies = all;
		// Licht-Ebene wie im Launcher: Deckkraft 0,85 (Hell 0,6), Maske 0,3 → 0,45 (35 %) → 1 (62 %).
		float opacity = day ? 0.6f : 0.85f;
		lightMask = new float[L.w];
		for (int x = 0; x < L.w; x++) {
			double u = x / (double) L.w;
			double m = u < 0.35 ? 0.3 + 0.15 * (u / 0.35) : (u < 0.62 ? 0.45 + 0.55 * ((u - 0.35) / 0.27) : 1);
			lightMask[x] = (float) (m * opacity);
		}
		foxCache = new List[4];
	}

	private static FoxParticles.Fireflies merge(FoxParticles.Fireflies a, FoxParticles.Fireflies b) {
		FoxParticles.Fireflies m = new FoxParticles.Fireflies(new WinterMath.Rng(0), a.count + b.count, 0, 0, 0, 0);
		System.arraycopy(a.hx, 0, m.hx, 0, a.count);
		System.arraycopy(b.hx, 0, m.hx, a.count, b.count);
		System.arraycopy(a.hy, 0, m.hy, 0, a.count);
		System.arraycopy(b.hy, 0, m.hy, a.count, b.count);
		System.arraycopy(a.phase, 0, m.phase, 0, a.count);
		System.arraycopy(b.phase, 0, m.phase, a.count, b.count);
		System.arraycopy(a.speed, 0, m.speed, 0, a.count);
		System.arraycopy(b.speed, 0, m.speed, a.count, b.count);
		System.arraycopy(a.blink, 0, m.blink, 0, a.count);
		System.arraycopy(b.blink, 0, m.blink, a.count, b.count);
		return m;
	}

	// --- Hilfen -------------------------------------------------------------------

	/** Verlauf in {@code steps} flächigen Stufen, nur die schmalen Übergänge gerastert. */
	static double banded(double t, int steps, int x, int y) {
		double q = clamp(t, 0, 1) * steps;
		int i = (int) Math.floor(q);
		double f = clamp((q - i - 0.5) * 4 + 0.5, 0, 1);
		return Math.min(steps, i + (f > bayer(x, y) ? 1 : 0)) / (double) steps;
	}

	/** Hash 0…1 (wie {@code nh} im Launcher). */
	static double nh(int x, int y, int s) {
		int h = x * 374761393 + y * 668265263 + s * 1442695041;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
	}

	private static double clump(int u, int v, int s) {
		return 0.62 * nh((u + (v & 2)) >> 1, v >> 1, s) + 0.38 * nh(u, v, s + 7);
	}

	// --- Minecraft-Texturen (je Block dieselbe, Blätter in 2 × 2-Büscheln) ---

	static final int GRASS = 0;
	static final int DIRT = 1;
	static final int SPRUCE_LOG = 2;
	static final int SPRUCE = 3;
	static final int CHERRY = 4;
	static final int CHERRY_LOG = 5;

	/** Texel (u, v) der 16 × 16-Textur {@code kind}; 0 = Lücke (Blätter). */
	static int tex(FoxPalette P, int kind, int u, int v) {
		switch (kind) {
			case GRASS: {
				double n = nh(u, v, 3);
				int drip = 3 + (nh(u, 0, 4) > 0.5 ? 1 : 0) + (nh(u, 0, 5) > 0.8 ? 1 : 0);
				if (v < drip) return v == 0 ? P.grass[2] : (n > 0.72 ? P.grass[2] : (n < 0.28 ? P.grass[0] : P.grass[1]));
				return n > 0.82 ? P.dirt[2] : (n < 0.3 ? P.dirt[0] : P.dirt[1]);
			}
			case DIRT: {
				double n = clump(u, v, 9);
				return n > 0.72 ? P.dirt[2] : (n < 0.32 ? P.dirt[0] : P.dirt[1]);
			}
			case SPRUCE_LOG: {
				double n = nh(u, v >> 2, 11);
				if (u % 4 == 0 || (u % 4 == 2 && n > 0.55)) return P.spruceLog[0];
				return n > 0.7 ? P.spruceLog[2] : P.spruceLog[1];
			}
			case SPRUCE: {
				double n = clump(u, v, 13);
				if (n < 0.12 && nh(u, v, 14) < 0.5) return 0;
				return n < 0.3 ? P.spruce[0] : (n > 0.74 ? P.spruce[3] : (n > 0.5 ? P.spruce[2] : P.spruce[1]));
			}
			case CHERRY: {
				double n = clump(u, v, 17);
				if (n < 0.12 && nh(u, v, 18) < 0.5) return 0;
				return n < 0.28 ? P.cherry[0] : (n > 0.76 ? P.cherry[3] : (n > 0.5 ? P.cherry[2] : P.cherry[1]));
			}
			default: {
				double n = nh(u, v >> 2, 19);
				if (u % 5 == 1 || (n > 0.8 && u % 5 == 3)) return P.cherryLog[0];
				return n > 0.68 ? P.cherryLog[2] : P.cherryLog[1];
			}
		}
	}

	/** Block mit Textur (Kantenlänge {@code bs}), getönt Richtung {@code to} um {@code shade}. */
	static void block(Raster R, FoxPalette P, int bx, int by, int bs, int kind, int seed, double shade, int to) {
		double tx = 16.0 / bs;
		double tint = (nh(seed, 1, 77) - 0.5) * 0.08;
		for (int y = 0; y < bs; y++) {
			for (int x = 0; x < bs; x++) {
				int c = tex(P, kind, (int) Math.floor(x * tx), (int) Math.floor(y * tx));
				if (c == 0) continue;
				if (tint > 0) c = mix(c, 0xFFFFE6D2, tint);
				else if (tint < 0) c = mix(c, BLACK, -tint);
				// Blockraster lesbar wie im Spiel: Unter- und rechte Kante etwas dunkler.
				if (bs >= 4 && (y == bs - 1 || x == bs - 1)) c = mix(c, BLACK, 0.14);
				if (shade > 0) c = mix(c, to, shade);
				R.set(bx + x, by + y, c);
			}
		}
	}

	private static void square(Raster R, int x, int y, int bs, int c) {
		for (int q = 0; q < bs; q++) for (int k = 0; k < bs; k++) R.set(x + k, y + q, c);
	}

	// --- Himmel --------------------------------------------------------------------

	static Raster paintSky(FoxLandscape L, FoxPalette P) {
		Raster R = new Raster(L.w, L.h);
		int bands = P.sky.length - 1;
		int end = Math.max(4, L.horizon);
		boolean light = P.variant == FoxPalette.LIGHT;
		for (int y = 0; y < L.h; y++) {
			// Oben länger Violett, unten rasch Gold (wie eine echte Dämmerung).
			double lin = clamp(y / (double) end, 0, 1);
			double pos = Math.pow(lin, 1.15) * bands;
			int i = Math.min(bands - 1, (int) Math.floor(pos));
			double f = clamp((pos - i - 0.5) * 3.2 + 0.5, 0, 1);
			for (int x = 0; x < L.w; x++) R.set(x, y, f > bayer(x, y) ? P.sky[i + 1] : P.sky[i]);
		}
		// Hof um die Sonne: drei gerasterte Ringe, etwas breiter als hoch – die Sonne steht tief.
		int sx = L.sunX;
		int sy = L.sunY;
		int half = L.sunHalf;
		int reach = (int) Math.round(half * (light ? 5 : 6.5));
		for (int y = Math.max(0, sy - reach); y < Math.min(L.h, sy + reach); y++) {
			for (int x = Math.max(0, sx - reach * 2); x < Math.min(L.w, sx + reach * 2); x++) {
				double d = Math.hypot((x - sx) * 0.62, y - sy);
				if (d >= reach) continue;
				double t = 1 - d / reach;
				double q = Math.floor(t * t * 3 + bayer(x, y)) / 3;
				if (q <= 0) continue;
				R.set(x, y, mix(R.get(x, y), P.halo, q * (light ? 0.38 : 0.3)));
			}
		}
		if (!light) {
			for (FoxLandscape.Star s : L.stars) R.set(s.x, s.y, s.bright ? P.star : P.starDim);
		}
		// Eckige Minecraft-Sonne: heller Kern, warmer Rand.
		int rimW = Math.max(1, (int) Math.round(half * 0.3));
		for (int dy = -half; dy < half; dy++) {
			for (int dx = -half; dx < half; dx++) {
				int edge = Math.min(Math.min(dx + half, half - 1 - dx), Math.min(dy + half, half - 1 - dy));
				R.set(sx + dx, sy + dy, edge < rimW ? P.sun : P.sunCore);
			}
		}
		return R;
	}

	/** Minecraft-Wolke: flache Platte aus Zellen (6 × 2 Texel), oben hell, unten vom Abendlicht rosa. */
	static Raster paintCloud(FoxLandscape.Cloud c, FoxPalette P) {
		int cw = 6;
		int ch = 2;
		Raster R = new Raster(c.cols * cw, c.rows * ch + 1);
		for (int i = 0; i < c.cols; i++) {
			for (int q = 0; q < c.rows; q++) {
				if (!cloudOn(c, i, q)) continue;
				for (int y = 0; y < ch; y++) {
					for (int x = 0; x < cw; x++) {
						boolean top = y == 0 && !cloudOn(c, i, q - 1);
						R.set(i * cw + x, q * ch + y, top ? P.cloudLit : P.cloud);
					}
				}
				if (!cloudOn(c, i, q + 1)) for (int x = 0; x < cw; x++) R.set(i * cw + x, q * ch + ch, P.cloudUnder);
			}
		}
		return R;
	}

	private static boolean cloudOn(FoxLandscape.Cloud c, int i, int q) {
		return i >= 0 && q >= 0 && i < c.cols && q < c.rows
				&& nh(i, q, c.seed & 0xffff) > 0.08 + 0.55 * Math.abs(i / (double) c.cols - 0.5) + (q == 0 ? 0.12 : 0);
	}

	// --- Landschaft ----------------------------------------------------------------

	static Raster paintLand(FoxLandscape L, FoxPalette P) {
		Raster R = new Raster(L.w, L.h);
		paintHills(R, L, L.hillsBack, P.hillBack, P.hillBackRim, P, 0.35);
		paintHills(R, L, L.hills, P.hill, P.hillRim, P, 0.15);
		paintForestFloor(R, L, P);
		for (FoxLandscape.Tree t : L.trees) if (t.layer == 0) paintTree(R, L, t, P, 0.62, P.forestFar);
		paintMeadow(R, L, P);
		for (int[] s : L.shrubs) paintShrub(R, L, s, P);
		int mid = mix(P.forestMid, P.haze, 0.35);
		for (FoxLandscape.Tree t : L.trees) if (t.layer == 1) paintTree(R, L, t, P, 0.34 * P.hazeScale, mid);
		paintGround(R, L, P);
		for (int[] t : L.tufts) paintTuft(R, t[0], t[1], t[2], P);
		for (FoxLandscape.Tree t : L.trees) if (t.layer == 2) paintTree(R, L, t, P, 0, P.deep);
		if (L.den != null) {
			paintDen(R, L, P);
			paintFireLight(R, L, P);
		}
		return R;
	}

	/** Blockige Hügel im Dunst; Oberkante als heller Saum, zur Sonne hin wärmer, unten neblig. */
	private static void paintHills(Raster R, FoxLandscape L, short[] line, int col, int rim, FoxPalette P, double mist) {
		int bottom = Math.min(L.h - 1, L.horizon + 3);
		for (int x = 0; x < L.w; x++) {
			int top = line[x];
			if (top > bottom) continue;
			double toSun = clamp(1 - Math.abs(x - L.sunX) / (L.w * 0.35), 0, 1);
			for (int y = top; y <= bottom; y++) {
				int c = y == top || (y == top + 1 && x > 0 && line[x - 1] > top) ? rim : col;
				if (y == top) c = mix(c, P.sunCore, 0.25 * toSun);
				double fog = banded((y - (L.horizon - L.hillAmp * 0.5)) / (L.hillAmp * 0.6), 2, x, y);
				R.set(x, y, mix(c, P.haze, (mist * fog) * P.hazeScale));
			}
		}
	}

	/** Boden unter dem fernen Waldband (dunkler Streifen bis zur Wiese). */
	private static void paintForestFloor(Raster R, FoxLandscape L, FoxPalette P) {
		for (int x = 0; x < L.w; x++) {
			for (int y = L.forest[x]; y <= L.meadow[x] + 1 && y < L.h; y++) {
				int c = noise(x, y, 37) < 0.2 ? P.spruce[1] : P.spruce[0];
				R.set(x, y, mix(c, P.forestFar, 0.62 * P.hazeScale));
			}
		}
	}

	/** Wiese im Abendlicht: oben warm von der Sonne angestrahlt, nach vorn tiefer im Schatten. */
	private static void paintMeadow(Raster R, FoxLandscape L, FoxPalette P) {
		int depth = Math.max(10, (int) Math.round((L.h - L.horizon) * 0.7));
		int s = L.seed & 0xffff;
		for (int x = 0; x < L.w; x++) {
			int top = L.meadow[x];
			// Unter der Sonne ein heller Schimmer auf dem Gras.
			double sheen = clamp(1 - Math.abs(x - L.sunX) / (L.sunHalf * 9.0), 0, 1);
			for (int y = Math.max(0, top); y < L.h; y++) {
				int d = y - top;
				int k = (int) Math.round(banded(d / (double) depth, 3, x, y) * 3);
				// Grasstreifen: kurze waagrechte Bahnen etwas heller oder dunkler.
				double rib = noise((x + (int) Math.round(5 * smooth(y / 3.0, s))) >> 3, y, s + 2);
				if (rib < 0.03) k = Math.max(0, k - 1);
				else if (rib > 0.975) k = Math.min(3, k + 1);
				int c = P.meadow[clamp(k, 0, 3)];
				if (d < 2) c = mix(P.meadow[0], P.rim, 0.25);
				if (sheen > 0 && d < depth * 0.5) {
					double glow = banded(sheen * (1 - d / (depth * 0.5)), 3, x, y);
					if (glow > 0) c = mix(c, P.rim, glow * 0.35);
				}
				R.set(x, y, c);
			}
		}
		for (int[] f : L.flowers) {
			if (f[1] >= L.h) continue;
			R.set(f[0], f[1], FoxPalette.lit(P.flowers[f[2]], P.ambient));
			if (f[1] - 1 >= L.meadow[f[0]]) R.set(f[0], f[1] + 1, P.meadow[2]);
		}
	}

	/** Vordere Reihe Grasblöcke, darunter Erde, die nach unten im Schatten versinkt. */
	private static void paintGround(Raster R, FoxLandscape L, FoxPalette P) {
		int bs = L.bs;
		for (int bx = 0; bx < L.w; bx += bs) {
			int top = L.ground[bx];
			for (int by = top, row = 0; by < L.h; by += bs, row++) {
				double shade = clamp(row * 0.34 + 0.06, 0, 0.9);
				block(R, P, bx, by, bs, row == 0 ? GRASS : DIRT, bx * 7 + row, shade, P.deep);
			}
			// Stufe nach oben: die Seite des höheren Blocks zeigt Erde.
			if (bx + bs < L.w) {
				int next = L.ground[bx + bs];
				if (next < top) for (int y = next; y < top; y++) R.set(bx + bs - 1, y, mix(P.dirt[0], P.deep, 0.3));
			}
		}
	}

	/** Busch auf der Wiese: runder Blätterball in Büscheln, oben vom Abendlicht gesäumt, fern im Dunst. */
	private static void paintShrub(Raster R, FoxLandscape L, int[] s, FoxPalette P) {
		int sx = s[0];
		int sy = s[1];
		int size = s[2];
		double far = 1 - clamp((sy - L.meadow[clamp(sx, 0, L.w - 1)]) / Math.max(8, (L.h - L.horizon) * 0.5), 0, 1);
		int w = (int) Math.round(size * 1.6);
		int h = size;
		for (int yy = -h; yy < 0; yy++) {
			for (int xx = 0; xx < w; xx++) {
				double d = Math.hypot((xx + 0.5 - w / 2.0) / (w / 2.0), (yy + 0.5 + h * 0.45) / (h * 0.55));
				double n = nh((sx + xx) >> 1, (sy + yy) >> 1, 31);
				if (d > 1.02 - (n - 0.5) * 0.35) continue;
				boolean top = yy == -h || R.get(sx + xx, sy + yy - 1) == 0 || (d > 0.8 && yy < -h * 0.4);
				int c = n > 0.66 ? P.berryLeaf[2] : (n < 0.3 ? P.berryLeaf[0] : P.berryLeaf[1]);
				c = FoxPalette.lit(c, P.ambient);
				if (top && yy < -h * 0.3) c = mix(c, P.rim, 0.35);
				R.set(sx + xx, sy + yy, mix(c, P.haze, far * 0.45 * P.hazeScale));
			}
		}
	}

	private static void paintTuft(Raster R, int x, int y, int h, FoxPalette P) {
		for (int k = 1; k <= h; k++) R.set(x, y - k, k == h ? P.grass[2] : P.grass[1]);
		if (h > 1) R.set(x + 1, y - 1, P.grass[0]);
	}

	// --- Bäume ------------------------------------------------------------------------

	/**
	 * Halbe Breite (Blöcke) der Nadel-Stufe i: wächst nach unten im Zickzack (schmal, breit, schmal …) wie bei
	 * Minecraft-Fichten; die unterste Stufe ist die breiteste.
	 */
	public static int spruceHalf(int i, int tiers) {
		if (i == 0 || tiers < 2) return 0;
		int max = tiers > 8 ? 3 : (tiers > 4 ? 2 : 1);
		int grow = (int) Math.ceil((i / (double) (tiers - 1)) * max);
		return (tiers - 1 - i) % 2 == 1 ? Math.max(1, grow - 1) : grow;
	}

	/** Gesetzte Blöcke einer Krone (Zeile i, Spalte j) in der Reihenfolge des Setzens. */
	private static final class Placed {
		final List<int[]> order = new ArrayList<int[]>();
		final java.util.HashSet<Long> set = new java.util.HashSet<Long>();

		void add(int i, int j) {
			if (set.add(key(i, j))) order.add(new int[]{i, j});
		}

		boolean has(int i, int j) {
			return set.contains(key(i, j));
		}

		private static long key(int i, int j) {
			return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
		}
	}

	private static void paintTree(Raster R, FoxLandscape L, FoxLandscape.Tree t, FoxPalette P, double haze, int to) {
		// Licht von der Sonne: die ihr zugewandte Seite bekommt den Saum.
		int side = t.x < L.sunX ? 1 : -1;
		if (t.kind == FoxLandscape.CHERRY) cherryTree(R, t, P, haze, to, side);
		else spruce(R, t, P, haze, to, side);
	}

	/** Minecraft-Fichte: Stamm + Nadel-Stufen (schmal, breit, schmal …), Ecken der breiten Stufen fehlen manchmal. */
	private static void spruce(Raster R, FoxLandscape.Tree t, FoxPalette P, double haze, int to, int side) {
		int bs = t.bs;
		int tiers = t.tiers;
		int ground = t.base;
		int x = t.x;
		WinterMath.Rng r = new WinterMath.Rng(t.seed);
		int trunkTop = ground - (tiers + 1) * bs;
		Placed placed = new Placed();
		if (bs <= 2) {
			// Fern: Schattenriss in zwei Tönen.
			int body = mix(P.spruce[1], to, haze);
			int dark = mix(P.spruce[0], to, haze);
			int logC = mix(P.spruceLog[1], to, haze);
			for (int y = ground - bs; y >= trunkTop; y -= bs) square(R, x, y, bs, logC);
			for (int i = 0; i < tiers; i++) {
				int hw = spruceHalf(i, tiers);
				int y = trunkTop - bs + i * bs;
				for (int j = -hw; j <= hw; j++) {
					square(R, x + j * bs, y, bs, j * side > 0 ? body : dark);
					placed.add(i, j);
				}
			}
			rimLight(R, placed, x, trunkTop - bs, bs, P, haze, side);
			return;
		}
		for (int y = ground - bs; y >= trunkTop; y -= bs) block(R, P, x, y, bs, SPRUCE_LOG, t.seed + y, haze, to);
		for (int i = 0; i < tiers; i++) {
			int w = 1 + 2 * spruceHalf(i, tiers);
			int y = trunkTop - bs + i * bs;
			for (int j = -(w >> 1); j <= w >> 1; j++) {
				if (w >= 5 && Math.abs(j) == w >> 1 && r.next() < 0.5) continue;
				double fall = 0.12 * (i / (double) tiers);
				// Abseits der Sonne etwas dunkler.
				double away = j * side < 0 ? 0.1 : 0;
				block(R, P, x + j * bs, y, bs, SPRUCE, t.seed + i * 31 + j, clamp(haze + fall + away, 0, 1), haze > 0 ? to : P.deep);
				placed.add(i, j);
			}
		}
		rimLight(R, placed, x, trunkTop - bs, bs, P, haze, side);
	}

	/** Warmer Saum auf Blöcken, über denen nichts liegt, und an der Seite zur Sonne. */
	private static void rimLight(Raster R, Placed placed, int x, int y0, int bs, FoxPalette P, double haze, int side) {
		double k = 0.42 * (1 - haze);
		if (k <= 0.03) return;
		int rows = Math.max(1, (int) Math.round(bs / 6.0));
		for (int[] key : placed.order) {
			int i = key[0];
			int j = key[1];
			int bx = x + j * bs;
			int by = y0 + i * bs;
			if (!placed.has(i - 1, j)) {
				for (int yy = 0; yy < rows; yy++) for (int xx = 0; xx < bs; xx++) rim(R, bx + xx, by + yy, P, k * (yy != 0 ? 0.5 : 1));
			}
			if (!placed.has(i, j + side)) {
				int ex = side > 0 ? bx + bs - 1 : bx;
				for (int yy = 0; yy < bs; yy++) rim(R, ex, by + yy, P, k * 0.55);
			}
		}
	}

	private static void rim(Raster R, int x, int y, FoxPalette P, double a) {
		int c = R.get(x, y);
		if (c != 0) R.set(x, y, mix(c, P.rim, a));
	}

	/** Minecraft-Kirschbaum: schräger Stamm, breite rosa Krone, hängende Blätter. */
	private static void cherryTree(Raster R, FoxLandscape.Tree t, FoxPalette P, double haze, int to, int side) {
		int bs = t.bs;
		int seed = t.seed;
		WinterMath.Rng r = new WinterMath.Rng(seed);
		int dir = t.flip ? -1 : 1;
		int cx = t.x;
		int cy = t.base - bs;
		boolean sil = bs <= 2;
		int logCol = mix(P.cherryLog[1], to, haze);
		int target = haze > 0 ? to : P.deep;
		for (int i = 0; i < t.tiers; i++) {
			if (sil) square(R, cx, cy, bs, logCol);
			else block(R, P, cx, cy, bs, CHERRY_LOG, seed + i, haze, target);
			if (i % 2 == 1) {
				cx += dir * bs;
				if (sil) square(R, cx, cy, bs, logCol);
				else block(R, P, cx, cy, bs, CHERRY_LOG, seed + i + 50, haze, target);
			}
			cy -= bs;
		}
		// Breite, flache Krone wie bei Minecraft-Kirschen (oben schmal, Mitte breit).
		int[] rows = {5, 9, 9, 7};
		int top = cy - 2 * bs;
		Placed placed = new Placed();
		for (int i = 0; i < rows.length; i++) {
			int w = rows[i];
			for (int j = -(w >> 1); j <= w >> 1; j++) {
				if (Math.abs(j) == w >> 1 && (i == 0 || i == rows.length - 1 ? r.next() < 0.7 : r.next() < 0.25)) continue;
				int flat = mix(j * side > 0 ? P.cherry[2] : P.cherry[1], to, haze);
				if (sil) square(R, cx + j * bs, top + i * bs, bs, flat);
				else block(R, P, cx + j * bs, top + i * bs, bs, CHERRY, seed + i * 17 + j,
						clamp(haze + (i / (double) rows.length) * 0.18 + (j * side < 0 ? 0.08 : 0), 0, 1), target);
				placed.add(i, j);
			}
		}
		rimLight(R, placed, cx, top, bs, P, haze, side);
		if (sil) return;
		// Hängende Blätter unter der Krone.
		for (int j = -2; j <= 2; j++) {
			if (r.next() < 0.45) continue;
			int hx = cx + j * bs;
			int hy = top + rows.length * bs;
			int len = 1 + (int) Math.floor(r.next() * 2);
			for (int q = 0; q < len * bs; q++) {
				for (int p = 0; p < bs; p++) {
					if (nh(p, q, seed + j) < 0.45 || p % 3 == 0) continue;
					int c = P.cherry[Math.min(3, (int) Math.floor(nh(p, q, seed + 9 + j) * 4))];
					R.set(hx + p, hy + q, mix(c, target, clamp(haze + 0.22, 0, 1)));
				}
			}
		}
	}

	// --- Fuchsbau ------------------------------------------------------------------------

	private static void paintDen(Raster R, FoxLandscape L, FoxPalette P) {
		FoxLandscape.Den d = L.den;
		FoxLandscape.Parts p = L.parts();
		int b = d.bs;
		// Grashügel aus Blöcken.
		for (int i = 0; i < p.heights.length; i++) {
			int n = p.heights[i];
			for (int k = 0; k < n; k++) {
				int by = d.y - (k + 1) * b;
				block(R, P, p.hillX + i * b, by, b, k == n - 1 ? GRASS : DIRT, d.x * 3 + i * 11 + k, 0.08 * (n - 1 - k), P.deep);
			}
		}
		// Loch: dunkler Bogen, nach innen tiefer.
		int x0 = p.holeX0;
		int x1 = p.holeX1;
		int top = p.holeTop;
		int y = p.holeY;
		double mid = (x0 + x1) / 2.0;
		double hw = (x1 - x0) / 2.0;
		for (int yy = top; yy < y; yy++) {
			for (int xx = x0; xx <= x1; xx++) {
				double u = (xx - mid) / hw;
				int arch = top + (int) Math.round((y - top) * 0.55 * u * u);
				if (yy < arch) continue;
				double depth = clamp((yy - arch) / Math.max(2, (y - top) * 0.45), 0, 1) * (1 - Math.abs(u) * 0.35);
				R.set(xx, yy, mix(P.hole[0], P.hole[1], banded(depth, 3, xx, yy)));
			}
		}
		// Wurzeln um den Eingang.
		if (b >= 6) {
			int m = (int) Math.round(mid);
			int[][] roots = {{x0 - 2, top - 2, x0, y - 1}, {x1 + 2, top - 2, x1, y - 1}, {x0, top - 2, m - 1, top}, {m + 2, top - 3, x1 - 1, top}};
			for (int[] rt : roots) {
				int n = (int) Math.ceil(Math.hypot(rt[2] - rt[0], rt[3] - rt[1]));
				for (int i = 0; i <= n; i++) {
					double t = i / (double) n;
					int rx = (int) Math.round(rt[0] + (rt[2] - rt[0]) * t + Math.sin(t * 5) * 0.8);
					int ry = (int) Math.round(rt[1] + (rt[3] - rt[1]) * t);
					R.set(rx, ry, P.root[0]);
					if (t < 0.5) R.set(rx + 1, ry, P.root[1]);
				}
			}
		}
		paintBush(R, p, P, b);
		paintCampfire(R, p, P);
		// Zaunpfahl mit Laterne auf dem Hügel (nur groß – klein wäre sie nur ein Fleck).
		if (b < 6) return;
		int lx = p.lanternX;
		for (int yy = p.lanternY + 3; yy < d.y - 2 * b; yy++) {
			R.set(lx, yy, P.fence[1]);
			R.set(lx + 1, yy, P.fence[0]);
		}
		paintLanternFrame(R, lx, p.lanternY, P, true);
	}

	/** Süßbeerenstrauch: runder Blätterbusch in 2er-Büscheln, rote Beeren mit hellem Glanz. */
	private static void paintBush(Raster R, FoxLandscape.Parts p, FoxPalette P, int b) {
		int x = p.bushX;
		int y = p.bushY;
		int size = p.bushSize;
		int w = (int) Math.round(size * 1.25);
		int h = size;
		int cell = b >= 6 ? 2 : 1;
		for (int yy = -h; yy < 0; yy++) {
			for (int xx = 0; xx < w; xx++) {
				int tx = Math.floorDiv(xx, cell);
				int ty = Math.floorDiv(yy, cell);
				double dd = Math.hypot((xx - w / 2.0) / (w / 2.0), (yy + h * 0.48) / (h * 0.52));
				double n = nh(tx, ty, x);
				if (dd > 1 - (n - 0.5) * 0.3 || n < 0.08) continue;
				int k = Math.min(2, (int) Math.floor(nh(tx, ty, x + 1) * (yy < -h * 0.5 ? 3 : 2.2)));
				R.set(x + xx, y + yy, P.berryLeaf[k]);
			}
		}
		WinterMath.Rng r = new WinterMath.Rng(x);
		for (int i = 0, n = Math.max(3, (int) Math.round(size / 2.0)); i < n; i++) {
			int bx = x + 1 + (int) Math.floor(r.next() * (w - 3));
			int by = y - 2 - (int) Math.floor(r.next() * (h - 3));
			R.set(bx, by, P.berryHi);
			R.set(bx + 1, by, P.berry);
			if (cell > 1) {
				R.set(bx, by + 1, P.berry);
				R.set(bx + 1, by + 1, mix(P.berry, P.deep, 0.4));
			}
		}
	}

	/** Gekreuzte Stämme des Lagerfeuers (die Flammen malt der Takt). */
	private static void paintCampfire(Raster R, FoxLandscape.Parts p, FoxPalette P) {
		int x = p.fireX;
		int y = p.fireY;
		int w = p.fireW;
		boolean big = w >= 6;
		for (int k = 0; k < w; k++) {
			R.set(x + k, y - 1, P.log[0]);
			if (big) R.set(x + k, y - 2, k == 0 || k == w - 1 ? P.log[2] : P.log[1]);
		}
		int top = big ? y - 3 : y - 2;
		int[] ks = big ? new int[]{0, 1, w - 2, w - 1} : new int[]{0, w - 1};
		for (int k : ks) R.set(x + k, top, k == 0 || k == w - 1 ? P.log[2] : P.log[1]);
	}

	/** Rahmen, Kappe und Henkel einer Laterne; das Glas malt jeder Takt. */
	private static void paintLanternFrame(Raster R, int x, int y, FoxPalette P, boolean big) {
		int f = P.lanternFrame;
		for (int dy = -1; dy <= 1; dy++) {
			R.set(x - 2, y + dy, f);
			R.set(x + 2, y + dy, f);
		}
		for (int dx = -2; dx <= 2; dx++) {
			R.set(x + dx, y - 2, f);
			R.set(x + dx, y + 2, f);
		}
		for (int dx = -1; dx <= 1; dx++) R.set(x + dx, y - 3, mix(f, P.rim, 0.35));
		if (big) R.set(x, y - 4, f);
	}

	/** Feste Lichtkarte des Lagerfeuers: warmer Schein auf Boden, Hügel und Bäumen (gestuft, gerastert). */
	private static void paintFireLight(Raster R, FoxLandscape L, FoxPalette P) {
		FoxLandscape.Den d = L.den;
		FoxLandscape.Parts p = L.parts();
		double cx = p.fireX + p.fireW / 2.0;
		double cy = p.fireY - p.fireW;
		double reach = d.bs * (d.large ? 6 : 5);
		double strength = P.variant == FoxPalette.LIGHT ? 0.22 : (P.variant == FoxPalette.OLED ? 0.5 : 0.4);
		int warm = FoxPalette.lit(P.glowFire, 0xFFFFE6C8);
		for (int y = Math.max(0, (int) Math.floor(cy - reach)); y < Math.min(L.h, cy + reach); y++) {
			for (int x = Math.max(0, (int) Math.floor(cx - reach * 1.3)); x < Math.min(L.w, cx + reach * 1.3); x++) {
				double dd = Math.hypot((x - cx) / 1.3, y - cy) / reach;
				if (dd >= 1) continue;
				int c = R.get(x, y);
				if (c == 0) continue;
				double k = banded((1 - dd) * (1 - dd), 4, x, y);
				if (k > 0) R.set(x, y, mix(c, warm, k * strength));
			}
		}
	}

	// --- Bild je Takt ------------------------------------------------------------------

	/** Zustand eines Bildes. */
	public static final class Frame {
		public int tick;
		public double seconds;
		/** Feuer 0…1 (ruhig … lodert). */
		public double fire = 0.5;
		/** Bewegung aus: nichts flackert, Glühwürmchen stehen. */
		public boolean calm;
		public boolean inhale;
		public boolean twitch;
		public Snow.Smoke smoke;
		public FoxParticles.Sparks sparks;
		public FoxParticles.Leaves leaves;
	}

	private static void setPx(int[] out, int w, int h, int x, int y, int c) {
		if (x < 0 || y < 0 || x >= w || y >= h) return;
		out[y * w + x] = c;
	}

	private static void rect(int[] out, int w, int h, int x, int y, int rw, int rh, int c) {
		for (int yy = Math.max(0, y); yy < Math.min(h, y + rh); yy++) {
			for (int xx = Math.max(0, x); xx < Math.min(w, x + rw); xx++) out[yy * w + xx] = c;
		}
	}

	/** Setzt ein ganzes Bild in {@code out} (Länge w·h) zusammen. */
	public void compose(int[] out, Frame s) {
		int w = L.w;
		int h = L.h;
		int n = w * h;
		boolean day = P.variant == FoxPalette.LIGHT;
		System.arraycopy(sky, 0, out, 0, n);

		// Wolken ziehen langsam nach rechts.
		for (int i = 0; i < clouds.length; i++) {
			FoxLandscape.Cloud c = L.clouds.get(i);
			Raster img = clouds[i];
			double span = w + img.w * 2;
			int x = (int) Math.floor((((c.x + img.w + s.tick * c.speed) % span) + span) % span) - img.w;
			for (int yy = 0; yy < img.h; yy++) {
				int ty = c.y + yy;
				if (ty < 0 || ty >= h) continue;
				for (int xx = 0; xx < img.w; xx++) {
					int v = img.px[yy * img.w + xx];
					int tx = x + xx;
					if (v != 0 && tx >= 0 && tx < w) out[ty * w + tx] = v;
				}
			}
		}

		// Funkelnde erste Sterne.
		if (!day && !s.calm) {
			int phase = s.tick / 6;
			for (int i = 0; i < L.stars.size(); i++) {
				FoxLandscape.Star st = L.stars.get(i);
				if (!st.twinkle) continue;
				double nz = noise(i, phase, 61);
				if (nz < 0.35) setPx(out, w, h, st.x, st.y, P.starDim);
				else if (nz > 0.88) setPx(out, w, h, st.x, st.y, P.star);
			}
		}

		// Landschaft darüber (deckend, wo gemalt).
		for (int i = 0; i < n; i++) {
			int v = land[i];
			if (v != 0) out[i] = v;
		}

		if (parts != null) {
			// Der schlafende Fuchs atmet neben dem Feuer.
			if (parts.hasFox) paintFox(out, parts.foxX, parts.foxY, s.inhale, s.twitch);
			paintFlames(out, s);
			// Laterne am Bau.
			if (L.den.large) {
				boolean flick = !s.calm && noise(1, s.tick >> 1, 67) < 0.08;
				glass(out, parts.lanternX, parts.lanternY, P.flame[1], P.flame[0], flick);
			}
		}

		// Rauch über dem Feuer.
		Snow.Smoke sm = s.smoke;
		if (sm != null) {
			for (int i = 0; i < sm.count; i++) {
				double age = 1 - sm.life[i] / (double) sm.max[i];
				if (age > 0.5 && age - 0.5 > bayer((int) sm.x[i], (int) sm.y[i]) * 0.5) continue;
				int size = age < 0.3 ? 1 : (age < 0.65 ? 2 : 3);
				rect(out, w, h, (int) Math.round(sm.x[i]), (int) Math.round(sm.y[i]), size, size, mix(P.smoke, P.sky[3], age * 0.5));
			}
		}
		// Funken.
		FoxParticles.Sparks sp = s.sparks;
		if (sp != null) {
			for (int i = 0; i < sp.count; i++) {
				double age = 1 - sp.life[i] / (double) sp.max[i];
				int x = (int) Math.round(sp.x[i]);
				if (!s.calm && noise(x, s.tick, 71) < 0.25) continue;
				setPx(out, w, h, x, (int) Math.round(sp.y[i]), age < 0.4 ? P.spark : P.ember);
			}
		}

		// Glühwürmchen.
		FoxParticles.Fireflies f = flies;
		for (int i = 0; i < f.count; i++) {
			f.at(i, s.seconds, s.calm);
			if (f.aa < 0.15) continue;
			setPx(out, w, h, f.ax, f.ay, f.aa > 0.55 ? P.fly : mix(P.fly, P.flyGlow, 0.6));
		}

		// Treibende Blätter und Kirschblüten.
		FoxParticles.Leaves lv = s.leaves;
		if (lv != null) {
			for (int i = 0; i < lv.count; i++) {
				int x = (int) Math.round(lv.x[i]);
				int y = (int) Math.round(lv.y[i]);
				int c = lv.petal[i] ? P.petals[lv.tone[i] % P.petals.length] : P.leaves[lv.tone[i] % P.leaves.length];
				int col = FoxPalette.lit(c, P.ambient);
				if (lv.size[i] == 0) col = mix(col, P.haze, 0.3);
				setPx(out, w, h, x, y, col);
				if (lv.wide(i, s.tick)) setPx(out, w, h, x + 1, y, col);
			}
		}

		glow(out, s);
	}

	/** Schlafender Fuchs, im Abendlicht getönt; die Seite zum Feuer warm angestrahlt. */
	private void paintFox(int[] out, int x0, int base, boolean inhale, boolean twitch) {
		int key = (inhale ? 1 : 0) | (twitch ? 2 : 0);
		List<int[]> px = foxCache[key];
		if (px == null) {
			px = new ArrayList<int[]>();
			double fireX = parts.fireX + parts.fireW / 2.0;
			// Er liegt im Schein des Feuers: weniger Dämmerung als die Welt ringsum.
			int amb = mix(P.ambient, WHITE, 0.55);
			double warm = P.variant == FoxPalette.LIGHT ? 0.05 : 0.12;
			for (int[] p : foxPixels(inhale, twitch)) {
				int col = FoxPalette.lit(p[2], amb);
				double near = clamp(1 - (x0 + p[0] - fireX) / 22.0, 0, 1);
				col = mix(col, P.glowFire, warm * near);
				px.add(new int[]{p[0], p[1], col});
			}
			foxCache[key] = px;
		}
		int oy = base - FOX_H;
		for (int[] p : px) setPx(out, L.w, L.h, x0 + p[0], oy + p[1], p[2]);
	}

	/** Lagerfeuer: Glut zwischen den Stämmen, gestufte Flammen, die je Takt flackern. */
	private void paintFlames(int[] out, Frame s) {
		int w0 = L.w;
		int h0 = L.h;
		int x = parts.fireX;
		int y = parts.fireY;
		int w = parts.fireW;
		boolean big = w >= 6;
		int base = big ? y - 3 : y - 2;
		for (int k = big ? 2 : 1; k < w - (big ? 2 : 1); k++) {
			setPx(out, w0, h0, x + k, base, noise(k, s.tick >> 2, 73) < 0.3 && !s.calm ? P.flame[0] : P.ember);
		}
		double tall = (big ? w * 1.15 : w * 1.4) * (0.75 + 0.35 * s.fire);
		for (int k = 1; k < w - 1; k++) {
			double off = Math.abs(k - (w - 1) / 2.0) / ((w - 1) / 2.0);
			double jitter = s.calm ? 0 : (noise(k, s.tick, 79) - 0.5) * (big ? 3 : 1.5) + Math.sin(s.tick * 0.5 + k) * 0.8;
			int hgt = Math.max(0, (int) Math.round(tall * (1 - off * 0.75) + jitter));
			for (int j = 0; j < hgt; j++) {
				double t = j / (double) Math.max(1, hgt);
				int c = t < 0.35 ? P.flame[0] : (t < 0.72 ? P.flame[1] : P.flame[2]);
				setPx(out, w0, h0, x + k, base - 1 - j, c);
			}
		}
		// Eine lose Flammenzunge oben.
		if (!s.calm && big && noise(3, s.tick, 83) < 0.4) {
			int k = 2 + (int) Math.floor(noise(4, s.tick, 83) * (w - 4));
			setPx(out, w0, h0, x + k, (int) Math.round(base - 1 - tall - 1 - noise(5, s.tick, 83) * 2), P.flame[2]);
		}
	}

	private void glass(int[] out, int x, int y, int on, int core, boolean flick) {
		rect(out, L.w, L.h, x - 1, y - 1, 3, 3, flick ? mix(on, P.lanternFrame, 0.35) : on);
		setPx(out, L.w, L.h, x, y, core);
	}

	// --- Weiches Licht -------------------------------------------------------------

	/** Weiches Licht (Sonne, Lagerfeuer, Laterne, Glühwürmchen) wie die Licht-Ebene im Launcher. */
	private void glow(int[] out, Frame s) {
		boolean day = P.variant == FoxPalette.LIGHT;
		light(out, L.sunX, L.sunY, L.sunHalf * 9, P.glowSun, day ? 0.35 : 0.42, day);
		if (parts != null) {
			int b = L.den.bs;
			// Lagerfeuer pulsiert sanft (ruhig: gleichmäßig).
			double pulse = s.calm ? 0.85 : 0.78 + 0.12 * Math.sin(s.seconds * 2.3) + 0.08 * (noise(9, s.tick >> 1, 89) - 0.5);
			double power = 0.7 + 0.3 * s.fire;
			light(out, parts.fireX + parts.fireW / 2.0, parts.fireY - b, b * (L.den.large ? 6.5 : 6) * power, P.glowFire,
					pulse * power * (day ? 0.55 : 0.85), day);
			if (L.den.large) {
				light(out, parts.lanternX, parts.lanternY, b * 2.4, P.flame[1], s.calm ? 0.55 : 0.5 + 0.08 * Math.sin(s.seconds * 3.1), day);
			}
		}
		if (!day) {
			FoxParticles.Fireflies f = flies;
			for (int i = 0; i < f.count; i++) {
				f.at(i, s.seconds, s.calm);
				if (f.aa > 0.35) light(out, f.ax, f.ay, 5, P.glowFly, 0.35 * f.aa, false);
			}
		}
	}

	/**
	 * Weiches rundes Licht wie ein radialer Verlauf (Mitte {@code a}, bei 40 % noch 45 %, außen 0), mit Maske und
	 * Deckkraft der Licht-Ebene; nachts „Aufhellen“ (screen), am Tag „Weiches Licht“ (soft-light).
	 */
	private void light(int[] out, double cx, double cy, double r, int c, double a, boolean day) {
		if (a <= 0.01 || r <= 0) return;
		int w = L.w;
		int h = L.h;
		int x0 = Math.max(0, (int) Math.floor(cx - r));
		int x1 = Math.min(w - 1, (int) Math.ceil(cx + r));
		int y0 = Math.max(0, (int) Math.floor(cy - r));
		int y1 = Math.min(h - 1, (int) Math.ceil(cy + r));
		int sr = (c >> 16) & 255;
		int sg = (c >> 8) & 255;
		int sb = c & 255;
		double r2 = r * r;
		float[] lut = day ? softLut(c) : null;
		for (int yy = y0; yy <= y1; yy++) {
			double dy = yy + 0.5 - cy;
			for (int xx = x0; xx <= x1; xx++) {
				double dx = xx + 0.5 - cx;
				double d2 = dx * dx + dy * dy;
				if (d2 >= r2) continue;
				double d = Math.sqrt(d2) / r;
				double prof = d < 0.4 ? 1 - 0.55 * (d / 0.4) : 0.45 * (1 - (d - 0.4) / 0.6);
				double al = a * prof * lightMask[xx];
				if (al <= 0.004) continue;
				int i = yy * w + xx;
				out[i] = day ? softLight(out[i], lut, al) : screen(out[i], sr, sg, sb, al);
			}
		}
	}

	private static int screen(int b, int sr, int sg, int sb, double a) {
		int br = (b >> 16) & 255;
		int bg = (b >> 8) & 255;
		int bb = b & 255;
		double r = sr * a;
		double g = sg * a;
		double bl = sb * a;
		int nr = (int) Math.round(br + r - br * r / 255);
		int ng = (int) Math.round(bg + g - bg * g / 255);
		int nb = (int) Math.round(bb + bl - bb * bl / 255);
		return 0xFF000000 | (clamp(nr, 0, 255) << 16) | (clamp(ng, 0, 255) << 8) | clamp(nb, 0, 255);
	}

	private static double soft(double b, double s) {
		if (s <= 0.5) return b - (1 - 2 * s) * b * (1 - b);
		double d = b <= 0.25 ? ((16 * b - 12) * b + 4) * b : Math.sqrt(b);
		return b + (2 * s - 1) * (d - b);
	}

	private final java.util.Map<Integer, float[]> softLuts = new java.util.HashMap<Integer, float[]>();

	/** „Weiches Licht“ je Kanal und Grundwert als Tabelle (Änderung in 0…255) – je Lichtfarbe einmal. */
	private float[] softLut(int c) {
		float[] lut = softLuts.get(c);
		if (lut != null) return lut;
		lut = new float[768];
		for (int ch = 0; ch < 3; ch++) {
			double s = ((c >> (16 - ch * 8)) & 255) / 255.0;
			for (int b = 0; b < 256; b++) lut[ch * 256 + b] = (float) ((soft(b / 255.0, s) - b / 255.0) * 255);
		}
		softLuts.put(c, lut);
		return lut;
	}

	private static int softLight(int b, float[] lut, double a) {
		int br = (b >> 16) & 255;
		int bg = (b >> 8) & 255;
		int bb = b & 255;
		int nr = (int) Math.round(br + lut[br] * a);
		int ng = (int) Math.round(bg + lut[256 + bg] * a);
		int nb = (int) Math.round(bb + lut[512 + bb] * a);
		return 0xFF000000 | (clamp(nr, 0, 255) << 16) | (clamp(ng, 0, 255) << 8) | clamp(nb, 0, 255);
	}
}

package dev.theredstonee.trsclient.core.ui.winter;

import java.util.List;

import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.bayer;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.clamp;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.mix;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.noise;
import static dev.theredstonee.trsclient.core.ui.winter.WinterMath.smooth;

/**
 * Malt die Winterlandschaft in Texeln (Port von {@code app/utils/winter/paint.ts}). Himmel und Landschaft entstehen
 * einmal je Aufbau/Farbschema als fertige Ebenen; je Takt setzt {@link #compose} daraus das Bild zusammen und malt nur
 * Polarlicht, Funkeln, Hütte mit Pinguin, Rauch, Schnee und das weiche Licht neu. Weiche Verläufe sind gerastert
 * (4 × 4 Bayer) – so bleibt alles Pixel-Art.
 */
public final class WinterPainter {
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

		int get(int x, int y) {
			return px[y * w + x];
		}
	}

	public final Landscape L;
	public final WinterPalette P;
	final int[] sky;
	final int[] land;
	private final Raster[] clouds;
	/** Weiches Licht, gepackt: A = Deckkraft, RGB = vormultipliziert (Maske/Deckkraft eingerechnet). */
	private final int[] glow;
	private String glowKey = "";

	public WinterPainter(Landscape L, WinterPalette P) {
		this.L = L;
		this.P = P;
		this.sky = paintSky(L, P).px;
		this.land = paintLand(L, P).px;
		boolean light = P.variant == WinterPalette.LIGHT;
		this.clouds = new Raster[light ? L.clouds.size() : 0];
		for (int i = 0; i < clouds.length; i++) clouds[i] = paintCloud(L.clouds.get(i), P);
		this.glow = new int[L.w * L.h];
	}

	// --- Himmel -------------------------------------------------------------------

	static Raster paintSky(Landscape L, WinterPalette P) {
		Raster R = new Raster(L.w, L.h);
		int bands = P.sky.length - 1;
		int end = Math.max(4, L.horizon);
		boolean light = P.variant == WinterPalette.LIGHT;
		for (int y = 0; y < L.h; y++) {
			double pos = clamp((y / (double) end) * bands, 0, bands);
			int i = Math.min(bands - 1, (int) Math.floor(pos));
			double f = clamp((pos - i - 0.5) * 3.2 + 0.5, 0, 1);
			for (int x = 0; x < L.w; x++) R.set(x, y, f > bayer(x, y) ? P.sky[i + 1] : P.sky[i]);
		}
		// Hof um Mond/Sonne: zwei gerasterte Ringe.
		int mx = L.moonX;
		int my = L.moonY;
		int r = L.moonR;
		int reach = (int) Math.round(r * (light ? 4.2 : 3.2));
		for (int y = Math.max(0, my - reach); y < Math.min(L.h, my + reach); y++) {
			for (int x = Math.max(0, mx - reach); x < Math.min(L.w, mx + reach); x++) {
				double d = Math.hypot(x - mx, y - my);
				if (d <= r || d >= reach) continue;
				double t = 1 - (d - r) / (reach - r);
				double q = Math.floor(t * t * 3 + bayer(x, y)) / 3;
				if (q <= 0) continue;
				R.set(x, y, mix(R.get(x, y), P.halo, q * (light ? 0.4 : 0.16)));
			}
		}
		if (!light) {
			for (Landscape.Star s : L.stars) {
				R.set(s.x, s.y, s.bright ? P.star : P.starDim);
				if (s.big) {
					R.set(s.x + 1, s.y, P.starDim);
					R.set(s.x - 1, s.y, P.starDim);
					R.set(s.x, s.y + 1, P.starDim);
					R.set(s.x, s.y - 1, P.starDim);
				}
			}
		}
		paintMoon(R, L, P);
		return R;
	}

	private static boolean inMoon(int dx, int dy, int r) {
		return dx * dx + dy * dy <= r * r + r * 0.8;
	}

	private static void paintMoon(Raster R, Landscape L, WinterPalette P) {
		int mx = L.moonX;
		int my = L.moonY;
		int r = L.moonR;
		boolean light = P.variant == WinterPalette.LIGHT;
		for (int dy = -r - 1; dy <= r + 1; dy++) {
			for (int dx = -r - 1; dx <= r + 1; dx++) {
				if (!inMoon(dx, dy, r)) continue;
				// Rand unten links dunkler, dazu eine schmale Schattensichel.
				boolean rim = !inMoon(dx - 1, dy, r) || !inMoon(dx, dy + 1, r);
				boolean shade = !light && -dx + dy > r * 1.05;
				R.set(mx + dx, my + dy, rim || shade ? P.moonShade : P.moon);
			}
		}
		if (light) return;
		double[][] craters = {{-0.35, -0.3, 1}, {0.3, 0.15, r >= 7 ? 2 : 1}, {-0.1, 0.45, 1}, {0.45, -0.4, 1}};
		for (double[] cr : craters) {
			int ox = (int) Math.round(mx + cr[0] * r);
			int oy = (int) Math.round(my + cr[1] * r);
			int rr = (int) cr[2];
			for (int dy = -rr + 1; dy < rr; dy++) {
				for (int dx = -rr + 1; dx < rr; dx++) if (inMoon(ox + dx - mx, oy + dy - my, r)) R.set(ox + dx, oy + dy, P.moonCrater);
			}
			if (rr == 1) R.set(ox, oy, P.moonCrater);
		}
	}

	// --- Landschaft ----------------------------------------------------------------

	private static int hazed(int c, int haze, double t) {
		return t <= 0 ? c : mix(c, haze, t);
	}

	/** Verlauf in {@code steps} flächigen Stufen, nur die schmalen Übergänge gerastert. */
	private static double banded(double t, int steps, int x, int y) {
		double q = clamp(t, 0, 1) * steps;
		int i = (int) Math.floor(q);
		double f = clamp((q - i - 0.5) * 4 + 0.5, 0, 1);
		return Math.min(steps, i + (f > bayer(x, y) ? 1 : 0)) / (double) steps;
	}

	static Raster paintLand(Landscape L, WinterPalette P) {
		Raster R = new Raster(L.w, L.h);
		paintDomes(R, L, L.mountainsBack, P, 0.62);
		paintDomes(R, L, L.mountains, P, 0.42);
		paintForest(R, L, P);
		for (Landscape.Pine t : L.pines) if (t.layer == 0) paintPine(R, t, P, 0.48);
		paintSnowfield(R, L, L.hills, P, 0.16, Math.max(22, (int) Math.round(L.h * 0.45)));
		for (Landscape.Pine t : L.pines) if (t.layer == 1) paintShadow(R, t, P, 0.16);
		for (Landscape.Pine t : L.pines) if (t.layer == 1) paintPine(R, t, P, 0.16);
		paintSnowfield(R, L, L.ground, P, 0, 34);
		for (int[] t : L.tufts) paintTuft(R, t[0], t[1], t[2], P);
		for (Landscape.Pine t : L.pines) if (t.layer == 2) paintShadow(R, t, P, 0);
		for (Landscape.Pine t : L.pines) if (t.layer == 2) paintPine(R, t, P, 0);
		if (L.hasCabin()) paintCabin(R, L.cabinX, L.cabinTop(), P);
		return R;
	}

	private static int at(short[] line, int x) {
		return line[clamp(x, 0, line.length - 1)];
	}

	/** Bergkette: Schnee über einer gezackten Schneegrenze, darunter Fels mit verschneiten Rinnen; Licht von rechts oben. */
	private static void paintDomes(Raster R, Landscape L, short[] line, WinterPalette P, double haze) {
		int w = L.w;
		int horizon = L.horizon;
		int s = (L.seed & 0xffff) + (int) Math.round(haze * 100);
		int peak = horizon;
		for (int x = 0; x < w; x++) peak = Math.min(peak, at(line, x));
		for (int x = 0; x < w; x++) {
			int top = at(line, x);
			if (top >= horizon) continue;
			int slope = at(line, x + 2) - at(line, x - 2);
			boolean lit = slope > 0 || (slope == 0 && at(line, x + 5) >= at(line, x - 5));
			int snowline = (int) Math.round(horizon - (horizon - peak) * 0.45 + 2.5 * (smooth(x / 3.0, s) - 0.5));
			boolean gully = smooth(x / 1.7, s + 3) > 0.72;
			for (int y = top; y <= horizon + 2 && y < L.h; y++) {
				if (y < 0) continue;
				int depth = y - top;
				int c;
				if (depth == 0) c = lit ? P.snowLit : P.snow;
				else if (y < snowline) c = lit ? P.snow : P.snowShade;
				else if (y == snowline && bayer(x, y) < 0.5) c = lit ? P.snowShade : P.snowDeep;
				else if (gully && y < snowline + (horizon - snowline) * 0.6 && bayer(x, y) < 0.6) c = lit ? P.snowShade : P.snowDeep;
				else c = lit ? P.rock : P.rockShade;
				double mist = banded((y - (horizon - L.mountainAmp * 0.5)) / (L.mountainAmp * 0.5), 2, x, y);
				R.set(x, y, hazed(c, P.haze, (haze + 0.22 * mist) * P.hazeScale));
			}
		}
	}

	private static void paintForest(Raster R, Landscape L, WinterPalette P) {
		int maxHill = 0;
		for (short v : L.hills) maxHill = Math.max(maxHill, v);
		int bottom = Math.min(L.h - 1, maxHill + 2);
		for (int x = 0; x < L.w; x++) {
			int top = L.forest[x];
			for (int y = Math.max(0, top); y <= bottom; y++) {
				int depth = y - top;
				int c = noise(x, y, 37) < 0.18 ? P.pine : P.pineDark;
				if (depth == 0) c = noise(x, 0, 39) < 0.55 ? P.snowShade : P.pine;
				else if (depth == 1 && noise(x, 1, 39) < 0.3) c = P.snowDeep;
				R.set(x, y, hazed(c, P.haze, 0.5 * P.hazeScale));
			}
		}
	}

	/** Verschneite Fläche (Ebene oder Vordergrund) mit Oberkante, Stufen-Verlauf und Verwehungen. */
	private static void paintSnowfield(Raster R, Landscape L, short[] line, WinterPalette P, double haze, int fade) {
		int[] tones = {P.snowLit, P.snow, P.snowShade, P.snowDeep};
		int s = (L.seed & 0xffff) + fade;
		for (int x = 0; x < L.w; x++) {
			int top = at(line, x);
			int slope = at(line, x + 4) + at(line, x + 2) - at(line, x - 2) - at(line, x - 4);
			boolean lit = slope >= 3;
			boolean shaded = slope <= -3;
			for (int y = Math.max(0, top); y < L.h; y++) {
				int depth = y - top;
				int c;
				if (depth < 2 || (lit && depth < 4)) {
					c = shaded ? P.snow : P.snowLit;
				} else {
					int step = (int) Math.round(banded((depth - 2) / (double) fade, 2, x, y) * 2);
					int k = 1 + step;
					double rib = noise((x + (int) Math.round(4 * smooth(y / 3.0, s))) >> 2, y, s);
					if (rib < 0.05) k = Math.max(0, k - 1);
					else if (rib > 0.965) k = Math.min(3, k + 1);
					c = tones[clamp(k, 0, 3)];
				}
				R.set(x, y, hazed(c, P.haze, haze * P.hazeScale));
			}
		}
	}

	/** Schatten einer Tanne auf dem Schnee, nach links. */
	private static void paintShadow(Raster R, Landscape.Pine t, WinterPalette P, double haze) {
		int len = (int) Math.round(t.h * 0.45);
		int y = t.base - 1;
		for (int k = 0; k < len; k++) {
			int x = t.x - k;
			if (x < 0 || x >= R.w) continue;
			double fade = k / (double) len;
			if (fade > 0.5 && fade - 0.5 > bayer(x, y) * 0.5) continue;
			R.set(x, y, hazed(P.snowShade, P.haze, haze));
			if (k < len * 0.6) R.set(x, y + 1, hazed(P.snowShade, P.haze, haze));
		}
	}

	/** Tanne mit Etagen, Schnee auf jedem Absatz, Licht von rechts. */
	static void paintPine(Raster R, Landscape.Pine t, WinterPalette P, double haze) {
		int cx = t.x;
		int base = t.base;
		int h = t.h;
		boolean detail = t.layer > 0;
		int trunkH = Math.max(1, (int) Math.round(h * (detail ? 0.1 : 0.14)));
		int top = base - h;
		int crownH = h - trunkH;
		if (crownH < 2) return;
		double maxHalf = Math.max(1, h * (detail ? 0.25 : 0.3));
		int tiers = clamp((int) Math.round(crownH / (detail ? 6.5 : 3.5)), 2, 12);
		int[] half = new int[crownH];
		boolean[] ledge = new boolean[crownH];
		int s = t.seed & 0xffff;
		for (int i = 0; i < crownH; i++) {
			double best = 0;
			boolean isLedge = false;
			for (int k = 0; k < tiers; k++) {
				int ts = (int) Math.floor(((crownH * (double) k) / tiers) * 0.9);
				int te = (int) Math.floor((crownH * (double) (k + 1)) / tiers);
				if (i < ts || i > te) continue;
				double local = (i - ts) / (double) Math.max(1, te - ts);
				double width = (0.22 + 0.78 * local) * maxHalf * (0.3 + (0.7 * (k + 1)) / tiers);
				if (width >= best) {
					best = width;
					isLedge = i == te;
				}
			}
			int hw = (int) Math.round(best);
			if (detail && hw > 2 && noise(i, s, 41) < 0.25) hw -= 1;
			half[i] = i == 0 ? 0 : hw;
			ledge[i] = isLedge;
		}
		double hz = haze > 0 ? haze * P.hazeScale : 0;
		int tw = h >= 40 ? 2 : 1;
		for (int y = base - trunkH - 1; y < base; y++) {
			for (int k = 0; k < tw; k++) {
				int c = k == tw - 1 && tw > 1 ? mix(P.trunk, P.wood, 0.5) : P.trunk;
				R.set(cx - (tw > 1 ? 1 : 0) + k, y, hazed(c, P.haze, hz));
			}
		}
		for (int i = 0; i < crownH; i++) {
			int y = top + i;
			int hw = half[i];
			int above = i > 0 ? half[i - 1] : -1;
			int above2 = i > 1 ? half[i - 2] : -1;
			for (int dx = -hw; dx <= hw; dx++) {
				int x = cx + dx;
				int ad = Math.abs(dx);
				int c;
				if (ad > above) {
					c = dx > hw * 0.2 ? P.snowLit : (dx < -hw * 0.45 ? P.snowShade : P.snow);
				} else if (detail && ad > above2 && noise(x, y, 43) < 0.6) {
					c = dx > 0 ? P.snow : P.snowShade;
				} else if (!detail) {
					c = dx > 0 && ad >= hw - 1 ? P.pine : P.pineDark;
				} else {
					double n = noise(x, y, 47);
					c = dx > hw * 0.35 ? P.pineLit : (dx < -hw * 0.25 ? P.pineDark : P.pine);
					if (n < 0.14) c = c == P.pineLit ? P.pine : P.pineDark;
					else if (n > 0.965) c = dx > 0 ? P.snow : P.snowShade;
				}
				R.set(x, y, hazed(c, P.haze, hz));
			}
			if (detail && ledge[i] && hw > 2) {
				R.set(cx - hw - 1, y + 1, hazed(P.pineDark, P.haze, hz));
				R.set(cx + hw + 1, y + 1, hazed(P.pine, P.haze, hz));
				R.set(cx + hw + 1, y, hazed(P.snow, P.haze, hz));
			}
		}
	}

	/**
	 * Große Blockhütte vorn: dickes Schneedach mit Eiszapfen, Schornstein, Stammwände mit Balkenköpfen, zwei warm
	 * erleuchtete Fenster, Tür und eine Laterne. Fenster und Tür malt der Takt darüber neu (Pinguin).
	 */
	static void paintCabin(Raster R, int x0, int oy, WinterPalette P) {
		int H = Landscape.CABIN_H;
		int woodHi = mix(P.wood, P.snowLit, 0.18);
		int logEnd = mix(P.wood, P.snowLit, 0.32);
		// Schornstein aus Feldsteinen (hinter dem Dach).
		for (int ry = 1; ry <= 10; ry++) {
			for (int rx = 31; rx <= 35; rx++) {
				boolean mortar = ry % 3 == 0 || (rx + (ry % 6 < 3 ? 0 : 2)) % 4 == 0;
				R.set(x0 + rx, oy + ry, mortar ? P.rockShade : (rx == 35 ? P.rockShade : P.rock));
			}
		}
		for (int rx = 30; rx <= 36; rx++) R.set(x0 + rx, oy, rx == 30 || rx == 36 ? P.snow : P.snowLit);
		// Wände: Stämme zu je drei Zeilen, Balkenköpfe an den Ecken.
		for (int ry = 16; ry < H; ry++) {
			int k = (ry - 16) % 3;
			for (int rx = 4; rx <= 41; rx++) R.set(x0 + rx, oy + ry, k == 0 ? woodHi : (k == 1 ? P.wood : P.woodDark));
			if (k == 1) {
				R.set(x0 + 3, oy + ry, logEnd);
				R.set(x0 + 42, oy + ry, logEnd);
				R.set(x0 + 2, oy + ry, P.woodDark);
				R.set(x0 + 43, oy + ry, P.woodDark);
			}
		}
		// Fenster links (groß) mit Rahmen, Glanz und verschneitem Sims.
		for (int ry = 20; ry <= 28; ry++) {
			for (int rx = 8; rx <= 18; rx++) {
				boolean frame = ry == 20 || ry == 28 || rx == 8 || rx == 18;
				R.set(x0 + rx, oy + ry, frame ? P.windowFrame : P.windowOn);
			}
		}
		windowLight(R.px, R.w, R.h, x0, oy, Landscape.WINDOWS[0], P);
		for (int rx = 7; rx <= 19; rx++) {
			R.set(x0 + rx, oy + 29, rx == 7 || rx == 19 ? P.snow : P.snowLit);
			R.set(x0 + rx, oy + 30, P.woodDark);
		}
		// Fenster rechts (klein).
		for (int ry = 20; ry <= 25; ry++) {
			for (int rx = 37; rx <= 41; rx++) R.set(x0 + rx, oy + ry, ry == 20 || ry == 25 || rx == 37 || rx == 41 ? P.windowFrame : P.windowOn);
		}
		windowLight(R.px, R.w, R.h, x0, oy, Landscape.WINDOWS[1], P);
		for (int rx = 36; rx <= 42; rx++) R.set(x0 + rx, oy + 26, P.snowLit);
		door(R.px, R.w, R.h, x0, oy, P, 0);
		// Dach: dicker Schnee, rechts im Licht; die Kante hängt über die Wände.
		boolean[] ridge = new boolean[Landscape.CABIN_W];
		for (int ry = 3; ry <= 15; ry++) {
			int inset = (int) Math.round((15 - ry) * 1.75);
			for (int rx = inset; rx <= 45 - inset; rx++) {
				int c = rx > 23 ? P.snowLit : P.snow;
				if (rx == inset || rx == inset + 1) c = P.snowShade;
				if (ry >= 14) c = ry == 15 ? P.snowDeep : P.snowShade;
				if (!ridge[rx] && ry < 13) c = rx > 22 ? P.sparkle : P.snowLit;
				ridge[rx] = true;
				R.set(x0 + rx, oy + ry, c);
			}
		}
		// Traufe, dann Eiszapfen in verschiedenen Längen.
		for (int rx = 2; rx <= 43; rx++) R.set(x0 + rx, oy + 16, P.woodDark);
		int[][] icicles = {{2, 3}, {5, 1}, {9, 2}, {14, 1}, {20, 3}, {25, 1}, {30, 2}, {36, 1}, {40, 3}, {43, 2}};
		for (int[] ic : icicles) {
			for (int k = 0; k < ic[1]; k++) R.set(x0 + ic[0], oy + 16 + k, k == ic[1] - 1 ? P.snowShade : P.snowLit);
		}
		// Laterne neben der Tür (warmes Licht) auf einem Pfosten.
		int lx = Landscape.LANTERN_X;
		int ly = Landscape.LANTERN_Y;
		for (int ry = ly + 3; ry < H; ry++) R.set(x0 + lx, oy + ry, P.woodDark);
		for (int rx = lx - 2; rx <= lx + 2; rx++) R.set(x0 + rx, oy + ly - 2, P.windowFrame);
		for (int rx = lx - 1; rx <= lx + 1; rx++) {
			R.set(x0 + rx, oy + ly - 3, P.snowLit);
			R.set(x0 + rx, oy + ly + 2, P.windowFrame);
		}
		int hot = mix(P.windowOn, 0xFFFFFFFF, 0.6);
		for (int ry = ly - 1; ry <= ly + 1; ry++) {
			R.set(x0 + lx - 2, oy + ry, P.windowFrame);
			R.set(x0 + lx + 2, oy + ry, P.windowFrame);
			for (int rx = lx - 1; rx <= lx + 1; rx++) R.set(x0 + rx, oy + ry, rx == lx && ry == ly ? hot : P.windowOn);
		}
		// Schneewehen an den Ecken und vor der Wand (vor der Tür bleibt der Schnee unberührt – kein Weg).
		for (int rx = 0; rx <= 45; rx++) {
			boolean corner = rx < 6 || rx > 39;
			if (corner) {
				int hgt = rx < 6 ? 6 - rx : rx - 39;
				int n = Math.min(4, hgt);
				for (int k = 0; k < n; k++) R.set(x0 + rx, oy + H - 1 - k, k == n - 1 ? P.snowLit : P.snow);
			} else if (rx < Landscape.DOOR.x0 || rx > Landscape.DOOR.x1) {
				R.set(x0 + rx, oy + H - 1, P.snowLit);
			}
		}
	}

	private static void put(int[] px, int w, int h, int x, int y, int c) {
		if (x < 0 || y < 0 || x >= w || y >= h) return;
		px[y * w + x] = c;
	}

	/** Fensterglas (ohne Sprossen, damit man den Pinguin sieht) mit Glanz oben links. */
	private static void windowLight(int[] px, int w, int h, int x0, int oy, Landscape.Rect g, WinterPalette P) {
		int hi = mix(P.windowOn, 0xFFFFFFFF, 0.35);
		int mx = (int) Math.round((g.x0 + g.x1) / 2.0);
		for (int ry = g.y0; ry <= g.y1; ry++) {
			for (int rx = g.x0; rx <= g.x1; rx++) put(px, w, h, x0 + rx, oy + ry, ry == g.y0 && rx < mx ? hi : P.windowOn);
		}
	}

	/** Tür: 0 zu, 1 halb, 2 offen (dann warmes Licht von innen). */
	private static void door(int[] px, int w, int h, int x0, int oy, WinterPalette P, int open) {
		Landscape.Rect d = Landscape.DOOR;
		int width = d.x1 - d.x0 - 1;
		for (int ry = d.y0; ry <= d.y1; ry++) {
			int inside = mix(P.windowOn, P.woodDark, ry > d.y1 - 2 ? 0.6 : 0.35);
			for (int rx = d.x0; rx <= d.x1; rx++) {
				boolean frame = rx == d.x0 || rx == d.x1 || ry == d.y0;
				if (frame) {
					put(px, w, h, x0 + rx, oy + ry, P.windowFrame);
					continue;
				}
				int leaf = rx - d.x0 - 1;
				int c;
				if (open == 0) c = leaf % 2 == 0 ? P.wood : P.woodDark;
				else if (open == 1) c = leaf < width / 2.0 ? inside : (leaf % 2 == 0 ? P.wood : P.woodDark);
				else c = leaf >= width - 1 ? P.woodDark : inside;
				put(px, w, h, x0 + rx, oy + ry, c);
			}
		}
		if (open == 0) {
			// Kleines Fenster in der Tür und Klinke.
			put(px, w, h, x0 + d.x0 + 3, oy + d.y0 + 2, P.windowOn);
			put(px, w, h, x0 + d.x0 + 4, oy + d.y0 + 2, P.windowOn);
			put(px, w, h, x0 + d.x0 + 3, oy + d.y0 + 3, P.windowOn);
			put(px, w, h, x0 + d.x0 + 4, oy + d.y0 + 3, P.windowOn);
			put(px, w, h, x0 + d.x1 - 2, oy + d.y0 + 7, mix(P.windowOn, 0xFFFFFFFF, 0.3));
		}
	}

	private static void paintTuft(Raster R, int x, int y, int h, WinterPalette P) {
		int c = mix(P.trunk, P.snowShade, 0.35);
		for (int k = 1; k <= h; k++) R.set(x, y - k, c);
		int d = mix(c, P.snowDeep, 0.4);
		for (int k = 1; k < h; k++) R.set(x + 1, y - k, d);
		R.set(x - 1, y - 1, c);
	}

	/** Wolke als kleine Fläche (nur am Tag). */
	static Raster paintCloud(Landscape.Cloud c, WinterPalette P) {
		Raster R = new Raster(c.w, c.h);
		int bumps = 3 + Math.floorMod(c.seed, 3);
		for (int y = 0; y < c.h; y++) {
			for (int x = 0; x < c.w; x++) {
				boolean inside = y >= c.h * 0.45 && x >= 1 && x < c.w - 1;
				for (int k = 0; k < bumps && !inside; k++) {
					double bx = ((k + 0.5) / bumps) * c.w;
					double br = c.h * (0.38 + noise(k, 0, c.seed & 0xffff) * 0.25);
					double by = c.h - br;
					double ddx = x - bx;
					double ddy = (y - by) * 1.15;
					inside = ddx * ddx + ddy * ddy <= br * br;
				}
				if (!inside) continue;
				boolean shade = y >= c.h - 2 || (y > c.h * 0.6 && x < c.w * 0.3);
				R.set(x, y, shade ? P.cloudShade : P.cloud);
			}
		}
		return R;
	}

	// --- Bild je Takt -------------------------------------------------------------

	/** Zustand eines Bildes. */
	public static final class Frame {
		public int tick;
		public double seconds;
		/** Polarlicht 0…1. */
		public double aurora;
		public Penguin penguin;
		public Snow.Fall flakes;
		public Snow.Smoke smoke;
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
		boolean light = P.variant == WinterPalette.LIGHT;
		System.arraycopy(sky, 0, out, 0, n);

		// Wolken ziehen langsam nach links (nur am Tag).
		for (int i = 0; i < clouds.length; i++) {
			Landscape.Cloud c = L.clouds.get(i);
			Raster img = clouds[i];
			double span = w + c.w * 2;
			int x = (int) Math.floor((((c.x + c.w - s.tick * c.speed) % span) + span) % span) - c.w;
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

		// Funkelnde Sterne.
		if (!light) {
			int phase = s.tick / 5;
			List<Landscape.Star> stars = L.stars;
			for (int i = 0; i < stars.size(); i++) {
				Landscape.Star st = stars.get(i);
				if (!st.twinkle) continue;
				double nz = noise(i, phase, 61);
				if (nz < 0.3) {
					setPx(out, w, h, st.x, st.y, P.starDim);
				} else if (nz > 0.86) {
					setPx(out, w, h, st.x - 1, st.y, P.starDim);
					setPx(out, w, h, st.x + 1, st.y, P.starDim);
					setPx(out, w, h, st.x, st.y - 1, P.starDim);
					setPx(out, w, h, st.x, st.y + 1, P.starDim);
					setPx(out, w, h, st.x, st.y, P.star);
				}
			}
			if (s.aurora > 0.02) aurora(out, s.seconds, s.aurora);
		}

		// Landschaft darüber (deckend, wo gemalt).
		for (int i = 0; i < n; i++) {
			int v = land[i];
			if (v != 0) out[i] = v;
		}

		// Der Pinguin: im Fenster, in der Tür oder draußen.
		if (s.penguin != null && L.hasCabin()) cabinLive(out, s.penguin);

		// Glitzern im Schnee.
		int sp = s.tick / 2;
		for (int i = 0; i < L.sparkles.length; i++) {
			if (noise(i, sp, 71) > 0.035) continue;
			setPx(out, w, h, L.sparkles[i][0], L.sparkles[i][1], P.sparkle);
		}

		// Rauch aus dem Schornstein.
		Snow.Smoke sm = s.smoke;
		if (sm != null) {
			for (int i = 0; i < sm.count; i++) {
				double age = 1 - sm.life[i] / (double) sm.max[i];
				if (age > 0.55 && age - 0.55 > bayer((int) sm.x[i], (int) sm.y[i]) * 0.45) continue;
				int size = age < 0.25 ? 1 : (age < 0.6 ? 2 : 3);
				rect(out, w, h, (int) Math.round(sm.x[i]), (int) Math.round(sm.y[i]), size, size - (size > 1 ? 1 : 0), P.smoke);
			}
		}

		// Schneefall: fern (blass), mittel, nah (2 × 2).
		Snow.Fall f = s.flakes;
		if (f != null) {
			for (int i = 0; i < f.count; i++) {
				int x = (int) Math.round(f.x[i]);
				int y = (int) Math.round(f.y[i]);
				int size = f.size[i];
				if (size == 0) {
					setPx(out, w, h, x, y, P.flakeFar);
				} else if (size == 1) {
					setPx(out, w, h, x, y, P.flake);
				} else {
					setPx(out, w, h, x, y, P.flake);
					setPx(out, w, h, x + 1, y, P.flake);
					setPx(out, w, h, x, y + 1, P.flake);
					setPx(out, w, h, x + 1, y + 1, P.flakeFar);
				}
			}
		}

		// Weiches Licht (Mond, Fenster, Laterne, offene Tür, Polarlicht-Schein).
		updateGlow(s);
		applyGlow(out, light);
	}

	/** Fenster mit Pinguin, Tür und Pinguin draußen – je Takt über die Landschaft. */
	private void cabinLive(int[] out, Penguin v) {
		int w = L.w;
		int h = L.h;
		int x0 = L.cabinX;
		int oy = L.cabinTop();
		Landscape.Rect win = Landscape.WINDOWS[0];
		if (v.phase() == Penguin.WINDOW) {
			windowLight(out, w, h, x0, oy, win, P);
			for (int[] p : Penguin.pixels(0, -1)) {
				int rx = v.x() + p[0];
				int ry = win.y0 + p[1] + v.dip();
				if (ry > win.y1 || rx < win.x0 || rx > win.x1) continue;
				put(out, w, h, x0 + rx, oy + ry, p[2]);
			}
		}
		if (v.door() != 0) door(out, w, h, x0, oy, P, v.door());
		if (v.outside()) {
			boolean walking = v.phase() == Penguin.WALK_OUT || v.phase() == Penguin.WALK_IN;
			int bob = walking && v.stepFrame() == 1 ? 1 : 0;
			for (int[] p : Penguin.pixels(v.stepFrame(), v.wave())) {
				put(out, w, h, x0 + v.x() + p[0], oy + Landscape.CABIN_H - Penguin.H + p[1] - bob, p[2]);
			}
		}
	}

	private static double smoothstep(double a, double b, double v) {
		double t = clamp((v - a) / (b - a), 0, 1);
		return t * t * (3 - 2 * t);
	}

	/** exp(-2.4·v) für v = 0…1 in 512 Stufen (je Pixel gebraucht – so ohne Math.exp). */
	private static final float[] FALL = new float[513];

	static {
		for (int i = 0; i <= 512; i++) FALL[i] = (float) Math.exp(-2.4 * i / 512.0);
	}

	private byte[] auroraLevel;
	private byte[] auroraColor;

	/**
	 * Polarlicht wie echt: Vorhänge mit scharfer, hellgrüner Unterkante, darüber grüne Strahlen, die nach oben
	 * ausbleichen und in Violett übergehen; Falten wandern langsam. Wird direkt in den Himmel gemischt.
	 */
	private void aurora(int[] out, double t, double level) {
		int W = L.w;
		int H = Math.max(1, Math.min(L.h, L.horizon));
		double sky = Math.max(10, L.horizon - L.mountainAmp * 0.45);
		double maxA = P.variant == WinterPalette.OLED ? 0.95 : 0.88;
		int s = (L.seed & 0xffff) + 51;
		int[] cols = P.aurora;
		// Deckkraft je Stufe (0…6) als 0…255.
		int[] alphaOf = new int[7];
		for (int q = 1; q <= 6; q++) alphaOf[q] = (int) Math.round(Math.min(1, q / 6.0) * maxA * 255);
		// Zwei Vorhänge: vorn kräftig, hinten höher und blasser; je Pixel zählt die stärkere Stufe.
		double[][] curtains = {{0.74, 0.5, 0, 1}, {0.5, 0.38, 2.1, 0.55}};
		if (auroraLevel == null || auroraLevel.length < H) {
			auroraLevel = new byte[H];
			auroraColor = new byte[H];
		}
		byte[] lv = auroraLevel;
		byte[] cl = auroraColor;
		for (int x = 0; x < W; x++) {
			double u = x / (double) W;
			double across = 0.3 + 0.7 * smoothstep(0.08, 0.4, u);
			int lo = H;
			int hi = -1;
			for (double[] c : curtains) {
				double yb = sky * (c[0] + 0.07 * Math.sin(x * 0.016 + t * 0.07 + c[2]) + 0.04 * Math.sin(x * 0.047 - t * 0.12 + c[2] * 2));
				double hc = sky * c[1] * (0.75 + 0.35 * smooth(x * 0.02 + t * 0.03, s + (int) (c[2] * 10)));
				double fold = 0.55 + 0.45 * Math.pow(Math.abs(Math.sin(x * 0.034 + t * 0.11 + c[2])), 0.6);
				double ray = 0.25 + 0.75 * Math.pow(smooth(x * 0.42 + t * 0.6, s + 3), 1.6);
				double shimmer = 0.85 + 0.15 * Math.sin(t * 2.3 + x * 0.9);
				double I = level * across * fold * ray * shimmer * c[3];
				if (I < 0.05) continue;
				int y0 = Math.max(0, (int) Math.floor(yb - hc));
				int y1 = Math.min(H - 1, (int) Math.ceil(yb + 2));
				double inv = 1 / hc;
				for (int y = y0; y <= y1; y++) {
					double v = (yb - y) * inv;
					double a;
					if (v < 0) a = Math.max(0, 1 + v * (hc / 2)) * 0.6;
					else a = FALL[Math.min(512, (int) (v * 512))] * (v < 0.05 ? 1.15 : 1);
					int q = (int) (a * I * 6 + 0.5);
					if (q <= 0) continue;
					if (q > 6) q = 6;
					if (y < lo) lo = y;
					if (y > hi) hi = y;
					if (q <= lv[y]) continue;
					lv[y] = (byte) q;
					cl[y] = (byte) (v < 0.06 ? 0 : (v < 0.42 ? 1 : (v < 0.72 ? 2 : 3)));
				}
			}
			for (int y = lo; y <= hi; y++) {
				int q = lv[y];
				if (q <= 0) continue;
				lv[y] = 0;
				int al = alphaOf[q];
				int col = cols[cl[y]];
				int i = y * W + x;
				int d = out[i];
				int r = (((col >> 16) & 255) * al + ((d >> 16) & 255) * (255 - al)) / 255;
				int g = (((col >> 8) & 255) * al + ((d >> 8) & 255) * (255 - al)) / 255;
				int b = ((col & 255) * al + (d & 255) * (255 - al)) / 255;
				out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
	}

	// --- Weiches Licht -------------------------------------------------------------

	private float[] gr;
	private float[] gg;
	private float[] gb;
	private float[] ga;

	/** Licht neu berechnen, wenn sich etwas ändert (Polarlicht-Stärke, Hüttentür). */
	private void updateGlow(Frame s) {
		int door = s.penguin != null && L.hasCabin() ? s.penguin.door() : 0;
		double aur = P.variant == WinterPalette.LIGHT ? 0 : Math.round(s.aurora * 50) / 50.0;
		String key = door + ":" + aur;
		if (key.equals(glowKey)) return;
		glowKey = key;
		int w = L.w;
		int h = L.h;
		int n = w * h;
		if (gr == null) {
			gr = new float[n];
			gg = new float[n];
			gb = new float[n];
			ga = new float[n];
		} else {
			java.util.Arrays.fill(gr, 0);
			java.util.Arrays.fill(gg, 0);
			java.util.Arrays.fill(gb, 0);
			java.util.Arrays.fill(ga, 0);
		}
		boolean light = P.variant == WinterPalette.LIGHT;
		double dayFactor = light ? 0.5 : 1;
		light(L.moonX, L.moonY, L.moonR * 6, P.glowMoon, 0.3 * dayFactor);
		if (L.hasCabin()) {
			int oy = L.cabinTop();
			for (Landscape.Rect win : Landscape.WINDOWS) {
				light(L.cabinX + (win.x0 + win.x1) / 2.0, oy + (win.y0 + win.y1) / 2.0, 14, P.glowWarm, 0.5);
			}
			light(L.cabinX + Landscape.LANTERN_X, oy + Landscape.LANTERN_Y, 12, P.glowWarm, 0.6);
			if (door > 0) {
				Landscape.Rect d = Landscape.DOOR;
				light(L.cabinX + (d.x0 + d.x1) / 2.0, oy + d.y1 - 3, 16, P.glowWarm, door == 2 ? 0.6 : 0.35);
			}
		}
		if (aur > 0.02) {
			// Grüner Schein über dem Horizont.
			double skyH = L.horizon - L.mountainAmp * 0.45;
			double a0 = skyH * 0.2;
			double a1 = skyH * 0.8;
			int c = P.aurora[1];
			int yEnd = Math.min(h, (int) Math.ceil(skyH * 0.9));
			for (int y = 0; y < yEnd; y++) {
				double f = (y - a0) / (a1 - a0);
				if (f <= 0 || f >= 1) continue;
				double a = (f < 0.7 ? f / 0.7 : (1 - f) / 0.3) * 0.22 * aur;
				for (int x = 0; x < w; x++) over(y * w + x, c, a);
			}
		}
		// Packen: Deckkraft der Licht-Ebene und Maske (links, hinter Schrift und Knöpfen, schwächer).
		double opacity = light ? 0.35 : 0.9;
		for (int x = 0; x < w; x++) {
			double u = x / (double) w;
			double m = u < 0.35 ? 0.3 + 0.15 * (u / 0.35) : (u < 0.62 ? 0.45 + 0.55 * ((u - 0.35) / 0.27) : 1);
			double f = opacity * m;
			for (int y = 0; y < h; y++) {
				int i = y * w + x;
				float a = ga[i];
				if (a <= 0.002f) {
					glow[i] = 0;
					continue;
				}
				int A = clamp((int) Math.round(a * f * 255), 0, 255);
				int R = clamp((int) Math.round(gr[i] * f * 255), 0, 255);
				int G = clamp((int) Math.round(gg[i] * f * 255), 0, 255);
				int B = clamp((int) Math.round(gb[i] * f * 255), 0, 255);
				glow[i] = (A << 24) | (R << 16) | (G << 8) | B;
			}
		}
	}

	/** Quelle-über-Ziel in die vormultiplizierte Licht-Ebene. */
	private void over(int i, int c, double a) {
		if (a <= 0) return;
		float af = (float) a;
		float k = 1 - af;
		gr[i] = ((c >> 16) & 255) / 255f * af + gr[i] * k;
		gg[i] = ((c >> 8) & 255) / 255f * af + gg[i] * k;
		gb[i] = (c & 255) / 255f * af + gb[i] * k;
		ga[i] = af + ga[i] * k;
	}

	/** Weiches rundes Licht: Deckkraft fällt linear zum Rand. */
	private void light(double x, double y, double r, int c, double a) {
		int w = L.w;
		int h = L.h;
		int x0 = Math.max(0, (int) Math.floor(x - r));
		int x1 = Math.min(w - 1, (int) Math.ceil(x + r));
		int y0 = Math.max(0, (int) Math.floor(y - r));
		int y1 = Math.min(h - 1, (int) Math.ceil(y + r));
		for (int yy = y0; yy <= y1; yy++) {
			for (int xx = x0; xx <= x1; xx++) {
				double d = Math.hypot(xx + 0.5 - x, yy + 0.5 - y);
				if (d >= r) continue;
				over(yy * w + xx, c, a * (1 - d / r));
			}
		}
	}

	/** Licht aufs Bild: nachts „Aufhellen“ (screen), am Tag leicht „Multiplizieren“. */
	private void applyGlow(int[] out, boolean light) {
		for (int i = 0; i < out.length; i++) {
			int g = glow[i];
			if (g == 0) continue;
			int b = out[i];
			int br = (b >> 16) & 255;
			int bg = (b >> 8) & 255;
			int bb = b & 255;
			int sr = (g >> 16) & 255;
			int sg = (g >> 8) & 255;
			int sb = g & 255;
			int r;
			int gn;
			int bl;
			if (light) {
				int A = (g >>> 24) & 255;
				r = br * (255 - A + sr) / 255;
				gn = bg * (255 - A + sg) / 255;
				bl = bb * (255 - A + sb) / 255;
			} else {
				r = br + sr - br * sr / 255;
				gn = bg + sg - bg * sg / 255;
				bl = bb + sb - bb * sb / 255;
			}
			out[i] = 0xFF000000 | (clamp(r, 0, 255) << 16) | (clamp(gn, 0, 255) << 8) | clamp(bl, 0, 255);
		}
	}
}

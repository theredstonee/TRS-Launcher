package dev.theredstonee.trsclient.core.map;

/**
 * Karte v3 ({@link MapPreview}): setzt einen Bereich zum fertigen Bild zusammen – Relief aus den Höhen aller vier
 * Nachbarn (Licht von links oben, deutlich kräftiger als bisher, auch über Bereichsgrenzen), Höhentönung, Wasser mit
 * Tiefe erst beim Zeichnen (flaches Wasser zeigt den Grund mit eigenem Relief, tiefes wird über eine lange Strecke
 * dunkler und satter) und lebendigere Biomfarben. Reine Mathematik, in allen Versionen gleich; thread-sicher (der
 * Bild-Export rechnet im Kartenthread).
 *
 * <p>Gelesen werden die Daten aus {@link MapRegion}: Pixel (Farbe + Merker), Höhe, bei Wasser Tiefe und Grundfarbe.
 * Wasser aus alten Karten (ohne Tiefe) ist schon fertig gemischt und bekommt nur eine leichte Höhentönung.
 */
public final class MapShade {
	/** Stärke des Reliefs (heller zum Licht hin, dunkler im Schatten) und Sättigung (größer = später gesättigt). */
	static final float RELIEF_LIGHT = 0.42f;
	static final float RELIEF_SHADOW = 0.52f;
	static final float RELIEF_SOFT = 1.0f;
	/** Relief des Grunds unter Wasser (volle Stärke – Abhänge des Meeresgrunds bleiben lesbar). */
	static final float FLOOR_RELIEF = 0.65f;
	/** Schatten in Senken (tiefer als der höchste Nachbar, z. B. Boden zwischen Baumkronen): je Block, höchstens 6 Blöcke. */
	static final float OCCLUSION = 0.03f;
	/** Helle, leicht gesättigte Flächen (Sand, Birkenholz …): so weit zu Weiß hin aufhellen. */
	static final float LIFT = 0.32f;
	/** Laub/Pflanzen (Kartenfarbe PLANT): dunkler und satter – im Spiel sieht man durch die Lücken ins dunkle Innere. */
	static final float FOLIAGE_DARK = 0.86f;
	static final float FOLIAGE_SATURATE = 1.05f;
	/** Höhentönung: so viele Blöcke über/unter Meereshöhe bis zur Grenze. */
	static final float HEIGHT_RANGE = 170f;
	static final float HEIGHT_UP = 0.14f;
	static final float HEIGHT_DOWN = 0.16f;
	/** Wasser: Deckkraft bei 1 Block Tiefe, Strecke (Blöcke), über die es deckend wird, und Abdunklung in der Tiefe. */
	static final float WATER_ALPHA0 = 0.30f;
	static final float WATER_FADE = 13f;
	static final float WATER_DARK = 0.34f;
	static final float WATER_DARK_RANGE = 22f;
	/** Tönung der Vanilla-Kartenfarben (Gras, Pflanzen, sonstiges) – heller als die bisherigen 0,76/0,64/0,8. */
	static final float TINT_GRASS = 0.84f;
	static final float TINT_PLANT = 0.74f;
	static final float TINT_OTHER = 0.86f;
	/** Wasserfarbe aus der Biom-Tönung ohne Textur. */
	static final float WATER_TINT = 1.0f;

	private static final int PAD = MapRegion.SIZE + 2;

	/** Zwischenspeicher je Thread (Höhen mit einem Rand aus den Nachbarbereichen). */
	private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
		@Override
		protected Scratch initialValue() {
			return new Scratch();
		}
	};

	private static final class Scratch {
		/** Höhe (Oberfläche) bzw. {@link Integer#MIN_VALUE} = unbekannt. */
		final int[] h = new int[PAD * PAD];
		/** Höhe des Grunds (Wasser: Oberfläche − Tiefe; Land: Höhe). */
		final int[] f = new int[PAD * PAD];
	}

	private MapShade() {
	}

	/** Vanilla-Kartenfarbe mit Biom-Tönung (Gras, Laub …): heller und satter als die alten festen Faktoren. */
	public static int tint(int mapRgb, int tint) {
		mapRgb &= MapColors.RGB;
		if (tint == -1) return mapRgb;
		tint &= MapColors.RGB;
		if (((tint >> 16) & 0xFF) > 0xF0 && ((tint >> 8) & 0xFF) > 0xF0 && (tint & 0xFF) > 0xF0) return mapRgb;
		float k = mapRgb == MapColors.MAP_GRASS ? TINT_GRASS : mapRgb == MapColors.MAP_PLANT ? TINT_PLANT : TINT_OTHER;
		return MapColors.mul(tint, k);
	}

	/** Farbe der Wasseroberfläche aus der Biom-Wasserfarbe (-1 = Standard). */
	public static int waterColor(int waterTint) {
		int t = waterTint & MapColors.RGB;
		// Ohne oder mit weißer Tönung (ältere Versionen tönen Wasser nicht): Standard-Wasserfarbe statt Grau.
		boolean none = waterTint == -1 || (((t >> 16) & 0xFF) > 0xF0 && ((t >> 8) & 0xFF) > 0xF0 && (t & 0xFF) > 0xF0);
		return MapColors.mul(none ? MapColors.DEFAULT_WATER : t, WATER_TINT);
	}

	/**
	 * Helligkeit aus der Neigung: {@code s} &gt; 0 = Fläche zum Licht (links oben) geneigt. Weich gesättigt, damit
	 * Klippen kräftig, aber nie schwarz/weiß werden.
	 */
	static float relief(float s) {
		if (s == 0f) return 1f;
		float a = s > 0f ? RELIEF_LIGHT : RELIEF_SHADOW;
		return 1f + a * s / (Math.abs(s) + RELIEF_SOFT);
	}

	/**
	 * Neigung zum Licht aus der eigenen Höhe und den vier Nachbarn: halb einseitig (Kante zu Nord/West – einzelne
	 * Blöcke und Mauern bleiben sichtbar), halb zentral (echte Hangneigung in x und z).
	 */
	static float slope(int h, int hn, int hs, int hw, int he) {
		float oneSided = (h - hn) + (h - hw);
		float central = ((he - hw) + (hs - hn)) * 0.5f;
		return 0.5f * oneSided + 0.5f * central;
	}

	/** Höhentönung: Berge heller, Täler/Tiefland dunkler. */
	static float heightTone(int height) {
		float t = (height - 64) / HEIGHT_RANGE;
		if (t > HEIGHT_UP) t = HEIGHT_UP;
		if (t < -HEIGHT_DOWN) t = -HEIGHT_DOWN;
		return 1f + t;
	}

	/** Deckkraft des Wassers über dem Grund bei {@code depth} Blöcken. */
	static float waterAlpha(int depth) {
		if (depth <= 1) return WATER_ALPHA0;
		return 1f - (1f - WATER_ALPHA0) * (float) Math.exp(-(depth - 1) / WATER_FADE);
	}

	/** Abdunklung tiefen Wassers (1 = gar nicht). */
	static float waterDark(int depth) {
		return 1f - WATER_DARK * (1f - (float) Math.exp(-depth / WATER_DARK_RANGE));
	}

	/**
	 * Fertige Farbe eines Wasserpixels.
	 *
	 * @param surface Farbe der Wasseroberfläche
	 * @param floor ungeschattete Grundfarbe, 0 = Grund nicht gefunden (tief)
	 * @param depth Tiefe in Blöcken (≥ 1)
	 * @param floorRelief Relief-Faktor des Grunds
	 * @param surfaceRelief leichter Relief-Faktor der Oberfläche
	 */
	static int water(int surface, int floor, int depth, float floorRelief, float surfaceRelief) {
		float alpha = floor == 0 ? 1f : waterAlpha(depth);
		int base = floor == 0 ? surface : MapColors.mul(absorb(floor, surface, depth), floorRelief);
		int c = MapColors.mix(base, surface, alpha);
		return MapColors.mul(c, waterDark(depth) * surfaceRelief);
	}

	/**
	 * Grund, wie man ihn durchs Wasser sieht: Rot und Grün werden mit der Tiefe stärker geschluckt als Blau – Sand
	 * wird türkis statt grau, Ton/Kies bläulich (kein Matsch an der Küste).
	 */
	static int absorb(int floor, int water, int depth) {
		int wr = (water >> 16) & 0xFF, wg = (water >> 8) & 0xFF, wb = water & 0xFF;
		int m = Math.max(1, Math.max(wr, Math.max(wg, wb)));
		float k = Math.min(1f, 0.45f + depth / 24f);
		float fr = 1f - k * (1f - wr / (float) m), fg = 1f - k * (1f - wg / (float) m), fb = 1f - k * (1f - wb / (float) m);
		int r = Math.round(((floor >> 16) & 0xFF) * fr), g = Math.round(((floor >> 8) & 0xFF) * fg), b = Math.round((floor & 0xFF) * fb);
		return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
	}

	/** Laub/Pflanzen-Farbe (Kartenfarbe PLANT) für die Karte: dunkler und satter. */
	public static int foliage(int rgb) {
		return saturate(MapColors.mul(rgb, FOLIAGE_DARK), FOLIAGE_SATURATE);
	}

	/** Sättigung um {@code k} ändern (1 = gleich), Helligkeit bleibt. */
	static int saturate(int rgb, float k) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		float l = 0.299f * r + 0.587f * g + 0.114f * b;
		int nr = clamp(Math.round(l + (r - l) * k)), ng = clamp(Math.round(l + (g - l) * k)), nb = clamp(Math.round(l + (b - l) * k));
		return (nr << 16) | (ng << 8) | nb;
	}

	/** Helle Flächen (Sand & Co., nicht Schnee) etwas heller und weniger gelb – wie im Spiel bei Tageslicht. */
	static int lift(int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		if (max >= 235) return rgb;
		int lum = (r * 299 + g * 587 + b * 114) / 1000;
		if (lum < 160) return rgb;
		float w = LIFT * Math.min(1f, (lum - 160) / 40f);
		return MapColors.mix(saturate(rgb, 1f - w * 0.45f), 0xFFFFFF, w * 0.6f);
	}

	private static int clamp(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}

	/** Schatten in Senken: tiefer als der höchste der vier Nachbarn → dunkler (Boden zwischen Bäumen, Gräben). */
	static float occlusion(int h, int hn, int hs, int hw, int he) {
		int top = Math.max(Math.max(hn, hs), Math.max(hw, he));
		int d = top - h - 1;
		if (d <= 0) return 1f;
		return 1f - OCCLUSION * Math.min(6, d);
	}

	/** Schattiertes ARGB-Bild (128×128) eines Bereichs in {@code out}; Nachbarn (für die Ränder) aus {@code layer}. */
	public static void compose(MapLayer layer, MapRegion r, int[] out) {
		int size = MapRegion.SIZE;
		Scratch sc = SCRATCH.get();
		int[] H = sc.h, F = sc.f;
		int cave = layer == null ? Integer.MIN_VALUE : layer.caveRef;
		// Rand: zuerst unbekannt, dann aus den Nachbarbereichen (falls geladen).
		java.util.Arrays.fill(H, 0, PAD, Integer.MIN_VALUE);
		java.util.Arrays.fill(H, PAD * (PAD - 1), PAD * PAD, Integer.MIN_VALUE);
		for (int z = 0; z < size; z++) {
			int row = (z + 1) * PAD;
			H[row] = Integer.MIN_VALUE;
			H[row + PAD - 1] = Integer.MIN_VALUE;
			fill(r, 0, z, size, H, F, row + 1);
		}
		if (layer != null) {
			MapRegion n = layer.peek(r.rx, r.rz - 1), s = layer.peek(r.rx, r.rz + 1);
			MapRegion w = layer.peek(r.rx - 1, r.rz), e = layer.peek(r.rx + 1, r.rz);
			if (n != null) fill(n, 0, size - 1, size, H, F, 1);
			if (s != null) fill(s, 0, 0, size, H, F, (PAD - 1) * PAD + 1);
			for (int z = 0; z < size; z++) {
				int row = (z + 1) * PAD;
				if (w != null) fill(w, size - 1, z, 1, H, F, row);
				if (e != null) fill(e, 0, z, 1, H, F, row + PAD - 1);
			}
		}
		int[] px = r.pixels;
		byte[] depthPlane = r.depth;
		int[] floorPlane = r.floor;
		for (int z = 0; z < size; z++) {
			int row = z * size;
			for (int x = 0; x < size; x++) {
				int i = row + x;
				int p = px[i];
				if ((p & MapColors.KNOWN) == 0) {
					out[i] = 0;
					continue;
				}
				if ((p & MapColors.EMPTY) != 0) {
					out[i] = cave == Integer.MIN_VALUE ? 0 : 0xFF0E0D12;
					continue;
				}
				int pi = (z + 1) * PAD + x + 1;
				int h = H[pi];
				int hn = or(H[pi - PAD], h), hs = or(H[pi + PAD], h), hw = or(H[pi - 1], h), he = or(H[pi + 1], h);
				if ((p & MapColors.WALL) != 0) {
					out[i] = 0xFF000000 | MapColors.mul(MapColors.WALL_RGB, relief(slope(h, hn, hs, hw, he)) * 0.95f);
					continue;
				}
				int rgb = p & MapColors.RGB;
				int c;
				if ((p & MapColors.WATER) != 0) {
					int depth = depthPlane == null ? 0 : depthPlane[i] & 0xFF;
					if (depth == 0) {
						// Alte Karte: Wasser schon fertig gemischt.
						c = MapColors.mul(rgb, 1f + (heightTone(h) - 1f) * 0.4f);
					} else {
						int fh = F[pi];
						int fn = or(F[pi - PAD], fh), fs = or(F[pi + PAD], fh), fw = or(F[pi - 1], fh), fe = or(F[pi + 1], fh);
						float fs1 = slope(fh, fn, fs, fw, fe);
						float floorRelief = 1f + (relief(fs1) - 1f) * FLOOR_RELIEF;
						float surface = 1f + 0.05f * fs1 / (Math.abs(fs1) + 2f) * waterAlpha(depth);
						c = water(rgb, floorPlane[i] & MapColors.RGB, depth, floorRelief * heightTone(fh), surface);
					}
				} else {
					int base = (p & MapColors.FOLIAGE) != 0 ? foliage(rgb) : headroom(lift(rgb));
					c = shade(base, relief(slope(h, hn, hs, hw, he)) * heightTone(h) * occlusion(h, hn, hs, hw, he));
				}
				if (cave != Integer.MIN_VALUE) {
					float below = (cave - h - 2) / 44f;
					if (below > 0f) c = MapColors.mul(c, 1f - Math.min(0.5f, below));
				}
				out[i] = 0xFF000000 | c;
			}
		}
	}

	/**
	 * Sehr helle Farben (Schnee, Quarz, weiße Wolle) etwas abdunkeln, damit beleuchtete Hänge noch heller werden
	 * können – sonst sähe man auf Schnee nur die Schattenseite.
	 */
	static int headroom(int rgb) {
		int m = Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF));
		if (m <= HEADROOM) return rgb;
		return MapColors.mul(rgb, (HEADROOM + (m - HEADROOM) * 0.35f) / m);
	}

	/** Ab dieser Kanal-Helligkeit wird für das Relief Platz nach oben gelassen. */
	static final int HEADROOM = 218;

	/**
	 * Helligkeit anwenden; Schatten heller Flächen (Schnee, Sand) leicht kühl (bläulich) wie Himmelslicht – so lesen sich
	 * Hänge auf Weiß als Relief statt als graue Flecken.
	 */
	static int shade(int rgb, float f) {
		if (f >= 1f) return MapColors.mul(rgb, f);
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		int lum = (r * 299 + g * 587 + b * 114) / 1000;
		if (lum < 170) return MapColors.mul(rgb, f);
		float cool = Math.min(1f, (lum - 170) / 60f) * 0.45f;
		float fb = f + (1f - f) * cool;
		float fr = f - (1f - f) * cool * 0.15f;
		int nr = Math.max(0, Math.min(255, Math.round(r * fr)));
		int ng = Math.max(0, Math.min(255, Math.round(g * f)));
		int nb = Math.max(0, Math.min(255, Math.round(b * fb)));
		return (nr << 16) | (ng << 8) | nb;
	}

	private static int or(int v, int fallback) {
		return v == Integer.MIN_VALUE ? fallback : v;
	}

	/** Schreibt Höhen/Grundhöhen von {@code n} Pixeln ab (x, z) aus {@code r} nach {@code H}/{@code F} ab {@code off}. */
	private static void fill(MapRegion r, int x0, int z, int n, int[] H, int[] F, int off) {
		int base = z * MapRegion.SIZE + x0;
		int[] px = r.pixels;
		short[] hs = r.heights;
		byte[] d = r.depth;
		for (int k = 0; k < n; k++) {
			int i = base + k;
			int p = px[i];
			if ((p & MapColors.KNOWN) == 0 || (p & MapColors.EMPTY) != 0) {
				H[off + k] = Integer.MIN_VALUE;
				F[off + k] = Integer.MIN_VALUE;
				continue;
			}
			int h = hs[i];
			H[off + k] = h;
			int dep = d == null || (p & MapColors.WATER) == 0 ? 0 : d[i] & 0xFF;
			F[off + k] = h - dep;
		}
	}

	/** Übersicht 64×64 (Mittel je 2×2 Blöcke) aus einem fertigen 128×128-Bild. */
	public static int[] summary64Of(int[] argb) {
		int n = RegionCodec.SUMMARY64;
		int step = MapRegion.SIZE / n;
		int[] out = new int[n * n];
		for (int sz = 0; sz < n; sz++) {
			for (int sx = 0; sx < n; sx++) {
				out[sz * n + sx] = MapExport.average(argb, MapRegion.SIZE, sx * step, sz * step, step, step);
			}
		}
		return out;
	}
}

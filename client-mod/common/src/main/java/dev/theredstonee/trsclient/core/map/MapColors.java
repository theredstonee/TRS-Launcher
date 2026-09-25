package dev.theredstonee.trsclient.core.map;

/**
 * Farben der Karte: Blockfarbe mit Biom-Tönung (Gras, Laub, Wasser), Wasser mit Tiefe, Relief-Schattierung
 * (Licht von Nordwesten, weich gesättigt) und Höhlen-Abdunklung. Reine Mathematik – in allen Versionen gleich.
 *
 * <p>Ein Karten-Pixel ({@code int}) trägt die ungeschattete Farbe (0xRRGGBB) plus Merker in den oberen Bits
 * ({@link #KNOWN}, {@link #WATER}, {@link #WALL}, {@link #EMPTY}); die Höhe liegt daneben. Erst beim Zusammensetzen
 * der Textur wird mit den Nachbarhöhen schattiert – so bleiben die gespeicherten Daten klein und neu schattierbar.
 */
public final class MapColors {
	/** Pixel ist erkundet. */
	public static final int KNOWN = 1 << 24;
	/** Wasserfläche (flach, keine Relief-Schattierung). */
	public static final int WATER = 1 << 25;
	/** Höhlenansicht: nur Gestein bis zur Suchgrenze (Wand). */
	public static final int WALL = 1 << 26;
	/** Erkundet, aber leer (Leere im End, Abgrund). */
	public static final int EMPTY = 1 << 27;
	public static final int RGB = 0xFFFFFF;

	/** Vanilla-Kartenfarben, an denen die Art des Blocks erkannt wird. */
	public static final int MAP_GRASS = 0x7FB238;
	public static final int MAP_PLANT = 0x007C00;
	public static final int MAP_WATER = 0x4040FF;
	/** Standard-Wasserfarbe (Ebenen), falls die Version keine Tönung liefert. */
	public static final int DEFAULT_WATER = 0x3F76E4;
	/** Höhlenwand. */
	public static final int WALL_RGB = 0x24232A;

	private MapColors() {
	}

	public static boolean known(int pixel) {
		return (pixel & KNOWN) != 0;
	}

	/** Kanalweise mit {@code f} multiplizieren (auf 0..255 begrenzt). */
	public static int mul(int rgb, float f) {
		int r = clamp255((int) (((rgb >> 16) & 0xFF) * f + 0.5f));
		int g = clamp255((int) (((rgb >> 8) & 0xFF) * f + 0.5f));
		int b = clamp255((int) ((rgb & 0xFF) * f + 0.5f));
		return (r << 16) | (g << 8) | b;
	}

	/** Linear mischen: t = 0 → a, t = 1 → b. */
	public static int mix(int a, int b, float t) {
		if (t <= 0f) return a & RGB;
		if (t >= 1f) return b & RGB;
		int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t + 0.5f);
		int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t + 0.5f);
		int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t + 0.5f);
		return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(bl);
	}

	/**
	 * Tönt eine Blockfarbe mit ihrer Biom-/Tönungsfarbe (Gras, Laub, Ranken …). Die Texturen dieser Blöcke sind grau;
	 * im Spiel sieht man Tönung × Texturhelligkeit – die mittlere Helligkeit hängt von der Blockart ab.
	 *
	 * @param tint 0xRRGGBB oder -1 (keine Tönung)
	 */
	public static int tint(int mapRgb, int tint) {
		mapRgb &= RGB;
		if (tint == -1) return mapRgb;
		tint &= RGB;
		// Weiß/fast weiß = keine echte Tönung (manche Blöcke melden 0xFFFFFF).
		if (((tint >> 16) & 0xFF) > 0xF0 && ((tint >> 8) & 0xFF) > 0xF0 && (tint & 0xFF) > 0xF0) return mapRgb;
		float k = mapRgb == MAP_GRASS ? 0.76f : mapRgb == MAP_PLANT ? 0.64f : 0.8f;
		return mul(tint, k);
	}

	/**
	 * Wasser über Grund: flaches Wasser lässt den Grund durchscheinen, tiefes wird dunkler und satter.
	 *
	 * @param floorRgb Farbe des Grunds (0 = unbekannt)
	 * @param waterTint Biom-Wasserfarbe oder -1
	 * @param depth Wasserblöcke über dem Grund (≥ 1)
	 */
	public static int water(int floorRgb, int waterTint, int depth) {
		int water = mul(waterTint == -1 ? DEFAULT_WATER : (waterTint & RGB), 0.86f);
		if (depth < 1) depth = 1;
		float alpha = Math.min(0.92f, 0.38f + depth * 0.07f);
		int base = floorRgb == 0 ? water : mul(floorRgb & RGB, 0.72f);
		int c = mix(base, water, alpha);
		float dark = 1f - Math.min(depth, 24) * 0.016f;
		return mul(c, dark);
	}

	/**
	 * Relief: Licht von Nordwesten. {@code slope} = (h − hNord) + (h − hWest); weich gesättigt, damit Klippen nicht
	 * schwarz/weiß werden und flaches Land ruhig bleibt.
	 */
	public static float relief(int slope) {
		if (slope == 0) return 1f;
		float s = slope;
		return 1f + 0.30f * s / (Math.abs(s) + 2.2f);
	}

	/** Leichte Höhentönung: Berge etwas heller, Täler etwas dunkler (±12 %). */
	public static float heightTone(int height) {
		float t = (height - 64) / 220f;
		if (t > 0.12f) t = 0.12f;
		if (t < -0.12f) t = -0.12f;
		return 1f + t;
	}

	/**
	 * Setzt ein Karten-Pixel zum fertigen ARGB zusammen.
	 *
	 * @param pixel gespeichertes Pixel (Farbe + Merker)
	 * @param height Höhe des Pixels
	 * @param north Höhe des nördlichen Nachbarn (gleich {@code height}, wenn unbekannt)
	 * @param west Höhe des westlichen Nachbarn
	 * @param caveRef Höhlenansicht: Bezugshöhe (Spielerebene) für die Tiefen-Abdunklung; {@link Integer#MIN_VALUE} = Oberfläche
	 * @return 0xAARRGGBB, 0 = durchsichtig (unerkundet)
	 */
	public static int compose(int pixel, int height, int north, int west, int caveRef) {
		if ((pixel & KNOWN) == 0) return 0;
		if ((pixel & EMPTY) != 0) return caveRef == Integer.MIN_VALUE ? 0 : 0xFF0E0D12;
		if ((pixel & WALL) != 0) {
			// Gestein: dunkel, mit leichtem Relief, damit Höhlenränder lesbar bleiben.
			return 0xFF000000 | mul(WALL_RGB, relief((height - north) + (height - west)) * 0.95f);
		}
		int rgb = pixel & RGB;
		float f;
		if ((pixel & WATER) != 0) {
			f = 1f + (heightTone(height) - 1f) * 0.4f;
		} else {
			f = relief((height - north) + (height - west)) * heightTone(height);
		}
		if (caveRef != Integer.MIN_VALUE) {
			float below = (caveRef - height - 2) / 44f;
			if (below > 0f) f *= 1f - Math.min(0.5f, below);
		}
		return 0xFF000000 | mul(rgb, f);
	}

	/** Mittelwert von ARGB-Farben (nur deckende), für Übersichtsbilder. 0 = alle durchsichtig. */
	public static int average(int[] argb, int offset, int stride, int w, int h) {
		int r = 0, g = 0, b = 0, n = 0;
		for (int y = 0; y < h; y++) {
			int row = offset + y * stride;
			for (int x = 0; x < w; x++) {
				int c = argb[row + x];
				if ((c >>> 24) < 0x80) continue;
				r += (c >> 16) & 0xFF;
				g += (c >> 8) & 0xFF;
				b += c & 0xFF;
				n++;
			}
		}
		if (n == 0) return 0;
		// Weniger als die Hälfte erkundet → halb durchsichtig (Rand der Erkundung wirkt weich).
		int alpha = n * 2 >= w * h ? 0xFF : 0xA0;
		return (alpha << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
	}

	private static int clamp255(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}
}

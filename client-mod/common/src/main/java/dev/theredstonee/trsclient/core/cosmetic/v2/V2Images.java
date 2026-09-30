package dev.theredstonee.trsclient.core.cosmetic.v2;

import java.io.IOException;

/**
 * Bilder für v2-Kosmetik: senkrechte Streifen in Einzelbilder zerlegen (eigene Maße – kein 2:1-Zwang wie bei Umhängen),
 * die Leucht-Schicht vormultiplizieren, Hof-Texturen erzeugen und (für die 2D-Vorschau der Garderobe) Leuchten
 * einrechnen und spiegeln. ARGB zeilenweise, ohne AWT.
 */
public final class V2Images {
	/** Kantenlänge der erzeugten Hof-Textur. */
	public static final int HALO_SIZE = 64;

	private V2Images() {
	}

	/**
	 * Zerlegt einen Streifen ({@code argb}, {@code width × height}) in Bilder von {@code frameW × frameH}. Maßgeblich
	 * sind Breite und Bildhöhe aus dem Modell; zählt die Datei mehr oder weniger Bilder als versprochen, gilt das
	 * Kleinere (mindestens eins). Falsche Breite/Bildhöhe → IOException.
	 */
	public static int[][] split(int[] argb, int width, int height, int frameW, int frameH, int frames) throws IOException {
		if (argb == null || width != frameW || frameH <= 0 || height < frameH || height % frameH != 0) {
			throw new IOException("Bildmaße " + width + "×" + height + ", erwartet " + frameW + "×" + frameH + "·n");
		}
		if (frameW > CosmeticV2.MAX_TEXTURE_EDGE || frameH > CosmeticV2.MAX_TEXTURE_EDGE || height > CosmeticV2.MAX_STRIP) {
			throw new IOException("Bild zu groß");
		}
		int count = Math.max(1, Math.min(height / frameH, Math.max(1, frames)));
		int[][] out = new int[count][];
		int n = frameW * frameH;
		for (int f = 0; f < count; f++) {
			int[] px = new int[n];
			System.arraycopy(argb, f * n, px, 0, n);
			out[f] = px;
		}
		return out;
	}

	/**
	 * Halbe Auflösung (2×2 → 1) für die Entfernungs-Stufen ({@link CosmeticV2Renderer#lodLevel}). Minecraft zeichnet
	 * Entity-Texturen ohne Mipmaps; eine HD-Textur (Faktor 8) wird aus normaler Entfernung stark verkleinert und dann je
	 * Bild an anderen Unter-Pixeln abgetastet – sie flimmert. Die Stufen ersetzen die fehlenden Mipmaps.
	 *
	 * <p>{@code additive} (Leucht-Schicht, vormultipliziert, Alpha 255): Mittelwert der Farbe. Sonst (Grundtextur):
	 * Farbe = Mittel der deckenden Pixel (nach Alpha gewichtet), Alpha = Abdeckung; Grundtexturen mit Alpha nur 0/255
	 * bleiben so (ab halber Abdeckung deckend) – wie der Alpha-Test 0,5 der Werkbank, ohne Kanten anzufressen.
	 */
	public static int[] downsample(int[] src, int width, int height, boolean additive) {
		int w = width / 2;
		int h = height / 2;
		int[] out = new int[w * h];
		boolean binary = true;
		if (!additive) {
			for (int p : src) {
				int a = p >>> 24;
				if (a != 0 && a != 255) {
					binary = false;
					break;
				}
			}
		}
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = (y * 2) * width + x * 2;
				int[] q = { src[i], src[i + 1], src[i + width], src[i + width + 1] };
				long r = 0, g = 0, b = 0, a = 0;
				for (int p : q) {
					int pa = additive ? 255 : p >>> 24;
					r += ((p >> 16) & 0xFF) * (long) pa;
					g += ((p >> 8) & 0xFF) * (long) pa;
					b += (p & 0xFF) * (long) pa;
					a += pa;
				}
				int oa;
				int or = 0, og = 0, ob = 0;
				if (a > 0) {
					or = (int) ((r + a / 2) / a);
					og = (int) ((g + a / 2) / a);
					ob = (int) ((b + a / 2) / a);
				}
				if (additive) {
					oa = 255;
				} else if (binary) {
					oa = a >= 2 * 255 ? 255 : 0;
				} else {
					oa = (int) ((a + 2) / 4);
				}
				out[y * w + x] = oa == 0 && !additive ? 0 : (oa << 24) | (or << 16) | (og << 8) | ob;
			}
		}
		return out;
	}

	/**
	 * Stufen eines Bildes: [0] = Original, [k] = Auflösung / 2^k, bis {@code levels} (je Stufe {@link #downsample}).
	 */
	public static int[][] levels(int[] src, int width, int height, int levels, boolean additive) {
		int[][] out = new int[levels + 1][];
		out[0] = src;
		int w = width;
		int h = height;
		for (int l = 1; l <= levels; l++) {
			out[l] = downsample(out[l - 1], w, h, additive);
			w /= 2;
			h /= 2;
		}
		return out;
	}

	/**
	 * Leucht-Schicht für additives Zeichnen mit {@code ONE, ONE}: RGB mit Alpha vormultipliziert, Alpha 255 (so
	 * wirkt auch eine halbdurchsichtige Datei wie in three.js mit {@code SRC_ALPHA, ONE}).
	 */
	public static void premultiplyGlow(int[] argb) {
		for (int i = 0; i < argb.length; i++) {
			int p = argb[i];
			int a = (p >>> 24) & 0xFF;
			if (a == 255) continue;
			int r = ((p >> 16) & 0xFF) * a / 255;
			int g = ((p >> 8) & 0xFF) * a / 255;
			int b = (p & 0xFF) * a / 255;
			argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
		}
	}

	/**
	 * Hof-Textur für additives Zeichnen ({@code ONE, ONE}): Graustufen {@code (1 − r)^2,5} in RGB. Alpha = vierfacher
	 * Verlauf (gekappt): Render-Typen mit Alpha-Test (ab 1.21.5 {@code energy_swirl}) verwerfen so nur den unsichtbaren
	 * Rand, statt ein Quadrat in den Tiefenpuffer zu schreiben.
	 */
	public static int[] haloAdditive() {
		int n = HALO_SIZE;
		int[] out = new int[n * n];
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				double r = Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0) / (n / 2.0);
				double f = CosmeticV2Renderer.haloFalloff(r);
				int v = (int) Math.round(f * 255);
				int a = (int) Math.min(255, Math.round(f * 4 * 255));
				out[y * n + x] = (a << 24) | (v << 16) | (v << 8) | v;
			}
		}
		return out;
	}

	/** Hof-Textur für normales Mischen (Menüs): weiß, Alpha = Verlauf. */
	public static int[] haloAlpha() {
		int n = HALO_SIZE;
		int[] out = new int[n * n];
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				double r = Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0) / (n / 2.0);
				int v = (int) Math.round(CosmeticV2Renderer.haloFalloff(r) * 255);
				out[y * n + x] = (v << 24) | 0xFFFFFF;
			}
		}
		return out;
	}

	/**
	 * Grundbild mit Licht und Leuchten verrechnet (für Menüs ohne additives Mischen): {@code rgb·light + glow}
	 * (gekappt), Alpha des Grundbilds. {@code glow} darf null sein.
	 */
	public static int[] composite(int[] base, int[] glow, float light, int[] out) {
		int n = base.length;
		if (out == null || out.length != n) out = new int[n];
		int l = Math.round(Math.max(0f, Math.min(1f, light)) * 256);
		for (int i = 0; i < n; i++) {
			int p = base[i];
			int a = p >>> 24;
			if (a == 0) {
				out[i] = 0;
				continue;
			}
			int r = (((p >> 16) & 0xFF) * l) >> 8;
			int g = (((p >> 8) & 0xFF) * l) >> 8;
			int b = ((p & 0xFF) * l) >> 8;
			if (glow != null && i < glow.length) {
				int q = glow[i];
				r = Math.min(255, r + ((q >> 16) & 0xFF));
				g = Math.min(255, g + ((q >> 8) & 0xFF));
				b = Math.min(255, b + (q & 0xFF));
			}
			out[i] = (a << 24) | (r << 16) | (g << 8) | b;
		}
		return out;
	}

	/** Waagerecht gespiegelte Kopie (für gespiegelt erscheinende Flächen in der 2D-Vorschau). */
	public static int[] mirror(int[] argb, int width, int height, int[] out) {
		if (out == null || out.length != argb.length) out = new int[argb.length];
		for (int y = 0; y < height; y++) {
			int row = y * width;
			for (int x = 0; x < width; x++) out[row + x] = argb[row + width - 1 - x];
		}
		return out;
	}
}

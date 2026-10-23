package dev.theredstonee.trsclient.core.cape;

import java.util.ArrayList;
import java.util.List;

/**
 * Filterung von TRS-Umhängen („Automatisch“): Pixel-Art bleibt scharf (nächster Nachbar), echte HD-Umhänge – Fotos,
 * Logos, gemalte Bilder aus „HD – wie Bild“ im Launcher – werden weich gefiltert (linear + Mipmaps), damit sie aus der
 * Entfernung nicht flimmern.
 *
 * <p>Regel (wie {@code capeLooksSmooth} im Launcher): eine Umhang-Textur ist Pixel-Art, wenn sie nur 64 breit ist, höchstens
 * {@value #MAX_PIXEL_ART_COLORS} Farben hat (Palette, z. B. TRS-Studio-Umhänge) oder aus einfarbigen Blöcken irgendeiner
 * Größe ≥ 2 besteht (pixelgenau hochskaliert, auch feiner als 64er-Raster) – sonst HD.
 * Ganz durchsichtige Pixel zählen unabhängig von ihrer Farbe als gleich.
 */
public final class CapeFilter {
	/** Mipmaps nur bis zur Vanilla-Größe (64 breit): darunter liefen die Farben über die Kanten der UV-Inseln. */
	public static final int MIN_MIP_WIDTH = 64;

	private CapeFilter() {
	}

	private static boolean same(int a, int b) {
		return a == b || ((a >>> 24) == 0 && (b >>> 24) == 0);
	}

	/** Besteht das Bild ({@code width × height}, ARGB) aus einfarbigen {@code b × b}-Blöcken? */
	public static boolean blockUniform(int[] argb, int width, int height, int b) {
		if (b < 1 || width % b != 0 || height % b != 0 || argb == null || argb.length < width * height) return false;
		if (b == 1) return true;
		for (int y = 0; y < height; y++) {
			int row = y * width;
			int top = (y - y % b) * width;
			for (int x = 0; x < width; x++) {
				int bx = x - x % b;
				if (!same(argb[row + x], argb[top + bx])) return false;
			}
		}
		return true;
	}

	/**
	 * Soll dieser Umhang weich gefiltert werden? {@code width}/{@code height} = Größe eines Bildes (nach einer evtl.
	 * Verkleinerung fürs Textur-Budget). Pixel-Art = einfarbige Blöcke irgendeiner Größe ≥ 2 – auch feinere HD-Pixel-Art
	 * (z. B. 4×4 auf 512 Breite) bleibt scharf; nur Fotos/gemalte Bilder werden weich. Geprüft werden die Primteiler der
	 * Kanten (gleichförmig bei 4 ⇒ auch bei 2). Ist die Breite kein Vielfaches von 64 mehr, wurde schon gemittelt – weich.
	 */
	public static boolean looksSmooth(int width, int height, int[][] frames) {
		if (width <= MIN_MIP_WIDTH || frames == null) return false;
		if (width % 64 != 0) return true;
		// Palette-Pixel-Art (TRS-Studio-/eingebaute Umhänge: 12–250 Farben, ohne exakte Blöcke) bleibt scharf;
		// echte Fotos/gemalte Bilder haben tausende Farben.
		if (fewColors(frames, width * height, MAX_PIXEL_ART_COLORS)) return false;
		int g = gcd(width, height);
		for (int p = 2; p <= g; p++) {
			if (g % p != 0 || !prime(p)) continue;
			boolean uniform = true;
			for (int[] frame : frames) {
				if (frame != null && frame.length >= width * height && !blockUniform(frame, width, height, p)) {
					uniform = false;
					break;
				}
			}
			if (uniform) return false;
		}
		return true;
	}

	/** Höchstens so viele verschiedene Farben (ganz durchsichtig zählt als eine) gelten als Palette/Pixel-Art. */
	public static final int MAX_PIXEL_ART_COLORS = 256;

	/** Haben alle Bilder zusammen höchstens {@code limit} Farben? */
	static boolean fewColors(int[][] frames, int pixels, int limit) {
		java.util.HashSet<Integer> seen = new java.util.HashSet<>();
		for (int[] frame : frames) {
			if (frame == null) continue;
			int n = Math.min(pixels, frame.length);
			for (int i = 0; i < n; i++) {
				int c = frame[i];
				if (seen.add((c >>> 24) == 0 ? 0 : c) && seen.size() > limit) return false;
			}
		}
		return true;
	}

	private static int gcd(int a, int b) {
		while (b != 0) {
			int t = a % b;
			a = b;
			b = t;
		}
		return a;
	}

	private static boolean prime(int n) {
		if (n < 2) return false;
		for (int i = 2; i * i <= n; i++) if (n % i == 0) return false;
		return true;
	}

	/**
	 * Mipmap-Kette: Ebene 0 = {@code argb}, jede weitere halb so groß (Kastenfilter, mit Deckkraft gewichtet – durchsichtige
	 * Pixel färben nichts dunkel). Halbiert wird, solange beide Kanten gerade sind und die Breite mindestens
	 * {@link #MIN_MIP_WIDTH} bleibt.
	 */
	public static int[][] mipChain(int[] argb, int width, int height) {
		List<int[]> levels = new ArrayList<>();
		levels.add(argb);
		int w = width;
		int h = height;
		int[] px = argb;
		while (w % 2 == 0 && h % 2 == 0 && w / 2 >= MIN_MIP_WIDTH && h / 2 >= 1) {
			px = CapeFrames.downscale(px, w, h, 2);
			w /= 2;
			h /= 2;
			levels.add(px);
		}
		return levels.toArray(new int[0][]);
	}

	/** Speicher (RGBA-Bytes) der Mipmaps über Ebene 0 hinaus. */
	public static long mipBytes(int width, int height) {
		long total = 0;
		int w = width;
		int h = height;
		while (w % 2 == 0 && h % 2 == 0 && w / 2 >= MIN_MIP_WIDTH) {
			w /= 2;
			h /= 2;
			total += (long) w * h * 4;
		}
		return total;
	}
}

package dev.theredstonee.trsclient.core.cape;

import java.util.ArrayList;
import java.util.List;

/**
 * Filterung von TRS-Umhängen („Automatisch“): Pixel-Art bleibt scharf (nächster Nachbar), echte HD-Umhänge – Fotos,
 * Logos, gemalte Bilder aus „HD – wie Bild“ im Launcher – werden weich gefiltert (linear + Mipmaps), damit sie aus der
 * Entfernung nicht flimmern.
 *
 * <p>Regel (wie {@code capeLooksSmooth} im Launcher): eine Umhang-Textur {@code 64·k × 32·k} ist Pixel-Art, wenn sie aus
 * einfarbigen {@code k × k}-Blöcken besteht (pixelgenau hochskaliert) oder {@code k = 1} ist – sonst HD. Ganz
 * durchsichtige Pixel zählen unabhängig von ihrer Farbe als gleich.
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
	 * Verkleinerung fürs Textur-Budget). Ist die Breite kein Vielfaches von 64 mehr, wurde schon gemittelt – dann weich.
	 */
	public static boolean looksSmooth(int width, int height, int[][] frames) {
		if (width <= MIN_MIP_WIDTH || frames == null) return false;
		if (width % 64 != 0) return true;
		int k = width / 64;
		for (int[] frame : frames) {
			if (frame != null && frame.length >= width * height && !blockUniform(frame, width, height, k)) return true;
		}
		return false;
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

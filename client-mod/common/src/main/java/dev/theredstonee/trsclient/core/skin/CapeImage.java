package dev.theredstonee.trsclient.core.skin;

/**
 * Umhang-Bilder in die Vanilla-Aufteilung bringen: Mojang-Umhänge sind 64×32 (ältere 22×17 – die werden wie
 * im Spiel in eine 64×32-Fläche gesetzt), HD-Umhänge Vielfache davon bis 1024×512 (wie TRS-Umhänge, Faktor 16).
 */
public final class CapeImage {
	/** Größte Breite eines HD-Umhangs (Faktor 16). */
	public static final int MAX_WIDTH = 64 * dev.theredstonee.trsclient.core.online.CapeInfo.MAX_SCALE;

	private CapeImage() {
	}

	/** Breite der Textur nach {@link #normalize} (0 = ungültig). */
	public static int width(int width, int height) {
		if (width == 22 && height == 17) return 64;
		if (width >= 64 && width <= MAX_WIDTH && width % 64 == 0 && height == width / 2) return width;
		return 0;
	}

	/** Pixel in Vanilla-Aufteilung (neues Array) oder null bei unbekannter Größe. */
	public static int[] normalize(int width, int height, int[] argb) {
		int w = width(width, height);
		if (w == 0 || argb == null || argb.length < width * height) return null;
		if (w == width) return argb.clone();
		int[] out = new int[64 * 32];
		for (int y = 0; y < height; y++) System.arraycopy(argb, y * width, out, y * 64, width);
		return out;
	}
}

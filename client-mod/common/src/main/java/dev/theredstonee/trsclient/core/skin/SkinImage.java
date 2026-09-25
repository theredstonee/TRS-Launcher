package dev.theredstonee.trsclient.core.skin;

/**
 * Skin-Pixel wie Minecraft sie aufbereitet: alte 64×32-Skins werden ins 64×64-Format umgebaut (linker Arm und
 * linkes Bein gespiegelt von rechts), die Grundebene wird deckend gemacht und ein vollständig deckender Hut
 * alter Skins durchsichtig („Notch-Hack“). Danach gilt für alle Skins dieselbe UV-Aufteilung.
 */
public final class SkinImage {
	private SkinImage() {
	}

	/** Gültige Skin-Größe (64×64 oder altes 64×32)? */
	public static boolean validSize(int width, int height) {
		return width == 64 && (height == 64 || height == 32);
	}

	/**
	 * Liefert die Pixel im 64×64-Format (neues Array).
	 *
	 * @throws IllegalArgumentException bei falscher Größe
	 */
	public static int[] normalize(int width, int height, int[] argb) {
		if (!validSize(width, height) || argb == null || argb.length < width * height) {
			throw new IllegalArgumentException("Skin muss 64×64 oder 64×32 sein, ist " + width + "×" + height);
		}
		int[] out = new int[64 * 64];
		System.arraycopy(argb, 0, out, 0, width * height);
		boolean legacy = height == 32;
		if (legacy) {
			// Wie Vanilla (HttpTexture#processLegacySkin): rechte Gliedmaßen gespiegelt nach links kopieren.
			copy(out, 4, 16, 16, 32, 4, 4);
			copy(out, 8, 16, 16, 32, 4, 4);
			copy(out, 0, 20, 24, 32, 4, 12);
			copy(out, 4, 20, 16, 32, 4, 12);
			copy(out, 8, 20, 8, 32, 4, 12);
			copy(out, 12, 20, 16, 32, 4, 12);
			copy(out, 44, 16, -8, 32, 4, 4);
			copy(out, 48, 16, -8, 32, 4, 4);
			copy(out, 40, 20, 0, 32, 4, 12);
			copy(out, 44, 20, -8, 32, 4, 12);
			copy(out, 48, 20, -16, 32, 4, 12);
			copy(out, 52, 20, -8, 32, 4, 12);
		}
		opaque(out, 0, 0, 32, 16);
		if (legacy) notchHack(out, 32, 0, 64, 16);
		opaque(out, 0, 16, 64, 32);
		opaque(out, 16, 48, 48, 64);
		return out;
	}

	/** NativeImage#copyRect mit waagerechter Spiegelung. */
	private static void copy(int[] px, int x, int y, int dx, int dy, int w, int h) {
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				int mirrored = w - 1 - col;
				px[(y + dy + row) * 64 + x + dx + mirrored] = px[(y + row) * 64 + x + col];
			}
		}
	}

	private static void opaque(int[] px, int x1, int y1, int x2, int y2) {
		for (int y = y1; y < y2; y++) {
			for (int x = x1; x < x2; x++) px[y * 64 + x] |= 0xFF000000;
		}
	}

	/** Ist der Bereich komplett deckend (alte Skins ohne echten Hut), wird er ganz durchsichtig. */
	private static void notchHack(int[] px, int x1, int y1, int x2, int y2) {
		for (int y = y1; y < y2; y++) {
			for (int x = x1; x < x2; x++) {
				if ((px[y * 64 + x] >>> 24) < 128) return;
			}
		}
		for (int y = y1; y < y2; y++) {
			for (int x = x1; x < x2; x++) px[y * 64 + x] &= 0x00FFFFFF;
		}
	}
}

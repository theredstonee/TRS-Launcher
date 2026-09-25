package dev.theredstonee.trsclient.core.ui;

import java.util.Locale;

/**
 * Minecraft-Gesicht (8×8 + Hut-Ebene) Pixel für Pixel über den Canvas – ohne Texturen, läuft in jeder Version.
 * Die Pixel kommen aus {@link dev.theredstonee.trsclient.core.account.FaceCache}; solange nichts da ist, steht
 * ein farbiger Platzhalter mit dem Anfangsbuchstaben.
 */
public final class Faces {
	private Faces() {
	}

	/**
	 * @param face 128 ARGB-Werte (64 Gesicht, 64 Hut) oder null
	 * @param px Kantenlänge eines Gesichtspixels
	 */
	public static void draw(Canvas c, int[] face, String key, String name, int x, int y, int px, boolean frame) {
		int size = 8 * px;
		if (frame) Redstone.block(c, x - 1, y - 1, size + 2, size + 2, Theme.get().bevelDark);
		if (face == null || face.length < 128) {
			int hash = key == null ? 0 : key.hashCode();
			int base = 0xFF000000 | (hash & 0x3F3F3F) | 0x202020;
			c.fill(x, y, x + size, y + size, base);
			if (size >= 8) {
				String initial = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
				if (size >= 12) {
					c.text(initial, x + (size - c.textWidth(initial)) / 2 + 1, y + (size - 8) / 2 + 1, 0xFFFFFFFF, true);
				}
			}
			return;
		}
		// Je Zeile gleichfarbige Läufe zu einem Rechteck zusammenfassen (Hut deckt das Gesicht ab).
		for (int row = 0; row < 8; row++) {
			int fy = y + row * px;
			int start = 0;
			int color = pixel(face, row * 8);
			for (int col = 1; col <= 8; col++) {
				int next = col < 8 ? pixel(face, row * 8 + col) : 0;
				if (col == 8 || next != color) {
					c.fill(x + start * px, fy, x + col * px, fy + px, color);
					start = col;
					color = next;
				}
			}
		}
	}

	private static int pixel(int[] face, int i) {
		int hat = face[64 + i];
		return (hat >>> 24) >= 0x80 ? hat | 0xFF000000 : face[i] | 0xFF000000;
	}
}

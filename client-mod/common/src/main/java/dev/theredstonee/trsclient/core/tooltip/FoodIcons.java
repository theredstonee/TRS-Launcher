package dev.theredstonee.trsclient.core.tooltip;

import dev.theredstonee.trsclient.core.ui.Canvas;

/**
 * Eigene Keulen-Symbole (9×9 Pixel) für Hunger und Sättigung im Tooltip – gezeichnet aus Rechtecken, damit sie in allen
 * Versionen gleich aussehen (die Vanilla-Symbole liegen je Version in anderen Texturen).
 */
public final class FoodIcons {
	public static final int SIZE = 9;
	/** Höchstens so viele Symbole je Zeile; mehr zeigt nur die Zahl. */
	public static final int MAX_ICONS = 10;

	private static final String[] SHAPE = {
			"....kkk..",
			"...kmllk.",
			"..kmmmlmk",
			"..kmmmmmk",
			".kmmmmmk.",
			"kbkmmmk..",
			"kbbkkk...",
			".kbk.....",
			"..k......"};
	/** Farben: Umriss, Fleisch, hell, Knochen – normal, Sättigung (gold), leer. */
	private static final int[] NORMAL = {0xFF3A1804, 0xFFC0602A, 0xFFE9965A, 0xFFF1E6CF};
	private static final int[] GOLD = {0xFF5A3C00, 0xFFE0A91E, 0xFFFFE27A, 0xFFFFF4C4};
	private static final int[] EMPTY = {0xFF2A1A12, 0x66201008, 0x66201008, 0x66403028};

	private FoodIcons() {
	}

	/** Anzahl voller und halber Symbole für {@code points} (2 Punkte = 1 Symbol), höchstens {@link #MAX_ICONS}. */
	public static int[] icons(float points) {
		if (points <= 0) return new int[]{0, 0};
		int halves = Math.max(1, Math.round(points));
		int full = halves / 2, half = halves % 2;
		if (full + half > MAX_ICONS) {
			full = MAX_ICONS;
			half = 0;
		}
		return new int[]{full, half};
	}

	/** Breite einer Symbolreihe. */
	public static int rowWidth(float points) {
		int[] n = icons(points);
		int count = n[0] + n[1];
		return count == 0 ? 0 : count * (SIZE - 1) + 1;
	}

	/** Zeichnet eine Reihe (volle, dann halbe Keule) ab (x, y). */
	public static void row(Canvas c, int x, int y, float points, boolean gold) {
		int[] n = icons(points);
		int[] colors = gold ? GOLD : NORMAL;
		for (int i = 0; i < n[0]; i++) icon(c, x + i * (SIZE - 1), y, colors, SIZE);
		if (n[1] > 0) {
			int hx = x + n[0] * (SIZE - 1);
			icon(c, hx, y, EMPTY, SIZE);
			icon(c, hx, y, colors, 5);
		}
	}

	/** Eine Keule; nur die linken {@code columns} Spalten (halbe Keule). */
	static void icon(Canvas c, int x, int y, int[] colors, int columns) {
		for (int row = 0; row < SIZE; row++) {
			String line = SHAPE[row];
			int start = -1;
			int color = 0;
			for (int col = 0; col <= Math.min(columns, SIZE); col++) {
				int argb = col < Math.min(columns, SIZE) ? colorOf(line.charAt(col), colors) : 0;
				if (start >= 0 && argb != color) {
					c.fill(x + start, y + row, x + col, y + row + 1, color);
					start = -1;
				}
				if (start < 0 && argb != 0) {
					start = col;
					color = argb;
				}
			}
		}
	}

	private static int colorOf(char ch, int[] colors) {
		switch (ch) {
			case 'k':
				return colors[0];
			case 'm':
				return colors[1];
			case 'l':
				return colors[2];
			case 'b':
				return colors[3];
			default:
				return 0;
		}
	}
}

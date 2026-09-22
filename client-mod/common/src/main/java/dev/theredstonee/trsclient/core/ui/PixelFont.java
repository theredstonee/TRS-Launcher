package dev.theredstonee.trsclient.core.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kleine 5×7-Pixelschrift für den "TRS Client"-Schriftzug des Startbildschirms
 * (im Code gezeichnet, keine fremden Grafiken). Liefert Rechtecke in Pixel-Einheiten.
 */
public final class PixelFont {
	public static final int HEIGHT = 7;
	private static final int SPACING = 1;
	private static final int SPACE_WIDTH = 3;

	private PixelFont() {
	}

	/** Zeilen eines Zeichens ('#' = Pixel) oder null, wenn unbekannt. */
	static String[] glyph(char c) {
		return switch (Character.toUpperCase(c)) {
			case 'T' -> new String[]{"#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."};
			case 'R' -> new String[]{"####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"};
			case 'S' -> new String[]{".####", "#....", "#....", ".###.", "....#", "....#", "####."};
			case 'C' -> new String[]{".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."};
			case 'L' -> new String[]{"#....", "#....", "#....", "#....", "#....", "#....", "#####"};
			case 'I' -> new String[]{"###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"};
			case 'E' -> new String[]{"#####", "#....", "#....", "####.", "#....", "#....", "#####"};
			case 'N' -> new String[]{"#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"};
			default -> null;
		};
	}

	/** Breite eines Textes in Pixeln (ohne Abstand nach dem letzten Zeichen). */
	public static int width(String text) {
		int w = 0;
		for (int i = 0; i < text.length(); i++) {
			w += advance(text.charAt(i));
		}
		return Math.max(0, w - SPACING);
	}

	private static int advance(char c) {
		String[] g = glyph(c);
		return (g == null ? SPACE_WIDTH : g[0].length()) + SPACING;
	}

	/** Rechtecke {x1, y1, x2, y2} (Pixel-Einheiten, Ursprung oben links); Zeilen-Läufe zusammengefasst. */
	public static List<int[]> rects(String text) {
		List<int[]> out = new ArrayList<>();
		int x = 0;
		for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
			String[] g = glyph(c);
			if (g != null) {
				for (int row = 0; row < g.length; row++) {
					String line = g[row];
					int start = -1;
					for (int col = 0; col <= line.length(); col++) {
						boolean on = col < line.length() && line.charAt(col) == '#';
						if (on && start < 0) start = col;
						if (!on && start >= 0) {
							out.add(new int[]{x + start, row, x + col, row + 1});
							start = -1;
						}
					}
				}
			}
			x += advance(c);
		}
		return out;
	}
}

package dev.theredstonee.trsclient.core.hunger;

import dev.theredstonee.trsclient.core.cape.PngDecoder;

/**
 * Woraus der Sättigungs-Rand kommt. Zuerst die AppleSkin-Textur ({@code appleskin:textures/icons.png}, Layout
 * gemeinfrei/Unlicense), sonst der Umriss der leeren Keule aus dem aktuellen Resource Pack, sonst die eingebaute
 * Keule. Größere Sprites (18×18, 36×36) werden auf 9×9 heruntergerechnet (jede Zelle ist gesetzt, wenn darin ein
 * deckender Pixel liegt).
 */
public final class HungerLook {
	public static final String APPLESKIN = "appleskin:textures/icons.png";
	/** Ab 1.20.2: eigene Sprite-Datei. */
	public static final String FOOD_EMPTY = "minecraft:textures/gui/sprites/hud/food_empty.png";
	/** Bis 1.20.1 und Legacy: Ausschnitt u=16, v=27 in {@code icons.png}. */
	public static final String ICONS = "minecraft:textures/gui/icons.png";

	public enum Source {
		APPLESKIN, PACK, BUILTIN
	}

	private HungerLook() {
	}

	/**
	 * AppleSkin gewinnt, sobald die Datei Bytes hat – auch wenn der Pack eine eigene Keule hätte. Sonst der
	 * Pack-Umriss, sonst die eingebaute Form.
	 */
	public static Source choose(byte[] appleskin, int[][][] packOutline) {
		if (appleskin != null && appleskin.length > 0) return Source.APPLESKIN;
		if (packOutline != null && packOutline.length > 0) return Source.PACK;
		return Source.BUILTIN;
	}

	/** Spalte in der AppleSkin-Textur (v=0, 9×9): leer, ¼, ½, ganz. */
	public static int saturationU(float part) {
		return part >= 1f ? 27 : part > 0.5f ? 18 : part > 0.25f ? 9 : 0;
	}

	/** Breite des Erschöpfungs-Balkens (81 px, von rechts). */
	public static int exhaustionWidth(float ratio) {
		float clamped = ratio < 0f ? 0f : (ratio > 1f ? 1f : ratio);
		return Math.round(clamped * 81f);
	}

	/** PNG-Bytes → Umriss, oder null (keine PNG, zu klein, keine deckenden Pixel). {@code sheet}: Ausschnitt aus icons.png. */
	public static int[][][] fromPng(byte[] png, boolean sheet) {
		if (png == null || png.length == 0) return null;
		try {
			PngDecoder.Image img = PngDecoder.decode(png);
			if (img == null || img.argb == null) return null;
			return fromArgb(img.argb, img.width, img.height, sheet);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * ARGB-Maske → Rand-Läufe auf 9×9. {@code sheet} schneidet die leere Keule aus {@code icons.png}
	 * (bei doppelter Atlas-Größe entsprechend skaliert).
	 */
	public static int[][][] fromArgb(int[] argb, int width, int height, boolean sheet) {
		if (argb == null || width < 1 || height < 1 || (long) width * height > argb.length) return null;
		if (!sheet) return outline(argb, width, height);
		int scale = Math.max(1, width / 256);
		int x = 16 * scale;
		int y = 27 * scale;
		int size = 9 * scale;
		if (x + size > width || y + size > height) return null;
		int[] crop = new int[size * size];
		for (int row = 0; row < size; row++) {
			System.arraycopy(argb, (y + row) * width + x, crop, row * size, size);
		}
		return outline(crop, size, size);
	}

	/**
	 * Deckende Pixel ({@code alpha > 0}) auf 9×9 skalieren, dann die Pixel, die an Leere grenzen (4er-Nachbarschaft),
	 * als Läufe je Zeile. Null, wenn nichts deckend ist.
	 */
	public static int[][][] outline(int[] argb, int width, int height) {
		if (argb == null || width < 1 || height < 1 || (long) width * height > argb.length) return null;
		boolean[][] mask = new boolean[9][9];
		boolean any = false;
		for (int y = 0; y < 9; y++) {
			int y0 = y * height / 9;
			int y1 = (y + 1) * height / 9;
			if (y1 <= y0) y1 = Math.min(height, y0 + 1);
			for (int x = 0; x < 9; x++) {
				int x0 = x * width / 9;
				int x1 = (x + 1) * width / 9;
				if (x1 <= x0) x1 = Math.min(width, x0 + 1);
				boolean solid = false;
				for (int sy = y0; sy < y1 && sy < height && !solid; sy++) {
					for (int sx = x0; sx < x1 && sx < width; sx++) {
						if ((argb[sy * width + sx] >>> 24) != 0) {
							solid = true;
							break;
						}
					}
				}
				mask[y][x] = solid;
				any |= solid;
			}
		}
		if (!any) return null;
		int[][][] rows = new int[9][][];
		for (int y = 0; y < 9; y++) {
			java.util.List<int[]> runs = new java.util.ArrayList<int[]>();
			int start = -1;
			for (int x = 0; x <= 9; x++) {
				boolean edge = on(mask, x, y) && (!on(mask, x - 1, y) || !on(mask, x + 1, y) || !on(mask, x, y - 1) || !on(mask, x, y + 1));
				if (edge && start < 0) start = x;
				if (!edge && start >= 0) {
					runs.add(new int[]{start, x});
					start = -1;
				}
			}
			rows[y] = runs.toArray(new int[0][]);
		}
		return rows;
	}

	private static boolean on(boolean[][] mask, int x, int y) {
		return y >= 0 && y < 9 && x >= 0 && x < 9 && mask[y][x];
	}
}

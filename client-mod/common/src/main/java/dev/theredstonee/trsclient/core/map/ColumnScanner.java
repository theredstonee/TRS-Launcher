package dev.theredstonee.trsclient.core.map;

/**
 * Tastet einen Chunk Spalte für Spalte ab: Oberfläche (oberster Block mit Kartenfarbe, Wasser mit Tiefe und Grund)
 * oder Höhlenschnitt (Boden unterhalb der Spielerebene: Gestein oberhalb wird übersprungen, geschlossene Spalten
 * werden Wand). Ergebnis je Spalte: Pixel ({@link MapColors}-Merker + Farbe) und Höhe; Index {@code z * 16 + x}.
 */
public final class ColumnScanner {
	/** So tief wird unter der Oberkante nach einem Block mit Farbe gesucht (Glas, Luft-Lücken). */
	static final int SURFACE_STEPS = 48;
	/** Maximal gezählte Wassertiefe. */
	static final int MAX_WATER = 24;
	/** Höhlenansicht: so weit reicht die Suche unter die Startebene. */
	public static final int CAVE_RANGE = 40;

	private ColumnScanner() {
	}

	/**
	 * Oberfläche eines Chunks.
	 *
	 * @return false, wenn der Chunk nicht gelesen werden konnte
	 */
	public static boolean surface(ChunkReader r, int chunkX, int chunkZ, int[] pixels, int[] heights) {
		if (!r.open(chunkX, chunkZ)) return false;
		int min = r.minY();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				int top = r.top(x, z);
				int y = top - 1;
				int rgb = 0;
				for (int steps = 0; steps < SURFACE_STEPS && y >= min; steps++, y--) {
					rgb = r.block(x, y, z) & MapColors.RGB;
					if (rgb != 0) break;
				}
				if (rgb == 0) {
					pixels[i] = MapColors.KNOWN | MapColors.EMPTY;
					heights[i] = Math.max(min, top);
					continue;
				}
				pixels[i] = colorAt(r, x, y, z, rgb, min);
				heights[i] = y;
			}
		}
		return true;
	}

	/**
	 * Höhlenschnitt: ab {@code yStart} (knapp über dem Kopf) nach unten. Liegt die Startebene im Gestein, wird es
	 * übersprungen (Decke); danach folgt Luft bis zum Boden. Ohne Luft bis zur Grenze: Wand.
	 */
	public static boolean cave(ChunkReader r, int chunkX, int chunkZ, int yStart, int[] pixels, int[] heights) {
		if (!r.open(chunkX, chunkZ)) return false;
		int min = r.minY();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				int top = r.top(x, z);
				int y = Math.min(yStart, top);
				int low = Math.max(min, y - CAVE_RANGE);
				// Decke: Gestein über der Höhle überspringen.
				while (y >= low && solid(r.block(x, y, z))) y--;
				if (y < low) {
					pixels[i] = MapColors.KNOWN | MapColors.WALL;
					heights[i] = Math.min(yStart, top);
					continue;
				}
				// Luft (und Pflanzen ohne Farbe) bis zum Boden.
				int rgb = 0;
				while (y >= low) {
					int b = r.block(x, y, z);
					rgb = b & MapColors.RGB;
					if (rgb != 0) break;
					y--;
				}
				if (rgb == 0) {
					pixels[i] = MapColors.KNOWN | MapColors.EMPTY;
					heights[i] = low;
					continue;
				}
				pixels[i] = colorAt(r, x, y, z, rgb, min);
				heights[i] = y;
			}
		}
		return true;
	}

	/** Gestein im Sinne des Höhlenschnitts: hat Kartenfarbe und ist kein Wasser. */
	static boolean solid(int block) {
		int rgb = block & MapColors.RGB;
		return rgb != 0 && rgb != MapColors.MAP_WATER && (block & ChunkReader.AIR) == 0;
	}

	/** Farbe eines gefundenen Blocks inkl. Tönung; Wasser mit Tiefe und Grund. */
	private static int colorAt(ChunkReader r, int x, int y, int z, int rgb, int min) {
		if (rgb != MapColors.MAP_WATER) {
			return MapColors.KNOWN | MapColors.tint(rgb, r.tint(x, y, z));
		}
		int waterTint = r.tint(x, y, z);
		int depth = 1;
		int floor = 0;
		int yy = y - 1;
		while (yy >= min && depth < MAX_WATER) {
			int c = r.block(x, yy, z) & MapColors.RGB;
			if (c != 0 && c != MapColors.MAP_WATER) {
				floor = MapColors.tint(c, r.tint(x, yy, z));
				break;
			}
			depth++;
			yy--;
		}
		return MapColors.KNOWN | MapColors.WATER | MapColors.water(floor, waterTint, depth);
	}
}

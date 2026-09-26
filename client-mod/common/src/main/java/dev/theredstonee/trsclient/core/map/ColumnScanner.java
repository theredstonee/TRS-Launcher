package dev.theredstonee.trsclient.core.map;

/**
 * Tastet einen Chunk Spalte für Spalte ab: Oberfläche (oberster Block mit Kartenfarbe, Wasser mit Tiefe und Grund),
 * Oberfläche unter einem Dach-Schnitt (wie Oberfläche, aber erst ab einer Höhe knapp unter dem Dach über dem
 * Spieler) oder Höhlenschnitt (Boden unterhalb der Spielerebene: Gestein oberhalb wird übersprungen, geschlossene
 * Spalten werden Wand). Ergebnis je Spalte: Pixel ({@link MapColors}-Merker + Farbe) und Höhe; Index {@code z * 16 + x}.
 *
 * <p>Unsichtbare Blöcke (Barriere, Licht-Block, Strukturleere) meldet der {@link ChunkReader} als Luft – die
 * Abtastung schaut durch sie hindurch; reine Luft-Abschnitte werden in einem Schritt übersprungen.
 */
public final class ColumnScanner {
	/** So viele Blöcke ohne Kartenfarbe (Glas, Zäune …) werden unter der Oberkante höchstens übersprungen. */
	static final int SURFACE_STEPS = 48;
	/** So viele Blöcke liest eine Spalte höchstens (Luft unter hohen Barriere-Decken). */
	static final int MAX_READS = 400;
	/** Maximal gezählte Wassertiefe. */
	static final int MAX_WATER = 24;
	/** Höhlenansicht: so weit reicht die Suche unter die Startebene. */
	public static final int CAVE_RANGE = 40;
	/** Kein Dach-Schnitt (normale Oberfläche) bzw. kein Dach gefunden. */
	public static final int NO_CUT = Integer.MAX_VALUE;

	private ColumnScanner() {
	}

	/**
	 * Oberfläche eines Chunks.
	 *
	 * @return false, wenn der Chunk nicht gelesen werden konnte
	 */
	public static boolean surface(ChunkReader r, int chunkX, int chunkZ, int[] pixels, int[] heights) {
		return surface(r, chunkX, chunkZ, NO_CUT, pixels, heights);
	}

	/**
	 * Oberfläche eines Chunks, auf Wunsch unter einem Dach-Schnitt: Blöcke oberhalb von {@code cut} zählen nicht
	 * (Dächer, Decken von Hallen) – man sieht das Innere. Was genau auf Schnitthöhe steht (Wände, Hügel), zeigt
	 * seine eigene Farbe.
	 *
	 * @param cut höchste berücksichtigte Höhe oder {@link #NO_CUT}
	 * @return false, wenn der Chunk nicht gelesen werden konnte
	 */
	public static boolean surface(ChunkReader r, int chunkX, int chunkZ, int cut, int[] pixels, int[] heights) {
		if (!r.open(chunkX, chunkZ)) return false;
		int min = r.minY();
		// Merker je Abschnitt für diesen Chunk: 0 = unbekannt, 1 = nur Luft, 2 = mit Blöcken.
		byte[] sections = new byte[64];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				int top = r.top(x, z);
				int start = cut == NO_CUT ? top : Math.min(top, cut + 1);
				int y = start - 1;
				int rgb = 0;
				int colorless = 0;
				for (int reads = 0; y >= min && reads < MAX_READS; reads++) {
					int b = r.block(x, y, z);
					rgb = b & MapColors.RGB;
					if (rgb != 0) break;
					if ((b & ChunkReader.AIR) != 0) {
						if (sectionEmpty(r, sections, y, min)) {
							y = (y & ~15) - 1;
							continue;
						}
					} else if (++colorless >= SURFACE_STEPS) {
						break;
					}
					y--;
				}
				if (rgb == 0) {
					pixels[i] = MapColors.KNOWN | MapColors.EMPTY;
					heights[i] = Math.max(min, start);
					continue;
				}
				// Blumen/Feldfrüchte (Pflanzenfarbe ohne Tönung): Boden darunter zeigen, leicht grün – statt grellem Grün.
				if (rgb == MapColors.MAP_PLANT && r.tint(x, y, z) == -1) {
					int ground = groundBelow(r, x, y, z, min);
					if (ground != 0) {
						pixels[i] = MapColors.KNOWN | MapColors.mix(ground, MapColors.FLOWER_GREEN, 0.3f);
						heights[i] = y - 1;
						continue;
					}
				}
				pixels[i] = colorAt(r, x, y, z, rgb, min);
				heights[i] = y;
			}
		}
		return true;
	}

	/** Liegt y in einem reinen Luft-Abschnitt? (je Chunk gemerkt) */
	private static boolean sectionEmpty(ChunkReader r, byte[] memo, int y, int min) {
		int idx = (y - min) >> 4;
		if (idx < 0 || idx >= memo.length) return r.sectionEmpty(y);
		if (memo[idx] == 0) memo[idx] = (byte) (r.sectionEmpty(y) ? 1 : 2);
		return memo[idx] == 1;
	}

	/**
	 * Dach über dem Kopf: Höhe des ersten vollen, undurchsichtigen Blocks ({@link ChunkReader#OPAQUE}) in der
	 * Spalte (bx, bz) zwischen {@code headY + 1} und {@code headY + range}. Barrieren, Glas, Laub zählen nicht.
	 *
	 * @return die Höhe oder {@link #NO_CUT}, wenn dort kein Dach ist (oder der Chunk nicht geladen ist)
	 */
	public static int roofAbove(ChunkReader r, int bx, int headY, int bz, int range) {
		if (!r.open(bx >> 4, bz >> 4)) return NO_CUT;
		int lx = bx & 15, lz = bz & 15;
		int top = r.top(lx, lz);
		for (int y = headY + 1; y <= headY + range && y < top; y++) {
			int b = r.block(lx, y, lz);
			if ((b & ChunkReader.AIR) == 0 && (b & ChunkReader.OPAQUE) != 0) return y;
		}
		return NO_CUT;
	}

	/** Farbe (getönt) des nächsten festen Blocks unter y (höchstens 3 tiefer), 0 = keiner/Wasser. */
	private static int groundBelow(ChunkReader r, int x, int y, int z, int min) {
		for (int yy = y - 1; yy >= Math.max(min, y - 3); yy--) {
			int c = r.block(x, yy, z) & MapColors.RGB;
			if (c == 0) continue;
			if (c == MapColors.MAP_WATER) return 0;
			return MapColors.tint(c, r.tint(x, yy, z));
		}
		return 0;
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

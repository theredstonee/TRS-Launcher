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
	/** Karte v3: maximal gezählte Wassertiefe (Tiefenverlauf über eine längere Strecke). */
	static final int MAX_WATER_V3 = 40;
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
		return surface(r, chunkX, chunkZ, cut, false, pixels, heights);
	}

	/**
	 * Wie {@link #surface(ChunkReader, int, int, int, int[], int[])}, auf Wunsch mit Farben aus den Block-Texturen
	 * ({@link ChunkReader#textureColor}; wo keine bekannt ist, gilt die Vanilla-Kartenfarbe).
	 *
	 * @param textures true = Texturfarben, false = Vanilla-Kartenfarben
	 */
	public static boolean surface(ChunkReader r, int chunkX, int chunkZ, int cut, boolean textures, int[] pixels,
			int[] heights) {
		return surface(r, chunkX, chunkZ, cut, textures, pixels, heights, null, null);
	}

	/**
	 * Karte v3 ({@link MapPreview}): wie {@link #surface(ChunkReader, int, int, int, boolean, int[], int[])}, aber Wasser
	 * wird nicht fertig gemischt – das Pixel trägt die Farbe der Wasseroberfläche, dazu kommen Tiefe ({@code depths}) und
	 * ungeschattete Grundfarbe ({@code floors}, 0 = nicht gefunden) je Spalte. So schattiert erst das Zeichnen
	 * ({@link MapShade}) Tiefe und Grund (mit eigenem Relief). {@code depths} = null → wie bisher.
	 */
	public static boolean surface(ChunkReader r, int chunkX, int chunkZ, int cut, boolean textures, int[] pixels,
			int[] heights, int[] depths, int[] floors) {
		if (!r.open(chunkX, chunkZ)) return false;
		if (depths != null) {
			java.util.Arrays.fill(depths, 0);
			java.util.Arrays.fill(floors, 0);
		}
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
					int ground = groundBelow(r, x, y, z, min, textures, depths != null);
					if (ground != 0) {
						pixels[i] = MapColors.KNOWN | MapColors.mix(ground, MapColors.FLOWER_GREEN, depths != null ? 0.22f : 0.3f);
						heights[i] = y - 1;
						continue;
					}
				}
				pixels[i] = depths != null ? colorAtV3(r, x, y, z, rgb, min, textures, i, depths, floors)
						: colorAt(r, x, y, z, rgb, min, textures);
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
	private static int groundBelow(ChunkReader r, int x, int y, int z, int min, boolean textures, boolean v3) {
		for (int yy = y - 1; yy >= Math.max(min, y - 3); yy--) {
			int c = r.block(x, yy, z) & MapColors.RGB;
			if (c == 0) continue;
			if (c == MapColors.MAP_WATER) return 0;
			return v3 ? blockColorV3(r, x, yy, z, c, textures) : blockColor(r, x, yy, z, c, textures);
		}
		return 0;
	}

	/** Karte v3: Blockfarbe mit Tönung ohne die alten festen Abdunkel-Faktoren (siehe {@link MapShade#tint}). */
	static int blockColorV3(ChunkReader r, int x, int y, int z, int mapRgb, boolean textures) {
		if (textures) {
			int t = r.textureColor(x, y, z);
			if (t != -1) return (t & TexturePalette.TINTED) != 0 ? TexturePalette.tint(t, r.tint(x, y, z)) : t & MapColors.RGB;
		}
		return MapShade.tint(mapRgb, r.tint(x, y, z));
	}

	/**
	 * Karte v3: Farbe eines gefundenen Blocks; Wasser als Oberflächenfarbe, Tiefe und Grund in {@code depths}/{@code floors}.
	 */
	private static int colorAtV3(ChunkReader r, int x, int y, int z, int rgb, int min, boolean textures, int i, int[] depths,
			int[] floors) {
		if (rgb != MapColors.MAP_WATER) {
			// Laub/Pflanzen merken: beim Zeichnen dunkler und satter (Kalibrierung ohne neu abzutasten).
			return MapColors.KNOWN | (rgb == MapColors.MAP_PLANT ? MapColors.FOLIAGE : 0) | blockColorV3(r, x, y, z, rgb, textures);
		}
		int waterTint = r.tint(x, y, z);
		int depth = 1;
		int floor = 0;
		int yy = y - 1;
		while (yy >= min && depth < MAX_WATER_V3) {
			int c = r.block(x, yy, z) & MapColors.RGB;
			if (c != 0 && c != MapColors.MAP_WATER) {
				floor = blockColorV3(r, x, yy, z, c, textures);
				break;
			}
			depth++;
			yy--;
		}
		int water = -1;
		if (textures) {
			int t = r.textureColor(x, y, z);
			// Graue Wassertextur (ab 1.13) × Biomfarbe; schon farbige Texturen (1.7–1.12) unverändert – sonst würde
			// Blau mit Blau multipliziert und das Wasser violett.
			if (t != -1) {
				if (grayish(t)) water = TexturePalette.tint(t, waterTint == -1 ? MapColors.DEFAULT_WATER : waterTint);
				else if (bluish(t)) water = t & MapColors.RGB;
				// sonst (z. B. Platzhalter-Textur „fehlt“ in Violett): Biom-Wasserfarbe
			}
		}
		if (water == -1) water = MapShade.waterColor(waterTint);
		depths[i] = depth;
		// Grund nicht gefunden (tiefer als gezählt): 0 – dann gilt das Wasser als tief.
		floors[i] = floor;
		return MapColors.KNOWN | MapColors.WATER | (water & MapColors.RGB);
	}

	/**
	 * Farbe eines Blocks mit Tönung: aus der Textur (falls gewünscht und bekannt – getönte Texturen × Tönung),
	 * sonst die Vanilla-Kartenfarbe mit Tönung.
	 */
	static int blockColor(ChunkReader r, int x, int y, int z, int mapRgb, boolean textures) {
		if (textures) {
			int t = r.textureColor(x, y, z);
			if (t != -1) {
				return (t & TexturePalette.TINTED) != 0 ? TexturePalette.tint(t, r.tint(x, y, z)) : t & MapColors.RGB;
			}
		}
		return MapColors.tint(mapRgb, r.tint(x, y, z));
	}

	/** Sieht die Farbe nach Wasser aus (deutlich mehr Blau als Rot)? */
	static boolean bluish(int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		return b > r + 30 && b + 10 >= g;
	}

	/** Ist die Farbe (fast) grau – also eine Textur, die erst durch die Tönung ihre Farbe bekommt? */
	static boolean grayish(int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		return max - min <= 24;
	}

	/**
	 * Höhlenschnitt: ab {@code yStart} (knapp über dem Kopf) nach unten. Liegt die Startebene im Gestein, wird es
	 * übersprungen (Decke); danach folgt Luft bis zum Boden. Ohne Luft bis zur Grenze: Wand.
	 */
	public static boolean cave(ChunkReader r, int chunkX, int chunkZ, int yStart, int[] pixels, int[] heights) {
		return cave(r, chunkX, chunkZ, yStart, false, pixels, heights);
	}

	/** Höhlenschnitt, auf Wunsch mit Texturfarben (siehe {@link #surface(ChunkReader, int, int, int, boolean, int[], int[])}). */
	public static boolean cave(ChunkReader r, int chunkX, int chunkZ, int yStart, boolean textures, int[] pixels,
			int[] heights) {
		return cave(r, chunkX, chunkZ, yStart, textures, pixels, heights, null, null);
	}

	/** Höhlenschnitt, Karte v3 (Wasser mit Tiefe/Grund getrennt, siehe {@link #surface(ChunkReader, int, int, int, boolean, int[], int[], int[], int[])}). */
	public static boolean cave(ChunkReader r, int chunkX, int chunkZ, int yStart, boolean textures, int[] pixels,
			int[] heights, int[] depths, int[] floors) {
		if (!r.open(chunkX, chunkZ)) return false;
		if (depths != null) {
			java.util.Arrays.fill(depths, 0);
			java.util.Arrays.fill(floors, 0);
		}
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
				pixels[i] = depths != null ? colorAtV3(r, x, y, z, rgb, min, textures, i, depths, floors)
						: colorAt(r, x, y, z, rgb, min, textures);
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
	private static int colorAt(ChunkReader r, int x, int y, int z, int rgb, int min, boolean textures) {
		if (rgb != MapColors.MAP_WATER) {
			return MapColors.KNOWN | blockColor(r, x, y, z, rgb, textures);
		}
		int waterTint = r.tint(x, y, z);
		int depth = 1;
		int floor = 0;
		int yy = y - 1;
		while (yy >= min && depth < MAX_WATER) {
			int c = r.block(x, yy, z) & MapColors.RGB;
			if (c != 0 && c != MapColors.MAP_WATER) {
				floor = blockColor(r, x, yy, z, c, textures);
				break;
			}
			depth++;
			yy--;
		}
		if (textures) {
			// Wasser aus der Wassertextur (Graustufe) × Biom-Wasserfarbe.
			int t = r.textureColor(x, y, z);
			if (t != -1) {
				int water = TexturePalette.tint(t, waterTint == -1 ? MapColors.DEFAULT_WATER : waterTint);
				return MapColors.KNOWN | MapColors.WATER | MapColors.waterOver(floor, water, depth);
			}
		}
		return MapColors.KNOWN | MapColors.WATER | MapColors.water(floor, waterTint, depth);
	}
}

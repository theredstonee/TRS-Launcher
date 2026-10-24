package dev.theredstonee.trsclient.core.map;

/**
 * Kartenbereich von 128×128 Blöcken (8×8 Chunks): ungeschattete Pixel ({@link MapColors}) und Höhen.
 * Jede Änderung erhöht {@link #version}; Textur und Speicherung vergleichen damit, ob sie veraltet sind.
 * Nur aus dem Spiel-Thread benutzen (Plattenzugriffe arbeiten mit Kopien).
 */
public final class MapRegion {
	public static final int SHIFT = 7;
	public static final int SIZE = 1 << SHIFT;
	public static final int AREA = SIZE * SIZE;
	/** Kantenlänge der Übersicht (ein Pixel je 4×4 Blöcke). */
	public static final int SUMMARY = 32;

	public final int rx;
	public final int rz;
	public final int[] pixels = new int[AREA];
	public final short[] heights = new short[AREA];
	/** Zählt jede Änderung. */
	public int version;
	/** Stand der letzten Speicherung. */
	public int savedVersion;
	/** Zeitpunkt der ersten ungespeicherten Änderung (ms), 0 = alles gespeichert. */
	public long dirtySince;
	/** Letzte Benutzung (ms) – die ältesten Bereiche fliegen zuerst aus dem Speicher. */
	public long lastUsed;
	/** Übersicht 32×32 ARGB (für die weit herausgezoomte Weltkarte), null = noch nicht berechnet. */
	public int[] summary;
	/** {@link #version}, aus der {@link #summary} stammt. */
	public int summaryVersion = -1;
	/**
	 * Karte v3 ({@link MapPreview}): Wassertiefe je Pixel (Blöcke Wasser über dem Grund, 0 = kein Wasser/unbekannt).
	 * null, solange der Bereich kein Wasser mit Tiefe hat.
	 */
	public byte[] depth;
	/** Karte v3: ungeschattete Farbe des Grunds unter Wasser (0xRRGGBB, 0 = unbekannt); zusammen mit {@link #depth}. */
	public int[] floor;
	/** Karte v3: Übersicht 64×64 (ein Pixel je 2×2 Blöcke) für die Weltkarte zwischen ½ und 1 Pixel je Block. */
	public int[] summary64;
	/** {@link #version}, aus der {@link #summary64} stammt. */
	public int summary64Version = -1;

	public MapRegion(int rx, int rz) {
		this.rx = rx;
		this.rz = rz;
	}

	public static long key(int rx, int rz) {
		return ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
	}

	public static int keyX(long key) {
		return (int) (key >> 32);
	}

	public static int keyZ(long key) {
		return (int) key;
	}

	public long key() {
		return key(rx, rz);
	}

	/** Schreibt einen abgetasteten Chunk (je 256 Werte, Index z*16+x) an seine Stelle. */
	public void writeChunk(int chunkX, int chunkZ, int[] chunkPixels, int[] chunkHeights, long now) {
		int ox = (chunkX & 7) << 4;
		int oz = (chunkZ & 7) << 4;
		boolean changed = false;
		for (int z = 0; z < 16; z++) {
			int row = (oz + z) * SIZE + ox;
			for (int x = 0; x < 16; x++) {
				int p = chunkPixels[z * 16 + x];
				short h = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, chunkHeights[z * 16 + x]));
				if (pixels[row + x] != p || heights[row + x] != h) {
					pixels[row + x] = p;
					heights[row + x] = h;
					changed = true;
				}
			}
		}
		if (changed) touch(now);
	}

	/**
	 * Karte v3: wie {@link #writeChunk(int, int, int[], int[], long)}, dazu Wassertiefe und Grundfarbe je Spalte
	 * (je 256 Werte; null = kein Wasser im Chunk).
	 */
	public void writeChunk(int chunkX, int chunkZ, int[] chunkPixels, int[] chunkHeights, int[] chunkDepths, int[] chunkFloors,
			long now) {
		int before = version;
		writeChunk(chunkX, chunkZ, chunkPixels, chunkHeights, now);
		boolean anyWater = false;
		if (chunkDepths != null) {
			for (int i = 0; i < 256 && !anyWater; i++) anyWater = chunkDepths[i] > 0;
		}
		if (!anyWater && depth == null) return;
		ensureWater();
		int ox = (chunkX & 7) << 4;
		int oz = (chunkZ & 7) << 4;
		boolean changed = false;
		for (int z = 0; z < 16; z++) {
			int row = (oz + z) * SIZE + ox;
			for (int x = 0; x < 16; x++) {
				int d = anyWater ? Math.max(0, Math.min(255, chunkDepths[z * 16 + x])) : 0;
				int f = anyWater && chunkFloors != null ? chunkFloors[z * 16 + x] & MapColors.RGB : 0;
				if ((depth[row + x] & 0xFF) != d || floor[row + x] != f) {
					depth[row + x] = (byte) d;
					floor[row + x] = f;
					changed = true;
				}
			}
		}
		if (changed && version == before) touch(now);
	}

	/** Legt die Wasser-Ebenen (Karte v3) an, falls noch nicht da. */
	public void ensureWater() {
		if (depth == null) depth = new byte[AREA];
		if (floor == null) floor = new int[AREA];
	}

	/** Wassertiefe an Index {@code i} (0 = keine/unbekannt). */
	public int depthAt(int i) {
		return depth == null ? 0 : depth[i] & 0xFF;
	}

	/** Kopiert alle Daten (Pixel, Höhen, Wasser) aus {@code src}. */
	public void copyFrom(MapRegion src) {
		System.arraycopy(src.pixels, 0, pixels, 0, AREA);
		System.arraycopy(src.heights, 0, heights, 0, AREA);
		if (src.depth != null) {
			ensureWater();
			System.arraycopy(src.depth, 0, depth, 0, AREA);
			System.arraycopy(src.floor, 0, floor, 0, AREA);
		} else {
			depth = null;
			floor = null;
		}
	}

	/** Merkt eine Änderung. */
	public void touch(long now) {
		version++;
		if (dirtySince == 0) dirtySince = now;
	}

	/** Übernimmt Pixel aus einem geladenen Stand, wo hier noch nichts erkundet ist (Zusammenführen). */
	public boolean mergeFrom(int[] otherPixels, short[] otherHeights, long now) {
		return mergeFrom(otherPixels, otherHeights, null, null, now);
	}

	/** Wie {@link #mergeFrom(int[], short[], long)}, mit Wassertiefe/Grund (Karte v3, null = keine). */
	public boolean mergeFrom(int[] otherPixels, short[] otherHeights, byte[] otherDepth, int[] otherFloor, long now) {
		boolean changed = false;
		for (int i = 0; i < AREA; i++) {
			if ((pixels[i] & MapColors.KNOWN) == 0 && (otherPixels[i] & MapColors.KNOWN) != 0) {
				pixels[i] = otherPixels[i];
				heights[i] = otherHeights[i];
				if (otherDepth != null && otherFloor != null) {
					ensureWater();
					depth[i] = otherDepth[i];
					floor[i] = otherFloor[i];
				} else if (depth != null) {
					depth[i] = 0;
					floor[i] = 0;
				}
				changed = true;
			}
		}
		if (changed) version++;
		return changed;
	}

	public boolean dirty() {
		return version != savedVersion;
	}

	/** Pixel an lokaler Position (0..127). */
	public int pixel(int localX, int localZ) {
		return pixels[localZ * SIZE + localX];
	}

	public int height(int localX, int localZ) {
		return heights[localZ * SIZE + localX];
	}

	/** Gibt es überhaupt erkundete Pixel? */
	public boolean anyKnown() {
		for (int i = 0; i < AREA; i++) {
			if ((pixels[i] & MapColors.KNOWN) != 0) return true;
		}
		return false;
	}
}

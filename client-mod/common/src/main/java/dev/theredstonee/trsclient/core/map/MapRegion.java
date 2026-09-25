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

	/** Merkt eine Änderung. */
	public void touch(long now) {
		version++;
		if (dirtySince == 0) dirtySince = now;
	}

	/** Übernimmt Pixel aus einem geladenen Stand, wo hier noch nichts erkundet ist (Zusammenführen). */
	public boolean mergeFrom(int[] otherPixels, short[] otherHeights, long now) {
		boolean changed = false;
		for (int i = 0; i < AREA; i++) {
			if ((pixels[i] & MapColors.KNOWN) == 0 && (otherPixels[i] & MapColors.KNOWN) != 0) {
				pixels[i] = otherPixels[i];
				heights[i] = otherHeights[i];
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

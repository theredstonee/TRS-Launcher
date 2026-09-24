package dev.theredstonee.trsclient.core.redstone;

/**
 * Richtungen wie Minecrafts {@code Direction}/{@code EnumFacing} (gleiche Reihenfolge):
 * 0 unten, 1 oben, 2 Norden (-Z), 3 Süden (+Z), 4 Westen (-X), 5 Osten (+X).
 * In 1.7.10 zählt {@code ForgeDirection}/"side" genauso.
 */
public final class Dir {
	public static final int DOWN = 0;
	public static final int UP = 1;
	public static final int NORTH = 2;
	public static final int SOUTH = 3;
	public static final int WEST = 4;
	public static final int EAST = 5;

	private static final int[] DX = {0, 0, 0, 0, -1, 1};
	private static final int[] DY = {-1, 1, 0, 0, 0, 0};
	private static final int[] DZ = {0, 0, -1, 1, 0, 0};
	private static final int[] OPPOSITE = {1, 0, 3, 2, 5, 4};

	private Dir() {
	}

	public static int dx(int dir) {
		return DX[dir];
	}

	public static int dy(int dir) {
		return DY[dir];
	}

	public static int dz(int dir) {
		return DZ[dir];
	}

	public static int opposite(int dir) {
		return OPPOSITE[dir];
	}

	/** Die beiden waagerechten Richtungen quer zu {@code dir} (für die Seiteneingänge eines Komparators). */
	public static int left(int dir) {
		switch (dir) {
			case NORTH: return WEST;
			case SOUTH: return EAST;
			case WEST: return SOUTH;
			case EAST: return NORTH;
			default: return -1;
		}
	}

	public static int right(int dir) {
		int l = left(dir);
		return l < 0 ? -1 : OPPOSITE[l];
	}

	public static boolean horizontal(int dir) {
		return dir >= 2 && dir <= 5;
	}

	/** Blockposition als eine Zahl (wie {@code BlockPos#asLong}, aber unabhängig von der Version). */
	public static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}
}

package dev.theredstonee.trsclient.core.ui;

/**
 * Raster der Modul-Kacheln: verteilt {@code count} Kacheln auf die verfügbare Breite,
 * rechnet Spalten, Kachelgröße, Gesamthöhe und die maximale Scroll-Strecke aus.
 */
public final class TileGrid {
	public final int columns;
	public final int tileWidth;
	public final int tileHeight;
	public final int gap;
	public final int rows;
	/** Gesamthöhe aller Zeilen. */
	public final int contentHeight;
	/** Wie weit gescrollt werden kann (0 = passt). */
	public final int maxScroll;

	private TileGrid(int columns, int tileWidth, int tileHeight, int gap, int rows, int contentHeight, int maxScroll) {
		this.columns = columns;
		this.tileWidth = tileWidth;
		this.tileHeight = tileHeight;
		this.gap = gap;
		this.rows = rows;
		this.contentHeight = contentHeight;
		this.maxScroll = maxScroll;
	}

	/**
	 * @param width verfügbare Breite
	 * @param height sichtbare Höhe
	 * @param count Anzahl Kacheln
	 * @param minTileWidth Mindestbreite einer Kachel
	 * @param tileHeight Höhe einer Kachel
	 * @param gap Abstand zwischen den Kacheln
	 */
	public static TileGrid of(int width, int height, int count, int minTileWidth, int tileHeight, int gap) {
		int usable = Math.max(minTileWidth, width);
		int columns = Math.max(1, (usable + gap) / (minTileWidth + gap));
		int tileWidth = (usable - gap * (columns - 1)) / columns;
		int rows = count <= 0 ? 0 : (count + columns - 1) / columns;
		int contentHeight = rows == 0 ? 0 : rows * (tileHeight + gap) - gap;
		int maxScroll = Math.max(0, contentHeight - Math.max(0, height));
		return new TileGrid(columns, tileWidth, tileHeight, gap, rows, contentHeight, maxScroll);
	}

	/** x-Position der Kachel {@code index} (relativ zur linken Kante des Rasters). */
	public int x(int index) {
		return (index % columns) * (tileWidth + gap);
	}

	/** y-Position der Kachel {@code index} (relativ zur Oberkante, ohne Scroll). */
	public int y(int index) {
		return (index / columns) * (tileHeight + gap);
	}

	/** Index der Kachel an der Position (relativ zum Raster) oder -1. */
	public int indexAt(double px, double py, int count) {
		if (px < 0 || py < 0) return -1;
		int col = (int) (px / (tileWidth + gap));
		int row = (int) (py / (tileHeight + gap));
		if (col >= columns) return -1;
		// Abstand zwischen den Kacheln zählt nicht als Treffer.
		if (px - col * (tileWidth + gap) >= tileWidth) return -1;
		if (py - row * (tileHeight + gap) >= tileHeight) return -1;
		int index = row * columns + col;
		return index >= 0 && index < count ? index : -1;
	}

	/** Begrenzt einen Scroll-Wert auf den gültigen Bereich. */
	public int clampScroll(int scroll) {
		return Math.max(0, Math.min(scroll, maxScroll));
	}
}

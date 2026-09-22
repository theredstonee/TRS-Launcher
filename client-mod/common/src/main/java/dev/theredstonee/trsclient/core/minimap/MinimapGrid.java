package dev.theredstonee.trsclient.core.minimap;

/**
 * Rechnet den Chunk-Speicher in ein Gitter aus Bildschirm-Zellen um (die Karte selbst).
 * Gedreht wird beim Abtasten – dadurch braucht das Zeichnen nur achsenparallele Rechtecke
 * und funktioniert in jeder Minecraft-Version gleich.
 */
public final class MinimapGrid {
	/** Farbe für unbekanntes Gelände (noch nicht geladen). */
	public static final int UNKNOWN = 0x1A1A1F;

	private int cols;
	private int rows;
	private int[] cells = new int[0];

	public int cols() {
		return cols;
	}

	public int rows() {
		return rows;
	}

	/** Farbe einer Zelle als 0xRRGGBB. */
	public int cell(int col, int row) {
		return cells[row * cols + col];
	}

	/**
	 * Baut das Gitter neu auf.
	 *
	 * @param centerX        Weltkoordinate in der Mitte der Karte
	 * @param blocksPerCell  Blöcke je Zelle (Zoom)
	 * @param rotationDeg    Drehung der Karte: 0 = Norden oben; für "Blickrichtung oben"
	 *                       den Wert {@code yaw + 180} übergeben
	 */
	public void build(MinimapCache cache, double centerX, double centerZ, int cols, int rows,
			double blocksPerCell, double rotationDeg) {
		if (cols < 1) cols = 1;
		if (rows < 1) rows = 1;
		if (this.cols != cols || this.rows != rows || cells.length != cols * rows) {
			cells = new int[cols * rows];
			this.cols = cols;
			this.rows = rows;
		}
		double rad = Math.toRadians(rotationDeg);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		double halfCols = cols / 2.0;
		double halfRows = rows / 2.0;
		for (int row = 0; row < rows; row++) {
			double dy = (row + 0.5 - halfRows) * blocksPerCell;
			for (int col = 0; col < cols; col++) {
				double dx = (col + 0.5 - halfCols) * blocksPerCell;
				// Bildschirm → Welt (Drehung rückgängig machen).
				double wx = centerX + dx * cos - dy * sin;
				double wz = centerZ + dx * sin + dy * cos;
				cells[row * cols + col] = sample(cache, (int) Math.floor(wx), (int) Math.floor(wz));
			}
		}
	}

	/** Farbe eines Blocks inkl. Höhen-Schattierung wie auf Karten (Norden = Lichtquelle). */
	private static int sample(MinimapCache cache, int x, int z) {
		int rgb = cache.color(x, z);
		if (rgb == 0) return UNKNOWN;
		int here = cache.height(x, z);
		int north = cache.color(x, z - 1) == 0 ? here : cache.height(x, z - 1);
		return shade(rgb, here - north);
	}

	/** Heller, wenn der Block höher liegt als sein nördlicher Nachbar (wie Vanilla-Karten). */
	public static int shade(int rgb, int deltaHeight) {
		int factor = deltaHeight > 0 ? 255 : (deltaHeight < 0 ? 180 : 220);
		int r = ((rgb >> 16) & 0xFF) * factor / 255;
		int g = ((rgb >> 8) & 0xFF) * factor / 255;
		int b = (rgb & 0xFF) * factor / 255;
		return (r << 16) | (g << 8) | b;
	}

	/**
	 * Rechnet eine Weltposition in Zellen-Koordinaten der Karte um (Wegpunkte, Spieler).
	 *
	 * @param out {x, y} in Zellen (Bruchteile möglich, kann außerhalb liegen)
	 */
	public static void toCell(double centerX, double centerZ, int cols, int rows, double blocksPerCell,
			double rotationDeg, double worldX, double worldZ, double[] out) {
		double rad = Math.toRadians(rotationDeg);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		double dx = worldX - centerX;
		double dz = worldZ - centerZ;
		// Welt → Bildschirm (Drehung anwenden).
		double sx = dx * cos + dz * sin;
		double sy = -dx * sin + dz * cos;
		out[0] = cols / 2.0 + sx / blocksPerCell;
		out[1] = rows / 2.0 + sy / blocksPerCell;
	}

	/** Hält einen Punkt im Rechteck [0,cols]×[0,rows] (Rand-Markierung für ferne Wegpunkte). */
	public static void clamp(double[] point, int cols, int rows, double margin) {
		point[0] = Math.max(margin, Math.min(cols - margin, point[0]));
		point[1] = Math.max(margin, Math.min(rows - margin, point[1]));
	}
}

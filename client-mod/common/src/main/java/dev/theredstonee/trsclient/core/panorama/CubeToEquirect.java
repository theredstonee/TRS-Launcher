package dev.theredstonee.trsclient.core.panorama;

/**
 * Setzt die sechs Würfelbilder eines Panoramas zu einem 360°-Bild (equirektangular, 2:1) zusammen.
 *
 * <p>Reihenfolge und Ausrichtung wie Vanillas Panorama ({@code panorama_0..5.png}, auch der Titelbildschirm):
 * 0 = Blickrichtung, 1 = 90° rechts, 2 = hinten, 3 = 90° links, 4 = oben, 5 = unten. Jedes Bild ist quadratisch mit
 * 90° Sichtfeld; „oben“ entsteht durch Kippen nach oben (Bildoberkante zeigt dann nach hinten), „unten“ durch Kippen
 * nach unten (Bildoberkante zeigt nach vorn).
 *
 * <p>Im Ergebnis liegt die Blickrichtung in der Bildmitte, rechts davon „rechts“; oben ist der Zenit.
 */
public final class CubeToEquirect {
	public static final int FRONT = 0, RIGHT = 1, BACK = 2, LEFT = 3, UP = 4, DOWN = 5;

	private CubeToEquirect() {
	}

	/**
	 * @param faces sechs quadratische ARGB-Bilder der Kantenlänge {@code size} (Zeilen von oben nach unten)
	 * @param outW  Breite des Ergebnisses (Höhe = outW / 2)
	 */
	public static int[] compose(int[][] faces, int size, int outW) {
		if (faces == null || faces.length != 6) throw new IllegalArgumentException("6 Bilder nötig");
		for (int[] f : faces) {
			if (f == null || f.length < size * size) throw new IllegalArgumentException("Bildgröße");
		}
		if (outW < 2 || (outW & 1) != 0) throw new IllegalArgumentException("Breite " + outW);
		int outH = outW / 2;
		int[] out = new int[outW * outH];
		double[] hit = new double[3];
		double[] sinLon = new double[outW], cosLon = new double[outW];
		for (int x = 0; x < outW; x++) {
			double lon = (x + 0.5) * 2 * Math.PI / outW - Math.PI;
			sinLon[x] = Math.sin(lon);
			cosLon[x] = Math.cos(lon);
		}
		for (int y = 0; y < outH; y++) {
			double lat = Math.PI / 2 - (y + 0.5) * Math.PI / outH;
			double cosLat = Math.cos(lat), sinLat = Math.sin(lat);
			for (int x = 0; x < outW; x++) {
				// Richtung: x rechts, y oben, z vorn.
				double dx = sinLon[x] * cosLat;
				double dy = sinLat;
				double dz = cosLon[x] * cosLat;
				project(dx, dy, dz, hit);
				out[y * outW + x] = sample(faces[(int) hit[0]], size, hit[1], hit[2]);
			}
		}
		return out;
	}

	/**
	 * Welche Würfelseite trifft die Richtung, und wo (u, v in 0..1, v von oben)?
	 *
	 * @param out {face, u, v}
	 */
	static void project(double dx, double dy, double dz, double[] out) {
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		int face;
		double right, up, forward;
		if (ay >= ax && ay >= az) {
			if (dy > 0) {
				// Oben: vorn = +y, rechts = +x, oben im Bild = hinten (-z).
				face = UP;
				forward = dy;
				right = dx;
				up = -dz;
			} else {
				// Unten: vorn = -y, rechts = +x, oben im Bild = vorn (+z).
				face = DOWN;
				forward = -dy;
				right = dx;
				up = dz;
			}
		} else if (az >= ax) {
			if (dz > 0) {
				face = FRONT;
				forward = dz;
				right = dx;
			} else {
				face = BACK;
				forward = -dz;
				right = -dx;
			}
			up = dy;
		} else {
			if (dx > 0) {
				// 90° rechts gedreht: vorn = +x, rechts = -z.
				face = RIGHT;
				forward = dx;
				right = -dz;
			} else {
				face = LEFT;
				forward = -dx;
				right = dz;
			}
			up = dy;
		}
		out[0] = face;
		out[1] = (right / forward + 1) / 2;
		out[2] = (1 - up / forward) / 2;
	}

	/** Bilinear gefiltert (Ränder geklemmt). */
	static int sample(int[] img, int size, double u, double v) {
		double fx = u * size - 0.5, fy = v * size - 0.5;
		int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
		double tx = fx - x0, ty = fy - y0;
		int x1 = clamp(x0 + 1, size), y1 = clamp(y0 + 1, size);
		x0 = clamp(x0, size);
		y0 = clamp(y0, size);
		int c00 = img[y0 * size + x0], c10 = img[y0 * size + x1], c01 = img[y1 * size + x0], c11 = img[y1 * size + x1];
		int result = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			double a = (c00 >>> shift & 0xFF) * (1 - tx) + (c10 >>> shift & 0xFF) * tx;
			double b = (c01 >>> shift & 0xFF) * (1 - tx) + (c11 >>> shift & 0xFF) * tx;
			int ch = (int) Math.round(a * (1 - ty) + b * ty);
			result |= Math.max(0, Math.min(255, ch)) << shift;
		}
		return result;
	}

	private static int clamp(int v, int size) {
		return v < 0 ? 0 : v >= size ? size - 1 : v;
	}

	/** Mittleres Quadrat eines Bildes (für eigene Aufnahmen mit 90° senkrechtem Sichtfeld). */
	public static int[] cropCenterSquare(int[] argb, int width, int height) {
		int size = Math.min(width, height);
		int ox = (width - size) / 2, oy = (height - size) / 2;
		int[] out = new int[size * size];
		for (int y = 0; y < size; y++) System.arraycopy(argb, (oy + y) * width + ox, out, y * size, size);
		return out;
	}
}

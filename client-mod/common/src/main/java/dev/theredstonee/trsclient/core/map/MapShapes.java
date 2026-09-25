package dev.theredstonee.trsclient.core.map;

/**
 * Zerlegt eine Kartenform (Kreis, gedrehtes Quadrat) in waagerechte Streifen im Karten-Koordinatensystem.
 * Die Kartentextur wird mit der Drehung der Karte gezeichnet; ein Streifen ist dort ein achsenparalleles
 * Textur-Rechteck – so braucht die runde (und die gedrehte eckige) Minimap weder Schablone noch Scissor.
 *
 * <p>Jeder Streifen liegt ganz in der Form; zusammen decken sie die um {@code tolerance} verkleinerte Form ab.
 * Den schmalen Rest verdeckt der Rahmen. Ausgabe: je Streifen {@code y0, y1, x0, x1} relativ zur Mitte.
 */
public final class MapShapes {
	private MapShapes() {
	}

	/**
	 * Kreis mit Radius {@code radius}. Die Streifenhöhen wachsen zur Mitte hin (dort ändert sich die Breite kaum) –
	 * typisch 10–20 Streifen.
	 *
	 * @return Anzahl Streifen (in {@code out} je 4 Werte)
	 */
	public static int circle(float radius, float tolerance, float[] out) {
		if (radius <= 0f) return 0;
		float t = Math.max(0.05f, Math.min(tolerance, radius * 0.5f));
		float inner = radius - t;
		float c = radius * radius - inner * inner;
		int max = out.length / 4;
		int n = 0;
		float y0 = -inner;
		while (y0 < inner && n < max) {
			float y1;
			if (y0 < 0f) {
				float rest = y0 * y0 - c;
				y1 = rest > 0f ? -(float) Math.sqrt(rest) : (float) Math.sqrt(c);
				// Mindestens ein kleines Stück vorankommen (Rundung).
				if (y1 <= y0 + 0.01f) y1 = y0 + 0.01f;
			} else {
				y1 = (float) Math.sqrt(y0 * y0 + c);
			}
			if (y1 > inner) y1 = inner;
			float ymax = Math.max(Math.abs(y0), Math.abs(y1));
			float half = (float) Math.sqrt(Math.max(0f, radius * radius - ymax * ymax));
			out[n * 4] = y0;
			out[n * 4 + 1] = y1;
			out[n * 4 + 2] = -half;
			out[n * 4 + 3] = half;
			n++;
			y0 = y1;
		}
		return n;
	}

	/**
	 * Quadrat mit halber Kantenlänge {@code half}, um {@code angle} (Bogenmaß) gedreht – in gleich hohen Streifen
	 * (Höhe = Toleranz: der Rand weicht höchstens so weit von der Kante ab).
	 */
	public static int rotatedSquare(float half, float angle, float tolerance, float[] out) {
		float cos = (float) Math.cos(angle);
		float sin = (float) Math.sin(angle);
		float[] vx = new float[4];
		float[] vy = new float[4];
		float[][] corners = {{-half, -half}, {half, -half}, {half, half}, {-half, half}};
		float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			vx[i] = corners[i][0] * cos - corners[i][1] * sin;
			vy[i] = corners[i][0] * sin + corners[i][1] * cos;
			top = Math.min(top, vy[i]);
			bottom = Math.max(bottom, vy[i]);
		}
		float h = Math.max(0.05f, tolerance);
		int max = out.length / 4;
		int n = 0;
		for (float y0 = top; y0 < bottom && n < max; y0 += h) {
			float y1 = Math.min(bottom, y0 + h);
			float a0 = extentMin(vx, vy, y0), a1 = extentMin(vx, vy, y1);
			float b0 = extentMax(vx, vy, y0), b1 = extentMax(vx, vy, y1);
			float x0 = Math.max(a0, a1);
			float x1 = Math.min(b0, b1);
			if (x1 <= x0) continue;
			out[n * 4] = y0;
			out[n * 4 + 1] = y1;
			out[n * 4 + 2] = x0;
			out[n * 4 + 3] = x1;
			n++;
		}
		return n;
	}

	private static float extentMin(float[] vx, float[] vy, float y) {
		float best = Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			float x = cross(vx[i], vy[i], vx[(i + 1) & 3], vy[(i + 1) & 3], y);
			if (!Float.isNaN(x)) best = Math.min(best, x);
		}
		return best;
	}

	private static float extentMax(float[] vx, float[] vy, float y) {
		float best = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			float x = cross(vx[i], vy[i], vx[(i + 1) & 3], vy[(i + 1) & 3], y);
			if (!Float.isNaN(x)) best = Math.max(best, x);
		}
		return best;
	}

	/** Schnittpunkt der Kante mit der Waagerechten y (NaN = keiner). */
	private static float cross(float x0, float y0, float x1, float y1, float y) {
		if ((y < Math.min(y0, y1)) || (y > Math.max(y0, y1))) return Float.NaN;
		if (y0 == y1) return Float.NaN;
		return x0 + (x1 - x0) * (y - y0) / (y1 - y0);
	}

	/** Liegt der Punkt (x, y) im Kreis? (Hilfe für Tests/Rand-Markierungen.) */
	public static boolean inCircle(float x, float y, float radius) {
		return x * x + y * y <= radius * radius;
	}
}

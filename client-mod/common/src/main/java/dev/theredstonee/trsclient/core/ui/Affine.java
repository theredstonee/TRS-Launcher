package dev.theredstonee.trsclient.core.ui;

/**
 * Beliebige 2D-Abbildung (Drehung, Scherung, ungleiche Skalierung) über die einfachen Canvas-Operationen.
 * Jede Version kann verschieben, drehen und skalieren – eine freie Matrix aber nicht überall (1.16:
 * Matrix4f ohne Laden, Legacy: GL). Deshalb wird die 2×2-Matrix per Singulärwertzerlegung in
 * Drehung · Skalierung · Drehung zerlegt; das geht mit jeder Version gleich.
 *
 * <p>Abbildung: x' = a·x + c·y + e, y' = b·x + d·y + f (GUI-Koordinaten, y nach unten).
 * Nur orientierungserhaltende Abbildungen (Determinante &gt; 0) – gespiegelte Flächen würden in manchen
 * Versionen weggeschnitten (Rückseiten-Culling); der Aufrufer (z. B. {@code SkinModel}) sortiert sie aus.
 */
public final class Affine {
	private Affine() {
	}

	/**
	 * Zerlegt [[a, c], [b, d]] = R(theta) · diag(sx, sy) · R(phi).
	 *
	 * @return {theta, sx, sy, phi}; bei Determinante ≤ 0 oder entarteter Matrix null
	 */
	public static float[] decompose(float a, float b, float c, float d) {
		double det = (double) a * d - (double) b * c;
		if (!(det > 1e-9)) return null;
		double e = (a + d) / 2.0;
		double f = (a - d) / 2.0;
		double g = (b + c) / 2.0;
		double h = (b - c) / 2.0;
		double q = Math.sqrt(e * e + h * h);
		double r = Math.sqrt(f * f + g * g);
		double sx = q + r;
		double sy = q - r;
		if (!(sy > 1e-9)) return null;
		double a1 = Math.atan2(g, f);
		double a2 = Math.atan2(h, e);
		double theta = (a2 + a1) / 2.0;
		double phi = (a2 - a1) / 2.0;
		return new float[]{(float) theta, (float) sx, (float) sy, (float) phi};
	}

	/**
	 * Multipliziert die Abbildung auf die aktuelle Transformation des Canvas (zwischen push/pop aufrufen).
	 *
	 * @return false, wenn sie nicht darstellbar ist (gespiegelt/entartet) – dann nichts zeichnen
	 */
	public static boolean apply(Canvas canvas, float a, float b, float c, float d, float e, float f) {
		float[] s = decompose(a, b, c, d);
		if (s == null) return false;
		canvas.translate(e, f);
		if (s[0] != 0f) canvas.rotate(s[0]);
		canvas.scale(s[1], s[2]);
		if (s[3] != 0f) canvas.rotate(s[3]);
		return true;
	}

	/**
	 * Zeichnet einen Texturausschnitt ({@code u}, {@code v}, {@code w}×{@code h} Texel) in ein achsenparalleles
	 * Rechteck – der häufige Fall (Vorschaubilder, Köpfe in Listen).
	 */
	public static void image(Canvas canvas, TextureRef texture, float x, float y, float width, float height, float u, float v,
			int w, int h, int argb) {
		if (texture == null || w <= 0 || h <= 0 || width <= 0 || height <= 0) return;
		canvas.push();
		canvas.translate(x, y);
		canvas.scale(width / w, height / h);
		canvas.image(texture, u, v, w, h, argb);
		canvas.pop();
	}
}

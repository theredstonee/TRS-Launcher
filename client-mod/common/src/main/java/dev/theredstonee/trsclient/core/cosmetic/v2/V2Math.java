package dev.theredstonee.trsclient.core.cosmetic.v2;

/**
 * 4×4-Matrizen spaltenweise (wie three.js/JOML und {@code cosmetic-format.mjs}) – 1:1 portiert, damit Studio, Launcher,
 * Website und TRS Client dieselben Zahlen rechnen. {@code double}, ohne Allokation (Ziel-Arrays werden übergeben).
 */
public final class V2Math {
	private V2Math() {
	}

	public static double rad(double deg) {
		return deg * Math.PI / 180.0;
	}

	public static void identity(double[] m) {
		for (int i = 0; i < 16; i++) m[i] = 0;
		m[0] = m[5] = m[10] = m[15] = 1;
	}

	/** out = a · b (out darf a oder b sein, {@code tmp} = 16 Plätze Zwischenspeicher). */
	public static void multiply(double[] out, double[] a, double[] b, double[] tmp) {
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				tmp[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
			}
		}
		System.arraycopy(tmp, 0, out, 0, 16);
	}

	public static void translation(double[] m, double x, double y, double z) {
		identity(m);
		m[12] = x;
		m[13] = y;
		m[14] = z;
	}

	/** Drehung in Grad, Reihenfolge ZYX: erst um x, dann um y, dann um z (R = Rz · Ry · Rx). */
	public static void rotationZYX(double[] m, double rx, double ry, double rz) {
		double a = rad(rx), b = rad(ry), c = rad(rz);
		double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cc = Math.cos(c), sc = Math.sin(c);
		m[0] = cc * cb;
		m[1] = sc * cb;
		m[2] = -sb;
		m[3] = 0;
		m[4] = cc * sb * sa - sc * ca;
		m[5] = sc * sb * sa + cc * ca;
		m[6] = cb * sa;
		m[7] = 0;
		m[8] = cc * sb * ca + sc * sa;
		m[9] = sc * sb * ca - cc * sa;
		m[10] = cb * ca;
		m[11] = 0;
		m[12] = 0;
		m[13] = 0;
		m[14] = 0;
		m[15] = 1;
	}

	public static void scaling(double[] m, double x, double y, double z) {
		identity(m);
		m[0] = x;
		m[5] = y;
		m[10] = z;
	}

	/** Punkt transformieren; Ergebnis in {@code out[o..o+2]}. */
	public static void transformPoint(double[] m, double x, double y, double z, double[] out, int o) {
		out[o] = m[0] * x + m[4] * y + m[8] * z + m[12];
		out[o + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
		out[o + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
	}

	/** Richtung transformieren (ohne Verschiebung). */
	public static void transformDir(double[] m, double x, double y, double z, double[] out, int o) {
		out[o] = m[0] * x + m[4] * y + m[8] * z;
		out[o + 1] = m[1] * x + m[5] * y + m[9] * z;
		out[o + 2] = m[2] * x + m[6] * y + m[10] * z;
	}

	/**
	 * Umkehrung einer affinen Matrix (Drehung, Verschiebung, Skalierung, auch Spiegelung) – für die Kamera im
	 * Modellraum. false bei singulärer Matrix.
	 */
	public static boolean invertAffine(double[] m, double[] out) {
		double a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
		double A = e * i - f * h;
		double B = -(d * i - f * g);
		double C = d * h - e * g;
		double det = a * A + b * B + c * C;
		if (Math.abs(det) < 1e-12 || Double.isNaN(det)) return false;
		double[] inv = {
			A / det, B / det, C / det,
			-(b * i - c * h) / det, (a * i - c * g) / det, -(a * h - b * g) / det,
			(b * f - c * e) / det, -(a * f - c * d) / det, (a * e - b * d) / det,
		};
		out[0] = inv[0];
		out[1] = inv[1];
		out[2] = inv[2];
		out[3] = 0;
		out[4] = inv[3];
		out[5] = inv[4];
		out[6] = inv[5];
		out[7] = 0;
		out[8] = inv[6];
		out[9] = inv[7];
		out[10] = inv[8];
		out[11] = 0;
		double tx = m[12], ty = m[13], tz = m[14];
		out[12] = -(out[0] * tx + out[4] * ty + out[8] * tz);
		out[13] = -(out[1] * tx + out[5] * ty + out[9] * tz);
		out[14] = -(out[2] * tx + out[6] * ty + out[10] * tz);
		out[15] = 1;
		return true;
	}

	/**
	 * Kameraposition im {@code ModelPart}-Raum des Kopfes (Blöcke) → im Anhängepunkt-Raum (Einheiten = Skin-Pixel,
	 * y oben, vorne +z). {@code pose} = spaltenweise Matrix Modell → Kamera (Kamera im Ursprung, z. B.
	 * {@code PoseStack.last().pose()} oder {@code GL_MODELVIEW}). Ergebnis in {@code out[0..2]}; false = unbrauchbar.
	 */
	public static boolean eyeInAttachSpace(float[] pose, double[] out) {
		double[] m = new double[16];
		for (int k = 0; k < 16; k++) m[k] = pose[k];
		double[] inv = new double[16];
		if (!invertAffine(m, inv)) return false;
		// Ursprung (Auge) im ModelPart-Raum
		double x = inv[12], y = inv[13], z = inv[14];
		if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return false;
		out[0] = x * 16.0;
		out[1] = -y * 16.0;
		out[2] = -z * 16.0;
		return true;
	}
}

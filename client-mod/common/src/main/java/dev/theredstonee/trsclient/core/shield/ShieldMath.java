package dev.theredstonee.trsclient.core.shield;

/**
 * Kleine 4×4-Matrix-Mathematik für die Schild-Haltung (Zeilen-Reihenfolge, Spaltenvektoren: {@code p' = M·p}, wie
 * Minecrafts PoseStack: translate → rotate → scale ergibt {@code T·R·S}). Nur double-Arrays, Java 8.
 */
public final class ShieldMath {
	private ShieldMath() {
	}

	public static double[] identity() {
		return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	public static double[] translation(double x, double y, double z) {
		return new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1};
	}

	public static double[] scale(double s) {
		return new double[]{s, 0, 0, 0, 0, s, 0, 0, 0, 0, s, 0, 0, 0, 0, 1};
	}

	public static double[] scale(double sx, double sy, double sz) {
		return new double[]{sx, 0, 0, 0, 0, sy, 0, 0, 0, 0, sz, 0, 0, 0, 0, 1};
	}

	public static double[] rotX(double deg) {
		double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
		return new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
	}

	public static double[] rotY(double deg) {
		double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
		return new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
	}

	public static double[] rotZ(double deg) {
		double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
		return new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	public static double[] mul(double[] a, double[] b) {
		double[] r = new double[16];
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				double v = 0;
				for (int k = 0; k < 4; k++) v += a[i * 4 + k] * b[k * 4 + j];
				r[i * 4 + j] = v;
			}
		}
		return r;
	}

	public static double[] mul(double[]... ms) {
		double[] r = identity();
		for (double[] m : ms) r = mul(r, m);
		return r;
	}

	/** Punkt transformieren (w = 1). */
	public static double[] apply(double[] m, double x, double y, double z) {
		return new double[]{
				m[0] * x + m[1] * y + m[2] * z + m[3],
				m[4] * x + m[5] * y + m[6] * z + m[7],
				m[8] * x + m[9] * y + m[10] * z + m[11]};
	}

	/**
	 * Matrix der Haltung {@code p}: {@code T·Rx·Ry·Rz·S}; in der linken Hand gespiegelt (X-Versatz sowie Drehung um
	 * Y und Z umgedreht – das entspricht der Spiegelung an der Y-Z-Ebene).
	 */
	public static double[] pose(ShieldPose p, boolean leftArm) {
		double sign = leftArm ? -1 : 1;
		return mul(translation(sign * p.x, p.y, p.z), rotX(p.rotX), rotY(sign * p.rotY), rotZ(sign * p.rotZ), scale(p.scale));
	}

	/**
	 * Vanillas Anzeige-Transformation eines Gegenstandsmodells (ItemTransform#apply): Verschiebung in Modellpixeln
	 * (1/16 Block), Drehung XYZ in Grad, Skalierung; die linke Hand spiegelt X-Versatz und Drehung um Y/Z.
	 */
	public static double[] display(double tx, double ty, double tz, double rx, double ry, double rz, double s, boolean leftHand) {
		double sign = leftHand ? -1 : 1;
		return mul(translation(sign * tx / 16.0, ty / 16.0, tz / 16.0), rotX(rx), rotY(sign * ry), rotZ(sign * rz), scale(s));
	}

	/**
	 * Inverse einer Matrix aus Verschiebung, Drehung und gleichmäßiger Skalierung (s &gt; 0).
	 */
	public static double[] invertSimilarity(double[] m) {
		double s2 = m[0] * m[0] + m[4] * m[4] + m[8] * m[8];
		double[] r = new double[16];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) r[i * 4 + j] = m[j * 4 + i] / s2;
		}
		for (int i = 0; i < 3; i++) {
			r[i * 4 + 3] = -(r[i * 4] * m[3] + r[i * 4 + 1] * m[7] + r[i * 4 + 2] * m[11]);
		}
		r[15] = 1;
		return r;
	}

	/** Zerlegung in Verschiebung, Drehung (Quaternion x, y, z, w) und gleichmäßige Skalierung. */
	public static final class Decomposed {
		public double tx, ty, tz;
		public double qx, qy, qz, qw = 1;
		public double scale = 1;
	}

	public static Decomposed decompose(double[] m) {
		Decomposed d = new Decomposed();
		d.tx = m[3];
		d.ty = m[7];
		d.tz = m[11];
		double det = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8]);
		double s = Math.cbrt(det);
		if (!(s > 1e-9)) s = 1e-9;
		d.scale = s;
		double r00 = m[0] / s, r01 = m[1] / s, r02 = m[2] / s;
		double r10 = m[4] / s, r11 = m[5] / s, r12 = m[6] / s;
		double r20 = m[8] / s, r21 = m[9] / s, r22 = m[10] / s;
		double trace = r00 + r11 + r22;
		if (trace > 0) {
			double t = Math.sqrt(trace + 1.0) * 2;
			d.qw = 0.25 * t;
			d.qx = (r21 - r12) / t;
			d.qy = (r02 - r20) / t;
			d.qz = (r10 - r01) / t;
		} else if (r00 > r11 && r00 > r22) {
			double t = Math.sqrt(1.0 + r00 - r11 - r22) * 2;
			d.qw = (r21 - r12) / t;
			d.qx = 0.25 * t;
			d.qy = (r01 + r10) / t;
			d.qz = (r02 + r20) / t;
		} else if (r11 > r22) {
			double t = Math.sqrt(1.0 + r11 - r00 - r22) * 2;
			d.qw = (r02 - r20) / t;
			d.qx = (r01 + r10) / t;
			d.qy = 0.25 * t;
			d.qz = (r12 + r21) / t;
		} else {
			double t = Math.sqrt(1.0 + r22 - r00 - r11) * 2;
			d.qw = (r10 - r01) / t;
			d.qx = (r02 + r20) / t;
			d.qy = (r12 + r21) / t;
			d.qz = 0.25 * t;
		}
		double n = Math.sqrt(d.qx * d.qx + d.qy * d.qy + d.qz * d.qz + d.qw * d.qw);
		d.qx /= n;
		d.qy /= n;
		d.qz /= n;
		d.qw /= n;
		if (d.qw < 0) {
			d.qx = -d.qx;
			d.qy = -d.qy;
			d.qz = -d.qz;
			d.qw = -d.qw;
		}
		return d;
	}

	/** Zusammensetzen: {@code T·R(q)·S}. */
	public static double[] compose(double tx, double ty, double tz, double qx, double qy, double qz, double qw, double s) {
		double xx = qx * qx, yy = qy * qy, zz = qz * qz, xy = qx * qy, xz = qx * qz, yz = qy * qz, wx = qw * qx, wy = qw * qy, wz = qw * qz;
		return new double[]{
				(1 - 2 * (yy + zz)) * s, 2 * (xy - wz) * s, 2 * (xz + wy) * s, tx,
				2 * (xy + wz) * s, (1 - 2 * (xx + zz)) * s, 2 * (yz - wx) * s, ty,
				2 * (xz - wy) * s, 2 * (yz + wx) * s, (1 - 2 * (xx + yy)) * s, tz,
				0, 0, 0, 1};
	}

	/**
	 * Zwischen zwei Haltungen überblenden: Verschiebung und Größe linear, Drehung sphärisch (slerp). f = 0 → a,
	 * f = 1 → b (genau, ohne Rundungssprung).
	 */
	public static void interpolate(Decomposed a, Decomposed b, double f, ShieldTransform out) {
		if (f <= 0) {
			out.set(a.tx, a.ty, a.tz, a.qx, a.qy, a.qz, a.qw, a.scale);
			return;
		}
		if (f >= 1) {
			out.set(b.tx, b.ty, b.tz, b.qx, b.qy, b.qz, b.qw, b.scale);
			return;
		}
		double bx = b.qx, by = b.qy, bz = b.qz, bw = b.qw;
		double dot = a.qx * bx + a.qy * by + a.qz * bz + a.qw * bw;
		if (dot < 0) {
			dot = -dot;
			bx = -bx;
			by = -by;
			bz = -bz;
			bw = -bw;
		}
		double wa, wb;
		if (dot > 0.9995) {
			wa = 1 - f;
			wb = f;
		} else {
			double theta = Math.acos(dot);
			double sin = Math.sin(theta);
			wa = Math.sin((1 - f) * theta) / sin;
			wb = Math.sin(f * theta) / sin;
		}
		double qx = wa * a.qx + wb * bx, qy = wa * a.qy + wb * by, qz = wa * a.qz + wb * bz, qw = wa * a.qw + wb * bw;
		double n = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
		out.set(a.tx + (b.tx - a.tx) * f, a.ty + (b.ty - a.ty) * f, a.tz + (b.tz - a.tz) * f,
				qx / n, qy / n, qz / n, qw / n, a.scale + (b.scale - a.scale) * f);
	}
}

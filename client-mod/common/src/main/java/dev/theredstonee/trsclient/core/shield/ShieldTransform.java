package dev.theredstonee.trsclient.core.shield;

/**
 * Ergebnis für den Hand-Renderer: erst verschieben ({@link #x}, {@link #y}, {@link #z}), dann drehen (Quaternion
 * {@link #qx} … {@link #qw}), dann gleichmäßig skalieren ({@link #scale}) – im Arm-Raum, direkt bevor Minecraft das
 * Schild mit seiner Anzeige-Transformation zeichnet. Wiederverwendbar (keine Allokation je Bild).
 */
public final class ShieldTransform {
	public float x;
	public float y;
	public float z;
	public float qx;
	public float qy;
	public float qz;
	public float qw = 1;
	public float scale = 1;

	public ShieldTransform set(double x, double y, double z, double qx, double qy, double qz, double qw, double scale) {
		this.x = (float) x;
		this.y = (float) y;
		this.z = (float) z;
		this.qx = (float) qx;
		this.qy = (float) qy;
		this.qz = (float) qz;
		this.qw = (float) qw;
		this.scale = (float) scale;
		return this;
	}

	public ShieldTransform identity() {
		return set(0, 0, 0, 0, 0, 0, 1, 1);
	}

	/** Ändert nichts (dann kann der Renderer den Aufruf sparen)? */
	public boolean isIdentity() {
		return Math.abs(x) < 1e-6f && Math.abs(y) < 1e-6f && Math.abs(z) < 1e-6f && Math.abs(qx) < 1e-6f && Math.abs(qy) < 1e-6f
				&& Math.abs(qz) < 1e-6f && Math.abs(scale - 1) < 1e-6f;
	}

	/** Drehwinkel in Grad (für GL-Versionen mit glRotate). */
	public float angleDegrees() {
		double w = Math.max(-1, Math.min(1, qw));
		return (float) Math.toDegrees(2 * Math.acos(w));
	}

	/** Drehachse X (normiert; bei Nulldrehung die X-Achse). */
	public float axisX() {
		double s = axisLength();
		return s < 1e-6 ? 1f : (float) (qx / s);
	}

	public float axisY() {
		double s = axisLength();
		return s < 1e-6 ? 0f : (float) (qy / s);
	}

	public float axisZ() {
		double s = axisLength();
		return s < 1e-6 ? 0f : (float) (qz / s);
	}

	private double axisLength() {
		return Math.sqrt((double) qx * qx + (double) qy * qy + (double) qz * qz);
	}

	@Override
	public String toString() {
		return "ShieldTransform[t=" + x + "," + y + "," + z + " q=" + qx + "," + qy + "," + qz + "," + qw + " s=" + scale + "]";
	}
}

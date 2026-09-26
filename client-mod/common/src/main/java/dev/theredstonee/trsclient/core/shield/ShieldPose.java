package dev.theredstonee.trsclient.core.shield;

/**
 * Eine Schild-Haltung als Versatz zur Vanilla-Haltung in der 1. Person: Verschiebung (Blöcke), Drehung um X, Y und Z
 * (Grad, in dieser Reihenfolge wie bei Minecrafts Anzeige-Transformationen) und Größe (Faktor, 1 = Vanilla).
 *
 * <p>Die Werte gelten für das Schild in der <b>rechten</b> Hand: +X = nach außen (rechts), +Y = nach oben,
 * +Z = zur Kamera hin. In der linken Hand wird gespiegelt (X, Drehung um Y und um Z umgedreht), damit „nach außen“
 * auf beiden Seiten nach außen zeigt.
 */
public final class ShieldPose {
	/** Keine Änderung (= Vanilla). */
	public static final ShieldPose NEUTRAL = new ShieldPose(0, 0, 0, 0, 0, 0, 1);

	public final double x;
	public final double y;
	public final double z;
	public final double rotX;
	public final double rotY;
	public final double rotZ;
	public final double scale;

	public ShieldPose(double x, double y, double z, double rotX, double rotY, double rotZ, double scale) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.rotX = rotX;
		this.rotY = rotY;
		this.rotZ = rotZ;
		this.scale = scale;
	}

	/** Werte in Einstellungs-Reihenfolge (X, Y, Z, Drehung X/Y/Z, Größe in Prozent). */
	public double[] toSettings() {
		return new double[]{x, y, z, rotX, rotY, rotZ, scale * 100.0};
	}

	/** Gegenstück zu {@link #toSettings()}. */
	public static ShieldPose fromSettings(double[] v) {
		return new ShieldPose(v[0], v[1], v[2], v[3], v[4], v[5], v[6] / 100.0);
	}

	/** Gleiche Werte (mit kleiner Toleranz für Rundungen der Regler)? */
	public boolean near(ShieldPose o) {
		return o != null && Math.abs(x - o.x) < 1e-6 && Math.abs(y - o.y) < 1e-6 && Math.abs(z - o.z) < 1e-6
				&& Math.abs(rotX - o.rotX) < 1e-6 && Math.abs(rotY - o.rotY) < 1e-6 && Math.abs(rotZ - o.rotZ) < 1e-6
				&& Math.abs(scale - o.scale) < 1e-6;
	}

	@Override
	public String toString() {
		return "ShieldPose[" + x + ", " + y + ", " + z + " | " + rotX + "°, " + rotY + "°, " + rotZ + "° | ×" + scale + "]";
	}
}

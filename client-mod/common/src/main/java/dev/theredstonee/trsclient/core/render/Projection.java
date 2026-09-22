package dev.theredstonee.trsclient.core.render;

import java.util.Locale;

/**
 * Rechnet einen Punkt der Welt in Bildschirmkoordinaten um (für Wegpunkt-Markierungen).
 * Bewusst eigene Mathematik statt der Projektionsmatrix: die liegt in jeder Minecraft-Version
 * woanders, die Formel dagegen ist überall gleich und lässt sich testen.
 *
 * <p>Minecraft-Winkel: yaw 0 = Süden (+Z), steigt nach Westen; pitch &gt; 0 = nach unten.
 */
public final class Projection {
	/** Punkte näher als das gelten als "hinter der Kamera". */
	private static final double NEAR = 0.05;

	private Projection() {
	}

	/**
	 * Projiziert (px, py, pz) auf den Bildschirm.
	 *
	 * @param fovDeg senkrechtes Sichtfeld in Grad (das tatsächlich benutzte, inkl. Zoom)
	 * @param out    Ergebnis: {x, y, Abstand vor der Kamera}
	 * @return false, wenn der Punkt hinter der Kamera liegt
	 */
	public static boolean project(double camX, double camY, double camZ, float yawDeg, float pitchDeg,
			double fovDeg, int width, int height, double px, double py, double pz, double[] out) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double cosPitch = Math.cos(pitch);
		double sinPitch = Math.sin(pitch);
		double cosYaw = Math.cos(yaw);
		double sinYaw = Math.sin(yaw);

		// Blickrichtung, Rechts- und Hoch-Vektor der Kamera.
		double fx = -sinYaw * cosPitch;
		double fy = -sinPitch;
		double fz = cosYaw * cosPitch;
		double rx = -cosYaw;
		double rz = -sinYaw;
		// Hoch-Vektor = rechts × vorne (der Rechts-Vektor hat kein y).
		double ux = -rz * fy;
		double uy = rz * fx - rx * fz;
		double uz = rx * fy;

		double dx = px - camX;
		double dy = py - camY;
		double dz = pz - camZ;
		double depth = dx * fx + dy * fy + dz * fz;
		if (depth <= NEAR) return false;

		double right = dx * rx + dz * rz;
		double up = dx * ux + dy * uy + dz * uz;
		double tan = Math.tan(Math.toRadians(fovDeg) / 2.0);
		if (tan <= 0) return false;
		double aspect = height == 0 ? 1 : (double) width / height;

		double ndcX = right / (depth * tan * aspect);
		double ndcY = up / (depth * tan);
		out[0] = width / 2.0 * (1 + ndcX);
		out[1] = height / 2.0 * (1 - ndcY);
		out[2] = depth;
		return true;
	}

	/** Entfernung als "12 m" bzw. "1,2 km". */
	public static String distanceLabel(double blocks) {
		if (blocks >= 1000) return String.format(Locale.GERMANY, "%.1f km", blocks / 1000.0);
		return String.format(Locale.ROOT, "%d m", Math.round(blocks));
	}
}

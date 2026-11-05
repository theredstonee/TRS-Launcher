package dev.theredstonee.trsclient.core.waypoint.live;

/**
 * Wann die eigene Position wieder gesendet wird (API.md §41): höchstens alle 3 s, und dann nur bei mehr als 2 Blöcken
 * Bewegung, Dimensionswechsel oder deutlich anderer Blickrichtung – sonst ein Lebenszeichen alle 15 s.
 */
final class PositionGate {
	static final long MIN_INTERVAL_MS = 3_000L;
	static final long KEEPALIVE_MS = 15_000L;
	static final double MIN_MOVE = 2.0;
	/** Blickrichtung zählt ab so vielen Grad als Bewegung (Kopf auf der Karte zeigt sie). */
	static final float MIN_TURN = 45f;

	private boolean any;
	private long lastAt;
	private double lastX, lastY, lastZ;
	private String lastDim;
	private float lastYaw;

	boolean due(long now, double x, double y, double z, String dim, float yaw) {
		if (!any) return true;
		long dt = now - lastAt;
		if (dt < 0) return true;
		if (dt < MIN_INTERVAL_MS) return false;
		if (dim == null ? lastDim != null : !dim.equals(lastDim)) return true;
		double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
		if (dx * dx + dy * dy + dz * dz > MIN_MOVE * MIN_MOVE) return true;
		if (turn(yaw, lastYaw) >= MIN_TURN) return true;
		return dt >= KEEPALIVE_MS;
	}

	void sent(long now, double x, double y, double z, String dim, float yaw) {
		any = true;
		lastAt = now;
		lastX = x;
		lastY = y;
		lastZ = z;
		lastDim = dim;
		lastYaw = yaw;
	}

	void reset() {
		any = false;
	}

	/** Kleinster Winkel zwischen zwei Blickrichtungen (0…180°). */
	static float turn(float a, float b) {
		float d = Math.abs(((a - b) % 360f + 540f) % 360f - 180f);
		return d;
	}
}

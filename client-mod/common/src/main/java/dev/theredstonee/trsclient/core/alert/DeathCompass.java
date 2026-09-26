package dev.theredstonee.trsclient.core.alert;

/**
 * Richtung zum letzten Todespunkt (nach dem Wiederbeleben): Entfernung und ein Pfeil relativ zur Blickrichtung. Die
 * Anzeige verschwindet, wenn man angekommen ist, nach {@link #SHOW_MS} oder in einer anderen Dimension.
 */
public final class DeathCompass {
	public static final long SHOW_MS = 10 * 60_000L;
	/** Näher als das gilt als angekommen. */
	public static final double ARRIVED = 4.0;

	private boolean active;
	private double x, y, z;
	private String dimension;
	private long since;

	/** Neuer Todespunkt (vom Wegpunkt-System). */
	public void set(double x, double y, double z, String dimension, long now) {
		this.x = x + 0.5;
		this.y = y;
		this.z = z + 0.5;
		this.dimension = dimension;
		this.since = now;
		this.active = true;
	}

	public void clear() {
		active = false;
	}

	/** Sichtbar für diese Position/Dimension? Schaltet sich bei Ankunft oder nach Ablauf ab. */
	public boolean visible(double px, double pz, String dim, long now) {
		if (!active) return false;
		if (now - since > SHOW_MS) {
			active = false;
			return false;
		}
		if (dimension != null && dim != null && !dimension.equals(dim)) return false;
		if (distance(px, pz) < ARRIVED) {
			active = false;
			return false;
		}
		return true;
	}

	public double distance(double px, double pz) {
		double dx = x - px, dz = z - pz;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Achtel-Sektor 0–7 (0 = geradeaus, 2 = rechts, 4 = hinten, 6 = links). */
	public static int sector(double dx, double dz, float yaw) {
		// Richtung zum Ziel als Minecraft-Gierwinkel.
		double target = Math.toDegrees(Math.atan2(-dx, dz));
		double rel = target - yaw;
		rel = ((rel % 360) + 360) % 360;
		return (int) Math.floor((rel + 22.5) / 45.0) % 8;
	}

	public boolean active() {
		return active;
	}

	public int blockX() {
		return (int) Math.floor(x);
	}

	public int blockY() {
		return (int) Math.floor(y);
	}

	public int blockZ() {
		return (int) Math.floor(z);
	}
}

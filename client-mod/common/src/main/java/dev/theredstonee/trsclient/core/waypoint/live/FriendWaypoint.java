package dev.theredstonee.trsclient.core.waypoint.live;

/**
 * Geteilter Wegpunkt eines Freundes (bereinigt aus {@code GET /v1/waypoint-share/friends}). Dimension als
 * Namensraum-Kennung ({@code minecraft:overworld}), Farbe 0xRRGGBB.
 */
public final class FriendWaypoint {
	public final String owner;
	public final String id;
	public final String name;
	public final int x;
	public final int y;
	public final int z;
	public final String dimension;
	public final int color;
	public final boolean death;

	public FriendWaypoint(String owner, String id, String name, int x, int y, int z, String dimension, int color, boolean death) {
		this.owner = owner;
		this.id = id;
		this.name = name;
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimension = dimension;
		this.color = color & 0xFFFFFF;
		this.death = death;
	}

	/** Entfernung (räumlich) zu einem Punkt. */
	public double distanceTo(double px, double py, double pz) {
		double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}

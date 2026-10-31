package dev.theredstonee.trsclient.core.waypoint.live;

/** Letzte gemeldete Position eines Freundes ({@code wpshare_position}); {@code at} = lokale Empfangszeit (ms). */
public final class FriendPosition {
	public final double x;
	public final double y;
	public final double z;
	/** Namensraum-Kennung. */
	public final String dimension;
	/** Minecraft-Yaw in Grad. */
	public final float yaw;
	public final long at;

	public FriendPosition(double x, double y, double z, String dimension, float yaw, long at) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimension = dimension;
		this.yaw = yaw;
		this.at = at;
	}
}

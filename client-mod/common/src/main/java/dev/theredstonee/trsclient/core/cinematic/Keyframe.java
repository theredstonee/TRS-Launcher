package dev.theredstonee.trsclient.core.cinematic;

/** Ein Punkt einer Kamera-Fahrt: Position, Blickrichtung (Grad, wie Minecraft) und Sichtfeld (Grad). Unveränderlich. */
public final class Keyframe {
	public final double x;
	public final double y;
	public final double z;
	/** Gierwinkel in Grad (Minecraft: 0 = Süden, 90 = Westen). */
	public final float yaw;
	/** Nickwinkel in Grad (−90 oben … 90 unten). */
	public final float pitch;
	/** Sichtfeld in Grad. */
	public final float fov;

	public Keyframe(double x, double y, double z, float yaw, float pitch, float fov) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.pitch = pitch;
		this.fov = fov;
	}

	/** Aus einer Kamera-Lage (Werte werden geprüft und begrenzt; NaN → 0 bzw. 70°). */
	public static Keyframe of(CameraPose pose) {
		return new Keyframe(finite(pose.x), finite(pose.y), finite(pose.z), (float) finite(pose.yaw),
				clampPitch((float) finite(pose.pitch)), clampFov(pose.fov));
	}

	/** Plausible Werte (für das Laden der Datei). */
	public boolean valid() {
		return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(yaw) && Float.isFinite(pitch)
				&& Float.isFinite(fov) && Math.abs(x) < 3.0e7 && Math.abs(z) < 3.0e7 && Math.abs(y) < 1.0e5;
	}

	/** Gleiche Werte, aber begrenzt (Nickwinkel ±90°, Sichtfeld 1…170°). */
	public Keyframe normalized() {
		return new Keyframe(x, y, z, yaw, clampPitch(pitch), clampFov(fov));
	}

	public double distanceTo(Keyframe o) {
		double dx = o.x - x;
		double dy = o.y - y;
		double dz = o.z - z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	static double finite(double v) {
		return Double.isFinite(v) ? v : 0;
	}

	static float clampPitch(float p) {
		if (!Float.isFinite(p)) return 0f;
		return Math.max(-90f, Math.min(90f, p));
	}

	static float clampFov(float f) {
		if (!Float.isFinite(f)) return 70f;
		return Math.max(1f, Math.min(170f, f));
	}
}

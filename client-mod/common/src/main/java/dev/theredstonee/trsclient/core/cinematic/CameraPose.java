package dev.theredstonee.trsclient.core.cinematic;

/** Veränderliche Kamera-Lage – Ergebnis je Bild (wird wiederverwendet, keine Allokation je Bild). */
public final class CameraPose {
	public double x;
	public double y;
	public double z;
	public float yaw;
	public float pitch;
	public float fov;

	public CameraPose set(double x, double y, double z, float yaw, float pitch, float fov) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.pitch = pitch;
		this.fov = fov;
		return this;
	}

	public CameraPose set(Keyframe k) {
		return set(k.x, k.y, k.z, k.yaw, k.pitch, k.fov);
	}

	@Override
	public String toString() {
		return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f) yaw %.1f pitch %.1f fov %.1f", x, y, z, yaw, pitch, fov);
	}
}

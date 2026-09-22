package dev.theredstonee.trsclient.core.camera;

/**
 * Freelook: eigene Kamera-Blickwinkel, die sich bei gehaltener Taste unabhängig von der
 * Spielfigur drehen. Mausbewegung wird wie bei Minecraft skaliert (0,15° je Einheit).
 */
public final class FreelookState {
	private static final float SENSITIVITY = 0.15F;

	private boolean active;
	private float yaw;
	private float pitch;

	/** Startet mit dem aktuellen Blick der Spielfigur. */
	public void start(float playerYaw, float playerPitch) {
		active = true;
		yaw = playerYaw;
		pitch = clampPitch(playerPitch);
	}

	public void stop() {
		active = false;
	}

	/** Mausbewegung (wie {@code Entity#turn(yRot, xRot)}). */
	public void turn(double yawDelta, double pitchDelta) {
		yaw += (float) yawDelta * SENSITIVITY;
		pitch = clampPitch(pitch + (float) pitchDelta * SENSITIVITY);
	}

	public boolean active() {
		return active;
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	private static float clampPitch(float p) {
		return Math.max(-90.0F, Math.min(90.0F, p));
	}
}

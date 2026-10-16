package dev.theredstonee.trsclient.core.cinematic;

/**
 * Freie Kamera: fliegt mit WASD/Leertaste/Schleichen (Sprinten = schneller) unabhängig von der Spielfigur, die Maus
 * dreht sie. Bewegt wird je Bild nach der Wanduhr (gleich schnell bei jeder Bildrate) mit kurzem, weichem Anfahren und
 * Abbremsen; mit Fair Play hält {@link CameraCollision} sie an massiven Blöcken auf.
 *
 * <p>Richtungen wie in Minecraft: Blick (yaw) 0° = +Z, 90° = −X; vorwärts/seitwärts bleiben waagerecht (wie der
 * Kreativ-Flug), hoch/runter senkrecht. Nur Spiel-/Render-Thread.
 */
public final class Freecam {
	/** Tasten der freien Kamera in diesem Bild. */
	public static final class Input {
		public boolean forward;
		public boolean back;
		public boolean left;
		public boolean right;
		public boolean up;
		public boolean down;
		/** Sprint-Taste: schneller. */
		public boolean boost;

		public Input clear() {
			forward = back = left = right = up = down = boost = false;
			return this;
		}

		public Input set(Input o) {
			forward = o.forward;
			back = o.back;
			left = o.left;
			right = o.right;
			up = o.up;
			down = o.down;
			boost = o.boost;
			return this;
		}
	}

	/** Mausempfindlichkeit wie {@code Entity#turn}. */
	static final float SENSITIVITY = 0.15F;
	/** Faktor der Sprint-Taste. */
	static final double BOOST = 2.5;
	/** Wie schnell die Geschwindigkeit dem Ziel folgt (je Sekunde). */
	static final double RESPONSE = 12;
	/** Längster Zeitschritt (Ruckler, Pause) in Sekunden. */
	static final double MAX_DT = 0.1;

	private boolean active;
	private final double[] pos = new double[3];
	private final double[] vel = new double[3];
	private float yaw;
	private float pitch;
	private long lastNanos;

	/** Freie Kamera an der aktuellen Kamera-Lage beginnen; mit {@code blocks} (Fair Play) erst einen freien Punkt suchen. */
	public void enter(CameraPose at, long nanos, CameraCollision.Blocks blocks) {
		active = true;
		pos[0] = at.x;
		pos[1] = at.y;
		pos[2] = at.z;
		vel[0] = vel[1] = vel[2] = 0;
		yaw = at.yaw;
		pitch = clampPitch(at.pitch);
		lastNanos = nanos;
		CameraCollision.settle(pos, blocks);
	}

	public void exit() {
		active = false;
		vel[0] = vel[1] = vel[2] = 0;
	}

	public boolean active() {
		return active;
	}

	/** Mausbewegung (Rohwerte wie für {@code Entity#turn}). */
	public void turn(double yawDelta, double pitchDelta) {
		yaw += (float) yawDelta * SENSITIVITY;
		pitch = clampPitch(pitch + (float) pitchDelta * SENSITIVITY);
	}

	/**
	 * Ein Bild: Geschwindigkeit aus den Tasten, Bewegung mit oder ohne Kollision.
	 *
	 * @param speed Blöcke je Sekunde
	 * @param blocks Wände (Fair Play) oder null (durch alles)
	 */
	public void update(Input in, double speed, CameraCollision.Blocks blocks, long nanos) {
		if (!active) return;
		double dt = (nanos - lastNanos) / 1e9;
		lastNanos = nanos;
		if (!(dt > 0)) return;
		if (dt > MAX_DT) dt = MAX_DT;

		double fwd = (in.forward ? 1 : 0) - (in.back ? 1 : 0);
		double side = (in.left ? 1 : 0) - (in.right ? 1 : 0);
		double vert = (in.up ? 1 : 0) - (in.down ? 1 : 0);
		double len = Math.sqrt(fwd * fwd + side * side);
		if (len > 1) {
			fwd /= len;
			side /= len;
		}
		double s = speed * (in.boost ? BOOST : 1);
		double rad = Math.toRadians(yaw);
		double sin = Math.sin(rad), cos = Math.cos(rad);
		// Entity#getInputVector: vorwärts (−sin, cos), links (cos, sin).
		double tx = (side * cos - fwd * sin) * s;
		double tz = (fwd * cos + side * sin) * s;
		double ty = vert * s;
		double k = 1 - Math.exp(-RESPONSE * dt);
		vel[0] += (tx - vel[0]) * k;
		vel[1] += (ty - vel[1]) * k;
		vel[2] += (tz - vel[2]) * k;
		if (Math.abs(vel[0]) < 1e-4 && tx == 0) vel[0] = 0;
		if (Math.abs(vel[1]) < 1e-4 && ty == 0) vel[1] = 0;
		if (Math.abs(vel[2]) < 1e-4 && tz == 0) vel[2] = 0;

		int hit = CameraCollision.move(pos, vel[0] * dt, vel[1] * dt, vel[2] * dt, blocks);
		if ((hit & CameraCollision.HIT_X) != 0) vel[0] = 0;
		if ((hit & CameraCollision.HIT_Y) != 0) vel[1] = 0;
		if ((hit & CameraCollision.HIT_Z) != 0) vel[2] = 0;
	}

	/** Lage in {@code out}; Sichtfeld 0 = das normale (inkl. Zoom) bleibt. */
	public CameraPose pose(CameraPose out) {
		return out.set(pos[0], pos[1], pos[2], yaw, pitch, 0f);
	}

	public double x() {
		return pos[0];
	}

	public double y() {
		return pos[1];
	}

	public double z() {
		return pos[2];
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	/** Für Selbsttests: Lage setzen (ohne Kollision). */
	public void teleport(double x, double y, double z, float yaw, float pitch) {
		pos[0] = x;
		pos[1] = y;
		pos[2] = z;
		this.yaw = yaw;
		this.pitch = clampPitch(pitch);
		vel[0] = vel[1] = vel[2] = 0;
	}

	private static float clampPitch(float p) {
		return p < -90 ? -90 : (p > 90 ? 90 : p);
	}
}

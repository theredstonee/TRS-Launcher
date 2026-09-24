package dev.theredstonee.trsclient.core.emote;

/**
 * Wendet eine Emote-Pose auf die sechs Teile eines Spielermodells an – reine Mathematik, kennt Minecraft nicht.
 *
 * <p>Eingabe/Ausgabe ist die Vanilla-Haltung nach {@code setupAnim}: je Teil ({@link #HEAD}, {@link #BODY},
 * {@link #RIGHT_ARM}, {@link #LEFT_ARM}, {@link #RIGHT_LEG}, {@link #LEFT_LEG}) sechs Werte
 * {@code x, y, z, xRot, yRot, zRot} (Drehpunkt in Pixeln, Winkel in Bogenmaß, Minecrafts Modellraum). Minecraft
 * dreht Teile in der Reihenfolge Z·Y·X um ihren Drehpunkt – genauso rechnet diese Klasse.
 *
 * <p>Der Oberkörper dreht sich um die Hüfte (Oberkante der Beine): Drehpunkt von Körper, Kopf und Schultern
 * werden mitbewegt, Kopf und Arme erben die Drehung des Oberkörpers. Mit {@code weight} < 1 wird zwischen
 * Vanilla-Haltung und Emote überblendet. Nicht thread-sicher (Zwischenspeicher) – eine Instanz je Render-Thread.
 */
public final class EmoteRig {
	public static final int HEAD = 0;
	public static final int BODY = 1;
	public static final int RIGHT_ARM = 2;
	public static final int LEFT_ARM = 3;
	public static final int RIGHT_LEG = 4;
	public static final int LEFT_LEG = 5;
	public static final int PARTS = 6;
	/** Werte je Teil: x, y, z, xRot, yRot, zRot. */
	public static final int STRIDE = 6;

	/** Länge des Oberkörpers (Hals → Hüfte) und Schulterabstand zur Mitte in Pixeln. */
	static final float TORSO_LENGTH = 12f;
	static final float SHOULDER_X = 5f;

	private final float[] target = new float[PARTS * STRIDE];
	private final float[] torso = new float[9];
	private final float[] local = new float[9];
	private final float[] world = new float[9];
	private final float[] vec = new float[3];
	private final float[] euler = new float[3];

	/**
	 * Überschreibt {@code parts} (Vanilla-Haltung) mit der Pose {@code frame} ({@link Channel}), überblendet mit
	 * {@code weight} (0 = Vanilla, 1 = Emote). {@code mask}: welche Glieder das Emote selbst führt
	 * ({@link Channel#MASK_RIGHT_ARM} …); die übrigen behalten ihre Vanilla-Winkel.
	 */
	public void apply(float[] parts, float[] frame, int mask, float weight) {
		if (weight <= 0f) return;
		float w = Math.min(1f, weight);
		System.arraycopy(parts, 0, target, 0, parts.length);

		float rootX = frame[Channel.ROOT_X];
		float rootY = -frame[Channel.ROOT_Y];
		float rootZ = -frame[Channel.ROOT_Z];
		float lean = frame[Channel.TORSO_LEAN];
		float twist = frame[Channel.TORSO_TWIST];
		float roll = frame[Channel.TORSO_ROLL];
		rotZYX(roll, twist, lean, torso);

		// Körper: Hüfte bleibt stehen, der Hals wandert.
		int b = BODY * STRIDE;
		float bx = parts[b], by = parts[b + 1], bz = parts[b + 2];
		mul(torso, 0f, TORSO_LENGTH, 0f, vec);
		float px = bx - vec[0] + rootX;
		float py = by + TORSO_LENGTH - vec[1] + rootY;
		float pz = bz - vec[2] + rootZ;
		set(b, px, py, pz, lean, twist, roll);

		// Kopf: sitzt auf dem Hals, Blickrichtung (Vanilla) + Emote-Kopfbewegung, erbt den Oberkörper.
		int h = HEAD * STRIDE;
		mul(torso, parts[h] - bx, parts[h + 1] - by, parts[h + 2] - bz, vec);
		float headX = parts[h + 3] + frame[Channel.HEAD_X];
		rotZYX(parts[h + 5] + frame[Channel.HEAD_Z], parts[h + 4] + frame[Channel.HEAD_Y], headX, local);
		compose(h, px + vec[0], py + vec[1], pz + vec[2], headX);

		// Arme: Schultern gehen mit dem Oberkörper; Winkel aus dem Emote oder (nicht geführt) aus Vanilla.
		float shoulders = frame[Channel.SHOULDERS];
		for (int side = 0; side < 2; side++) {
			int part = side == 0 ? RIGHT_ARM : LEFT_ARM;
			int o = part * STRIDE;
			boolean led = (mask & (side == 0 ? Channel.MASK_RIGHT_ARM : Channel.MASK_LEFT_ARM)) != 0;
			int ch = side == 0 ? Channel.R_ARM_X : Channel.L_ARM_X;
			float sx = side == 0 ? -SHOULDER_X : SHOULDER_X;
			mul(torso, sx, parts[o + 1] - by - shoulders, 0f, vec);
			float armX = led ? frame[ch] : parts[o + 3];
			if (led) rotZYX(frame[ch + 2], frame[ch + 1], armX, local);
			else rotZYX(parts[o + 5], parts[o + 4], armX, local);
			compose(o, px + vec[0], py + vec[1], pz + vec[2], armX);
		}

		// Beine: bleiben an der Hüfte (nur das ganze Modell verschiebt sich).
		for (int side = 0; side < 2; side++) {
			int part = side == 0 ? RIGHT_LEG : LEFT_LEG;
			int o = part * STRIDE;
			boolean led = (mask & (side == 0 ? Channel.MASK_RIGHT_LEG : Channel.MASK_LEFT_LEG)) != 0;
			int ch = side == 0 ? Channel.R_LEG_X : Channel.L_LEG_X;
			target[o] = parts[o] + rootX;
			target[o + 1] = parts[o + 1] + rootY;
			target[o + 2] = parts[o + 2] + rootZ;
			if (led) {
				target[o + 3] = frame[ch];
				target[o + 4] = frame[ch + 1];
				target[o + 5] = frame[ch + 2];
			}
		}

		if (w >= 1f) {
			System.arraycopy(target, 0, parts, 0, parts.length);
			return;
		}
		for (int i = 0; i < parts.length; i++) parts[i] += (target[i] - parts[i]) * w;
	}

	/**
	 * Teil {@code o}: Drehpunkt setzen, Winkel = Oberkörper · lokal (in {@link #local}). {@code localX} = gewollter
	 * x-Winkel, um die Mehrdeutigkeit (±2π) aufzulösen – sonst dreht z. B. ein Arm bei −180° beim Überblenden
	 * einmal über den Rücken.
	 */
	private void compose(int o, float x, float y, float z, float localX) {
		mul3(torso, local, world);
		toEuler(world, euler);
		float refX = target[BODY * STRIDE + 3] + localX;
		target[o] = x;
		target[o + 1] = y;
		target[o + 2] = z;
		target[o + 3] = nearest(euler[0], refX);
		target[o + 4] = euler[1];
		target[o + 5] = euler[2];
	}

	private void set(int o, float x, float y, float z, float xr, float yr, float zr) {
		target[o] = x;
		target[o + 1] = y;
		target[o + 2] = z;
		target[o + 3] = xr;
		target[o + 4] = yr;
		target[o + 5] = zr;
	}

	// --- 3×3-Matrizen (zeilenweise) ---

	/** R = Rz(z)·Ry(y)·Rx(x) – Minecrafts Reihenfolge ({@code Quaternionf.rotationZYX}). */
	static void rotZYX(float z, float y, float x, float[] m) {
		float ca = (float) Math.cos(x), sa = (float) Math.sin(x);
		float cb = (float) Math.cos(y), sb = (float) Math.sin(y);
		float cc = (float) Math.cos(z), sc = (float) Math.sin(z);
		m[0] = cb * cc;
		m[1] = sa * sb * cc - ca * sc;
		m[2] = ca * sb * cc + sa * sc;
		m[3] = cb * sc;
		m[4] = sa * sb * sc + ca * cc;
		m[5] = ca * sb * sc - sa * cc;
		m[6] = -sb;
		m[7] = sa * cb;
		m[8] = ca * cb;
	}

	/** Winkel (x, y, z) mit R = Rz·Ry·Rx aus einer Drehmatrix. */
	static void toEuler(float[] m, float[] out) {
		float sy = Math.max(-1f, Math.min(1f, -m[6]));
		float y = (float) Math.asin(sy);
		if (Math.abs(sy) < 0.9999f) {
			out[0] = (float) Math.atan2(m[7], m[8]);
			out[2] = (float) Math.atan2(m[3], m[0]);
		} else {
			out[0] = (float) Math.atan2(-m[5], m[4]);
			out[2] = 0f;
		}
		out[1] = y;
	}

	static void mul3(float[] a, float[] b, float[] out) {
		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 3; c++) {
				out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
			}
		}
	}

	static void mul(float[] m, float x, float y, float z, float[] out) {
		out[0] = m[0] * x + m[1] * y + m[2] * z;
		out[1] = m[3] * x + m[4] * y + m[5] * z;
		out[2] = m[6] * x + m[7] * y + m[8] * z;
	}

	/** {@code a} ± k·2π, so nah wie möglich an {@code ref}. */
	static float nearest(float a, float ref) {
		double twoPi = Math.PI * 2;
		double k = Math.rint((ref - a) / twoPi);
		return (float) (a + k * twoPi);
	}
}

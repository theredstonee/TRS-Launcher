package dev.theredstonee.trsclient.core.cosmetic;

import dev.theredstonee.trsclient.core.cape.ClothMesh;

/**
 * Baut die Vierecke einer Kopf-Vorlage mit Animation. Gerechnet wird im Anhängepunkt-Raum (API.md §11.2:
 * +x Spieler-links, +y oben, +z vorne, Pixel); ausgegeben im {@code ModelPart}-Raum des Kopfes
 * ({@code x' = x, y' = −y, z' = −z}, in Blöcken = Pixel / 16) für einen PoseStack, der schon auf den Kopf
 * gesetzt ist. UV normiert (0–1), damit jede scale passt; Texel-Abbildung wie das Vanilla-Box-UV-Netz (§11.3).
 *
 * <p>Jedes Viereck ist so geordnet, dass {@code (p1 − p0) × (p2 − p0)} nach außen zeigt – wie bei Vanilla –,
 * damit Rückseiten-Culling passt. Nur Render-Thread (Arbeitsspeicher wird wiederverwendet).
 */
public final class CosmeticMesh {
	/** Die Ente sitzt auf der Hut-Schicht des Skins (+0,5 px) – mit Helm etwas höher. */
	static final float HAT_LIFT = 0.5f;
	static final float HELMET_LIFT = 1.5f;

	private static final int FRONT = 0, BACK = 1, RIGHT = 2, LEFT = 3, TOP = 4, BOTTOM = 5;
	private static final float[][] NORMALS = {
		{ 0, 0, 1 }, { 0, 0, -1 }, { -1, 0, 0 }, { 1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 },
	};
	private static final float[][] CORNERS = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };

	private final float[] q = new float[12];
	private final float[] uv = new float[8];
	private final float[] n = new float[3];
	private final float[] m = new float[12];
	private final float[] tmp = new float[12];

	/**
	 * Gibt alle Würfel von {@code model} aus. {@code pose} = Haltung des Rigs (null = starr),
	 * {@code nowMillis} für die Wanduhr-Animationen (flap/bob/spin).
	 */
	public void emit(CosmeticModel model, DuckRig.Pose pose, long nowMillis, boolean helmet, ClothMesh.QuadSink sink) {
		float lift = helmet ? HELMET_LIFT : HAT_LIFT;
		float tw = model.textureWidth;
		float th = model.textureHeight;
		// Fußpunkt des Modells (tiefster Punkt, Mitte) als Drehpunkt für Watscheln/Stauchen
		float baseY = Float.MAX_VALUE;
		for (CosmeticModel.Cube c : model.cubes) baseY = Math.min(baseY, c.y0);
		for (CosmeticModel.Cube c : model.cubes) {
			cubeMatrix(model, c, pose, nowMillis, baseY, lift);
			float w = c.x1 - c.x0, h = c.y1 - c.y0, d = c.z1 - c.z0;
			for (int f = 0; f < 6; f++) {
				// Fläche im UV-Netz (Einheiten der Textur bei scale 1)
				float rx, ry, rw, rh;
				switch (f) {
					case TOP: rx = c.u + d; ry = c.v; rw = w; rh = d; break;
					case BOTTOM: rx = c.u + d + w; ry = c.v; rw = w; rh = d; break;
					case RIGHT: rx = c.u; ry = c.v + d; rw = d; rh = h; break;
					case FRONT: rx = c.u + d; ry = c.v + d; rw = w; rh = h; break;
					case LEFT: rx = c.u + d + w; ry = c.v + d; rw = d; rh = h; break;
					default: rx = c.u + 2 * d + w; ry = c.v + d; rw = w; rh = h; break;
				}
				for (int k = 0; k < 4; k++) {
					float a = CORNERS[k][0], b = CORNERS[k][1];
					float x, y, z;
					switch (f) {
						case FRONT: x = c.x0 + a * w; y = c.y1 - b * h; z = c.z1; break;
						case BACK: x = c.x1 - a * w; y = c.y1 - b * h; z = c.z0; break;
						case RIGHT: x = c.x0; y = c.y1 - b * h; z = c.z0 + a * d; break;
						case LEFT: x = c.x1; y = c.y1 - b * h; z = c.z1 - a * d; break;
						case TOP: x = c.x0 + a * w; y = c.y1; z = c.z0 + b * d; break;
						default: x = c.x0 + a * w; y = c.y0; z = c.z0 + b * d; break;
					}
					// Anhängepunkt-Raum → ModelPart-Raum (Blöcke)
					float tx = m[0] * x + m[1] * y + m[2] * z + m[9];
					float ty = m[3] * x + m[4] * y + m[5] * z + m[10];
					float tz = m[6] * x + m[7] * y + m[8] * z + m[11];
					q[k * 3] = tx / 16f;
					q[k * 3 + 1] = -ty / 16f;
					q[k * 3 + 2] = -tz / 16f;
					uv[k * 2] = (rx + a * rw) / tw;
					uv[k * 2 + 1] = (ry + b * rh) / th;
				}
				float[] nn = NORMALS[f];
				float nx = m[0] * nn[0] + m[1] * nn[1] + m[2] * nn[2];
				float ny = m[3] * nn[0] + m[4] * nn[1] + m[5] * nn[2];
				float nz = m[6] * nn[0] + m[7] * nn[1] + m[8] * nn[2];
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len < 1e-6f) continue; // zu Null gestaucht (geschlossenes Auge von der Seite)
				n[0] = nx / len;
				n[1] = -ny / len;
				n[2] = -nz / len;
				emitQuad(sink);
			}
		}
	}

	/** Viereck mit Außen-Umlaufsinn ausgeben. */
	private void emitQuad(ClothMesh.QuadSink sink) {
		float ax = q[3] - q[0], ay = q[4] - q[1], az = q[5] - q[2];
		float bx = q[6] - q[0], by = q[7] - q[1], bz = q[8] - q[2];
		float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
		boolean flip = cx * n[0] + cy * n[1] + cz * n[2] < 0f;
		for (int i = 0; i < 4; i++) {
			int k = flip ? (4 - i) % 4 : i;
			sink.vertex(q[k * 3], q[k * 3 + 1], q[k * 3 + 2], uv[k * 2], uv[k * 2 + 1], n[0], n[1], n[2]);
		}
	}

	// --- Transformationen: m = [3×3 zeilenweise | Verschiebung], wirkt auf Punkte im Anhängepunkt-Raum ---

	private void cubeMatrix(CosmeticModel model, CosmeticModel.Cube c, DuckRig.Pose pose, long nowMillis, float baseY,
			float lift) {
		identity(m);
		float pi2 = (float) (Math.PI * 2);
		switch (c.anim) {
			case BLINK:
				if (pose != null) scaleAround(m, 1f, pose.eyeOpen, 1f, c.px, c.py, c.pz);
				break;
			case QUACK:
				if (pose != null) rotateX(m, rad(pose.beak), c.px, c.py, c.pz);
				break;
			case WING:
				if (pose != null) rotateZ(m, rad(pose.wing) * (c.centerX() > c.px ? 1f : -1f), c.px, c.py, c.pz);
				break;
			case BOB:
				translate(m, 0f, 0.5f * (float) Math.sin(pi2 * (nowMillis % 2000L) / 2000f), 0f);
				break;
			case SPIN:
				rotateY(m, pi2 * (nowMillis % 4000L) / 4000f, c.px, c.py, c.pz);
				break;
			case FLAP: {
				float s = c.centerX() > c.px ? 1f : -1f;
				rotateY(m, s * rad(15f) * (1f - (float) Math.cos(pi2 * (nowMillis % 1600L) / 1600f)), c.px, c.py, c.pz);
				break;
			}
			default:
				break;
		}
		if (pose == null) {
			translate(m, 0f, lift, 0f);
			return;
		}
		if (c.anim == CosmeticModel.Anim.LOOK || c.anim == CosmeticModel.Anim.QUACK || c.anim == CosmeticModel.Anim.BLINK) {
			rotateX(m, rad(pose.lookPitch), model.neckX, model.neckY, model.neckZ);
			rotateY(m, rad(pose.lookYaw), model.neckX, model.neckY, model.neckZ);
		}
		// Ganzes Tier um seinen Fußpunkt
		float sq = pose.squash;
		float side = (float) (1.0 / Math.sqrt(sq));
		scaleAround(m, side, sq, side, 0f, baseY, 0f);
		rotateZ(m, rad(pose.roll), 0f, baseY, 0f);
		rotateX(m, rad(pose.lean), 0f, baseY, 0f);
		rotateY(m, rad(pose.yaw), 0f, baseY, 0f);
		translate(m, 0f, pose.bob + lift, 0f);
	}

	private static float rad(float deg) {
		return deg * ((float) Math.PI / 180f);
	}

	private static void identity(float[] a) {
		for (int i = 0; i < 12; i++) a[i] = 0f;
		a[0] = a[4] = a[8] = 1f;
	}

	private static void translate(float[] a, float x, float y, float z) {
		a[9] += x;
		a[10] += y;
		a[11] += z;
	}

	/** a = R · a um den Punkt p (R zeilenweise r0…r8). */
	private void apply(float[] a, float r0, float r1, float r2, float r3, float r4, float r5, float r6, float r7, float r8,
			float px, float py, float pz) {
		// verschieben (−p), R anwenden, zurück (+p)
		float t0 = a[9] - px, t1 = a[10] - py, t2 = a[11] - pz;
		tmp[0] = r0 * a[0] + r1 * a[3] + r2 * a[6];
		tmp[1] = r0 * a[1] + r1 * a[4] + r2 * a[7];
		tmp[2] = r0 * a[2] + r1 * a[5] + r2 * a[8];
		tmp[3] = r3 * a[0] + r4 * a[3] + r5 * a[6];
		tmp[4] = r3 * a[1] + r4 * a[4] + r5 * a[7];
		tmp[5] = r3 * a[2] + r4 * a[5] + r5 * a[8];
		tmp[6] = r6 * a[0] + r7 * a[3] + r8 * a[6];
		tmp[7] = r6 * a[1] + r7 * a[4] + r8 * a[7];
		tmp[8] = r6 * a[2] + r7 * a[5] + r8 * a[8];
		tmp[9] = r0 * t0 + r1 * t1 + r2 * t2 + px;
		tmp[10] = r3 * t0 + r4 * t1 + r5 * t2 + py;
		tmp[11] = r6 * t0 + r7 * t1 + r8 * t2 + pz;
		System.arraycopy(tmp, 0, a, 0, 12);
	}

	/** Um x: positiver Winkel senkt die Vorderseite (+z → −y). */
	private void rotateX(float[] a, float ang, float px, float py, float pz) {
		if (ang == 0f) return;
		float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
		apply(a, 1, 0, 0, 0, c, -s, 0, s, c, px, py, pz);
	}

	/** Um y: positiver Winkel dreht die Vorderseite nach +x (Spieler-links). */
	private void rotateY(float[] a, float ang, float px, float py, float pz) {
		if (ang == 0f) return;
		float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
		apply(a, c, 0, s, 0, 1, 0, -s, 0, c, px, py, pz);
	}

	/** Um z: positiver Winkel hebt die Unterseite nach +x. */
	private void rotateZ(float[] a, float ang, float px, float py, float pz) {
		if (ang == 0f) return;
		float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
		apply(a, c, -s, 0, s, c, 0, 0, 0, 1, px, py, pz);
	}

	private void scaleAround(float[] a, float sx, float sy, float sz, float px, float py, float pz) {
		if (sx == 1f && sy == 1f && sz == 1f) return;
		apply(a, sx, 0, 0, 0, sy, 0, 0, 0, sz, px, py, pz);
	}
}

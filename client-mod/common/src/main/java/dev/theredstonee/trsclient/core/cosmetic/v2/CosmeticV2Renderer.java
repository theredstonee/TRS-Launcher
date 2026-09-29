package dev.theredstonee.trsclient.core.cosmetic.v2;

/**
 * Rechnet ein {@link CosmeticV2}-Modell für ein Bild: Knochen-Matrizen mit Animations-Pose (§4/§7), Flächen mit
 * Ecken + UV (§5), Leucht-Höfe (§9). Port von {@code cosmetic-format.mjs} / {@code cosmetic-view.mjs} – gleiche Achsen,
 * gleiche Zeitbasis (Wanduhr {@code System.currentTimeMillis()}), gleiche Formeln.
 *
 * <p>Zwei Ausgaben: {@link #emit} liefert Vierecke im {@code ModelPart}-Raum des Kopfes (Blöcke, y unten, vorne −z;
 * {@code x' = x/16, y' = −y/16, z' = −z/16}) für die Minecraft-Renderer, {@link #faces}/{@link #halos} liefern den
 * Anhängepunkt-Raum für die 2D-Vorschau der Garderobe. Kein {@code HAT_LIFT}: v2-Koordinaten sind absolut zum Kopf.
 *
 * <p>Nicht threadsicher (Arbeitsspeicher wird wiederverwendet) – eine Instanz je Render-Thread.
 */
public final class CosmeticV2Renderer {
	/** Zeichen-Durchgänge in Minecraft. */
	public static final int PASS_CUTOUT = 0, PASS_EMISSIVE = 1, PASS_TRANSLUCENT = 2, PASS_GLOW = 3, PASS_HALO = 4;

	/** Minecraft-Vierecke: Position im ModelPart-Raum (Blöcke), UV 0–1 im aktuellen Bild, Normale, Farbe ARGB. */
	public interface VertexSink {
		void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int argb);
	}

	/** Eine Fläche im Anhängepunkt-Raum: 4 Ecken (OL, OR, UR, UL) und ihre UV in Textur-Einheiten. */
	public interface FaceSink {
		void face(double[] corners, float[] uv, int material, double nx, double ny, double nz);
	}

	/**
	 * Ein Leucht-Hof im Anhängepunkt-Raum: Mitte (schon zur Kamera geschoben), halbe Kantenlänge, Farbe (RGB, schon mit
	 * der Helligkeit multipliziert, gekappt), Helligkeit {@code i} (Deckkraft = min(1, i)).
	 */
	public interface HaloSink {
		void halo(double cx, double cy, double cz, double half, int rgb, float intensity);
	}

	private static final int MAX = CosmeticV2.MAX_BONES;
	private final double[][] world = new double[MAX][16];
	private final double[] local = new double[16];
	private final double[] tmp = new double[16];
	private final double[] tmp2 = new double[16];
	private final float[][] pPos = new float[MAX][3];
	private final float[][] pRot = new float[MAX][3];
	private final float[][] pScale = new float[MAX][3];
	private final double[] corners = new double[12];
	private final double[] c = new double[12];
	private final float[] uv = new float[8];
	private final double[] v3 = new double[3];
	private final double[] n3 = new double[3];

	// =====================================================================================================
	// Pose
	// =====================================================================================================

	/**
	 * Knochen-Matrizen zur Wanduhr {@code timeMs} berechnen ({@code animate} false = Ruhepose). Danach gelten
	 * {@link #faces}, {@link #halos}, {@link #emit} und {@link #world(int)}.
	 */
	public void pose(CosmeticV2 m, long timeMs, boolean animate) {
		int n = Math.min(MAX, m.bones.size());
		for (int b = 0; b < n; b++) {
			pPos[b][0] = pPos[b][1] = pPos[b][2] = 0f;
			pRot[b][0] = pRot[b][1] = pRot[b][2] = 0f;
			pScale[b][0] = pScale[b][1] = pScale[b][2] = 1f;
		}
		if (animate) samplePoses(m, timeMs);
		for (int b = 0; b < n; b++) {
			CosmeticV2.Bone bone = m.bones.get(b);
			boneLocal(bone, pPos[b], pRot[b], pScale[b], local);
			if (bone.parent >= 0) V2Math.multiply(world[b], world[bone.parent], local, tmp);
			else System.arraycopy(local, 0, world[b], 0, 16);
		}
	}

	/** Welt-Matrix (Anhängepunkt-Raum) eines Knochens nach {@link #pose}. */
	public double[] world(int bone) {
		return world[bone];
	}

	/** Lokale Matrix: T(pivot + pos) · R(rest + rot) · S(scale) · T(−pivot). */
	private void boneLocal(CosmeticV2.Bone b, float[] pos, float[] rot, float[] sc, double[] out) {
		V2Math.translation(out, b.px + pos[0], b.py + pos[1], b.pz + pos[2]);
		V2Math.rotationZYX(tmp2, b.rx + rot[0], b.ry + rot[1], b.rz + rot[2]);
		V2Math.multiply(out, out, tmp2, tmp);
		if (sc[0] != 1f || sc[1] != 1f || sc[2] != 1f) {
			V2Math.scaling(tmp2, sc[0], sc[1], sc[2]);
			V2Math.multiply(out, out, tmp2, tmp);
		}
		V2Math.translation(tmp2, -b.px, -b.py, -b.pz);
		V2Math.multiply(out, out, tmp2, tmp);
	}

	/** Pose aller Knochen (nur Treiber {@code idle}); Drehung/Position addieren, Skalierung multiplizieren. */
	private void samplePoses(CosmeticV2 m, long timeMs) {
		for (CosmeticV2.Animation anim : m.animations) {
			if (anim.driver != 0) continue; // walk/sneak/jump/air: noch nicht unterstützt → nicht abspielen
			long len = anim.lengthMs;
			long t = timeMs + anim.offsetMs;
			double tt;
			if (!anim.loop) tt = Math.min(Math.max(t, 0L), len);
			else tt = ((t % len) + len) % len;
			for (CosmeticV2.Track tr : anim.tracks) {
				if (tr.bone >= MAX) continue;
				sampleTrack(tr, tt, v3);
				float[] target = tr.channel == CosmeticV2.SCALE ? pScale[tr.bone]
						: tr.channel == CosmeticV2.POSITION ? pPos[tr.bone] : pRot[tr.bone];
				for (int j = 0; j < 3; j++) {
					if (tr.channel == CosmeticV2.SCALE) target[j] = (float) (target[j] * v3[j]);
					else target[j] = (float) (target[j] + v3[j]);
				}
			}
		}
	}

	static void sampleTrack(CosmeticV2.Track track, double t, double[] out) {
		int[] kt = track.t;
		float[][] kv = track.v;
		int last = kt.length - 1;
		if (t <= kt[0]) {
			set(out, kv[0]);
			return;
		}
		if (t >= kt[last]) {
			set(out, kv[last]);
			return;
		}
		int i = 0;
		while (i < kt.length - 2 && t >= kt[i + 1]) i++;
		float[] a = kv[i];
		float[] b = kv[i + 1];
		int mode = track.keyInterpolation[i + 1] >= 0 ? track.keyInterpolation[i + 1] : track.interpolation;
		if (mode == CosmeticV2.STEP) {
			set(out, a);
			return;
		}
		double u = (t - kt[i]) / (double) (kt[i + 1] - kt[i]);
		if (mode == CosmeticV2.SMOOTH) u = u * u * (3 - 2 * u);
		for (int j = 0; j < 3; j++) out[j] = a[j] + (b[j] - a[j]) * u;
	}

	private static void set(double[] out, float[] v) {
		out[0] = v[0];
		out[1] = v[1];
		out[2] = v[2];
	}

	// =====================================================================================================
	// Flächen
	// =====================================================================================================

	/** Ecken einer Fläche [OL, OR, UR, UL] (von außen betrachtet) im Modellraum, ohne Knochen. */
	static void faceCorners(CosmeticV2.Cube cb, int face, double[] out) {
		double x0 = cb.x0 - cb.inflate, y0 = cb.y0 - cb.inflate, z0 = cb.z0 - cb.inflate;
		double x1 = cb.x1 + cb.inflate, y1 = cb.y1 + cb.inflate, z1 = cb.z1 + cb.inflate;
		switch (face) {
			case CosmeticV2.SOUTH:
				put(out, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1);
				break;
			case CosmeticV2.NORTH:
				put(out, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0);
				break;
			case CosmeticV2.EAST:
				put(out, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1);
				break;
			case CosmeticV2.WEST:
				put(out, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0);
				break;
			case CosmeticV2.UP:
				put(out, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
				break;
			default:
				put(out, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
				break;
		}
	}

	private static void put(double[] o, double... v) {
		System.arraycopy(v, 0, o, 0, 12);
	}

	/** UV je Ecke (gleiche Reihenfolge wie die Ecken) in Textur-Einheiten; rotation dreht im Uhrzeigersinn. */
	static void faceUv(CosmeticV2.Face f, float[] out) {
		float[] img = { f.u0, f.v0, f.u1, f.v0, f.u1, f.v1, f.u0, f.v1 };
		int q = ((f.rotation / 90) % 4 + 4) % 4;
		for (int i = 0; i < 4; i++) {
			int k = (i - q + 4) % 4;
			out[i * 2] = img[k * 2];
			out[i * 2 + 1] = img[k * 2 + 1];
		}
	}

	/** Alle Flächen im Anhängepunkt-Raum ausgeben (nach {@link #pose}); {@code material} −1 = alle. */
	public void faces(CosmeticV2 m, int material, FaceSink sink) {
		for (CosmeticV2.Cube cb : m.cubes) {
			double[] w = world[cb.bone];
			for (int f = 0; f < 6; f++) {
				CosmeticV2.Face face = cb.faces[f];
				if (face == null || (material >= 0 && face.material != material)) continue;
				faceCorners(cb, f, c);
				for (int k = 0; k < 4; k++) V2Math.transformPoint(w, c[k * 3], c[k * 3 + 1], c[k * 3 + 2], corners, k * 3);
				faceUv(face, uv);
				float[] fn = CosmeticV2.FACE_NORMAL[f];
				V2Math.transformDir(w, fn[0], fn[1], fn[2], n3, 0);
				double len = Math.sqrt(n3[0] * n3[0] + n3[1] * n3[1] + n3[2] * n3[2]);
				if (len < 1e-9) len = 1;
				sink.face(corners, uv, face.material, n3[0] / len, n3[1] / len, n3[2] / len);
			}
		}
	}

	// =====================================================================================================
	// Leucht-Höfe
	// =====================================================================================================

	/** Helligkeit eines Hofs (0 … intensity) zur Wanduhr. */
	public static double haloIntensity(CosmeticV2.Halo h, long timeMs) {
		if (!h.pulse) return h.intensity;
		double ph = (double) (((timeMs + h.phaseMs) % h.periodMs + h.periodMs) % h.periodMs) / h.periodMs;
		return h.intensity * (h.min + (h.max - h.min) * (0.5 - 0.5 * Math.cos(2 * Math.PI * ph)));
	}

	/** Ausblenden nach Blickrichtung (voll ab d ≥ 0,35, weg bei d ≤ 0). */
	public static double haloFacing(double d) {
		double u = Math.max(0, Math.min(1, d / 0.35));
		return u * u * (3 - 2 * u);
	}

	/** Helligkeitsverlauf über den Radius r ∈ [0, 1]. */
	public static double haloFalloff(double r) {
		return Math.pow(Math.max(0, 1 - r), 2.5);
	}

	/**
	 * Leucht-Höfe ausgeben (nach {@link #pose}). {@code eye} im Anhängepunkt-Raum: Kameraposition, oder bei
	 * {@code direction} = true die Richtung zur Kamera (orthografische Vorschau). Unsichtbare Höfe fallen weg.
	 */
	public void halos(CosmeticV2 m, long timeMs, double ex, double ey, double ez, boolean direction, HaloSink sink) {
		for (CosmeticV2.Halo h : m.halos) {
			double[] w = world[h.bone];
			V2Math.transformPoint(w, h.x, h.y, h.z, v3, 0);
			double dx, dy, dz;
			if (direction) {
				dx = ex;
				dy = ey;
				dz = ez;
			} else {
				dx = ex - v3[0];
				dy = ey - v3[1];
				dz = ez - v3[2];
			}
			double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (dl < 1e-9) continue;
			dx /= dl;
			dy /= dl;
			dz /= dl;
			double facing = 1;
			if (h.normal != null) {
				V2Math.transformDir(w, h.normal[0], h.normal[1], h.normal[2], n3, 0);
				double nl = Math.sqrt(n3[0] * n3[0] + n3[1] * n3[1] + n3[2] * n3[2]);
				if (nl > 1e-9) facing = haloFacing((n3[0] * dx + n3[1] * dy + n3[2] * dz) / nl);
			}
			double i = haloIntensity(h, timeMs) * facing;
			if (i <= 0.001) continue;
			// Skalierung des Knochens (Animationen) wirkt auf Größe und Verschiebung wie beim Sprite in three.js.
			double bs = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
			double half = h.size / 2.0 * bs;
			double cx = v3[0] + dx * half;
			double cy = v3[1] + dy * half;
			double cz = v3[2] + dz * half;
			double k = Math.max(1, i);
			int r = clamp255(((h.color >> 16) & 0xFF) * k);
			int g = clamp255(((h.color >> 8) & 0xFF) * k);
			int b = clamp255((h.color & 0xFF) * k);
			sink.halo(cx, cy, cz, half, (r << 16) | (g << 8) | b, (float) i);
		}
	}

	private static int clamp255(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	// =====================================================================================================
	// Minecraft: Vierecke im ModelPart-Raum
	// =====================================================================================================

	/**
	 * Einen Durchgang als Vierecke ausgeben (nach {@link #pose}). UV normiert auf ein Bild der Textur. Für
	 * {@link #PASS_HALO} braucht es die Kamera ({@code eye}, Anhängepunkt-Raum; null = keine Höfe) – UV dann 0–1 über
	 * die Hof-Textur, Farbe = Hof-Farbe × Helligkeit (für additives Zeichnen, Alpha 255).
	 */
	public void emit(final CosmeticV2 m, int pass, long timeMs, double[] eye, final VertexSink sink) {
		if (pass == PASS_HALO) {
			if (eye == null) return;
			halos(m, timeMs, eye[0], eye[1], eye[2], false, new HaloSink() {
				@Override
				public void halo(double cx, double cy, double cz, double half, int rgb, float intensity) {
					emitHalo(cx, cy, cz, half, rgb, intensity, eye, sink);
				}
			});
			return;
		}
		final float tw = m.textureWidth;
		final float th = m.textureHeight;
		final int material = pass == PASS_CUTOUT ? CosmeticV2.CUTOUT : pass == PASS_EMISSIVE ? CosmeticV2.EMISSIVE
				: pass == PASS_TRANSLUCENT ? CosmeticV2.TRANSLUCENT : -1;
		faces(m, material, new FaceSink() {
			@Override
			public void face(double[] p, float[] t, int mat, double nx, double ny, double nz) {
				float fx = (float) nx, fy = (float) -ny, fz = (float) -nz;
				for (int k = 0; k < 4; k++) {
					sink.vertex((float) (p[k * 3] / 16.0), (float) (-p[k * 3 + 1] / 16.0), (float) (-p[k * 3 + 2] / 16.0),
							t[k * 2] / tw, t[k * 2 + 1] / th, fx, fy, fz, 0xFFFFFFFF);
				}
			}
		});
	}

	private final double[] right = new double[3];
	private final double[] up = new double[3];

	/** Kamera-zugewandtes Quadrat um (cx, cy, cz). */
	private void emitHalo(double cx, double cy, double cz, double half, int rgb, float intensity, double[] eye,
			VertexSink sink) {
		double dx = eye[0] - cx, dy = eye[1] - cy, dz = eye[2] - cz;
		double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dl < 1e-6) return;
		dx /= dl;
		dy /= dl;
		dz /= dl;
		// rechts = oben × Blick (oben = +y, außer fast senkrecht)
		double ux = 0, uy = 1, uz = 0;
		if (Math.abs(dy) > 0.99) {
			ux = 0;
			uy = 0;
			uz = 1;
		}
		right[0] = uy * dz - uz * dy;
		right[1] = uz * dx - ux * dz;
		right[2] = ux * dy - uy * dx;
		double rl = Math.sqrt(right[0] * right[0] + right[1] * right[1] + right[2] * right[2]);
		right[0] /= rl;
		right[1] /= rl;
		right[2] /= rl;
		up[0] = dy * right[2] - dz * right[1];
		up[1] = dz * right[0] - dx * right[2];
		up[2] = dx * right[1] - dy * right[0];
		// additiv: Farbe × min(1, i) (Deckkraft) – die Farbe trägt schon max(1, i)
		float a = Math.min(1f, intensity);
		int r = Math.round(((rgb >> 16) & 0xFF) * a);
		int g = Math.round(((rgb >> 8) & 0xFF) * a);
		int b = Math.round((rgb & 0xFF) * a);
		int argb = 0xFF000000 | (r << 16) | (g << 8) | b;
		float nx = (float) dx, ny = (float) -dy, nz = (float) -dz;
		double[][] q = { { -1, 1 }, { 1, 1 }, { 1, -1 }, { -1, -1 } };
		float[][] quv = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };
		for (int k = 0; k < 4; k++) {
			double px = cx + (right[0] * q[k][0] + up[0] * q[k][1]) * half;
			double py = cy + (right[1] * q[k][0] + up[1] * q[k][1]) * half;
			double pz = cz + (right[2] * q[k][0] + up[2] * q[k][1]) * half;
			sink.vertex((float) (px / 16.0), (float) (-py / 16.0), (float) (-pz / 16.0), quv[k][0], quv[k][1], nx, ny, nz, argb);
		}
	}

	/**
	 * Beidseitig: jedes Viereck zusätzlich mit umgekehrter Reihenfolge ausgeben. Für Render-Typen mit Rückseiten-Culling
	 * (Minecrafts {@code eyes} für Leucht-Schicht und Höfe) – so leuchten auch Innenseiten wie in der Werkbank. Nicht für
	 * Wege ohne Culling benutzen (additiv würde es doppelt hell).
	 */
	public static VertexSink bothSides(final VertexSink sink) {
		return new VertexSink() {
			private final float[] q = new float[4 * 9];
			private final int[] c = new int[4];
			private int n;

			@Override
			public void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int argb) {
				int o = n * 9;
				q[o] = x;
				q[o + 1] = y;
				q[o + 2] = z;
				q[o + 3] = u;
				q[o + 4] = v;
				q[o + 5] = nx;
				q[o + 6] = ny;
				q[o + 7] = nz;
				c[n] = argb;
				sink.vertex(x, y, z, u, v, nx, ny, nz, argb);
				if (++n < 4) return;
				n = 0;
				for (int k = 3; k >= 0; k--) {
					int p = k * 9;
					sink.vertex(q[p], q[p + 1], q[p + 2], q[p + 3], q[p + 4], -q[p + 5], -q[p + 6], -q[p + 7], c[k]);
				}
			}
		};
	}

	/** Bild eines Streifens zur Wanduhr (wie bei Umhängen). */
	public static int frameAt(long timeMs, int frames, int frameTimeMs) {
		if (frames <= 1 || frameTimeMs <= 0) return 0;
		return (int) Math.floorMod(timeMs / frameTimeMs, (long) frames);
	}
}

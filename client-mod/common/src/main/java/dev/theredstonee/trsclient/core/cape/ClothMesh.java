package dev.theredstonee.trsclient.core.cape;

/**
 * Erzeugt die Vierecke eines simulierten Umhangs mit den Vanilla-Umhang-UVs (Textur 64 × 32 bzw.
 * {@code scale}-fach – die Bruchteile bleiben gleich; jedes Animationsbild ist eine eigene Textur).
 *
 * <p>Ausgabe in Modell-Einheiten (Pixel / 16) im Körper-System (x links, y unten, z hinten), gedacht für
 * einen PoseStack, der auf den Rücken (Körper-Teil + 2 Pixel nach hinten) verschoben ist. Außenseite =
 * Bereich (1, 1, 10, 16), Innenseite (12, 1, 10, 16), Ränder (0, 1, 1, 16) / (11, 1, 1, 16), oben (1, 0, 10, 1),
 * unten (11, 0, 10, 1) – wie in der TRS-API-Doku §5.2.
 *
 * <p>Die Reihenfolge jedes Vierecks wird am gewünschten Normalenvektor ausgerichtet, damit Flächen mit
 * Rückseiten-Culling (entitySolid) von außen sichtbar sind.
 */
public final class ClothMesh {
	/** Ziel für die Eckpunkte – je Version über VertexConsumer/Tessellator umgesetzt. */
	public interface QuadSink {
		/** Ein Eckpunkt; je vier ergeben ein Viereck. u/v normiert (0–1), Normale normiert. */
		void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz);
	}

	private float[] pos = new float[0];
	private float[] nrm = new float[0];
	// Arbeitsspeicher je Viereck (keine Allokationen pro Bild)
	private final float[] q = new float[12];
	private final float[] uvq = new float[8];
	private final float[] ns = new float[12];
	private final int[] ids = new int[4];

	/** Zeichnet den Umhang (Interpolation {@code partial} zwischen den letzten Ticks). */
	public void emit(ClothSim sim, float partial, QuadSink sink) {
		int w = sim.cols + 1;
		int h = sim.rows + 1;
		int n = w * h;
		if (pos.length != n * 3) {
			pos = new float[n * 3];
			nrm = new float[n * 3];
		}
		sim.positions(partial, pos);
		normals(w, h);
		float t = ClothSim.HALF_THICKNESS;
		int cols = sim.cols;
		int rows = sim.rows;
		for (int j = 0; j < rows; j++) {
			for (int i = 0; i < cols; i++) {
				int a = j * w + i;         // links oben (x groß, y klein)
				int b = j * w + i + 1;     // rechts oben
				int c = (j + 1) * w + i + 1;
				int d = (j + 1) * w + i;
				float u0 = 1f + 10f * i / cols;
				float u1 = 1f + 10f * (i + 1) / cols;
				float v0 = 1f + 16f * j / rows;
				float v1 = 1f + 16f * (j + 1) / rows;
				// Außenseite (+Normale, von hinten sichtbar)
				quad(sink, t, a, b, c, d, u0, u1, v0, v1, 1f);
				// Innenseite (zum Rücken), gespiegelt: u läuft von 22 nach 12
				float iu0 = 22f - 10f * i / cols;
				float iu1 = 22f - 10f * (i + 1) / cols;
				quad(sink, -t, a, b, c, d, iu0, iu1, v0, v1, -1f);
			}
		}
		// Seitenränder: linker Rand des Spielers (i = 0) → (0, 1, 1, 16), rechter (i = cols) → (11, 1, 1, 16)
		for (int j = 0; j < rows; j++) {
			float v0 = 1f + 16f * j / rows;
			float v1 = 1f + 16f * (j + 1) / rows;
			edge(sink, j * w, (j + 1) * w, 0f, 1f, v0, v1, true, 1f);
			edge(sink, j * w + cols, (j + 1) * w + cols, 11f, 12f, v0, v1, true, -1f);
		}
		// Oben (Schulterkante) und unten (Saum)
		for (int i = 0; i < cols; i++) {
			float u0 = 1f + 10f * i / cols;
			float u1 = 1f + 10f * (i + 1) / cols;
			edge(sink, i, i + 1, u0, u1, 0f, 1f, false, -1f);
			float b0 = 11f + 10f * i / cols;
			float b1 = 11f + 10f * (i + 1) / cols;
			int base = rows * w;
			edge(sink, base + i, base + i + 1, b0, b1, 0f, 1f, false, 1f);
		}
	}

	/** Normalen je Punkt aus den Nachbarn (Außenseite zeigt in Ruhe nach +z). */
	private void normals(int w, int h) {
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int l = j * w + Math.max(0, i - 1);
				int r = j * w + Math.min(w - 1, i + 1);
				int u = Math.max(0, j - 1) * w + i;
				int d = Math.min(h - 1, j + 1) * w + i;
				// tx: quer (i wächst → x fällt), ty: längs nach unten; n = ty × tx
				float txx = pos[r * 3] - pos[l * 3];
				float txy = pos[r * 3 + 1] - pos[l * 3 + 1];
				float txz = pos[r * 3 + 2] - pos[l * 3 + 2];
				float tyx = pos[d * 3] - pos[u * 3];
				float tyy = pos[d * 3 + 1] - pos[u * 3 + 1];
				float tyz = pos[d * 3 + 2] - pos[u * 3 + 2];
				float nx = tyy * txz - tyz * txy;
				float ny = tyz * txx - tyx * txz;
				float nz = tyx * txy - tyy * txx;
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				int p = (j * w + i) * 3;
				if (len < 1e-6f) {
					nrm[p] = 0f;
					nrm[p + 1] = 0f;
					nrm[p + 2] = 1f;
				} else {
					nrm[p] = nx / len;
					nrm[p + 1] = ny / len;
					nrm[p + 2] = nz / len;
				}
			}
		}
	}

	/** Viereck einer Fläche, um {@code offset}·Normale verschoben; {@code side} = ±1 (außen/innen). */
	private void quad(QuadSink sink, float offset, int a, int b, int c, int d,
			float u0, float u1, float v0, float v1, float side) {
		ids[0] = a;
		ids[1] = b;
		ids[2] = c;
		ids[3] = d;
		for (int k = 0; k < 4; k++) {
			int p = ids[k] * 3;
			q[k * 3] = pos[p] + nrm[p] * offset;
			q[k * 3 + 1] = pos[p + 1] + nrm[p + 1] * offset;
			q[k * 3 + 2] = pos[p + 2] + nrm[p + 2] * offset;
		}
		setUv(u0, v0, u1, v0, u1, v1, u0, v1);
		// Soll-Normale dieser Fläche: Mittel der Punktnormalen × Seite
		float nx = 0f;
		float ny = 0f;
		float nz = 0f;
		for (int id : ids) {
			nx += nrm[id * 3];
			ny += nrm[id * 3 + 1];
			nz += nrm[id * 3 + 2];
		}
		for (int k = 0; k < 4; k++) {
			int p = ids[k] * 3;
			ns[k * 3] = nrm[p] * side;
			ns[k * 3 + 1] = nrm[p + 1] * side;
			ns[k * 3 + 2] = nrm[p + 2] * side;
		}
		put(sink, q, uvq, ns, nx * side, ny * side, nz * side);
	}

	/**
	 * Randstreifen zwischen zwei benachbarten Punkten (Außen- und Innenkante). {@code alongV}: die Kante
	 * läuft entlang v (Seitenränder), sonst entlang u (oben/unten). {@code outward}: Vorzeichen der
	 * Richtung weg vom Umhang (für die Ausrichtung).
	 */
	private void edge(QuadSink sink, int p0, int p1, float ua, float ub, float va, float vb, boolean alongV,
			float outward) {
		float t = ClothSim.HALF_THICKNESS;
		ids[0] = p0;
		ids[1] = p1;
		ids[2] = p1;
		ids[3] = p0;
		for (int k = 0; k < 4; k++) {
			int p = ids[k] * 3;
			float off = k < 2 ? t : -t;
			q[k * 3] = pos[p] + nrm[p] * off;
			q[k * 3 + 1] = pos[p + 1] + nrm[p + 1] * off;
			q[k * 3 + 2] = pos[p + 2] + nrm[p + 2] * off;
		}
		// Richtung der Kante und Normale des Stoffs → nach außen zeigende Randnormale
		float ex = pos[p1 * 3] - pos[p0 * 3];
		float ey = pos[p1 * 3 + 1] - pos[p0 * 3 + 1];
		float ez = pos[p1 * 3 + 2] - pos[p0 * 3 + 2];
		float mx = (nrm[p0 * 3] + nrm[p1 * 3]) * 0.5f;
		float my = (nrm[p0 * 3 + 1] + nrm[p1 * 3 + 1]) * 0.5f;
		float mz = (nrm[p0 * 3 + 2] + nrm[p1 * 3 + 2]) * 0.5f;
		float ox = ey * mz - ez * my;
		float oy = ez * mx - ex * mz;
		float oz = ex * my - ey * mx;
		float len = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
		if (len < 1e-6f) return;
		ox = ox / len * outward;
		oy = oy / len * outward;
		oz = oz / len * outward;
		if (alongV) setUv(ua, va, ua, vb, ub, vb, ub, va);
		else setUv(ua, va, ub, va, ub, vb, ua, vb);
		for (int k = 0; k < 4; k++) {
			ns[k * 3] = ox;
			ns[k * 3 + 1] = oy;
			ns[k * 3 + 2] = oz;
		}
		put(sink, q, uvq, ns, ox, oy, oz);
	}

	private void setUv(float a, float b, float c, float d, float e, float f, float g, float h) {
		uvq[0] = a;
		uvq[1] = b;
		uvq[2] = c;
		uvq[3] = d;
		uvq[4] = e;
		uvq[5] = f;
		uvq[6] = g;
		uvq[7] = h;
	}

	/** Gibt das Viereck so aus, dass es von der Seite der Soll-Normale gegen den Uhrzeigersinn läuft. */
	private static void put(QuadSink sink, float[] q, float[] uv, float[] ns, float wx, float wy, float wz) {
		float ax = q[3] - q[0];
		float ay = q[4] - q[1];
		float az = q[5] - q[2];
		float bx = q[6] - q[0];
		float by = q[7] - q[1];
		float bz = q[8] - q[2];
		float cx = ay * bz - az * by;
		float cy = az * bx - ax * bz;
		float cz = ax * by - ay * bx;
		boolean reverse = cx * wx + cy * wy + cz * wz < 0f;
		for (int k = 0; k < 4; k++) {
			int s = reverse ? 3 - k : k;
			sink.vertex(q[s * 3] / 16f, q[s * 3 + 1] / 16f, q[s * 3 + 2] / 16f,
					uv[s * 2] / 64f, uv[s * 2 + 1] / 32f, ns[s * 3], ns[s * 3 + 1], ns[s * 3 + 2]);
		}
	}
}

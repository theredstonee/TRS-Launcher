package dev.theredstonee.trsclient.core.skin;

import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;

/**
 * Spielerfigur für Menüs – ohne lebenden Spieler, in jeder Minecraft-Version gleich.
 *
 * <p>Die Figur wird hier selbst berechnet: sechs Körperteile (plus zweite Skin-Ebene und Umhang) als Quader
 * mit der Vanilla-UV-Aufteilung, gedreht und orthografisch projiziert (wie die Inventar-Vorschau). Jede
 * sichtbare Quaderseite ist danach ein Parallelogramm – das zeichnet der {@link Canvas} als ganz normalen
 * Textur-Ausschnitt mit einer 2D-Abbildung ({@link Affine}). Pro Version braucht es dafür nur „Textur-Rechteck
 * zeichnen, drehen, skalieren“ – kein Entity-Rendering, keine Render-States, keine Welt.
 *
 * <p>Verdeckung: Rückseiten fallen über das Vorzeichen der Abbildung weg (jede Seite ist von außen gesehen
 * nicht gespiegelt), die Teile werden von hinten nach vorn gezeichnet (Maleralgorithmus über die Teilmitte) –
 * für die sich nicht durchdringenden Quader der Figur genügt das. Kosten: höchstens ~40 Textur-Rechtecke je Bild.
 *
 * <p>Koordinaten der Figur in Skin-Pixeln: Füße bei y = 0, y nach oben, z zum Betrachter (Vorderseite),
 * x nach rechts (aus Sicht des Betrachters = linke Hand der Figur).
 */
public final class SkinModel {
	/** Höhe der Figur in Skin-Pixeln (ohne Hut). */
	public static final float HEIGHT = 32f;

	private static final int HEAD = 0;
	private static final int BODY = 1;
	private static final int RIGHT_ARM = 2;
	private static final int LEFT_ARM = 3;
	private static final int RIGHT_LEG = 4;
	private static final int LEFT_LEG = 5;
	private static final int CAPE = 6;
	private static final int PARTS = 7;

	/** Lichtrichtung (oben links vorn), normiert. */
	private static final float LX = -0.352f;
	private static final float LY = 0.755f;
	private static final float LZ = 0.553f;

	/** Ein Körperteil in diesem Bild (wiederverwendet). */
	private static final class Part {
		/** Drehung (Zeilen) und Lage des Drehpunkts in Kamerakoordinaten. */
		final float[] m = new float[9];
		final float[] t = new float[3];
		float depth;
		boolean visible;
		/** Quader: min-Ecke relativ zum Drehpunkt, Größe, UV, Aufblähen der zweiten Ebene. */
		float x0;
		float y0;
		float z0;
		float w;
		float h;
		float d;
		int u;
		int v;
		int u2;
		int v2;
		float inflate2;
	}

	private final Part[] parts = new Part[PARTS];
	private final int[] order = new int[PARTS];
	private final float[] tmpA = new float[9];
	private final float[] tmpB = new float[9];
	private final float[] view = new float[9];
	/** Anzahl gezeichneter Flächen im letzten Bild (Test/Messung). */
	private int faces;

	public SkinModel() {
		for (int i = 0; i < PARTS; i++) parts[i] = new Part();
	}

	/** Gezeichnete Flächen im letzten Aufruf. */
	public int lastFaceCount() {
		return faces;
	}

	/**
	 * Zeichnet die Figur so groß wie möglich in das Rechteck (Füße unten mittig).
	 *
	 * @return false, wenn der Canvas keine Texturen kann oder kein Skin gesetzt ist
	 */
	public boolean drawFitted(Canvas c, float x, float y, float w, float h, SkinModelSpec spec) {
		float scale = fitScale(w, h);
		return draw(c, x + w / 2f, y + h - 1f, scale, spec);
	}

	/** GUI-Pixel je Skin-Pixel, damit die Figur in {@code w}×{@code h} passt. */
	public static float fitScale(float w, float h) {
		return Math.max(0.1f, Math.min(h / 34f, w / 18f));
	}

	/**
	 * Zeichnet die Figur mit den Füßen bei ({@code cx}, {@code feetY}).
	 *
	 * @param scale GUI-Pixel je Skin-Pixel
	 * @return false, wenn der Canvas keine Texturen kann oder kein Skin gesetzt ist
	 */
	public boolean draw(Canvas c, float cx, float feetY, float scale, SkinModelSpec spec) {
		faces = 0;
		if (spec == null || spec.skin == null || !c.images() || scale <= 0f) return false;
		setup(spec);
		// Von hinten nach vorn (kleines z = weiter weg).
		int n = 0;
		for (int i = 0; i < PARTS; i++) {
			if (!parts[i].visible) continue;
			int k = n++;
			while (k > 0 && parts[order[k - 1]].depth > parts[i].depth) {
				order[k] = order[k - 1];
				k--;
			}
			order[k] = i;
		}
		for (int k = 0; k < n; k++) {
			int i = order[k];
			Part p = parts[i];
			TextureRef tex = i == CAPE ? spec.cape : spec.skin;
			// Texel je Skin-Pixel: Skins 64 breit, Umhänge 64 (Vanilla) bis 512 (TRS, Faktor 8).
			float unit = tex.width / 64f;
			box(c, p, tex, 0f, p.u, p.v, unit, cx, feetY, scale, spec);
			if (spec.layers && i != CAPE) box(c, p, tex, p.inflate2, p.u2, p.v2, unit, cx, feetY, scale, spec);
		}
		return true;
	}

	// --- Pose ---

	private void setup(SkinModelSpec s) {
		float yaw = (float) Math.toRadians(s.yaw);
		float pitch = (float) Math.toRadians(s.pitch);
		// Kamera: erst um die Hochachse drehen, dann neigen.
		rotY(tmpA, yaw);
		rotX(tmpB, pitch);
		mul(view, tmpB, tmpA);

		float swing = s.walkAmount;
		float phase = s.walkPhase * 0.6662f;
		float age = s.idleTime * 20f;
		float bobZ = s.idleTime > 0f ? (float) (Math.cos(age * 0.09f) * 0.05f + 0.05f) : 0f;
		float bobX = s.idleTime > 0f ? (float) (Math.sin(age * 0.067f) * 0.05f) : 0f;
		float armW = s.slim ? 3f : 4f;
		float armY = s.slim ? 21.5f : 22f;

		// Kopf: Drehpunkt am Hals.
		part(HEAD, 0f, 24f, 0f, (float) Math.toRadians(s.headPitch), (float) Math.toRadians(s.headYaw), 0f,
				-4f, 0f, -4f, 8f, 8f, 8f, 0, 0, 32, 0, 0.5f);
		part(BODY, 0f, 24f, 0f, 0f, 0f, 0f, -4f, -12f, -2f, 8f, 12f, 4f, 16, 16, 16, 32, 0.25f);
		float rightArm = (float) (Math.cos(phase + Math.PI) * swing) + bobX;
		float leftArm = (float) (Math.cos(phase) * swing) - bobX;
		part(RIGHT_ARM, -5f, armY, 0f, rightArm, 0f, -bobZ, -armW + 1f, -10f, -2f, armW, 12f, 4f, 40, 16, 40, 32, 0.25f);
		part(LEFT_ARM, 5f, armY, 0f, leftArm, 0f, bobZ, -1f, -10f, -2f, armW, 12f, 4f, 32, 48, 48, 48, 0.25f);
		float leg = (float) (Math.cos(phase) * 1.4f * swing);
		part(RIGHT_LEG, -1.9f, 12f, 0f, leg, 0f, 0f, -2f, -12f, -2f, 4f, 12f, 4f, 0, 16, 0, 32, 0.25f);
		part(LEFT_LEG, 1.9f, 12f, 0f, -leg, 0f, 0f, -2f, -12f, -2f, 4f, 12f, 4f, 16, 48, 0, 48, 0.25f);
		parts[CAPE].visible = s.cape != null;
		if (s.cape != null) {
			// Der Umhang hängt schräg nach hinten (Vanilla ≈ 6°), beim Laufen und Wippen mehr.
			float lift = 6f + s.capeLift + swing * 22f + (s.idleTime > 0f ? (float) (Math.sin(age * 0.05f) * 1.5f) : 0f);
			// Wie Vanilla um 180° gewendet (Außenseite = Vorderseiten-UV zeigt nach hinten): Ry(π)·Rx(-a) = Rx(a)·Ry(π).
			part(CAPE, 0f, 24f, -2f, (float) -Math.toRadians(Math.max(0f, Math.min(80f, lift))), (float) Math.PI, 0f,
					-5f, -16f, 0f, 10f, 16f, 1f, 0, 0, 0, 0, 0f);
		}
	}

	/** Setzt ein Teil: Drehpunkt, Drehung (Rz·Ry·Rx wie Vanilla), Quader und UV beider Ebenen. */
	private void part(int index, float px, float py, float pz, float xRot, float yRot, float zRot, float x0, float y0, float z0,
			float w, float h, float d, int u, int v, int u2, int v2, float inflate2) {
		Part p = parts[index];
		p.visible = true;
		float[] r = tmpA;
		rotZYX(r, zRot, yRot, xRot);
		mul(p.m, view, r);
		p.t[0] = view[0] * px + view[1] * py + view[2] * pz;
		p.t[1] = view[3] * px + view[4] * py + view[5] * pz;
		p.t[2] = view[6] * px + view[7] * py + view[8] * pz;
		p.x0 = x0;
		p.y0 = y0;
		p.z0 = z0;
		p.w = w;
		p.h = h;
		p.d = d;
		p.u = u;
		p.v = v;
		p.u2 = u2;
		p.v2 = v2;
		p.inflate2 = inflate2;
		float cx = x0 + w / 2f;
		float cy = y0 + h / 2f;
		float cz = z0 + d / 2f;
		p.depth = p.t[2] + p.m[6] * cx + p.m[7] * cy + p.m[8] * cz;
	}

	// --- Flächen ---

	/** Zeichnet die sichtbaren Seiten eines Quaders (Vanilla-UV ab u, v; {@code unit} Texel je Skin-Pixel). */
	private void box(Canvas c, Part p, TextureRef tex, float g, int u, int v, float unit, float cx, float cy, float s,
			SkinModelSpec spec) {
		float x0 = p.x0 - g;
		float y0 = p.y0 - g;
		float z0 = p.z0 - g;
		float x1 = p.x0 + p.w + g;
		float y1 = p.y0 + p.h + g;
		float z1 = p.z0 + p.d + g;
		int w = Math.round(p.w);
		int h = Math.round(p.h);
		int d = Math.round(p.d);
		// Vorn, hinten, rechts (x0), links (x1), oben, unten – je Ursprung, Kante U, Kante V (von außen nicht gespiegelt).
		face(c, p, tex, u + d, v + d, w, h, unit, x0, y1, z1, x1 - x0, 0, 0, 0, y0 - y1, 0, cx, cy, s, spec);
		face(c, p, tex, u + 2 * d + w, v + d, w, h, unit, x1, y1, z0, x0 - x1, 0, 0, 0, y0 - y1, 0, cx, cy, s, spec);
		face(c, p, tex, u, v + d, d, h, unit, x0, y1, z0, 0, 0, z1 - z0, 0, y0 - y1, 0, cx, cy, s, spec);
		face(c, p, tex, u + d + w, v + d, d, h, unit, x1, y1, z1, 0, 0, z0 - z1, 0, y0 - y1, 0, cx, cy, s, spec);
		face(c, p, tex, u + d, v, w, d, unit, x0, y1, z0, x1 - x0, 0, 0, 0, 0, z1 - z0, cx, cy, s, spec);
		face(c, p, tex, u + d + w, v, w, d, unit, x0, y0, z1, x1 - x0, 0, 0, 0, 0, z0 - z1, cx, cy, s, spec);
	}

	private void face(Canvas c, Part p, TextureRef tex, int tu, int tv, int tw, int th, float unit, float ox, float oy, float oz,
			float ux, float uy, float uz, float vx, float vy, float vz, float cx, float cy, float s, SkinModelSpec spec) {
		if (tw <= 0 || th <= 0) return;
		float[] m = p.m;
		// In Kamerakoordinaten
		float oX = p.t[0] + m[0] * ox + m[1] * oy + m[2] * oz;
		float oY = p.t[1] + m[3] * ox + m[4] * oy + m[5] * oz;
		float uX = m[0] * ux + m[1] * uy + m[2] * uz;
		float uY = m[3] * ux + m[4] * uy + m[5] * uz;
		float uZ = m[6] * ux + m[7] * uy + m[8] * uz;
		float vX = m[0] * vx + m[1] * vy + m[2] * vz;
		float vY = m[3] * vx + m[4] * vy + m[5] * vz;
		float vZ = m[6] * vx + m[7] * vy + m[8] * vz;
		// Bildschirm (y nach unten)
		float sux = uX * s;
		float suy = -uY * s;
		float svx = vX * s;
		float svy = -vY * s;
		float det = sux * svy - suy * svx;
		if (det <= 0.02f) return; // Rückseite oder hochkant
		int tint = spec.tint;
		if (spec.shade) {
			// Normale = V × U (nach außen), normiert
			float nx = vY * uZ - vZ * uY;
			float ny = vZ * uX - vX * uZ;
			float nz = vX * uY - vY * uX;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			float lit = len > 0f ? (nx * LX + ny * LY + nz * LZ) / len : 0f;
			float b = Math.min(1f, 0.6f + 0.5f * Math.max(0f, lit));
			tint = scale(tint, b);
		}
		int w = Math.round(tw * unit);
		int h = Math.round(th * unit);
		c.push();
		if (Affine.apply(c, sux / w, suy / w, svx / h, svy / h, cx + oX * s, cy - oY * s)) {
			c.image(tex, tu * unit, tv * unit, w, h, tint);
			faces++;
		}
		c.pop();
	}

	private static int scale(int argb, float f) {
		int r = Math.round(((argb >> 16) & 0xFF) * f);
		int g = Math.round(((argb >> 8) & 0xFF) * f);
		int b = Math.round((argb & 0xFF) * f);
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	// --- 3×3-Matrizen (zeilenweise) ---

	static void mul(float[] out, float[] a, float[] b) {
		float[] r = out == a || out == b ? new float[9] : out;
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
			}
		}
		if (r != out) System.arraycopy(r, 0, out, 0, 9);
	}

	static void rotX(float[] m, float a) {
		float c = (float) Math.cos(a);
		float s = (float) Math.sin(a);
		set(m, 1, 0, 0, 0, c, -s, 0, s, c);
	}

	static void rotY(float[] m, float a) {
		float c = (float) Math.cos(a);
		float s = (float) Math.sin(a);
		set(m, c, 0, s, 0, 1, 0, -s, 0, c);
	}

	/** Rz · Ry · Rx (Reihenfolge wie Vanillas ModelPart). */
	static void rotZYX(float[] m, float z, float y, float x) {
		float cz = (float) Math.cos(z);
		float sz = (float) Math.sin(z);
		float cy = (float) Math.cos(y);
		float sy = (float) Math.sin(y);
		float cx = (float) Math.cos(x);
		float sx = (float) Math.sin(x);
		set(m,
				cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
				sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
				-sy, cy * sx, cy * cx);
	}

	private static void set(float[] m, float a, float b, float c, float d, float e, float f, float g, float h, float i) {
		m[0] = a;
		m[1] = b;
		m[2] = c;
		m[3] = d;
		m[4] = e;
		m[5] = f;
		m[6] = g;
		m[7] = h;
		m[8] = i;
	}
}

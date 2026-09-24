package dev.theredstonee.trsclient.core.cape;

/**
 * Stoff-Simulation eines Umhangs (Verlet-Punkte im Gitter, Abstands-Bedingungen, Kollision mit dem Körper).
 *
 * <p>Koordinaten: Modell-Pixel (1/16 Block) im <b>Körper-System</b> des Spielers – x = links des Spielers,
 * y = nach unten, z = nach hinten (weg vom Rücken). Der Ursprung liegt mittig an der Schulterkante direkt
 * am Rücken. Der Umhang ist 10 × 16 Pixel groß; die oberste Punktreihe ist an den Schultern festgemacht.
 *
 * <p>Die Punkte behalten ihren Schwung in der Welt: Bewegt oder dreht sich der Körper, verschiebt
 * {@link #tick} die freien Punkte (samt Vorposition) entgegengesetzt – dadurch schleift der Umhang beim
 * Loslaufen nach, schwingt beim Drehen und flattert beim Springen/Fallen. Luftwiderstand (quadratisch)
 * drückt ihn beim Laufen nach hinten, Schwerkraft zieht ihn nach unten (beim Schleichen gekippt).
 *
 * <p>Pro Spiel-Tick wird in {@link #SUBSTEPS} Teilschritten gerechnet; gezeichnet wird zwischen den
 * letzten beiden Tick-Ständen interpoliert ({@link #positions}).
 */
public final class ClothSim {
	public static final float WIDTH = 10f;
	public static final float HEIGHT = 16f;
	/** Halbe Stoffdicke: Mittelfläche liegt so weit hinter dem Rücken. */
	public static final float HALF_THICKNESS = 0.5f;
	static final int SUBSTEPS = 4;
	static final float TICK_SECONDS = 0.05f;
	/** Schwerkraft in Pixel/s² (≈ 1,2 g bei 1 Pixel = 6,25 cm – etwas straffer wirkt besser). */
	static final float GRAVITY = 190f;
	/** Größte Beschleunigung durch Luftwiderstand (sonst klappt der Umhang beim Fallen über den Kopf). */
	static final float MAX_DRAG = 1.8f * GRAVITY;
	/** Senkrechte Bewegung wirkt schwächer (sonst schlägt der Umhang nach jedem Sprung über den Kopf). */
	static final float VERTICAL_INERTIA = 0.55f;
	/** Kein Punkt höher als so viele Pixel über der Schulterkante (Umhang klappt höchstens waagerecht hoch). */
	static final float MAX_RISE = 2f;
	/** Weiter als so viele Pixel in einem Tick = Teleport → Umhang neu aufhängen. */
	static final float TELEPORT_PX = 4 * 16f;
	/** Größte Bewegung eines Punkts je Teilschritt (Pixel) – hält die Simulation bei wilden Eingaben stabil. */
	static final float MAX_STEP = 6f;
	/** Ein Punkt darf höchstens so viel weiter von seinem Aufhängepunkt weg sein als in Ruhe (kein Gummi). */
	static final float MAX_STRETCH = 1.08f;
	static final float TORSO = 12f;
	static final float LEGS = 12f;

	/** Bewegung des Körpers seit dem letzten Tick (alles im Körper-System des neuen Ticks). */
	public static final class Motion {
		/** Verschiebung in Pixeln (x links, y unten, z hinten). */
		public float dx;
		public float dy;
		public float dz;
		/** Drehung des Körpers in Radiant (positiv = nach rechts, wie Minecrafts Yaw). */
		public float dYaw;
		/** Vorneigung des Oberkörpers in Radiant (Schleichen ≈ 0,5). */
		public float tilt;
		/** Wie weit das hintere Bein gerade nach hinten schwingt (Radiant, ≥ 0). */
		public float legSwing;
		/** Laufende Zeit in Sekunden (für das Flattern). */
		public float time;
		/** Zeitversatz je Spieler (Sekunden), damit Böen nicht bei allen gleichzeitig kommen. */
		public float phase;
	}

	/** Wind ohne eigene Bewegung: keiner. */
	public static final int WIND_OFF = 0;
	/** Gleichmäßige Wellen (Flattern beim Laufen, leichtes Wiegen im Stand). */
	public static final int WIND_WAVES = 1;
	/** Wellen plus Böen, die den Umhang auch im Stand anheben. */
	public static final int WIND_GUSTS = 2;

	/**
	 * Einstellungen aus dem Menü (siehe {@link CapeSettings#apply}). Die Standardwerte sind das
	 * ursprüngliche Verhalten.
	 */
	public static final class Params {
		/** Anteil der Körperbewegung, den der Stoff in der Welt behält (Trägheit), 0–2. */
		public float strength = 1f;
		/** Anteil der Körperdrehung, den der Stoff behält (zusätzlich zu {@link #strength}), 0–1. */
		public float turn = 1f;
		/** Stärke von Flattern, Wiegen und Böen, 0–2. */
		public float wind = 1f;
		public int windMode = WIND_WAVES;
		/** Faktor der Schwerkraft. */
		public float gravity = 1f;
		/** Anhebung durch Luftwiderstand (Fahrtwind), 0–2. */
		public float lift = 1f;
		/** Faktor der Biege-/Scher-Steifheit (0 = weich wie Seide). */
		public float stiffness = 1f;
		/** Anteil der Geschwindigkeit, den ein Punkt je Teilschritt behält. */
		public float damping = 0.995f;
		/** Tempo der Wellen (1 = normal, kleiner = ruhiger). */
		public float waveSpeed = 1f;
	}

	final int cols;
	final int rows;
	final int w;
	final int h;
	final float[] x;
	final float[] y;
	final float[] z;
	final float[] ox;
	final float[] oy;
	final float[] oz;
	/** Stand am Ende des vorletzten / letzten Ticks (für das Interpolieren beim Zeichnen). */
	final float[] prev;
	final float[] cur;
	private final int[] ca;
	private final int[] cb;
	private final float[] rest;
	private final float[] stiff;
	private final int iterations;
	private boolean fresh = true;
	/** Steifheits-Faktor des laufenden Ticks (aus {@link Params#stiffness}). */
	private float stiffness = 1f;

	/** @param cols Zellen quer (z. B. 10), @param rows Zellen längs (z. B. 16) */
	public ClothSim(int cols, int rows) {
		if (cols < 1 || rows < 1 || cols > 32 || rows > 32) throw new IllegalArgumentException("Gittergröße");
		this.cols = cols;
		this.rows = rows;
		this.w = cols + 1;
		this.h = rows + 1;
		int n = w * h;
		x = new float[n];
		y = new float[n];
		z = new float[n];
		ox = new float[n];
		oy = new float[n];
		oz = new float[n];
		prev = new float[n * 3];
		cur = new float[n * 3];
		iterations = cols * rows >= 60 ? 5 : 3;

		// Bedingungen: Struktur (waagerecht/senkrecht), Scherung (diagonal), Biegung (übernächster Punkt).
		int max = (cols * h + rows * w) + 2 * cols * rows + (Math.max(0, cols - 1) * h + Math.max(0, rows - 1) * w);
		ca = new int[max];
		cb = new int[max];
		rest = new float[max];
		stiff = new float[max];
		int k = 0;
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				if (i + 1 < w) k = link(k, idx(i, j), idx(i + 1, j), 1f);
				if (j + 1 < h) k = link(k, idx(i, j), idx(i, j + 1), 1f);
				if (i + 1 < w && j + 1 < h) {
					k = link(k, idx(i, j), idx(i + 1, j + 1), 0.7f);
					k = link(k, idx(i + 1, j), idx(i, j + 1), 0.7f);
				}
				if (i + 2 < w) k = link(k, idx(i, j), idx(i + 2, j), 0.35f);
				if (j + 2 < h) k = link(k, idx(i, j), idx(i, j + 2), 0.25f);
			}
		}
		count = k;
		reset(0f);
		for (int c = 0; c < count; c++) rest[c] = dist(ca[c], cb[c]);
	}

	private final int count;

	private int link(int k, int a, int b, float s) {
		ca[k] = a;
		cb[k] = b;
		stiff[k] = s;
		return k + 1;
	}

	final int idx(int i, int j) {
		return j * w + i;
	}

	/** Ruheposition der Spalte i (i = 0 → linker Rand des Spielers, x = +5). */
	float restX(int i) {
		return WIDTH / 2f - WIDTH * i / cols;
	}

	float restY(int j) {
		return HEIGHT * j / rows;
	}

	/** Umhang gerade herabhängend neu aufhängen (leicht vom Rücken abstehend, je nach Neigung). */
	public void reset(float tilt) {
		float sin = (float) Math.sin(tilt);
		float cos = (float) Math.cos(tilt);
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int p = idx(i, j);
				float d = restY(j);
				x[p] = restX(i);
				// Senkrecht in der Welt = im gekippten Körper-System schräg nach hinten.
				y[p] = d * cos;
				z[p] = HALF_THICKNESS + d * Math.max(0.12f, sin);
				ox[p] = x[p];
				oy[p] = y[p];
				oz[p] = z[p];
			}
		}
		snapshot(cur);
		snapshot(prev);
		fresh = true;
	}

	private float dist(int a, int b) {
		float dx = x[b] - x[a];
		float dy = y[b] - y[a];
		float dz = z[b] - z[a];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Ein Spiel-Tick (50 ms) mit voller Genauigkeit ({@link #SUBSTEPS} Teilschritte). */
	public void tick(Motion m, Params p) {
		tick(m, p, SUBSTEPS);
	}

	/**
	 * Ein Spiel-Tick mit {@code substeps} Teilschritten (1–{@link #SUBSTEPS}). Weniger Teilschritte für ferne
	 * Umhänge (grobes Gitter) sparen Rechenzeit; die Dämpfung wird so umgerechnet, dass der Stoff gleich
	 * schnell zur Ruhe kommt.
	 */
	public void tick(Motion m, Params p, int substeps) {
		int sub = Math.max(1, Math.min(SUBSTEPS, substeps));
		System.arraycopy(cur, 0, prev, 0, cur.length);
		float move = (float) Math.sqrt(m.dx * m.dx + m.dy * m.dy + m.dz * m.dz);
		if (move > TELEPORT_PX || Math.abs(m.dYaw) > Math.PI / 2 || !finite(m)) {
			reset(m.tilt);
			return;
		}
		float strength = clamp(p.strength, 0f, 2f);
		float turn = clamp(p.turn, 0f, 1f);
		float wind = clamp(p.wind, 0f, 2f);
		float gravity = clamp(p.gravity, 0.1f, 3f);
		float lift = clamp(p.lift, 0f, 3f);
		float damping = clamp(p.damping, 0.8f, 1f);
		if (sub != SUBSTEPS) damping = (float) Math.pow(damping, (double) SUBSTEPS / sub);
		float waveSpeed = clamp(p.waveSpeed, 0.1f, 3f);
		stiffness = clamp(p.stiffness, 0f, 5f);
		boolean windy = p.windMode != WIND_OFF;

		float hStep = TICK_SECONDS / sub;
		float gy = GRAVITY * gravity * (float) Math.cos(m.tilt);
		float gz = -GRAVITY * gravity * (float) Math.sin(m.tilt);
		// Horizontale Geschwindigkeit des Körpers (für das Flattern), Pixel/s.
		float speed = (float) Math.sqrt(m.dx * m.dx + m.dz * m.dz) / TICK_SECONDS;
		float flutter = windy ? Math.min(1f, speed / 90f) * 0.55f * GRAVITY * wind : 0f;
		float idle = windy ? 0.04f * GRAVITY * wind : 0f;
		float dragK = 0.035f * lift;
		float part = strength / sub;
		for (int s = 0; s < sub; s++) {
			float t = m.time + s * hStep;
			float gust = p.windMode == WIND_GUSTS ? gust(t + m.phase) * wind : 0f;
			// Körper hat sich bewegt/gedreht: freie Punkte behalten ihre Lage in der Welt (samt Schwung),
			// verteilt auf die Teilschritte.
			frameShift(-m.dx * part, -m.dy * part * VERTICAL_INERTIA, -m.dz * part, m.dYaw * part * turn);
			integrate(hStep, gy, gz, dragK, flutter + gust * 0.3f * GRAVITY, idle, gust, t, t + m.phase, damping, waveSpeed);
			for (int it = 0; it < iterations; it++) {
				solve();
				collide(m.tilt, m.legSwing);
			}
			tether();
			pin();
		}
		snapshot(cur);
		if (fresh) {
			System.arraycopy(cur, 0, prev, 0, cur.length);
			fresh = false;
		}
	}

	/** Verschiebt und dreht (um die Körperachse, 2 px vor dem Rücken) alle freien Punkte. */
	void frameShift(float sx, float sy, float sz, float dYaw) {
		float s = (float) Math.sin(dYaw);
		float c = (float) Math.cos(dYaw);
		boolean rotate = Math.abs(dYaw) > 1e-6f;
		for (int p = w; p < x.length; p++) {
			if (rotate) {
				float rz = z[p] + 2f;
				float nx = x[p] * c - rz * s;
				float nz = x[p] * s + rz * c;
				x[p] = nx;
				z[p] = nz - 2f;
				rz = oz[p] + 2f;
				nx = ox[p] * c - rz * s;
				nz = ox[p] * s + rz * c;
				ox[p] = nx;
				oz[p] = nz - 2f;
			}
			x[p] += sx;
			y[p] += sy;
			z[p] += sz;
			ox[p] += sx;
			oy[p] += sy;
			oz[p] += sz;
		}
	}

	/**
	 * Böe zur Zeit {@code t} (Sekunden): 0 = Flaute, 1 = volle Böe. Überlagerte Sinuswellen mit
	 * unterschiedlichen Perioden – wirkt zufällig, ist aber reproduzierbar und stetig.
	 */
	static float gust(float t) {
		double s = Math.sin(t * 0.9) + 0.6 * Math.sin(t * 2.3 + 1.1) + 0.35 * Math.sin(t * 5.1 + 2.3);
		float v = clamp((float) ((s - 0.35) / 1.6), 0f, 1f);
		return v * v * (3f - 2f * v);
	}

	private void integrate(float hStep, float gy, float gz, float dragK, float flutter, float idle, float gust,
			float t, float tGust, float damping, float waveSpeed) {
		float h2 = hStep * hStep;
		float gustBack = gust * 0.7f * GRAVITY;
		float gustSide = gust * 0.25f * GRAVITY * (float) Math.sin(tGust * 0.37);
		for (int j = 1; j < h; j++) {
			float depth = (float) j / rows;
			float wave = (float) Math.sin(t * 17.0 * waveSpeed + j * 0.85) * depth * depth;
			float sway = (float) Math.sin(t * 2.3 * waveSpeed + j * 0.4) * depth;
			for (int i = 0; i < w; i++) {
				int p = idx(i, j);
				float vx = x[p] - ox[p];
				float vy = y[p] - oy[p];
				float vz = z[p] - oz[p];
				// Luftwiderstand gegen die Bewegung in der Welt (quadratisch, begrenzt).
				float sx = vx / hStep;
				float sy = vy / hStep;
				float sz = vz / hStep;
				float sp = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
				float drag = Math.min(MAX_DRAG, dragK * sp * sp);
				float ax = 0f;
				float ay = gy;
				float az = gz;
				if (sp > 1e-3f) {
					ax -= drag * sx / sp;
					ay -= drag * sy / sp;
					az -= drag * sz / sp;
				}
				float edge = (i == 0 || i == cols) ? 0.6f : 1f;
				az += flutter * wave * edge + idle * sway;
				ax += flutter * 0.25f * wave * (i - cols / 2f) / (cols / 2f);
				// Böe: drückt den Umhang nach hinten und etwas zur Seite (unten stärker als oben).
				az += gustBack * depth;
				ax += gustSide * depth;
				// Grunddämpfung für Stabilität, Tempo begrenzt (nie mehr als MAX_STEP je Teilschritt).
				vx *= damping;
				vy *= damping;
				vz *= damping;
				float v2 = vx * vx + vy * vy + vz * vz;
				if (v2 > MAX_STEP * MAX_STEP) {
					float k = MAX_STEP / (float) Math.sqrt(v2);
					vx *= k;
					vy *= k;
					vz *= k;
				}
				ox[p] = x[p];
				oy[p] = y[p];
				oz[p] = z[p];
				x[p] += vx + ax * h2;
				y[p] += vy + ay * h2;
				z[p] += vz + az * h2;
			}
		}
	}

	private void solve() {
		for (int c = 0; c < count; c++) {
			int a = ca[c];
			int b = cb[c];
			float dx = x[b] - x[a];
			float dy = y[b] - y[a];
			float dz = z[b] - z[a];
			float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < 1e-6f) continue;
			float r = rest[c];
			// Biegung/Scherung dürfen stauchen, aber nicht dehnen → Stoff wirft Falten statt Gummi.
			float k = stiff[c];
			if (k < 1f) k = Math.min(1f, k * stiffness);
			float diff = (d - r) / d * k;
			if (stiff[c] < 1f && d < r) diff *= 0.25f;
			boolean pa = a < w;
			boolean pb = b < w;
			if (pa && pb) continue;
			if (pa) {
				x[b] -= dx * diff;
				y[b] -= dy * diff;
				z[b] -= dz * diff;
			} else if (pb) {
				x[a] += dx * diff;
				y[a] += dy * diff;
				z[a] += dz * diff;
			} else {
				float half = diff * 0.5f;
				x[a] += dx * half;
				y[a] += dy * half;
				z[a] += dz * half;
				x[b] -= dx * half;
				y[b] -= dy * half;
				z[b] -= dz * half;
			}
		}
	}

	/**
	 * Körper als Halbraum hinter dem Rücken: Oberkörper (y 0–12) und Beine (y 12–24; das hintere Bein
	 * schiebt beim Laufen, beim Schleichen stehen die Beine vor dem Körper). Oberhalb der Schultern der
	 * Kopf. Nur im Bereich der Körperbreite.
	 */
	private void collide(float tilt, float legSwing) {
		float legSlope = (float) Math.tan(clamp(legSwing - tilt, -1.2f, 1.2f));
		for (int p = w; p < x.length; p++) {
			if (y[p] < -MAX_RISE) {
				y[p] = -MAX_RISE;
				oy[p] += (y[p] - oy[p]) * 0.5f;
			}
			float px = x[p];
			if (px < -6.5f || px > 6.5f) continue;
			float py = y[p];
			float min;
			if (py < -10f || py > TORSO + LEGS) continue;
			if (py <= TORSO) min = HALF_THICKNESS;
			else min = HALF_THICKNESS + (py - TORSO) * legSlope;
			if (z[p] < min) {
				z[p] = min;
				// Reibung am Körper: seitliches Rutschen bremsen.
				ox[p] += (x[p] - ox[p]) * 0.2f;
				oy[p] += (y[p] - oy[p]) * 0.2f;
			}
		}
	}

	/**
	 * Fernbindung: jeder Punkt höchstens {@link #MAX_STRETCH} × Ruheabstand von seinem Aufhängepunkt
	 * (gleiche Spalte, oberste Reihe). Verhindert Dehnen, egal wie wenige Iterationen laufen.
	 */
	private void tether() {
		for (int j = 1; j < h; j++) {
			float max = restY(j) * MAX_STRETCH;
			for (int i = 0; i < w; i++) {
				int p = idx(i, j);
				float dx = x[p] - restX(i);
				float dy = y[p];
				float dz = z[p] - HALF_THICKNESS;
				float d2 = dx * dx + dy * dy + dz * dz;
				if (d2 > max * max) {
					float k = max / (float) Math.sqrt(d2);
					x[p] = restX(i) + dx * k;
					y[p] = dy * k;
					z[p] = HALF_THICKNESS + dz * k;
				}
			}
		}
	}

	private void pin() {
		for (int i = 0; i < w; i++) {
			int p = idx(i, 0);
			x[p] = restX(i);
			y[p] = 0f;
			z[p] = HALF_THICKNESS;
			ox[p] = x[p];
			oy[p] = y[p];
			oz[p] = z[p];
		}
	}

	private void snapshot(float[] out) {
		for (int p = 0; p < x.length; p++) {
			out[p * 3] = x[p];
			out[p * 3 + 1] = y[p];
			out[p * 3 + 2] = z[p];
		}
	}

	/** Punkte zwischen den letzten beiden Ticks interpoliert ({@code partial} 0–1) → out[x,y,z,…]. */
	public void positions(float partial, float[] out) {
		float t = clamp(partial, 0f, 1f);
		for (int k = 0; k < cur.length; k++) out[k] = prev[k] + (cur[k] - prev[k]) * t;
	}

	public int cols() {
		return cols;
	}

	public int rows() {
		return rows;
	}

	/** Anzahl der Punkte. */
	public int points() {
		return w * h;
	}

	/** Aktueller Punkt (für Tests): [x, y, z]. */
	float[] point(int i, int j) {
		int p = idx(i, j);
		return new float[]{x[p], y[p], z[p]};
	}

	private static boolean finite(Motion m) {
		return !Float.isNaN(m.dx + m.dy + m.dz + m.dYaw + m.tilt) && !Float.isInfinite(m.dx + m.dy + m.dz + m.dYaw);
	}

	static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	/** Sind alle Punkte endlich? (Schutz: sonst neu aufhängen.) */
	public boolean healthy() {
		for (int p = 0; p < x.length; p++) {
			if (Float.isNaN(x[p]) || Float.isNaN(y[p]) || Float.isNaN(z[p])) return false;
			if (Math.abs(x[p]) > 64 || Math.abs(y[p]) > 64 || Math.abs(z[p]) > 64) return false;
		}
		return true;
	}
}

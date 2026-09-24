package dev.theredstonee.trsclient.core.render;

/**
 * Farb-Nachbearbeitung des Spielbilds (Modul „Farben“): Helligkeit, Kontrast, Sättigung und
 * Farbtemperatur als eine affine 3 × 4-Farbmatrix, dazu Dynamik (Vibrance) als nicht-linearer Schritt.
 *
 * <p>Die Rechnung steht hier einmal für alle Versionen: {@link #matrix} liefert die Zeilen für den Shader,
 * {@link #apply} rechnet genau dasselbe auf der CPU (für Tests). Die Shader-Quelltexte gibt es für
 * GLSL 1.20 (OpenGL 2.1, Minecraft ≤ 1.16 und Forge 1.8.9–1.12.2) und GLSL 1.50 (Core-Profil ab 1.17).
 */
public final class ColorGrade {
	/** Luma-Gewichte nach Rec. 709 (sRGB). */
	public static final float LR = 0.2126f;
	public static final float LG = 0.7152f;
	public static final float LB = 0.0722f;
	/** Größte Verschiebung der Farbtemperatur (Anteil je Kanal bei ±100 %). */
	static final float TEMPERATURE_GAIN = 0.12f;

	/** Sättigung 0–2 (1 = unverändert, 0 = Graustufen). */
	public float saturation = 1f;
	/** Kontrast 0,5–1,5 (um mittleres Grau). */
	public float contrast = 1f;
	/** Helligkeit 0,5–1,5 (Faktor). */
	public float brightness = 1f;
	/** Dynamik −1–1: hebt blasse Farben stärker an als bereits kräftige. */
	public float vibrance = 0f;
	/** Farbtemperatur −1 (kühl, blau) bis 1 (warm, orange). */
	public float temperature = 0f;

	/** Setzt die Werte aus den Menü-Prozenten (Sättigung 0–200, Kontrast/Helligkeit 50–150, Rest −100–100). */
	public ColorGrade set(double saturationPct, double contrastPct, double brightnessPct, double vibrancePct,
			double temperaturePct) {
		saturation = clamp((float) saturationPct / 100f, 0f, 2f);
		contrast = clamp((float) contrastPct / 100f, 0.5f, 1.5f);
		brightness = clamp((float) brightnessPct / 100f, 0.5f, 1.5f);
		vibrance = clamp((float) vibrancePct / 100f, -1f, 1f);
		temperature = clamp((float) temperaturePct / 100f, -1f, 1f);
		return this;
	}

	/** Ändert nichts am Bild (dann braucht es keinen Durchgang). */
	public boolean identity() {
		return near(saturation, 1f) && near(contrast, 1f) && near(brightness, 1f) && near(vibrance, 0f)
				&& near(temperature, 0f);
	}

	/**
	 * Farbmatrix als drei Zeilen (r, g, b) zu je vier Werten: {@code out = M · (r, g, b, 1)}.
	 * Reihenfolge: Helligkeit → Kontrast → Sättigung → Temperatur.
	 *
	 * @param out mindestens 12 Werte, zeilenweise
	 */
	public float[] matrix(float[] out) {
		// Helligkeit: c·B
		float[] m = {brightness, 0, 0, 0, 0, brightness, 0, 0, 0, 0, brightness, 0};
		// Kontrast: (c − ½)·C + ½
		float off = 0.5f * (1f - contrast);
		m = mul(new float[]{contrast, 0, 0, off, 0, contrast, 0, off, 0, 0, contrast, off}, m);
		// Sättigung: L + S·(c − L)
		float s = saturation;
		float is = 1f - s;
		m = mul(new float[]{
				is * LR + s, is * LG, is * LB, 0,
				is * LR, is * LG + s, is * LB, 0,
				is * LR, is * LG, is * LB + s, 0}, m);
		// Temperatur: warm = mehr Rot, weniger Blau (und umgekehrt), Grün kaum
		float t = temperature * TEMPERATURE_GAIN;
		m = mul(new float[]{1f + t, 0, 0, 0, 0, 1f + t * 0.15f, 0, 0, 0, 0, 1f - t, 0}, m);
		System.arraycopy(m, 0, out, 0, 12);
		return out;
	}

	/** Dieselbe Rechnung wie der Shader, auf der CPU: {@code rgb} (0–1) wird überschrieben. */
	public void apply(float[] rgb) {
		float[] m = matrix(new float[12]);
		float r = rgb[0];
		float g = rgb[1];
		float b = rgb[2];
		float nr = m[0] * r + m[1] * g + m[2] * b + m[3];
		float ng = m[4] * r + m[5] * g + m[6] * b + m[7];
		float nb = m[8] * r + m[9] * g + m[10] * b + m[11];
		float mx = Math.max(nr, Math.max(ng, nb));
		float mn = Math.min(nr, Math.min(ng, nb));
		float l = nr * LR + ng * LG + nb * LB;
		float k = 1f + vibrance * (1f - (mx - mn));
		rgb[0] = clamp(l + (nr - l) * k, 0f, 1f);
		rgb[1] = clamp(l + (ng - l) * k, 0f, 1f);
		rgb[2] = clamp(l + (nb - l) * k, 0f, 1f);
	}

	/** a · b für affine 3 × 4-Matrizen (zeilenweise, vierte Zeile implizit 0 0 0 1). */
	static float[] mul(float[] a, float[] b) {
		float[] o = new float[12];
		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 4; c++) {
				float v = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c];
				if (c == 3) v += a[r * 4 + 3];
				o[r * 4 + c] = v;
			}
		}
		return o;
	}

	private static boolean near(float a, float b) {
		return Math.abs(a - b) < 1e-3f;
	}

	static float clamp(float v, float lo, float hi) {
		if (Float.isNaN(v)) return lo;
		return v < lo ? lo : (v > hi ? hi : v);
	}

	// --- Shader (Vollbild-Dreieck aus einem Attribut "Position" in −1…3) ---

	/** Gemeinsamer Rumpf des Fragment-Shaders (nach den Deklarationen). */
	private static final String BODY =
			"    vec4 src = SAMPLE(Scene, uv);\n"
					+ "    vec4 h = vec4(src.rgb, 1.0);\n"
					+ "    vec3 c = vec3(dot(RowR, h), dot(RowG, h), dot(RowB, h));\n"
					+ "    float mx = max(c.r, max(c.g, c.b));\n"
					+ "    float mn = min(c.r, min(c.g, c.b));\n"
					+ "    float l = dot(c, vec3(" + LR + ", " + LG + ", " + LB + "));\n"
					+ "    c = mix(vec3(l), c, 1.0 + Vibrance * (1.0 - (mx - mn)));\n"
					+ "    OUT = vec4(clamp(c, 0.0, 1.0), src.a);\n";

	/** GLSL 1.20 (OpenGL 2.1 / Kompatibilitätsprofil). */
	public static final String VERTEX_120 = "#version 120\n"
			+ "attribute vec2 Position;\n"
			+ "varying vec2 uv;\n"
			+ "void main() {\n"
			+ "    uv = Position * 0.5 + 0.5;\n"
			+ "    gl_Position = vec4(Position, 0.0, 1.0);\n"
			+ "}\n";

	public static final String FRAGMENT_120 = "#version 120\n"
			+ "uniform sampler2D Scene;\n"
			+ "uniform vec4 RowR;\n"
			+ "uniform vec4 RowG;\n"
			+ "uniform vec4 RowB;\n"
			+ "uniform float Vibrance;\n"
			+ "varying vec2 uv;\n"
			+ "void main() {\n"
			+ BODY.replace("SAMPLE", "texture2D").replace("OUT", "gl_FragColor")
			+ "}\n";

	/** GLSL 1.50 (Core-Profil 3.2, Minecraft ab 1.17). */
	public static final String VERTEX_150 = "#version 150\n"
			+ "in vec2 Position;\n"
			+ "out vec2 uv;\n"
			+ "void main() {\n"
			+ "    uv = Position * 0.5 + 0.5;\n"
			+ "    gl_Position = vec4(Position, 0.0, 1.0);\n"
			+ "}\n";

	public static final String FRAGMENT_150 = "#version 150\n"
			+ "uniform sampler2D Scene;\n"
			+ "uniform vec4 RowR;\n"
			+ "uniform vec4 RowG;\n"
			+ "uniform vec4 RowB;\n"
			+ "uniform float Vibrance;\n"
			+ "in vec2 uv;\n"
			+ "out vec4 fragColor;\n"
			+ "void main() {\n"
			+ BODY.replace("SAMPLE", "texture").replace("OUT", "fragColor")
			+ "}\n";

	/** Eckpunkte des Vollbild-Dreiecks (x, y) – deckt den Bildschirm mit einem einzigen Dreieck ab. */
	public static final float[] TRIANGLE = {-1f, -1f, 3f, -1f, -1f, 3f};
}

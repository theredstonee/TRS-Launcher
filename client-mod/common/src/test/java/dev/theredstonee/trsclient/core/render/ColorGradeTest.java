package dev.theredstonee.trsclient.core.render;

import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Farbmatrix des Moduls „Farben“ (Sättigung, Kontrast, Helligkeit, Dynamik, Temperatur). */
class ColorGradeTest {

	static float[] grade(ColorGrade g, float r, float gr, float b) {
		float[] c = {r, gr, b};
		g.apply(c);
		return c;
	}

	static float luma(float[] c) {
		return c[0] * ColorGrade.LR + c[1] * ColorGrade.LG + c[2] * ColorGrade.LB;
	}

	@Test
	void defaultsAreTheIdentity() {
		ColorGrade g = new ColorGrade();
		assertTrue(g.identity());
		assertArrayEquals(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}, g.matrix(new float[12]), 1e-6f);
		assertArrayEquals(new float[]{0.3f, 0.6f, 0.1f}, grade(g, 0.3f, 0.6f, 0.1f), 1e-6f);
		// Menü-Standardwerte = Identität (Modul an ändert erst mit geänderten Reglern etwas)
		TrsModules modules = new TrsModules();
		assertTrue(modules.colorGrade(new ColorGrade()).identity());
		assertFalse(modules.colors.isEnabled(), "Farben sind standardmäßig aus");
	}

	@Test
	void zeroSaturationIsGreyscaleWithTheSameLuma() {
		ColorGrade g = new ColorGrade().set(0, 100, 100, 0, 0);
		float[] c = grade(g, 0.9f, 0.2f, 0.1f);
		assertEquals(c[0], c[1], 1e-5f);
		assertEquals(c[1], c[2], 1e-5f);
		assertEquals(luma(new float[]{0.9f, 0.2f, 0.1f}), c[0], 1e-5f, "Helligkeitseindruck bleibt");
	}

	@Test
	void doubleSaturationPushesColoursApart() {
		ColorGrade g = new ColorGrade().set(200, 100, 100, 0, 0);
		float[] in = {0.6f, 0.4f, 0.3f};
		float[] c = grade(g, in[0], in[1], in[2]);
		assertTrue(c[0] - c[2] > in[0] - in[2], "kräftiger");
		assertEquals(luma(in), luma(c), 1e-5f, "Luma bleibt (ohne Abschneiden)");
		// Grau bleibt grau
		float[] grey = grade(g, 0.5f, 0.5f, 0.5f);
		assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f}, grey, 1e-5f);
	}

	@Test
	void contrastPivotsAroundMidGrey() {
		ColorGrade g = new ColorGrade().set(100, 150, 100, 0, 0);
		assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f}, grade(g, 0.5f, 0.5f, 0.5f), 1e-5f);
		assertEquals(0.8f, grade(g, 0.7f, 0.7f, 0.7f)[0], 1e-5f);
		assertEquals(0.2f, grade(g, 0.3f, 0.3f, 0.3f)[0], 1e-5f);
	}

	@Test
	void brightnessScalesAndClamps() {
		ColorGrade g = new ColorGrade().set(100, 100, 150, 0, 0);
		assertEquals(0.6f, grade(g, 0.4f, 0.4f, 0.4f)[0], 1e-5f);
		assertEquals(1f, grade(g, 0.9f, 0.9f, 0.9f)[0], 1e-6f, "abgeschnitten auf 1");
		ColorGrade dark = new ColorGrade().set(100, 100, 50, 0, 0);
		assertEquals(0.2f, grade(dark, 0.4f, 0.4f, 0.4f)[0], 1e-5f);
	}

	@Test
	void temperatureShiftsRedAndBlue() {
		ColorGrade warm = new ColorGrade().set(100, 100, 100, 0, 100);
		float[] w = grade(warm, 0.5f, 0.5f, 0.5f);
		assertTrue(w[0] > 0.5f && w[2] < 0.5f, "warm = mehr Rot, weniger Blau");
		ColorGrade cool = new ColorGrade().set(100, 100, 100, 0, -100);
		float[] c = grade(cool, 0.5f, 0.5f, 0.5f);
		assertTrue(c[0] < 0.5f && c[2] > 0.5f, "kühl = mehr Blau");
	}

	@Test
	void vibranceBoostsDullColoursMoreThanVividOnes() {
		ColorGrade g = new ColorGrade().set(100, 100, 100, 100, 0);
		float[] dullIn = {0.55f, 0.5f, 0.45f};
		float[] vividIn = {0.9f, 0.1f, 0.1f};
		float[] dull = grade(g, dullIn[0], dullIn[1], dullIn[2]);
		float[] vivid = grade(g, vividIn[0], vividIn[1], vividIn[2]);
		float dullGain = (dull[0] - dull[2]) / (dullIn[0] - dullIn[2]);
		float vividGain = (vivid[0] - vivid[2]) / (vividIn[0] - vividIn[2]);
		assertTrue(dullGain > vividGain, "blasse Farben gewinnen mehr: " + dullGain + " vs " + vividGain);
		assertFalse(g.identity());
	}

	@Test
	void settingsAreClampedAndNaNSafe() {
		ColorGrade g = new ColorGrade().set(999, 10, 999, -500, Double.NaN);
		assertEquals(2f, g.saturation, 1e-6f);
		assertEquals(0.5f, g.contrast, 1e-6f);
		assertEquals(1.5f, g.brightness, 1e-6f);
		assertEquals(-1f, g.vibrance, 1e-6f);
		assertEquals(-1f, g.temperature, 1e-6f);
		for (float[] c : new float[][]{{0, 0, 0}, {1, 1, 1}, {1, 0, 0}, {0.2f, 0.9f, 0.4f}}) {
			g.apply(c);
			for (float v : c) assertTrue(v >= 0f && v <= 1f && !Float.isNaN(v));
		}
	}

	@Test
	void shaderSourcesMatchTheCpuReference() {
		// Die Shader lesen dieselben Uniforms, die die Loader aus matrix() setzen.
		for (String src : new String[]{ColorGrade.FRAGMENT_120, ColorGrade.FRAGMENT_150}) {
			assertTrue(src.contains("uniform vec4 RowR;") && src.contains("uniform vec4 RowG;") && src.contains("uniform vec4 RowB;"));
			assertTrue(src.contains("uniform float Vibrance;"));
			assertTrue(src.contains("uniform sampler2D Scene;"));
			assertFalse(src.contains("SAMPLE") || src.contains("OUT ="));
		}
		assertTrue(ColorGrade.FRAGMENT_120.contains("texture2D(") && ColorGrade.FRAGMENT_120.contains("gl_FragColor"));
		assertTrue(ColorGrade.FRAGMENT_150.contains("texture(Scene") && ColorGrade.FRAGMENT_150.contains("out vec4 fragColor"));
		assertTrue(ColorGrade.VERTEX_120.contains("attribute vec2 Position"));
		assertTrue(ColorGrade.VERTEX_150.contains("in vec2 Position"));
		assertEquals(6, ColorGrade.TRIANGLE.length);
	}
}

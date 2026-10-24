package dev.theredstonee.trsclient.core.cosmetic.v2;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Leucht-Höfe als Dreiecks-Scheibe ohne Tiefe ({@link CosmeticV2Renderer#haloTriangles}, ab 1.21.5 „dragon_rays“). */
class HaloMeshTest {
	@Test
	void discMatchesTheFalloffAndFacesTheCamera() {
		final List<float[]> v = new ArrayList<float[]>();
		CosmeticV2Renderer.VertexSink s = CosmeticV2Renderer.haloTriangles((x, y, z, argb) -> v.add(new float[] { x, y, z, Float.intBitsToFloat(argb) }));
		// Hof-Viereck wie emitHalo: Mitte (0, 0, 0), Kamera auf +z, rechts = +x, oben = +y, halbe Kante 2
		int color = 0xFF80C0FF;
		s.vertex(-2, 2, 0, 0, 0, 0, 0, 1, color); // OL
		s.vertex(2, 2, 0, 1, 0, 0, 0, 1, color); // OR
		s.vertex(2, -2, 0, 1, 1, 0, 0, 1, color); // UR
		s.vertex(-2, -2, 0, 0, 1, 0, 0, 1, color); // UL
		assertEquals(0, v.size() % 3, "nur ganze Dreiecke");
		assertTrue(v.size() > 100, "fein genug: " + v.size());
		double area = 0;
		float maxR = 0;
		for (int i = 0; i < v.size(); i += 3) {
			float[] a = v.get(i), b = v.get(i + 1), c = v.get(i + 2);
			// gegen den Uhrzeigersinn von der Kamera (+z) aus gesehen
			double cross = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
			assertTrue(cross > 0, "Dreieck " + i / 3 + " zeigt zur Kamera");
			area += cross / 2;
			for (float[] p : new float[][] { a, b, c }) {
				maxR = Math.max(maxR, (float) Math.hypot(p[0], p[1]));
				assertEquals(color & 0xFFFFFF, Float.floatToRawIntBits(p[3]) & 0xFFFFFF, "Farbe des Hofs");
				assertEquals(0f, p[2], 1e-6f, "in der Ebene des Vierecks");
			}
		}
		assertEquals(2f, maxR, 1e-4f, "Radius = halbe Kante");
		int n = CosmeticV2Renderer.HALO_SEGMENTS;
		assertEquals(0.5 * n * 4 * Math.sin(2 * Math.PI / n), area, 1e-3, "ganze Scheibe (Vieleck) ohne Überlappung");
		// Mitte voll, Rand 0 – wie (1 − r)^2,5
		assertEquals(255, Float.floatToRawIntBits(v.get(0)[3]) >>> 24, "Mitte");
		int rim = 0;
		for (float[] p : v) if (Math.hypot(p[0], p[1]) > 1.999) rim = Math.max(rim, Float.floatToRawIntBits(p[3]) >>> 24);
		assertEquals(0, rim, "Rand unsichtbar");
	}
}

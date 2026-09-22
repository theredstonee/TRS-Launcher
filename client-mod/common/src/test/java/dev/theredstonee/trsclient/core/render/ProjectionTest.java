package dev.theredstonee.trsclient.core.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionTest {
	private final double[] out = new double[3];

	@Test
	void pointStraightAheadLandsInTheMiddle() {
		// yaw 0 = Blick nach Süden (+Z)
		assertTrue(Projection.project(0, 64, 0, 0, 0, 70, 640, 360, 0, 64, 10, out));
		assertEquals(320.0, out[0], 1e-6);
		assertEquals(180.0, out[1], 1e-6);
		assertEquals(10.0, out[2], 1e-6);
	}

	@Test
	void pointBehindIsRejected() {
		assertFalse(Projection.project(0, 64, 0, 0, 0, 70, 640, 360, 0, 64, -10, out));
		assertFalse(Projection.project(0, 64, 0, 180, 0, 70, 640, 360, 0, 64, 10, out),
				"bei Blick nach Norden liegt +Z hinten");
	}

	@Test
	void higherPointsAreAboveTheCenterAndWestIsRight() {
		Projection.project(0, 64, 0, 0, 0, 70, 640, 360, 0, 70, 10, out);
		assertTrue(out[1] < 180.0, "höher = weiter oben");
		// Blick nach Süden: Westen (-X) liegt rechts
		Projection.project(0, 64, 0, 0, 0, 70, 640, 360, -5, 64, 10, out);
		assertTrue(out[0] > 320.0);
		Projection.project(0, 64, 0, 0, 0, 70, 640, 360, 5, 64, 10, out);
		assertTrue(out[0] < 320.0);
	}

	@Test
	void fovMatchesTheScreenEdge() {
		// Ein Punkt genau am oberen Rand des Sichtfelds: tan(fov/2) * Tiefe
		double fov = 70;
		double depth = 10;
		double up = Math.tan(Math.toRadians(fov / 2)) * depth;
		Projection.project(0, 64, 0, 0, 0, fov, 640, 360, 0, 64 + up, depth, out);
		assertEquals(0.0, out[1], 1e-6);
	}

	@Test
	void lookingDownMovesThePointUp() {
		Projection.project(0, 64, 0, 0, 45, 70, 640, 360, 0, 64, 10, out);
		assertTrue(out[1] < 180.0, "beim Blick nach unten liegt ein Punkt auf Augenhöhe oben");
	}

	@Test
	void distanceLabel() {
		assertEquals("12 m", Projection.distanceLabel(12.4));
		assertEquals("13 m", Projection.distanceLabel(12.6));
		assertEquals("1,2 km", Projection.distanceLabel(1234));
	}
}

package dev.theredstonee.trsclient.core.zoom;

import dev.theredstonee.trsclient.core.util.FlagOverride;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoomStateTest {
	private static final long MS = 1_000_000L;

	@Test
	void instantWithoutSmoothing() {
		ZoomState z = new ZoomState();
		z.update(true, 4.0, false, 0);
		assertEquals(4.0, z.factor());
		z.update(false, 4.0, false, 16 * MS);
		assertEquals(1.0, z.factor());
	}

	@Test
	void smoothTransitionApproachesTarget() {
		ZoomState z = new ZoomState();
		z.update(true, 4.0, true, 0);
		z.update(true, 4.0, true, 16 * MS);
		double first = z.factor();
		assertTrue(first > 1.0 && first < 4.0, "zwischen 1 und 4: " + first);
		for (int i = 2; i < 200; i++) z.update(true, 4.0, true, i * 16 * MS);
		assertEquals(4.0, z.factor());
	}

	@Test
	void scrollAdjustsLevelOnlyWhileActiveAndResetsOnNextPress() {
		ZoomState z = new ZoomState();
		z.scroll(1);
		assertEquals(1.0, z.level());
		z.update(true, 4.0, false, 0);
		z.scroll(1);
		assertEquals(4.8, z.level(), 1e-9);
		z.update(true, 4.0, false, MS);
		assertEquals(4.8, z.factor(), 1e-9);
		z.update(false, 4.0, false, 2 * MS);
		z.update(true, 4.0, false, 3 * MS);
		assertEquals(4.0, z.level());
	}

	@Test
	void smoothingIsFrameRateIndependentAndMonotonic() {
		ZoomState fast = new ZoomState();
		ZoomState slow = new ZoomState();
		fast.update(true, 4.0, true, 0);
		slow.update(true, 4.0, true, 0);
		double last = 1.0;
		for (int i = 1; i <= 8; i++) {
			fast.update(true, 4.0, true, i * 8 * MS);
			assertTrue(fast.factor() >= last, "steigt monoton");
			last = fast.factor();
		}
		for (int i = 1; i <= 4; i++) slow.update(true, 4.0, true, i * 16 * MS);
		// 8 × 8 ms und 4 × 16 ms landen an derselben Stelle der Kurve.
		assertEquals(slow.factor(), fast.factor(), 1e-9);
		// Kurve: 1 - e^(-t·14) – nach 64 ms ist gut die Hälfte des Wegs geschafft.
		double expected = 1.0 + 3.0 * (1.0 - Math.exp(-0.064 * 14.0));
		assertEquals(expected, fast.factor(), 1e-9);
		// Ein Ruckler (1 s ohne Frame) springt nicht über das Ziel hinaus.
		fast.update(true, 4.0, true, 2000 * MS);
		assertTrue(fast.factor() <= 4.0);
		// Auszoomen ist ebenso weich.
		fast.update(true, 4.0, false, 2001 * MS);
		fast.update(false, 4.0, true, 2017 * MS);
		assertTrue(fast.factor() > 1.0 && fast.factor() < 4.0, "zoomt weich heraus: " + fast.factor());
	}

	@Test
	void spyglassTakesOverImmediately() {
		ZoomState z = new ZoomState();
		z.frame(true, false, 4.0, true, 0);
		for (int i = 1; i < 100; i++) z.frame(true, false, 4.0, true, i * 16 * MS);
		assertEquals(4.0, z.factor());
		assertEquals(1.0, z.frame(true, true, 4.0, true, 100 * 16 * MS), "Fernrohr: sofort kein TRS-Zoom");
		assertFalse(z.isActive());
		z.scroll(1);
		assertEquals(1.0, z.frame(true, true, 4.0, true, 101 * 16 * MS), "Mausrad gehört dem Spiel");
		// Fernrohr weg, Taste noch gehalten: neuer Zoom von vorn.
		z.frame(true, false, 4.0, false, 102 * 16 * MS);
		assertEquals(4.0, z.factor());
	}

	@Test
	void mouseSlowsDownProportionally() {
		ZoomState z = new ZoomState();
		assertEquals(1.0, z.mouseDivisor(true));
		z.update(true, 6.0, false, 0);
		assertEquals(6.0, z.mouseDivisor(true));
		assertEquals(1.0, z.mouseDivisor(false));
		z.scroll(1);
		z.update(true, 6.0, false, MS);
		assertEquals(7.2, z.mouseDivisor(true), 1e-9);
	}

	@Test
	void cinematicCameraIsRestoredAfterZoom() {
		FlagOverride cinematic = new FlagOverride();
		assertFalse(cinematic.update(false, false));
		assertTrue(cinematic.update(false, true), "während des Zooms an");
		assertTrue(cinematic.update(true, true));
		assertFalse(cinematic.update(true, false), "danach wie vorher (aus)");
		// Hatte der Spieler die filmische Kamera selbst an, bleibt sie an.
		assertTrue(cinematic.update(true, true));
		assertTrue(cinematic.update(true, false));
		assertTrue(cinematic.update(true, false));
	}

	@Test
	void levelIsClamped() {
		ZoomState z = new ZoomState();
		z.update(true, 4.0, false, 0);
		for (int i = 0; i < 100; i++) z.scroll(-1);
		assertEquals(ZoomState.MIN_LEVEL, z.level());
		for (int i = 0; i < 100; i++) z.scroll(1);
		assertEquals(ZoomState.MAX_LEVEL, z.level());
	}
}

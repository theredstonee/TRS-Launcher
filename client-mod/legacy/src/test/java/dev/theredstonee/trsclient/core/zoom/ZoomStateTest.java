package dev.theredstonee.trsclient.core.zoom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
	void levelIsClamped() {
		ZoomState z = new ZoomState();
		z.update(true, 4.0, false, 0);
		for (int i = 0; i < 100; i++) z.scroll(-1);
		assertEquals(ZoomState.MIN_LEVEL, z.level());
		for (int i = 0; i < 100; i++) z.scroll(1);
		assertEquals(ZoomState.MAX_LEVEL, z.level());
	}
}

package dev.theredstonee.trsclient.core.hud;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudSnapTest {

	private static final List<int[]> NONE = new ArrayList<int[]>();

	@Test
	void snapsToScreenEdges() {
		HudSnap.Result r = HudSnap.snap(4, 290, 40, 10, 400, 300, NONE, HudSnap.DISTANCE);
		assertEquals(HudSnap.EDGE_MARGIN, r.x);
		assertEquals(300 - 10 - HudSnap.EDGE_MARGIN, r.y);
		assertTrue(r.hasGuideX());
		assertEquals(HudSnap.EDGE_MARGIN, r.guideX);
	}

	@Test
	void snapsToScreenCenter() {
		// Mitte für ein 40 px breites Element auf 400 px: x = 180
		HudSnap.Result r = HudSnap.snap(183, 100, 40, 10, 400, 300, NONE, HudSnap.DISTANCE);
		assertEquals(180, r.x);
		assertEquals(200, r.guideX);
		assertFalse(r.hasGuideY());
	}

	@Test
	void snapsToElementCenter() {
		List<int[]> others = Arrays.asList(new int[]{120, 80, 60, 20});
		// Mitte an Mitte: 80 + (20 - 10) / 2 = 85
		HudSnap.Result r = HudSnap.snap(300, 86, 30, 10, 400, 300, others, HudSnap.DISTANCE);
		assertEquals(85, r.y);
		assertEquals(90, r.guideY);
	}

	@Test
	void snapsToOtherElements() {
		List<int[]> others = Arrays.asList(new int[]{120, 80, 60, 20});
		// linke Kante an die linke Kante des anderen Elements
		HudSnap.Result r = HudSnap.snap(123, 82, 30, 10, 400, 300, others, HudSnap.DISTANCE);
		assertEquals(120, r.x);
		assertEquals(120, r.guideX);
		assertEquals(80, r.y);
		assertEquals(80, r.guideY);

		// rechte Kante an die rechte Kante (120 + 60 - 30 = 150)
		HudSnap.Result right = HudSnap.snap(152, 200, 30, 10, 400, 300, others, HudSnap.DISTANCE);
		assertEquals(150, right.x);
		assertEquals(180, right.guideX);
	}

	@Test
	void keepsPositionWhenNothingIsClose() {
		HudSnap.Result r = HudSnap.snap(137, 160, 30, 10, 400, 300, NONE, HudSnap.DISTANCE);
		assertEquals(137, r.x);
		assertEquals(160, r.y);
		assertFalse(r.hasGuideX());
		assertFalse(r.hasGuideY());
	}

	@Test
	void clampOnlyStaysOnScreenWithoutGuides() {
		HudSnap.Result r = HudSnap.clampOnly(-50, 999, 30, 10, 400, 300);
		assertEquals(0, r.x);
		assertEquals(290, r.y);
		assertFalse(r.hasGuideX());
		assertFalse(r.hasGuideY());
	}

	@Test
	void distanceZeroDisablesSnapping() {
		HudSnap.Result r = HudSnap.snap(3, 3, 30, 10, 400, 300, NONE, 0);
		assertEquals(3, r.x);
		assertEquals(3, r.y);
	}
}

package dev.theredstonee.trsclient.core.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudLayoutTest {

	@Test
	void topLeftWithoutOffsetIsOrigin() {
		HudPosition p = new HudPosition(HudAnchor.TOP_LEFT, 0, 0);
		assertEquals(0, HudLayout.resolveX(p, 50, 400));
		assertEquals(0, HudLayout.resolveY(p, 10, 300));
	}

	@Test
	void bottomRightStaysFlushAcrossResolutions() {
		HudPosition p = new HudPosition(HudAnchor.BOTTOM_RIGHT, 0, 0);
		assertEquals(350, HudLayout.resolveX(p, 50, 400));
		assertEquals(290, HudLayout.resolveY(p, 10, 300));
		assertEquals(910, HudLayout.resolveX(p, 50, 960));
		assertEquals(530, HudLayout.resolveY(p, 10, 540));
	}

	@Test
	void centerAnchorCentersElement() {
		HudPosition p = new HudPosition(HudAnchor.CENTER, 0, 0);
		assertEquals(175, HudLayout.resolveX(p, 50, 400));
		assertEquals(145, HudLayout.resolveY(p, 10, 300));
	}

	@Test
	void offsetScalesWithScreenSize() {
		HudPosition p = new HudPosition(HudAnchor.TOP_LEFT, 0.1, 0.2);
		assertEquals(40, HudLayout.resolveX(p, 20, 400));
		assertEquals(60, HudLayout.resolveY(p, 10, 300));
		assertEquals(80, HudLayout.resolveX(p, 20, 800));
		assertEquals(120, HudLayout.resolveY(p, 10, 600));
	}

	@Test
	void resultIsClampedToScreen() {
		HudPosition p = new HudPosition(HudAnchor.TOP_LEFT, 5.0, -3.0);
		assertEquals(350, HudLayout.resolveX(p, 50, 400));
		assertEquals(0, HudLayout.resolveY(p, 10, 300));
	}

	@Test
	void fromPixelsPicksAnchorByThird() {
		assertEquals(HudAnchor.TOP_LEFT, HudLayout.fromPixels(10, 10, 20, 10, 300, 300).anchor);
		assertEquals(HudAnchor.CENTER, HudLayout.fromPixels(140, 145, 20, 10, 300, 300).anchor);
		assertEquals(HudAnchor.BOTTOM_RIGHT, HudLayout.fromPixels(270, 280, 20, 10, 300, 300).anchor);
		assertEquals(HudAnchor.TOP_CENTER, HudLayout.fromPixels(140, 0, 20, 10, 300, 300).anchor);
		assertEquals(HudAnchor.CENTER_RIGHT, HudLayout.fromPixels(280, 150, 20, 10, 300, 300).anchor);
	}

	@Test
	void fromPixelsRoundTripsAtSameResolution() {
		int sw = 427, sh = 240, w = 37, h = 15;
		for (int x = 0; x <= sw - w; x += 13) {
			for (int y = 0; y <= sh - h; y += 7) {
				HudPosition p = HudLayout.fromPixels(x, y, w, h, sw, sh);
				assertEquals(x, HudLayout.resolveX(p, w, sw), "x bei " + x + "," + y);
				assertEquals(y, HudLayout.resolveY(p, h, sh), "y bei " + x + "," + y);
			}
		}
	}

	@Test
	void rightAnchoredElementKeepsDistanceToRightEdgeRatio() {
		// Element 10 px vom rechten Rand bei 400 px Breite …
		HudPosition p = HudLayout.fromPixels(400 - 50 - 10, 0, 50, 10, 400, 300);
		assertEquals(HudAnchor.TOP_RIGHT, p.anchor);
		// … bleibt bei doppelter Breite rechts verankert (Abstand skaliert mit).
		assertEquals(800 - 50 - 20, HudLayout.resolveX(p, 50, 800));
	}

	@Test
	void snapsToEdgesAndCenter() {
		int m = HudLayout.EDGE_MARGIN;
		assertEquals(m, HudLayout.snapX(4, 50, 400));
		assertEquals(400 - 50 - m, HudLayout.snapX(345, 50, 400));
		assertEquals(175, HudLayout.snapX(178, 50, 400));
		assertEquals(100, HudLayout.snapX(100, 50, 400));
		assertEquals(145, HudLayout.snapY(142, 10, 300));
		assertTrue(HudLayout.isCentered(175, 50, 400));
		assertFalse(HudLayout.isCentered(176, 50, 400));
	}

	@Test
	void snapClampsOutsideValues() {
		assertEquals(0, HudLayout.snapX(-40, 50, 400));
		assertEquals(350, HudLayout.snapX(900, 50, 400));
	}

	@Test
	void anchorParsingFallsBack() {
		assertEquals(HudAnchor.CENTER, HudAnchor.parse("center", HudAnchor.TOP_LEFT));
		assertEquals(HudAnchor.TOP_LEFT, HudAnchor.parse("quatsch", HudAnchor.TOP_LEFT));
		assertEquals(HudAnchor.BOTTOM_LEFT, HudAnchor.parse(null, HudAnchor.BOTTOM_LEFT));
	}
}

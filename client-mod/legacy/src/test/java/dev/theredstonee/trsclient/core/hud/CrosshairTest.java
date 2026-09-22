package dev.theredstonee.trsclient.core.hud;

import java.util.Arrays;
import dev.theredstonee.trsclient.core.hud.Crosshair.Shape;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrosshairTest {

	/** Alle gefüllten Pixel als "x,y". */
	private static Set<String> pixels(List<int[]> rects) {
		Set<String> out = new HashSet<>();
		for (int[] r : rects) {
			for (int x = r[0]; x < r[2]; x++) {
				for (int y = r[1]; y < r[3]; y++) out.add(x + "," + y);
			}
		}
		return out;
	}

	@Test
	void crossHasFourArmsSymmetricAroundCenterWithGap() {
		List<int[]> r = Crosshair.rects(Shape.CROSS, 4, 2, 1);
		assertEquals(4, r.size());
		Set<String> p = pixels(r);
		assertEquals(16, p.size());
		assertFalse(p.contains("0,0"), "Mitte bleibt frei");
		assertFalse(p.contains("0,-2"), "Abstand 2 → erstes Pixel bei 3");
		assertTrue(p.contains("0,-3") && p.contains("0,3") && p.contains("-3,0") && p.contains("3,0"));
		assertTrue(p.contains("0,-6") && !p.contains("0,-7"));
		// Punktsymmetrisch
		for (String s : p) {
			String[] xy = s.split(",");
			assertTrue(p.contains((-Integer.parseInt(xy[0])) + "," + (-Integer.parseInt(xy[1]))), s);
		}
	}

	@Test
	void crossDotAddsCenterAndTOmitsTopArm() {
		assertTrue(pixels(Crosshair.rects(Shape.CROSS_DOT, 3, 1, 1)).contains("0,0"));
		Set<String> t = pixels(Crosshair.rects(Shape.T, 3, 1, 1));
		assertFalse(t.contains("0,-2"));
		assertTrue(t.contains("0,2"));
	}

	@Test
	void thicknessWidensArms() {
		List<int[]> r = Crosshair.rects(Shape.CROSS, 5, 0, 3);
		for (int[] rect : r) {
			int w = rect[2] - rect[0];
			int h = rect[3] - rect[1];
			assertEquals(3, Math.min(w, h));
			assertEquals(5, Math.max(w, h));
		}
	}

	@Test
	void dotIsCenteredSquare() {
		assertArrayEquals(new int[]{-1, -1, 2, 2}, Crosshair.rects(Shape.DOT, 4, 2, 3).get(0));
		assertArrayEquals(new int[]{0, 0, 1, 1}, Crosshair.rects(Shape.DOT, 4, 2, 1).get(0));
	}

	@Test
	void circleIsSymmetricRingWithHole() {
		Set<String> p = pixels(Crosshair.rects(Shape.CIRCLE, 4, 1, 1));
		assertFalse(p.contains("0,0"));
		assertTrue(p.contains("5,0") && p.contains("-5,0") && p.contains("0,5") && p.contains("0,-5"));
		for (String s : p) {
			String[] xy = s.split(",");
			int x = Integer.parseInt(xy[0]);
			int y = Integer.parseInt(xy[1]);
			assertTrue(p.contains(-x + "," + y) && p.contains(x + "," + -y), s);
			double d = Math.sqrt(x * x + y * y);
			assertTrue(d > 3.4 && d < 5.6, "Ring-Pixel " + s + " bei Abstand " + d);
		}
	}

	@Test
	void outlineGrowsEveryRectByOne() {
		List<int[]> o = Crosshair.outline(Arrays.asList(new int[]{0, 0, 1, 4}));
		assertArrayEquals(new int[]{-1, -1, 2, 5}, o.get(0));
		assertArrayEquals(new int[]{-1, -1, 2, 5}, Crosshair.bounds(o));
	}

	@Test
	void invalidValuesAreClamped() {
		assertFalse(Crosshair.rects(Shape.CROSS, 0, -3, 0).isEmpty());
	}
}

package dev.theredstonee.trsclient.core.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MouseScalerTest {

	@Test
	void divisorOneOrLessPassesThrough() {
		MouseScaler s = new MouseScaler();
		s.scale(7, -3, 1.0);
		assertEquals(7, s.x());
		assertEquals(-3, s.y());
		s.scale(5, 5, Double.NaN);
		assertEquals(5, s.x());
	}

	@Test
	void smallMovementsAccumulateInsteadOfVanishing() {
		MouseScaler s = new MouseScaler();
		int sumX = 0;
		int sumY = 0;
		for (int i = 0; i < 8; i++) {
			s.scale(1, -1, 4.0);
			sumX += s.x();
			sumY += s.y();
		}
		assertEquals(2, sumX);
		assertEquals(-2, sumY);
	}

	@Test
	void largeMovementIsDivided() {
		MouseScaler s = new MouseScaler();
		s.scale(40, -20, 4.0);
		assertEquals(10, s.x());
		assertEquals(-5, s.y());
	}

	@Test
	void restIsDroppedWhenZoomEnds() {
		MouseScaler s = new MouseScaler();
		s.scale(3, 3, 4.0); // 0.75 Rest
		assertEquals(0, s.x());
		s.scale(0, 0, 1.0);
		s.scale(1, 1, 4.0); // ohne alten Rest nur 0.25
		assertEquals(0, s.x());
	}
}

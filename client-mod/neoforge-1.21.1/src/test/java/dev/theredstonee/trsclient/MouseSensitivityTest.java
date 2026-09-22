package dev.theredstonee.trsclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MouseSensitivityTest {
	/** Drehfaktor wie in Vanilla MouseHandler#turnPlayer. */
	private static double turn(double s) {
		double d = s * 0.6 + 0.2;
		return d * d * d * 8.0;
	}

	@Test
	void divisorOneKeepsSensitivity() {
		assertEquals(0.5, MouseSensitivity.divided(0.5, 1.0));
		assertEquals(0.5, MouseSensitivity.divided(0.5, 0.5));
	}

	@Test
	void turnIsDividedExactly() {
		for (double s : new double[]{0.0, 0.25, 0.5, 1.0}) {
			for (double k : new double[]{1.5, 2.0, 4.0, 10.0, 50.0}) {
				assertEquals(turn(s) / k, turn(MouseSensitivity.divided(s, k)), 1e-12, "s=" + s + " k=" + k);
			}
		}
	}
}

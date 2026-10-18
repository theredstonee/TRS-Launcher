package dev.theredstonee.trsclient.core.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Prozent → Gamma für das Modul „Gamma“. */
class BrightnessTest {
	@Test
	void mapsPercentToGamma() {
		// 100 % = Vanilla-Maximum, 1000 % = Fullbright.
		assertEquals(1.0, Brightness.gamma(100), 1e-9);
		assertEquals(16.0, Brightness.gamma(1000), 1e-9);
		// Linear dazwischen: 550 % → 1 + 450/900*15 = 8.5.
		assertEquals(8.5, Brightness.gamma(550), 1e-9);
		// Ein Reglerschritt: 150 % → 1 + 50/900*15 ≈ 1.8333.
		assertEquals(1.0 + 50.0 / 900.0 * 15.0, Brightness.gamma(150), 1e-9);
	}

	@Test
	void clampsOutOfRange() {
		assertEquals(1.0, Brightness.gamma(0), 1e-9);
		assertEquals(1.0, Brightness.gamma(-500), 1e-9);
		assertEquals(16.0, Brightness.gamma(5000), 1e-9);
	}
}

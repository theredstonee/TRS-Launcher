package dev.theredstonee.trsclient.core.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BorderlessTest {
	@Test
	void memoryKeepsTheLatestGoodBounds() {
		WindowMemory m = new WindowMemory();
		assertFalse(m.saved());
		m.track(10, 20, 800, 600);
		assertTrue(m.saved());
		assertEquals(10, m.x());
		assertEquals(20, m.y());
		assertEquals(800, m.width());
		assertEquals(600, m.height());
		m.track(1, 2, 900, 500);
		assertEquals(1, m.x());
		assertEquals(900, m.width());
		// Kaputte Größe verwirft den Stand nicht.
		m.track(5, 5, 0, 10);
		assertEquals(2, m.y());
		assertEquals(500, m.height());
		m.clear();
		assertFalse(m.saved());
	}

	@Test
	void planEntersRestoresAndReturnsToExclusive() {
		assertEquals(BorderlessPlan.Step.REMEMBER, BorderlessPlan.step(true, false, false, false));
		assertEquals(BorderlessPlan.Step.ENTER, BorderlessPlan.step(true, true, true, false));
		assertEquals(BorderlessPlan.Step.NONE, BorderlessPlan.step(true, true, false, true));
		assertEquals(BorderlessPlan.Step.RESTORE_WINDOW, BorderlessPlan.step(true, false, false, true));
		assertEquals(BorderlessPlan.Step.TO_EXCLUSIVE, BorderlessPlan.step(false, true, false, true));
		assertEquals(BorderlessPlan.Step.NONE, BorderlessPlan.step(false, true, true, false));
		assertEquals(BorderlessPlan.Step.NONE, BorderlessPlan.step(true, true, false, false));
	}
}

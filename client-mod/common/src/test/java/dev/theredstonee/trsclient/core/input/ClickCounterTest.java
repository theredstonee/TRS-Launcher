package dev.theredstonee.trsclient.core.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClickCounterTest {

	@Test
	void countsClicksInsideWindow() {
		ClickCounter c = new ClickCounter();
		c.record(1000);
		c.record(1100);
		c.record(1500);
		assertEquals(3, c.count(1500));
		assertEquals(3, c.count(1999));
	}

	@Test
	void dropsClicksOlderThanWindow() {
		ClickCounter c = new ClickCounter();
		c.record(1000);
		c.record(1500);
		// Genau 1000 ms später zählt der erste Klick nicht mehr.
		assertEquals(1, c.count(2000));
		assertEquals(1, c.count(2499));
		assertEquals(0, c.count(2500));
	}

	@Test
	void emptyCounterIsZero() {
		assertEquals(0, new ClickCounter().count(123_456));
	}

	@Test
	void overflowKeepsNewestClicks() {
		ClickCounter c = new ClickCounter(1000, 4);
		for (int i = 0; i < 10; i++) c.record(100 + i);
		assertEquals(4, c.count(200));
		// Die 4 neuesten (106..109) laufen bei 1106 ab bzw. danach.
		assertEquals(3, c.count(1106));
		assertEquals(0, c.count(1109));
	}

	@Test
	void ringBufferWrapsAroundCorrectly() {
		ClickCounter c = new ClickCounter(100, 3);
		long t = 0;
		for (int round = 0; round < 20; round++) {
			c.record(t);
			c.record(t + 10);
			assertEquals(2, c.count(t + 10));
			t += 200; // alles Alte läuft ab
			assertEquals(0, c.count(t));
		}
	}

	@Test
	void resetClearsEverything() {
		ClickCounter c = new ClickCounter();
		c.record(5);
		c.reset();
		assertEquals(0, c.count(5));
	}

	@Test
	void rejectsInvalidArguments() {
		assertThrows(IllegalArgumentException.class, () -> new ClickCounter(0, 10));
		assertThrows(IllegalArgumentException.class, () -> new ClickCounter(1000, 0));
	}
}

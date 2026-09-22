package dev.theredstonee.trsclient.core.pvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpTrackersTest {
	@Test
	void reachRemembersLastHitAndExpires() {
		ReachTracker reach = new ReachTracker();
		assertFalse(reach.valid(0, 1000));
		reach.record(3.04, 1000);
		assertEquals(3.04, reach.distance(), 1e-9);
		assertTrue(reach.valid(2000, 4000));
		assertFalse(reach.valid(6000, 4000));
		// 0 = dauerhaft anzeigen
		assertTrue(reach.valid(999_999, 0));
	}

	@Test
	void reachIgnoresNonsenseAndFormats() {
		ReachTracker reach = new ReachTracker();
		reach.record(Double.NaN, 1);
		reach.record(-1, 1);
		assertFalse(reach.valid(1, 0));
		assertEquals("3.04 m", ReachTracker.format(3.0449, 2));
		assertEquals("3 m", ReachTracker.format(3.04, 0));
	}

	@Test
	void comboCountsOnlyConfirmedHits() {
		ComboTracker combo = new ComboTracker();
		combo.onAttack(7, 0);
		assertTrue(combo.onTargetHurt(7, 100));
		assertEquals(1, combo.combo());
		// Treffer ohne Schlag zählt nicht
		assertFalse(combo.onTargetHurt(7, 200));
		assertEquals(1, combo.combo());
		// Zu spät bestätigt zählt nicht
		combo.onAttack(7, 300);
		assertFalse(combo.onTargetHurt(7, 300 + ComboTracker.CONFIRM_MS + 1));
		assertEquals(1, combo.combo());
	}

	@Test
	void comboResetsOnOwnDamageTargetChangeAndTimeout() {
		ComboTracker combo = new ComboTracker();
		combo.onAttack(1, 0);
		combo.onTargetHurt(1, 10);
		combo.onAttack(1, 100);
		combo.onTargetHurt(1, 110);
		assertEquals(2, combo.combo());
		assertEquals(2, combo.best());

		combo.onSelfHurt();
		assertEquals(0, combo.combo());

		combo.onAttack(1, 200);
		combo.onTargetHurt(1, 210);
		combo.onAttack(2, 300);
		combo.onTargetHurt(2, 310);
		assertEquals(1, combo.combo(), "neues Ziel beginnt eine neue Combo");

		combo.tick(310 + 2999, 3000);
		assertEquals(1, combo.combo());
		combo.tick(310 + 3001, 3000);
		assertEquals(0, combo.combo());
		assertEquals(2, combo.best());
	}

	@Test
	void speedAveragesBlocksPerSecond() {
		SpeedTracker speed = new SpeedTracker();
		assertEquals(0, speed.blocksPerSecond(), 1e-9);
		double x = 0;
		for (int i = 0; i < 20; i++) {
			speed.tick(x, 64, 0, false);
			x += 0.2;
		}
		// 0,2 Blöcke pro Tick = 4 Blöcke pro Sekunde
		assertEquals(4.0, speed.blocksPerSecond(), 1e-6);
		assertEquals("4.00 b/s", SpeedTracker.format(speed.blocksPerSecond()));
	}

	@Test
	void speedIgnoresHeightUnlessAsked() {
		SpeedTracker flat = new SpeedTracker();
		SpeedTracker withY = new SpeedTracker();
		for (int i = 0; i < 12; i++) {
			flat.tick(0, i, 0, false);
			withY.tick(0, i, 0, true);
		}
		assertEquals(0.0, flat.blocksPerSecond(), 1e-9);
		assertEquals(20.0, withY.blocksPerSecond(), 1e-6);
		flat.reset();
		assertEquals(0.0, flat.blocksPerSecond(), 1e-9);
	}
}

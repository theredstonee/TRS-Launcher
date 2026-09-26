package dev.theredstonee.trsclient.core.social;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ausweichen vor Vanilla-Toasts: Versatz, Animation, Erfolgs-Fenster bis 1.11.2, Feldsuche per Typ. */
class ToastAvoidTest {

	@Test
	void targetOffsetPlacesStackBelowVanilla() {
		assertEquals(0, ToastAvoid.targetOffset(0, 4, 3));
		// Ein Vanilla-Platz (32 px): oberste TRS-Karte bei 32 + 3 = margin 4 + 31.
		assertEquals(31, ToastAvoid.targetOffset(32, 4, 3));
		assertEquals(95, ToastAvoid.targetOffset(ToastAvoid.slotsBottom(3), 4, 3));
		assertEquals(0, ToastAvoid.targetOffset(-5, 4, 3));
	}

	@Test
	void slotsBottom() {
		assertEquals(0, ToastAvoid.slotsBottom(0));
		assertEquals(32, ToastAvoid.slotsBottom(1));
		assertEquals(160, ToastAvoid.slotsBottom(5));
	}

	@Test
	void firstStepSnaps() {
		ToastAvoid a = new ToastAvoid();
		assertEquals(31f, a.step(31, 1000));
	}

	@Test
	void movesSmoothlyAndMonotonically() {
		ToastAvoid a = new ToastAvoid();
		a.step(0, 1000);
		float prev = 0;
		long t = 1000;
		boolean between = false;
		for (int i = 0; i < 30; i++) {
			t += 16;
			float o = a.step(63, t);
			assertTrue(o >= prev, "monoton");
			assertTrue(o <= 63f, "kein Überschwingen");
			if (o > 0 && o < 63) between = true;
			prev = o;
		}
		assertTrue(between, "Zwischenwerte (keine Sprünge)");
		// nach ~0,5 s eingerastet
		assertEquals(63f, a.offset());
	}

	@Test
	void independentOfFrameRate() {
		ToastAvoid slow = new ToastAvoid();
		ToastAvoid fast = new ToastAvoid();
		slow.step(0, 0);
		fast.step(0, 0);
		// 30 FPS vs. 240 FPS: nach 99 ms (fast) gleich weit
		for (long t = 33; t <= 99; t += 33) slow.step(100, t);
		for (int i = 1; i <= 24; i++) fast.step(100, Math.round(i * 99 / 24.0));
		assertEquals(slow.offset(), fast.offset(), 1.0f);
		assertTrue(slow.offset() > 50 && slow.offset() < 80, "≈ 1 - e^-1.1");
	}

	@Test
	void movesBackUpWhenVanillaToastsLeave() {
		ToastAvoid a = new ToastAvoid();
		a.step(31, 0);
		long t = 0;
		for (int i = 0; i < 40; i++) a.step(0, t += 16);
		assertEquals(0f, a.offset());
	}

	@Test
	void pauseSnapsInsteadOfAnimatingStaleOffset() {
		ToastAvoid a = new ToastAvoid();
		a.step(95, 0);
		// lange kein TRS-Toast gezeichnet: neuer Toast erscheint direkt an der richtigen Stelle
		assertEquals(0f, a.step(0, ToastAvoid.SNAP_AFTER_MS + 1));
		// Uhr springt rückwärts: ebenfalls direkt
		assertEquals(31f, a.step(31, 5));
	}

	@Test
	void zeroDeltaKeepsOffset() {
		ToastAvoid a = new ToastAvoid();
		a.step(0, 100);
		a.step(64, 116);
		float o = a.offset();
		// zweimal im selben Bild gezeichnet (z. B. HUD + Bildschirm-Haken): kein zusätzlicher Schritt
		assertEquals(o, a.step(64, 116));
	}

	@Test
	void achievementWindowSlidesVertically() {
		// Vanilla 1.8.9: y = -(int)(d^4 * 36), d = 1 - 4·min(2t, 2-2t); Unterkante = y + 32
		assertEquals(0, ToastAvoid.achievementBottom(0, false)); // ganz oben außerhalb (y = -36)
		int in = ToastAvoid.achievementBottom(200, false);
		assertTrue(in > 0 && in < 32, "fährt herein: " + in);
		assertEquals(32, ToastAvoid.achievementBottom(375, false)); // t = 0.125 → ganz da
		assertEquals(32, ToastAvoid.achievementBottom(1500, false));
		int out = ToastAvoid.achievementBottom(2850, false);
		assertTrue(out > 0 && out < 32, "fährt hinaus: " + out);
		assertEquals(0, ToastAvoid.achievementBottom(3001, false)); // abgelaufen
		assertEquals(0, ToastAvoid.achievementBottom(-10, false));
		// dauerhaft (Tutorial „Inventar öffnen“): bleibt stehen
		assertEquals(32, ToastAvoid.achievementBottom(60_000, true));
	}

	@Test
	void achievementMatchesVanillaFormula() {
		for (long ms = 0; ms <= 3000; ms += 7) {
			double d0 = ms / 3000.0;
			double d1 = d0 * 2;
			if (d1 > 1) d1 = 2 - d1;
			d1 *= 4;
			d1 = 1 - d1;
			if (d1 < 0) d1 = 0;
			d1 = d1 * d1;
			d1 = d1 * d1;
			int j = 0 - (int) (d1 * 36);
			assertEquals(Math.max(0, j + 32), ToastAvoid.achievementBottom(ms, false), "ms=" + ms);
		}
	}

	// --- Feldsuche per Typ (wie ToastComponent/ToastManager/GuiToast/GuiAchievement) ---

	static final class ModernToasts {
		static final int SLOT_COUNT = 5;
		private final java.util.List<Object> visibleToasts = new java.util.ArrayList<>();
		private final BitSet occupiedSlots = new BitSet(5);
		private final Deque<Object> queued = new ArrayDeque<>();
	}

	static final class Instance {
	}

	static final class OldToasts {
		private final Object mc = new Object();
		private final Instance[] visible = new Instance[5];
		private final Deque<Object> queued = new ArrayDeque<>();
	}

	static final class OddToasts {
		private final Object[] a = new Object[2];
		private final Object[] b = new Object[2];
	}

	@Test
	void probeReadsOccupiedSlots() {
		ModernToasts m = new ModernToasts();
		VanillaToastProbe p = new VanillaToastProbe();
		assertEquals(0, p.bottom(m));
		assertTrue(p.working());
		m.occupiedSlots.set(0);
		assertEquals(32, p.bottom(m));
		m.occupiedSlots.set(1, 3); // hoher Toast über zwei Plätze
		assertEquals(96, p.bottom(m));
		m.occupiedSlots.clear(0);
		assertEquals(96, p.bottom(m)); // Lücke oben: trotzdem bis zur untersten Kante
		m.occupiedSlots.clear();
		assertEquals(0, p.bottom(m));
	}

	@Test
	void probeReadsSlotArray() {
		OldToasts o = new OldToasts();
		VanillaToastProbe p = new VanillaToastProbe();
		assertEquals(0, p.bottom(o));
		o.visible[0] = new Instance();
		assertEquals(32, p.bottom(o));
		o.visible[2] = new Instance();
		assertEquals(96, p.bottom(o));
		o.visible[0] = null;
		o.visible[2] = null;
		assertEquals(0, p.bottom(o));
	}

	@Test
	void probeGivesUpOnAmbiguousLayout() {
		VanillaToastProbe p = new VanillaToastProbe();
		assertEquals(0, p.bottom(new OddToasts()));
		assertFalse(p.working());
		assertEquals(0, p.bottom(null));
	}

	static final class Achievements {
		private final Object mc = new Object();
		private int width = 320;
		private String title = "x";
		private long notificationTime;
		private boolean permanentNotification;
	}

	@Test
	void achievementProbe() {
		Achievements a = new Achievements();
		VanillaToastProbe.Achievement p = new VanillaToastProbe.Achievement();
		assertEquals(0, p.bottom(a, 10_000));
		assertTrue(p.working());
		a.notificationTime = 10_000;
		assertEquals(32, p.bottom(a, 11_000));
		assertEquals(0, p.bottom(a, 14_000));
		a.permanentNotification = true;
		assertEquals(32, p.bottom(a, 14_000));
	}
}

package dev.theredstonee.trsclient.core.inventory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Logik des automatischen Nachlegens mit einem nachgebauten Inventar. */
class AutoRefillTest {
	/** Inventar-Attrappe: 36 Slots (0..8 Hotbar, 9..35 Hauptfeld), merkt Tausch-Aufrufe. */
	static final class FakeGame implements AutoRefill.Game {
		final String[] slots = new String[36];
		int selected;
		boolean active = true;
		final List<int[]> swaps = new ArrayList<int[]>();

		@Override
		public boolean active() {
			return active;
		}

		@Override
		public int selectedSlot() {
			return selected;
		}

		@Override
		public String itemKey(int slot) {
			return slot >= 0 && slot < slots.length ? slots[slot] : null;
		}

		@Override
		public void swap(int from, int toHotbar) {
			swaps.add(new int[]{from, toHotbar});
			slots[toHotbar] = slots[from];
			slots[from] = null;
		}
	}

	@Test
	void findsMainBeforeHotbarAndSkipsSelected() {
		FakeGame g = new FakeGame();
		g.selected = 0;
		g.slots[0] = "stone";   // ausgewählt (wird übersprungen)
		g.slots[5] = "stone";   // anderer Hotbar-Slot
		g.slots[20] = "stone";  // Hauptfeld – Vorrang
		assertEquals(20, AutoRefill.findRefillSlot("stone", 0, g));

		g.slots[20] = null;
		assertEquals(5, AutoRefill.findRefillSlot("stone", 0, g));

		assertEquals(-1, AutoRefill.findRefillSlot("dirt", 0, g));
		assertEquals(-1, AutoRefill.findRefillSlot(null, 0, g));
	}

	@Test
	void refillsWhenSelectedSlotEmpties() {
		AutoRefill refill = new AutoRefill();
		FakeGame g = new FakeGame();
		g.selected = 0;
		g.slots[0] = "stone";   // in der Hand
		g.slots[18] = "stone";  // Nachschub im Hauptfeld

		refill.tick(g);                 // merkt „stone“ im Slot 0
		assertTrue(g.swaps.isEmpty());

		g.slots[0] = null;              // letzter Block gesetzt → Slot leer
		refill.tick(g);                 // legt nach
		assertEquals(1, g.swaps.size());
		assertEquals(18, g.swaps.get(0)[0]);
		assertEquals(0, g.swaps.get(0)[1]);
		assertEquals("stone", g.slots[0]);
		assertEquals(null, g.slots[18]);
	}

	@Test
	void doesNotRefillWhenSwitchingToAnEmptySlot() {
		AutoRefill refill = new AutoRefill();
		FakeGame g = new FakeGame();
		g.selected = 0;
		g.slots[0] = "stone";
		g.slots[18] = "stone";
		refill.tick(g);                 // Slot 0 voll

		g.selected = 1;                 // auf leeren Slot 1 gewechselt
		refill.tick(g);                 // darf NICHT nachlegen (Slot-Wechsel)
		assertTrue(g.swaps.isEmpty());
	}

	@Test
	void onlyOneAttemptPerEmptyEventAndNoneWithoutStock() {
		AutoRefill refill = new AutoRefill();
		FakeGame g = new FakeGame();
		g.selected = 3;
		g.slots[3] = "arrow";           // kein Nachschub vorhanden
		refill.tick(g);
		g.slots[3] = null;
		refill.tick(g);                 // nichts zu holen
		refill.tick(g);                 // und kein erneuter Versuch
		assertTrue(g.swaps.isEmpty());
	}

	@Test
	void pausesWhenInactive() {
		AutoRefill refill = new AutoRefill();
		FakeGame g = new FakeGame();
		g.selected = 0;
		g.slots[0] = "stone";
		g.slots[18] = "stone";
		refill.tick(g);                 // gemerkt
		g.active = false;               // z. B. Fair Play / Behälter offen
		g.slots[0] = null;
		refill.tick(g);                 // pausiert, Merker vergessen
		assertTrue(g.swaps.isEmpty());
		g.active = true;
		refill.tick(g);                 // frisch: Slot 0 ist leer, kein „vorher voll“ bekannt
		assertTrue(g.swaps.isEmpty());
	}

	@Test
	void replacesABrokenToolWithTheSameType() {
		AutoRefill refill = new AutoRefill();
		FakeGame g = new FakeGame();
		g.selected = 2;
		g.slots[2] = "diamond_pickaxe";
		g.slots[27] = "diamond_pickaxe"; // Ersatzspitzhacke im Hauptfeld
		refill.tick(g);
		g.slots[2] = null;               // Werkzeug zerbrochen
		refill.tick(g);
		assertEquals(1, g.swaps.size());
		assertEquals("diamond_pickaxe", g.slots[2]);
	}

	@Test
	void cooldownBlocksAnImmediateSecondRefill() {
		AutoRefill refill = new AutoRefill();
		FakeGame g = new FakeGame();
		g.selected = 0;
		g.slots[0] = "stone";
		g.slots[18] = "stone";
		g.slots[19] = "stone";

		refill.tick(g);                 // merkt „stone“
		g.slots[0] = null;
		refill.tick(g);                 // 1. Tausch (18 → 0), füllt Slot 0 sofort, Pause gesetzt
		assertEquals(1, g.swaps.size());

		refill.tick(g);                 // Slot 0 zeigt wieder „stone“ (vorhergesagt), Pause läuft
		g.slots[0] = null;              // sofort wieder leer
		refill.tick(g);                 // innerhalb der Pause → kein weiterer Tausch
		assertEquals(1, g.swaps.size());

		// Pause ablaufen lassen, dann legt es wieder nach.
		g.slots[0] = "stone";
		for (int i = 0; i < AutoRefill.COOLDOWN_TICKS + 1; i++) refill.tick(g);
		g.slots[0] = null;
		refill.tick(g);
		assertEquals(2, g.swaps.size());
		assertFalse(g.swaps.isEmpty());
	}
}

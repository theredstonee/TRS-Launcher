package dev.theredstonee.trsclient.core.circuit;

import dev.theredstonee.trsclient.core.circuit.sim.CircuitSimTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jede Schaltung mit Tests läuft in der Redstone-Simulation wie beschrieben (Wahrheitstabellen, Takte, Pulse). */
class CircuitSimulationTest {
	@Test
	void everyCircuitPassesItsSimulation() {
		CircuitLibrary lib = CircuitLibrary.load(true);
		List<String> errors = new ArrayList<String>();
		int tested = 0;
		for (Circuit c : lib.all()) {
			if (c.tests.size() == 0) continue;
			tested++;
			errors.addAll(CircuitSimTest.run(c));
		}
		assertEquals(new ArrayList<String>(), errors);
		assertTrue(tested >= 20, "zu wenige simulierte Schaltungen: " + tested);
	}
}

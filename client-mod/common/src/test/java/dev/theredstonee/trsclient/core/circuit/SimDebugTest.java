package dev.theredstonee.trsclient.core.circuit;

import dev.theredstonee.trsclient.core.circuit.sim.RedstoneSim;
import org.junit.jupiter.api.Test;

/** Temporär: Zustände ausgeben. */
class SimDebugTest {
	@Test
	void dump() {
		String id = System.getProperty("trsclient.test.circuit");
		if (id == null) return;
		CircuitLibrary lib = CircuitLibrary.load(true);
		RedstoneSim sim = new RedstoneSim(lib.byId(id));
		sim.run(40);
		String[] steps = System.getProperty("trsclient.test.steps", "").split(";");
		for (String s : steps) {
			if (s.isEmpty()) continue;
			String[] kv = s.split("=");
			sim.set(kv[0], Integer.parseInt(kv[1]));
			for (int i = 0; i < 30; i++) {
				sim.tick();
				System.out.println("t" + sim.time() + " " + sim.dump());
			}
		}
	}
}

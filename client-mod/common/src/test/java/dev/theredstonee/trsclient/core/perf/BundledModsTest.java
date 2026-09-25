package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledModsTest {
	@AfterEach
	void reset() {
		BundledMods.setLoaded(null);
	}

	@Test
	void readsTheListAndTellsWhichVersionRuns() {
		BundledMods b = BundledMods.of(new StringReader("[{\"id\":\"lithium\",\"name\":\"Lithium\",\"version\":\"0.15.4+mc1.21.1\","
				+ "\"license\":\"LGPL-3.0-only\"},{\"id\":\"ferritecore\",\"name\":\"FerriteCore\",\"version\":\"7.0.3\"},{\"name\":\"ohne id\"}]"));
		assertTrue(b.available());
		assertEquals(2, b.entries().size(), "Einträge ohne ID fallen weg");
		BundledMods.Entry lithium = b.entries().get(0);
		assertEquals("Lithium", lithium.name);

		assertEquals(BundledMods.State.OFF, b.state(lithium), "ohne Loader-Auskunft: aus");
		BundledMods.setLoaded(new BundledMods.Loaded() {
			@Override
			public String version(String id) {
				if (id.equals("lithium")) return "0.15.4+mc1.21.1";
				if (id.equals("ferritecore")) return "7.1.0";
				return null;
			}
		});
		assertEquals(BundledMods.State.ACTIVE, b.state(lithium));
		assertEquals(BundledMods.State.OTHER_VERSION, b.state(b.entries().get(1)), "eigene neuere Fassung des Spielers");
		assertEquals("7.1.0", b.runningVersion(b.entries().get(1)));
	}

	@Test
	void emptyOrBrokenListMeansNoModule() {
		assertFalse(BundledMods.of(new StringReader("[]")).available());
		assertFalse(BundledMods.of(new StringReader("{\"x\":1}")).available());
		// Im Test-Klassenpfad liegt keine Liste (nur die Fabric-Builds schreiben sie) → Modul ausgeblendet.
		assertFalse(BundledMods.get().available());
		assertFalse(new TrsModules().builtinOptimizations.available());
	}
}

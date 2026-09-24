package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.hud.ArmorLayout;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.util.LongObjectMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bausteine der Leistungs-Überarbeitung: Long-Map, Rüstungs-Anordnung, Speichern im Hintergrund, AFK-Standard. */
class SmoothnessTest {
	@Test
	void longMapBehavesLikeAHashMap() {
		LongObjectMap<String> map = new LongObjectMap<>(4);
		Map<Long, String> ref = new HashMap<>();
		Random r = new Random(42);
		for (int i = 0; i < 20_000; i++) {
			long key = r.nextInt(3000) - 1500L * (r.nextBoolean() ? 1 : 1_000_000_007L);
			int op = r.nextInt(3);
			if (op == 0) {
				map.put(key, "v" + i);
				ref.put(key, "v" + i);
			} else if (op == 1) {
				assertEquals(ref.remove(key), map.remove(key));
			} else {
				assertEquals(ref.get(key), map.get(key));
			}
			assertEquals(ref.size(), map.size());
		}
		for (Map.Entry<Long, String> e : ref.entrySet()) assertEquals(e.getValue(), map.get(e.getKey()));
		map.clear();
		assertEquals(0, map.size());
		assertNull(map.get(ref.keySet().iterator().next()));
	}

	@Test
	void armorLayoutVerticalAndHorizontal() {
		int[] widths = {0, 12, 30, 0, 18};
		// Senkrecht wie bisher: 5 Zeilen à 17, Breite Symbol + 3 + breitester Text.
		assertEquals(3 * 2 + 16 + 3 + 30, ArmorLayout.width(false, 5, widths));
		assertEquals(3 * 2 + 5 * 17 - 1, ArmorLayout.height(false, 5, widths));
		// Waagerecht: Zellen max(16, Text) + 2 Abstand, darunter eine Textzeile.
		int w = ArmorLayout.width(true, 5, widths);
		assertEquals(3 * 2 + 16 + 16 + 30 + 16 + 18 + 4 * 2, w);
		assertEquals(3 * 2 + 16 + 10, ArmorLayout.height(true, 5, widths));
		int[] places = new int[20];
		ArmorLayout.place(true, 5, widths, places);
		// Symbole in einer Zeile, nebeneinander ohne Überlappung, Text mittig darunter.
		for (int i = 0; i < 5; i++) {
			assertEquals(3, places[4 * i + 1]);
			assertEquals(3 + 16 + 1, places[4 * i + 3]);
			if (i > 0) assertTrue(places[4 * i] >= places[4 * (i - 1)] + 16, "Symbol " + i);
			assertTrue(places[4 * i] + 16 <= w - 3);
		}
		// Text 30 px breit: Zelle 30, Symbol darin mittig (7 px Rand), Text füllt die Zelle.
		assertEquals(places[4 * 2 + 2] + 7, places[4 * 2]);
		// Ohne Haltbarkeit keine Textzeile.
		assertEquals(3 * 2 + 16, ArmorLayout.height(true, 2, new int[]{0, 0}));
	}

	@Test
	void configIsWrittenInTheBackgroundWithTheNewestState(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("trsclient.json");
		TrsModules modules = new TrsModules();
		ConfigStore store = new ConfigStore(file);
		store.load(modules.registry);
		modules.fps.setEnabled(false);
		store.saveLater(modules.registry);
		modules.fps.setEnabled(true);
		modules.cps.setEnabled(false);
		store.saveLater(modules.registry);
		long until = System.currentTimeMillis() + 5000;
		String text = "";
		while (System.currentTimeMillis() < until) {
			text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			TrsModules check = new TrsModules();
			new ConfigStore(file).load(check.registry);
			if (check.fps.isEnabled() && !check.cps.isEnabled()) break;
			Thread.sleep(20);
		}
		TrsModules check = new TrsModules();
		new ConfigStore(file).load(check.registry);
		assertTrue(check.fps.isEnabled(), text);
		assertFalse(check.cps.isEnabled(), "neuester Stand geschrieben");
		// Sofortiges Speichern gewinnt nicht gegen einen älteren Stand im Hintergrund.
		modules.cps.setEnabled(true);
		store.save(modules.registry);
		store.flush();
		TrsModules again = new TrsModules();
		new ConfigStore(file).load(again.registry);
		assertTrue(again.cps.isEnabled());
	}

	@Test
	void afkLimitIsOffByDefault() {
		TrsModules modules = new TrsModules();
		assertEquals(0, modules.dynamicFpsAfk.getInt(), "AFK-Grenze standardmäßig aus");
		Performance perf = new Performance(modules, PerfCompat.all());
		perf.refresh();
		assertFalse(perf.afkActive());
		modules.dynamicFpsAfk.set(30);
		perf.refresh();
		assertEquals(modules.fpsBoost.isEnabled() && modules.dynamicFps.isEnabled(), perf.afkActive());
	}
}

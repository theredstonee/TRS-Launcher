package dev.theredstonee.trsclient.core.config;

import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {

	@TempDir
	Path dir;

	@Test
	void missingFileGivesDefaultsAndCreatesFile() {
		Path file = dir.resolve("config/trsclient.json");
		TrsModules m = new TrsModules();
		assertEquals(ConfigStore.Status.CREATED, new ConfigStore(file).load(m.registry));
		assertTrue(Files.exists(file));
		assertTrue(m.fps.isEnabled());
		assertFalse(m.fullbright.isEnabled());
		assertEquals(4.0, m.zoomFactor.get());
		assertEquals(HudAnchor.TOP_LEFT, m.fps.position().anchor);
	}

	@Test
	void saveAndLoadRoundTrip() throws IOException {
		Path file = dir.resolve("trsclient.json");
		TrsModules a = new TrsModules();
		ConfigStore store = new ConfigStore(file);
		store.load(a.registry);
		a.fps.setEnabled(false);
		a.fullbright.setEnabled(true);
		a.zoomFactor.set(6.5);
		a.keystrokesShowCps.set(false);
		a.ping.textColor.set(0xE0281E);
		a.cps.position().anchor = HudAnchor.BOTTOM_CENTER;
		a.cps.position().offsetX = 0.125;
		a.cps.position().offsetY = -0.25;
		store.save(a.registry);

		TrsModules b = new TrsModules();
		assertEquals(ConfigStore.Status.LOADED, new ConfigStore(file).load(b.registry));
		assertFalse(b.fps.isEnabled());
		assertTrue(b.fullbright.isEnabled());
		assertEquals(6.5, b.zoomFactor.get());
		assertFalse(b.keystrokesShowCps.get());
		assertEquals(0xE0281E, b.ping.textColor.rgb());
		assertEquals(HudAnchor.BOTTOM_CENTER, b.cps.position().anchor);
		assertEquals(0.125, b.cps.position().offsetX);
		assertEquals(-0.25, b.cps.position().offsetY);
		assertFalse(Files.exists(file.resolveSibling("trsclient.json.tmp")));
	}

	@Test
	void corruptFileFallsBackToDefaultsAndKeepsBackup() throws IOException {
		Path file = dir.resolve("trsclient.json");
		Files.writeString(file, "{ \"modules\": { \"fps\": { \"enabled\": fal", StandardCharsets.UTF_8);
		TrsModules m = new TrsModules();
		ConfigStore store = new ConfigStore(file);
		assertEquals(ConfigStore.Status.RECOVERED, store.load(m.registry));
		assertTrue(m.fps.isEnabled());
		assertTrue(Files.exists(store.brokenFile()));
		// Neue, gültige Datei wurde geschrieben.
		assertEquals(ConfigStore.Status.LOADED, new ConfigStore(file).load(new TrsModules().registry));
	}

	@Test
	void wrongTypesCountAsCorrupt() throws IOException {
		Path file = dir.resolve("trsclient.json");
		Files.writeString(file, "{ \"modules\": [1, 2, 3] }", StandardCharsets.UTF_8);
		TrsModules m = new TrsModules();
		assertEquals(ConfigStore.Status.RECOVERED, new ConfigStore(file).load(m.registry));
		assertEquals(4.0, m.zoomFactor.get());
	}

	@Test
	void emptyFileCountsAsCorrupt() throws IOException {
		Path file = dir.resolve("trsclient.json");
		Files.writeString(file, "", StandardCharsets.UTF_8);
		assertEquals(ConfigStore.Status.RECOVERED, new ConfigStore(file).load(new TrsModules().registry));
	}

	@Test
	void partialFileFillsMissingValuesWithDefaults() throws IOException {
		Path file = dir.resolve("trsclient.json");
		Files.writeString(file, """
				{
				  "modules": {
				    "zoom": { "numbers": { "factor": 99.0 } },
				    "ping": { "enabled": false, "colors": { "textColor": "kaputt" }, "flags": null },
				    "fps": { "anchor": "NOPE", "offsetX": "NaN" },
				    "unbekannt": { "enabled": true }
				  }
				}
				""", StandardCharsets.UTF_8);
		TrsModules m = new TrsModules();
		assertEquals(ConfigStore.Status.LOADED, new ConfigStore(file).load(m.registry));
		assertEquals(10.0, m.zoomFactor.get(), "auf Maximum begrenzt");
		assertTrue(m.zoom.isEnabled());
		assertTrue(m.zoomSmooth.get());
		assertFalse(m.ping.isEnabled());
		assertEquals(0xFFFFFF, m.ping.textColor.rgb());
		assertTrue(m.ping.background.get());
		assertEquals(HudAnchor.TOP_LEFT, m.fps.position().anchor);
		assertEquals(0.0, m.fps.position().offsetX);
	}
}

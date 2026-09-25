package dev.theredstonee.trsclient.core.perf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FpsConfigModeTest {
	@TempDir
	Path dir;

	@Test
	void newPlayersStartPrettyAndHaveNotChosen() {
		FpsConfigMode m = FpsConfigMode.load(dir);
		assertEquals(FpsConfigMode.Mode.PRETTY, m.mode());
		assertFalse(m.chosen());
		assertFalse(m.gamePending());
		PerformanceTest.FakeOptions o = new PerformanceTest.FakeOptions();
		assertEquals(0, m.applyGame(o, false), "Schön ändert an frischen Optionen nichts");
	}

	@Test
	void maxLowersOnlyVisualsAndPrettyRestoresWhatThePlayerDidNotTouch() {
		PerformanceTest.FakeOptions o = new PerformanceTest.FakeOptions();
		o.values.put(GameOptions.Opt.GRAPHICS, 1);
		o.values.put(GameOptions.Opt.CLOUDS, 2);
		o.values.put(GameOptions.Opt.PARTICLES, 0);
		o.values.put(GameOptions.Opt.SMOOTH_LIGHTING, 2);
		o.values.put(GameOptions.Opt.BIOME_BLEND, 2);
		int view = o.get(GameOptions.Opt.VIEW_DISTANCE);

		FpsConfigMode m = FpsConfigMode.load(dir);
		m.choose(FpsConfigMode.Mode.MAX);
		assertTrue(m.chosen());
		assertEquals(5, m.applyGame(o, false));
		assertEquals(0, o.get(GameOptions.Opt.GRAPHICS));
		assertEquals(0, o.get(GameOptions.Opt.CLOUDS));
		assertEquals(1, o.get(GameOptions.Opt.PARTICLES));
		assertEquals(0, o.get(GameOptions.Opt.SMOOTH_LIGHTING));
		assertEquals(1, o.get(GameOptions.Opt.BIOME_BLEND));
		assertEquals(view, o.get(GameOptions.Opt.VIEW_DISTANCE), "Sichtweite bleibt (Spielgefühl)");
		assertEquals(0, m.applyGame(o, false), "nur beim Wechsel");

		// Der Spieler schaltet die Wolken selbst wieder an – das bleibt beim Zurückschalten so.
		o.values.put(GameOptions.Opt.CLOUDS, 1);
		// Neustart: Stand aus der Datei.
		FpsConfigMode again = FpsConfigMode.load(dir);
		assertEquals(FpsConfigMode.Mode.MAX, again.mode());
		again.choose(FpsConfigMode.Mode.PRETTY);
		assertEquals(4, again.applyGame(o, false));
		assertEquals(1, o.get(GameOptions.Opt.GRAPHICS));
		assertEquals(1, o.get(GameOptions.Opt.CLOUDS), "Wahl des Spielers bleibt");
		assertEquals(0, o.get(GameOptions.Opt.PARTICLES));
		assertEquals(2, o.get(GameOptions.Opt.SMOOTH_LIGHTING));
		assertEquals(2, o.get(GameOptions.Opt.BIOME_BLEND));
	}

	@Test
	void shadersStayPrettyAndCustomGraphicsStays() {
		PerformanceTest.FakeOptions o = new PerformanceTest.FakeOptions();
		o.values.put(GameOptions.Opt.GRAPHICS, 3);
		FpsConfigMode m = FpsConfigMode.load(dir);
		m.choose(FpsConfigMode.Mode.MAX);
		assertEquals(0, m.applyGame(o, true), "Shader-Stufe bleibt schön");
		assertEquals(GameOptions.NONE, FpsConfigMode.maxTarget(GameOptions.Opt.GRAPHICS, 3, true), "benutzerdefiniert bleibt");
		assertEquals(GameOptions.NONE, FpsConfigMode.maxTarget(GameOptions.Opt.VIEW_DISTANCE, 32, true));
	}

	@Test
	void sodiumKeysOnlyWhenPresentAndStillDefault() throws Exception {
		Path sodium = dir.resolve("sodium-options.json");
		Files.write(sodium, ("{\"quality\":{\"weather_quality\":\"DEFAULT\",\"leaves_quality\":\"FANCY\",\"enable_vignette\":true},"
				+ "\"performance\":{\"chunk_builder_threads\":0}}").getBytes(StandardCharsets.UTF_8));
		FpsConfigMode m = FpsConfigMode.load(dir);
		m.choose(FpsConfigMode.Mode.MAX);
		assertEquals(1, m.applyModFiles(), "Laub hat der Spieler auf FANCY gestellt – bleibt");
		String text = new String(Files.readAllBytes(sodium), StandardCharsets.UTF_8);
		assertTrue(text.contains("\"weather_quality\": \"FAST\""), text);
		assertTrue(text.contains("\"leaves_quality\": \"FANCY\""), text);
		assertTrue(text.contains("\"chunk_builder_threads\": 0"), "Rest der Datei bleibt");
		assertEquals(0, m.applyModFiles(), "nur beim Wechsel");

		FpsConfigMode back = FpsConfigMode.load(dir);
		back.choose(FpsConfigMode.Mode.PRETTY);
		assertEquals(1, back.applyModFiles());
		text = new String(Files.readAllBytes(sodium), StandardCharsets.UTF_8);
		assertTrue(text.contains("\"weather_quality\": \"DEFAULT\""), text);

		// Neuere Sodium-Versionen ohne die Schlüssel: nichts erfinden.
		Files.write(sodium, "{\"quality\":{\"pixel_filtering_mode\":\"NEAREST\"}}".getBytes(StandardCharsets.UTF_8));
		FpsConfigMode newer = FpsConfigMode.load(dir);
		newer.choose(FpsConfigMode.Mode.MAX);
		assertEquals(0, newer.applyModFiles());
		assertFalse(new String(Files.readAllBytes(sodium), StandardCharsets.UTF_8).contains("leaves_quality"));
	}

	@Test
	void launcherWrittenFileIsRead() throws Exception {
		Files.createDirectories(dir.resolve("trsclient"));
		Files.write(dir.resolve(FpsConfigMode.FILE), "{\"version\":1,\"mode\":\"max\",\"chosen\":true}".getBytes(StandardCharsets.UTF_8));
		FpsConfigMode m = FpsConfigMode.load(dir);
		assertEquals(FpsConfigMode.Mode.MAX, m.mode());
		assertTrue(m.chosen());
		assertTrue(m.gamePending());
		Files.write(dir.resolve(FpsConfigMode.FILE), "kaputt".getBytes(StandardCharsets.UTF_8));
		assertEquals(FpsConfigMode.Mode.PRETTY, FpsConfigMode.load(dir).mode());
	}
}

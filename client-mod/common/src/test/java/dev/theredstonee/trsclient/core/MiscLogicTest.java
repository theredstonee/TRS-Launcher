package dev.theredstonee.trsclient.core;

import dev.theredstonee.trsclient.core.camera.FreelookState;
import dev.theredstonee.trsclient.core.config.ConfigStore;
import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.input.ToggleState;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.server.QuickJoin;
import dev.theredstonee.trsclient.core.server.QuickJoin.Server;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.util.PlatformOpen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kleinere reine Logik: Toggle-Tasten, Schnellbeitritt, Pixelschrift, Ordner öffnen, Auswahl-Einstellungen. */
class MiscLogicTest {

	@TempDir
	Path dir;

	@Test
	void toggleFlipsOnEachPressAndResetsWhenDisabled() {
		ToggleState t = new ToggleState();
		assertFalse(t.update(0, true, false));
		assertTrue(t.update(1, true, false));
		assertTrue(t.update(0, true, false));
		assertFalse(t.update(1, true, false));
		assertFalse(t.update(2, true, false), "zwei Drücke im selben Tick heben sich auf");
		assertTrue(t.update(1, true, false));
		assertTrue(t.update(1, true, true), "blockiert: Druck zählt nicht, Zustand bleibt");
		assertFalse(t.update(0, false, false), "Modul aus → aus");
		assertFalse(t.active());
	}

	@Test
	void freelookTurnsIndependentlyAndClampsPitch() {
		FreelookState f = new FreelookState();
		assertFalse(f.active());
		f.start(90F, 10F);
		assertTrue(f.active());
		f.turn(100, 0);
		assertEquals(105F, f.yaw(), 1e-4);
		f.turn(0, 1000);
		assertEquals(90F, f.pitch(), 1e-4);
		f.turn(0, -5000);
		assertEquals(-90F, f.pitch(), 1e-4);
		f.stop();
		assertFalse(f.active());
		f.start(0F, 120F);
		assertEquals(90F, f.pitch(), 1e-4);
	}

	@Test
	void quickJoinSkipsEmptyAddressesAndLimits() {
		List<Server> all = Arrays.asList(
				new Server("Lobby", " play.example.net "),
				new Server("Kaputt", ""),
				null,
				new Server("", "10.0.0.2:25566"),
				new Server("Drei", "c.example"),
				new Server("Vier", "d.example"));
		List<Server> picked = QuickJoin.pick(all, 3);
		assertEquals(3, picked.size());
		assertEquals("play.example.net", picked.get(0).address());
		assertEquals("10.0.0.2:25566", picked.get(1).label());
		assertEquals("Drei", picked.get(2).label());
	}

	@Test
	void pixelFontWordmark() {
		assertEquals(5, PixelFont.width("T"));
		// T(5)+1 + R(5)+1 + S(5) = 17
		assertEquals(17, PixelFont.width("TRS"));
		// Leerzeichen = 3 + 1
		assertEquals(17 + 1 + 3 + 1 + 5, PixelFont.width("TRS C"));
		for (int[] r : PixelFont.rects("TRS Client")) {
			assertTrue(r[0] >= 0 && r[2] <= PixelFont.width("TRS Client") && r[1] >= 0 && r[3] <= PixelFont.HEIGHT);
			assertTrue(r[2] > r[0] && r[3] == r[1] + 1);
		}
		// "T": oberste Zeile ist ein durchgehender Lauf
		assertTrue(PixelFont.rects("T").stream().anyMatch(r -> r[0] == 0 && r[2] == 5 && r[1] == 0));
	}

	@Test
	void platformOpenCommands() {
		Path p = Path.of("packs");
		assertEquals("explorer.exe", PlatformOpen.command("Windows 11", p).get(0));
		assertEquals("open", PlatformOpen.command("Mac OS X", p).get(0));
		assertEquals("xdg-open", PlatformOpen.command("Linux", p).get(0));
		assertEquals(p.toAbsolutePath().toString(), PlatformOpen.command(null, p).get(1));
	}

	@Test
	void choiceSettingRoundTripAndFallback() throws IOException {
		Path file = dir.resolve("trsclient.json");
		TrsModules a = new TrsModules();
		ConfigStore store = new ConfigStore(file);
		store.load(a.registry);
		assertEquals(Crosshair.Shape.CROSS, a.crosshairShape.get());
		a.crosshairShape.set(Crosshair.Shape.CIRCLE_DOT);
		a.crosshairSize.set(7);
		store.save(a.registry);

		TrsModules b = new TrsModules();
		new ConfigStore(file).load(b.registry);
		assertEquals(Crosshair.Shape.CIRCLE_DOT, b.crosshairShape.get());
		assertEquals(7, b.crosshairSize.getInt());
		assertEquals("7", b.crosshairSize.display());

		b.crosshairShape.cycle();
		assertEquals(Crosshair.Shape.CROSS, b.crosshairShape.get(), "rundherum");
		b.crosshairShape.cycle(-1);
		assertEquals(Crosshair.Shape.CIRCLE_DOT, b.crosshairShape.get());
	}

	@Test
	void newModulesHaveSafeDefaults() {
		TrsModules m = new TrsModules();
		assertFalse(m.freelook.isEnabled(), "Freelook ist standardmäßig aus (auf manchen Servern verboten)");
		assertTrue(m.titleScreen.isEnabled());
		assertFalse(m.crosshair.isEnabled());
		assertTrue(m.freelook.description().contains("Server"));
	}
}

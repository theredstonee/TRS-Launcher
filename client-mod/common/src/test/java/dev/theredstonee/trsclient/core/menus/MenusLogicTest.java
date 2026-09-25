package dev.theredstonee.trsclient.core.menus;

import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Menü-Stil: Schalter je Menü, angeheftete Server (Adressen, Reihenfolge, Speichern). */
class MenusLogicTest {
	@Test
	void styleSwitchesPerMenu() {
		TrsModules modules = new TrsModules();
		MenuStyle.install(modules);
		assertTrue(MenuStyle.enabled(MenuStyle.Kind.PAUSE), "Standard: an");
		assertTrue(MenuStyle.pauseButtons());
		modules.menuMultiplayer.set(false);
		assertFalse(MenuStyle.enabled(MenuStyle.Kind.MULTIPLAYER), "einzeln klassisch");
		assertTrue(MenuStyle.enabled(MenuStyle.Kind.OPTIONS));
		modules.menuStyle.setEnabled(false);
		for (MenuStyle.Kind k : MenuStyle.Kind.values()) assertFalse(MenuStyle.enabled(k), "Modul aus = alles klassisch");
		assertFalse(MenuStyle.pauseButtons());
		assertFalse(MenuStyle.enabled(null));
	}

	@Test
	void normalizesAddresses() {
		assertEquals("play.example.net", ServerPins.normalize(" Play.Example.NET:25565 "));
		assertEquals("play.example.net:25566", ServerPins.normalize("play.example.net:25566"));
		assertEquals("mc.test", ServerPins.normalize("minecraft://mc.test/"));
		assertNull(ServerPins.normalize("bad host"));
		assertNull(ServerPins.normalize(""));
		assertNull(ServerPins.normalize(null));
	}

	@Test
	void pinnedServersMoveUpStably(@TempDir Path dir) {
		ServerPins pins = new ServerPins(dir.resolve("server-pins.json"));
		List<String> list = Arrays.asList("a.net", "b.net", "c.net", "d.net");
		assertTrue(pins.swaps(list).isEmpty(), "nichts angeheftet = nichts tauschen");
		assertTrue(pins.toggle("C.net:25565"));
		assertTrue(pins.toggle("d.net"));
		assertTrue(pins.isPinned("c.net"));
		assertEquals(Arrays.asList(2, 3, 0, 1), toList(pins.order(list)));
		List<String> current = new ArrayList<>(list);
		for (int[] s : pins.swaps(list)) Collections.swap(current, s[0], s[1]);
		assertEquals(Arrays.asList("c.net", "d.net", "a.net", "b.net"), current, "Tausch wie Vanilla ergibt die Zielreihenfolge");
		assertTrue(pins.swaps(current).isEmpty(), "schon sortiert");

		ServerPins again = new ServerPins(dir.resolve("server-pins.json"));
		again.load();
		assertTrue(again.isPinned("d.net"), "gespeichert");
		assertFalse(again.toggle("d.net"));
		ServerPins third = new ServerPins(dir.resolve("server-pins.json"));
		third.load();
		assertFalse(third.isPinned("d.net"), "Lösen gespeichert");
	}

	private static List<Integer> toList(int[] a) {
		List<Integer> out = new ArrayList<>();
		for (int v : a) out.add(v);
		return out;
	}
}

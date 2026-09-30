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
	void dialogsFollowTheirOpener() {
		// „Server löschen?“ aus der Serverliste, „Welt löschen?“ aus der Weltenliste.
		assertEquals(MenuStyle.Kind.MULTIPLAYER, MenuStyle.dialogKind(MenuStyle.Kind.MULTIPLAYER, false, false));
		assertEquals(MenuStyle.Kind.WORLDS, MenuStyle.dialogKind(MenuStyle.Kind.WORLDS, false, false));
		// Paket-Frage des Servers während des Verbindens (Ladebildschirm) = Mehrspieler.
		assertEquals(MenuStyle.Kind.MULTIPLAYER, MenuStyle.dialogKind(MenuStyle.Kind.LOADING, false, true));
		assertEquals(MenuStyle.Kind.MULTIPLAYER, MenuStyle.dialogKind(MenuStyle.Kind.LOADING, false, false));
		// Ohne bekanntes Menü: auf einem Server Mehrspieler, in der eigenen Welt Pause, im Hauptmenü klassisch.
		assertEquals(MenuStyle.Kind.MULTIPLAYER, MenuStyle.dialogKind(null, true, true));
		assertEquals(MenuStyle.Kind.PAUSE, MenuStyle.dialogKind(null, true, false));
		assertNull(MenuStyle.dialogKind(null, false, false));
		// Aus dem Pausenmenü (z. B. „Feedback geben“) bleibt es das Pausenmenü.
		assertEquals(MenuStyle.Kind.PAUSE, MenuStyle.dialogKind(MenuStyle.Kind.PAUSE, true, true));
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

	/** „Server hinzufügen“ 1.21.11 (ManageServerScreen): Felder und Knöpfe mittig, 200 breit. */
	private static List<int[]> manageServer(int w, int h) {
		int x = w / 2 - 100;
		List<int[]> r = new ArrayList<>();
		r.add(new int[]{x, 66, 200, 20});
		r.add(new int[]{x, 106, 200, 20});
		r.add(new int[]{x, h / 4 + 72, 200, 20});
		r.add(new int[]{x, h / 4 + 96 + 18, 200, 20});
		r.add(new int[]{x, h / 4 + 120 + 18, 200, 20});
		return r;
	}

	@Test
	void formPanelHugsTheCentredForm() {
		// Breites Fenster (1920×1080, GUI-Größe 2 → 960×540): Fläche = Formular + Rand, zentriert.
		int[] b = FormPanel.bounds(manageServer(960, 540), 960, 540, 32);
		assertEquals(480 - 100 - FormPanel.PAD_X, b[0]);
		assertEquals(480 + 100 + FormPanel.PAD_X, b[2]);
		assertEquals(66 - FormPanel.PAD_TOP, b[1]);
		assertEquals(540 / 4 + 138 + 20 + FormPanel.PAD_BOTTOM, b[3]);
	}

	@Test
	void formPanelIgnoresForeignCornerButtons() {
		// ViaFabricPlus setzt „Set version“ oben rechts in die Ecke – früher reichte die Fläche dadurch bis zum Rand.
		for (int[] size : new int[][]{{960, 540}, {1000, 520}, {427, 240}, {320, 240}}) {
			int w = size[0];
			int h = size[1];
			List<int[]> rects = manageServer(w, h);
			rects.add(new int[]{w - 98 - 5, 5, 98, 20});
			int[] b = FormPanel.bounds(rects, w, h, 32);
			assertEquals(w / 2 - 112, b[0], "links bei " + w);
			assertEquals(w / 2 + 112, b[2], "rechts bei " + w);
			assertTrue(b[1] >= 36, "nie in der Kopfleiste");
		}
		// Fremder Knopf unten rechts in der Ecke (weit weg vom Formular) zählt ebenfalls nicht.
		List<int[]> rects = manageServer(960, 540);
		rects.add(new int[]{960 - 105, 540 - 25, 100, 20});
		assertEquals(480 + 112, FormPanel.bounds(rects, 960, 540, 32)[2]);
	}

	@Test
	void formPanelKeepsAdjacentButtons() {
		// Zusatzknopf direkt neben dem Adressfeld (andere Mod) gehört zum Formular; Fläche bleibt symmetrisch.
		List<int[]> rects = manageServer(960, 540);
		rects.add(new int[]{480 + 104, 106, 20, 20});
		int[] b = FormPanel.bounds(rects, 960, 540, 32);
		assertEquals(480 + 124 + FormPanel.PAD_X, b[2]);
		assertEquals(480 - 124 - FormPanel.PAD_X, b[0]);
		// Zwei Knöpfe nebeneinander (keiner schneidet die Mitte) unter dem Formular bleiben dabei.
		rects = manageServer(960, 540);
		rects.add(new int[]{480 - 154, 540 / 4 + 162, 150, 20});
		rects.add(new int[]{480 + 4, 540 / 4 + 162, 150, 20});
		b = FormPanel.bounds(rects, 960, 540, 32);
		assertEquals(480 - 154 - FormPanel.PAD_X, b[0]);
		assertEquals(540 / 4 + 182 + FormPanel.PAD_BOTTOM, b[3]);
		// Nichts sichtbar → keine Fläche; nur Widgets in der Kopfleiste → keine Fläche.
		assertNull(FormPanel.bounds(new ArrayList<int[]>(), 960, 540, 32));
		List<int[]> onlyHeader = new ArrayList<>();
		onlyHeader.add(new int[]{900, 5, 50, 20});
		assertNull(FormPanel.bounds(onlyHeader, 960, 540, 32));
	}
}

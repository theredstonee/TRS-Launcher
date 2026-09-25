package dev.theredstonee.trsclient.core.intro;

import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.ModulePacks;
import dev.theredstonee.trsclient.core.module.NewMarkers;
import dev.theredstonee.trsclient.core.module.NewSince;
import dev.theredstonee.trsclient.core.module.Setting;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** „NEU“-Markierungen, Einführungs-Zustand und Modul-Pakete. */
class IntroLogicTest {
	@Test
	void versionsCompare() {
		assertTrue(NewSince.compare("0.10.0", "0.9.9") > 0);
		assertEquals(0, NewSince.compare("0.5", "0.5.0"));
		assertTrue(NewSince.compare("0.6.0", NewSince.LEGACY_BASELINE) > 0);
		assertTrue(NewSince.valid("0.6.0"));
		assertFalse(NewSince.valid("0.6.0; rm"));
		assertTrue(NewSince.compare(NewSince.latest(), "0.6.0") >= 0);
	}

	@Test
	void freshInstallMarksNothing() {
		TrsModules m = new TrsModules();
		m.registry.apply(new TrsConfig());
		assertFalse(m.clientState.introDone());
		for (Module module : m.registry.all()) assertFalse(m.clientState.news().hasNew(module), module.id());
	}

	@Test
	void upgradeMarksOnlyNewerItemsUntilOpened() {
		TrsModules m = new TrsModules();
		TrsConfig old = new TrsConfig();
		old.modules.put("fps", new dev.theredstonee.trsclient.core.config.ModuleConfig());
		m.registry.apply(old);
		NewMarkers news = m.clientState.news();
		assertEquals(NewSince.LEGACY_BASELINE, news.baseline());
		assertFalse(m.clientState.introDone(), "die Einführung kommt auch nach einem Update (einmal)");
		assertFalse(news.hasNew(m.zoom), "0.1.0 ist alt");
		assertFalse(news.isNew(m.trsOnline), "das Modul selbst ist alt");
		assertTrue(news.hasNew(m.trsOnline), "aber es hat eine neue Einstellung");
		assertTrue(news.isNew(m.trsOnline, m.syncClient));
		assertFalse(news.isNew(m.trsOnline, m.badgeTab));
		assertTrue(news.anyNew(Collections.singletonList(m.trsOnline)));

		news.opened(m.trsOnline);
		assertFalse(news.hasNew(m.trsOnline), "Kachel verliert das Schild sofort");
		assertTrue(news.isNew(m.trsOnline, m.syncClient), "Zeile bleibt markiert, solange die Seite offen ist");
		news.closed();
		assertFalse(news.isNew(m.trsOnline, m.syncClient));

		// Speichern und neu laden: bleibt gesehen.
		TrsConfig saved = m.registry.capture();
		TrsModules again = new TrsModules();
		again.registry.apply(saved);
		assertFalse(again.clientState.news().hasNew(again.trsOnline));
		assertEquals(Arrays.asList("trsOnline.sync"), saved.clientState.newSeen);
	}

	@Test
	void everyModuleAndSettingHasARelease() {
		TrsModules m = new TrsModules();
		for (Module module : m.registry.all()) {
			assertNotNull(NewSince.of(module.id()), "NewSince fehlt für Modul " + module.id());
			assertTrue(NewSince.compare(NewSince.of(module.id()), NewSince.latest()) <= 0);
			for (Setting s : module.settings()) {
				String since = NewSince.of(NewMarkers.id(module, s));
				// Einstellungen ohne Eintrag gibt es seit ihrem Modul; mit Eintrag nie älter als das Modul.
				if (since != null) {
					assertTrue(NewSince.compare(since, NewSince.of(module.id())) >= 0, NewMarkers.id(module, s));
				}
			}
		}
		for (String extra : NewSince.extras("fpsBoost")) assertNotNull(NewSince.of(extra));
	}

	@Test
	void upgradeMarksEverythingNewInThisRelease() {
		TrsModules m = new TrsModules();
		TrsConfig old = new TrsConfig();
		old.modules.put("fps", new dev.theredstonee.trsclient.core.config.ModuleConfig());
		m.registry.apply(old);
		NewMarkers news = m.clientState.news();
		// Neue Module (samt ihrer Einstellungen) …
		assertTrue(news.isNew(m.menuStyle));
		assertTrue(news.isNew(m.menuStyle, m.menuPause));
		assertTrue(news.isNew(m.menuStyle, m.menuWorlds));
		assertTrue(news.isNew(m.builtinOptimizations));
		// … ein neuer Bereich auf einer alten Seite (Grafik-Modus auf „FPS-Boost“) markiert die Kachel …
		assertFalse(news.isNew(m.fpsBoost));
		assertTrue(news.hasNew(m.fpsBoost));
		assertTrue(news.badge(NewSince.FPS_MODE));
		// … Menü-Bereiche und die neue Taste.
		for (String id : new String[]{NewSince.MENU_WARDROBE, NewSince.MENU_ACCOUNTS, NewSince.MENU_FRIENDS,
				NewSince.MENU_CLIPS, NewSince.MENU_PACKS, NewSince.KEY_WARDROBE}) {
			assertTrue(news.isNew(id), id);
		}
		// Alte Bereiche bleiben ohne Schild.
		assertFalse(news.hasNew(m.zoom));
		assertFalse(news.isNew("menu:unknown"));

		// Seite „FPS-Boost“ geöffnet: Kachel sofort ohne Schild, der Grafik-Modus bis zum Verlassen.
		news.opened(m.fpsBoost);
		assertFalse(news.hasNew(m.fpsBoost));
		assertTrue(news.badge(NewSince.FPS_MODE));
		news.closed();
		assertFalse(news.badge(NewSince.FPS_MODE));

		// Menü-Bereich geöffnet: sofort weg (kein Nachleuchten); zweites Mal ändert nichts.
		assertTrue(news.markSeen(NewSince.MENU_WARDROBE));
		assertFalse(news.isNew(NewSince.MENU_WARDROBE));
		assertFalse(news.badge(NewSince.MENU_WARDROBE));
		assertFalse(news.markSeen(NewSince.MENU_WARDROBE));

		// Taste in der Einführung gezeigt: gesehen, Schild bleibt bis die Einführung zu ist.
		assertTrue(news.shown(NewSince.KEY_WARDROBE));
		assertTrue(news.badge(NewSince.KEY_WARDROBE));
		assertFalse(news.shown(NewSince.KEY_WARDROBE));
		news.closed();
		assertFalse(news.badge(NewSince.KEY_WARDROBE));

		// Bleibt über Speichern/Laden erhalten.
		TrsModules again = new TrsModules();
		again.registry.apply(m.registry.capture());
		NewMarkers loaded = again.clientState.news();
		assertFalse(loaded.isNew(NewSince.MENU_WARDROBE));
		assertFalse(loaded.hasNew(again.fpsBoost));
		assertTrue(loaded.isNew(NewSince.MENU_FRIENDS));
		assertTrue(loaded.hasNew(again.menuStyle));
	}

	@Test
	void freshInstallMarksNoMenuAreas() {
		TrsModules m = new TrsModules();
		m.registry.apply(new TrsConfig());
		assertFalse(m.clientState.news().isNew(NewSince.MENU_WARDROBE));
		assertFalse(m.clientState.news().badge(NewSince.KEY_WARDROBE));
		assertFalse(m.clientState.news().hasNew(m.fpsBoost));
	}

	@Test
	void mergeUnionsSeenAndTakesAccountBaseline() {
		NewSince.put("testOnly.a", "0.9.0");
		NewSince.put("testOnly.b", "0.9.0");
		NewMarkers local = new NewMarkers("0.8.0");
		local.set("0.8.0", Collections.singletonList("testOnly.a"));
		assertTrue(local.merge("0.5.0", Collections.singletonList("testOnly.b")));
		assertEquals("0.5.0", local.baseline());
		assertFalse(local.isNew("testOnly.a"));
		assertFalse(local.isNew("testOnly.b"));
		assertFalse(local.merge("0.5.0", Collections.singletonList("testOnly.b")));
		// Unbekannte Einträge (neuere Version auf einem anderen PC) bleiben erhalten.
		local.merge(null, Collections.singletonList("fromTheFuture.x"));
		assertTrue(local.seen().contains("fromTheFuture.x"));
	}

	@Test
	void introStateRoundTrip() {
		TrsModules m = new TrsModules();
		m.registry.apply(new TrsConfig());
		m.clientState.markIntro(ClientState.SKIPPED, null, 123L);
		m.clientState.chooseLook("oled", "lapis", "de", 456L);
		m.clientState.setFpsMode(FpsModeChooser.MAX);
		TrsConfig c = m.registry.capture();
		TrsModules b = new TrsModules();
		b.registry.apply(c);
		assertTrue(b.clientState.introDone());
		assertEquals(ClientState.SKIPPED, b.clientState.introHow());
		assertEquals("oled", b.clientState.lookTheme());
		assertEquals(456L, b.clientState.lookAt());
		assertEquals(FpsModeChooser.MAX, new FpsModeChooser.Default().current(b));
		b.clientState.setFpsMode("evil; value");
		assertNull(b.clientState.fpsMode());
	}

	@Test
	void packsPreviewApplyUndo() {
		TrsModules m = new TrsModules();
		m.registry.apply(new TrsConfig());
		boolean minimapBefore = m.minimap.isEnabled();
		ModulePacks.Preview p = ModulePacks.preview(m, ModulePacks.PVP, true, null);
		assertTrue(p.enable.contains(m.reach));
		assertFalse(p.enable.contains(m.minimap));
		assertFalse(p.isEmpty());
		TrsConfig undo = ModulePacks.apply(m, ModulePacks.PVP, true, null);
		assertTrue(m.reach.isEnabled());
		assertTrue(m.keystrokes.isEnabled());
		assertFalse(m.minimap.isEnabled());
		assertEquals(HudAnchor.CENTER_LEFT, m.combo.position().anchor);
		assertTrue(ModulePacks.preview(m, ModulePacks.PVP, true, null).isEmpty(), "zweimal anwenden ändert nichts");
		assertTrue(m.trsOnline.isEnabled(), "Pakete lassen TRS-Online in Ruhe");

		// „NEU“-Stand wird durch Rückgängig nicht zurückgedreht.
		m.clientState.markIntro(ClientState.FINISHED, "pvp", 1L);
		ModulePacks.undo(m, undo);
		assertFalse(m.reach.isEnabled());
		assertEquals(minimapBefore, m.minimap.isEnabled());
		assertTrue(m.clientState.introDone());

		ModulePacks.apply(m, ModulePacks.MINIMAL, false, null);
		assertTrue(m.fps.isEnabled());
		assertFalse(m.cps.isEnabled());
		assertFalse(m.keystrokes.isEnabled());

		// Nicht unterstützte Module fasst ein Paket nicht an.
		m.minimap.setEnabled(false);
		ModulePacks.apply(m, ModulePacks.COMFORT, true, new ModulePacks.Support() {
			@Override
			public boolean supports(Module module) {
				return module != null && !module.id().equals("minimap");
			}
		});
		assertFalse(m.minimap.isEnabled());
		for (ModulePacks.Pack pack : ModulePacks.all()) {
			assertNotNull(ModulePacks.byId(pack.id));
			for (String id : pack.modules.keySet()) assertNotNull(m.registry.byId(id), pack.id + ": " + id);
			for (String id : pack.hud.keySet()) assertNotNull(m.registry.byId(id), pack.id + ": " + id);
		}
	}
}

package dev.theredstonee.trsclient.core.intro;

import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.hud.HudAnchor;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.ModulePacks;
import dev.theredstonee.trsclient.core.module.NewMarkers;
import dev.theredstonee.trsclient.core.module.NewSince;
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
		assertTrue(m.clientState.introDone());
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

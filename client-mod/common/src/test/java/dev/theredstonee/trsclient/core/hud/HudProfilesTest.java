package dev.theredstonee.trsclient.core.hud;

import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudProfilesTest {

	@Test
	void startsWithOneProfile() {
		TrsModules m = new TrsModules();
		assertEquals(1, m.profiles.size());
		assertEquals(HudProfiles.DEFAULT_NAME, m.profiles.activeName());
		assertFalse(m.profiles.canDelete());
	}

	@Test
	void newProfileKeepsCurrentLayoutAndSwitchingRestoresIt() {
		TrsModules m = new TrsModules();
		m.fps.position().offsetX = 0.1;
		m.fps.scale.set(1.0);

		assertNull(m.profiles.create("PvP"));
		assertEquals(2, m.profiles.size());
		assertEquals("PvP", m.profiles.activeName());

		// Im neuen Profil etwas verschieben …
		m.fps.position().offsetX = 0.5;
		m.fps.scale.set(2.0);
		m.cps.setEnabled(false);

		// … zurück zum Standard: altes Layout ist wieder da.
		m.profiles.switchTo(0);
		assertEquals(0.1, m.fps.position().offsetX, 1e-9);
		assertEquals(1.0, m.fps.scale.get(), 1e-9);
		assertTrue(m.cps.isEnabled());

		// … und wieder zurück ins PvP-Profil.
		m.profiles.switchTo(1);
		assertEquals(0.5, m.fps.position().offsetX, 1e-9);
		assertEquals(2.0, m.fps.scale.get(), 1e-9);
		assertFalse(m.cps.isEnabled());
	}

	@Test
	void nonHudModulesAreShared() {
		TrsModules m = new TrsModules();
		assertNull(m.profiles.create("Bauen"));
		m.fullbright.setEnabled(true);
		m.profiles.switchTo(0);
		assertTrue(m.fullbright.isEnabled(), "Nicht-HUD-Module gehören nicht zum Profil");
	}

	@Test
	void cycleWalksThroughProfiles() {
		TrsModules m = new TrsModules();
		m.profiles.create("PvP");
		m.profiles.create("Bauen");
		assertEquals("Standard", m.profiles.cycle());
		assertEquals("PvP", m.profiles.cycle());
		assertEquals("Bauen", m.profiles.cycle());
	}

	@Test
	void rejectsBadNames() {
		TrsModules m = new TrsModules();
		assertNotNull(m.profiles.create("   "));
		assertNotNull(m.profiles.create("standard"), "Namen sind ohne Groß-/Kleinschreibung eindeutig");
		assertNotNull(m.profiles.create("Dieser Name ist viel zu lang für ein Profil"));
		assertNull(m.profiles.create("PvP"));
		assertNotNull(m.profiles.rename(0, "pvp"));
		assertNull(m.profiles.rename(0, "Basis"));
		assertEquals("Basis", m.profiles.name(0));
	}

	@Test
	void deleteKeepsAtLeastOneProfile() {
		TrsModules m = new TrsModules();
		assertFalse(m.profiles.delete(0));
		m.profiles.create("PvP");
		assertTrue(m.profiles.delete(1));
		assertEquals(1, m.profiles.size());
		assertEquals(0, m.profiles.activeIndex());
	}

	@Test
	void roundTripThroughConfig() {
		TrsModules a = new TrsModules();
		a.profiles.create("PvP");
		a.fps.scale.set(2.0);
		a.profiles.switchTo(0);
		a.fps.scale.set(0.5);
		a.profiles.switchTo(1);
		TrsConfig config = a.registry.capture();
		assertEquals(TrsConfig.CURRENT_VERSION, config.configVersion);
		assertEquals(2, config.hudProfiles.profiles.size());
		assertEquals("PvP", config.hudProfiles.active);

		TrsModules b = new TrsModules();
		b.registry.apply(config);
		assertEquals(2, b.profiles.size());
		assertEquals("PvP", b.profiles.activeName());
		assertEquals(2.0, b.fps.scale.get(), 1e-9);
		b.profiles.switchTo(0);
		assertEquals(0.5, b.fps.scale.get(), 1e-9);
	}

	@Test
	void oldConfigWithoutProfilesBecomesStandardProfile() {
		TrsConfig old = new TrsConfig();
		old.configVersion = 1;
		old.hudProfiles = null;
		TrsModules m = new TrsModules();
		m.registry.apply(old);
		assertEquals(1, m.profiles.size());
		assertEquals(HudProfiles.DEFAULT_NAME, m.profiles.activeName());
		assertTrue(m.keyDefaults.needsZoomKeyMigration(), "Alte Config: Zoom-Taste wird umgestellt");
	}

	@Test
	void unknownModulesInProfilesAreIgnored() {
		TrsConfig config = new TrsModules().registry.capture();
		TrsConfig.Profile ghost = new TrsConfig.Profile();
		ghost.name = "Geist";
		ghost.modules.put("gibtesnicht", null);
		config.hudProfiles.profiles.add(ghost);
		TrsModules m = new TrsModules();
		m.registry.apply(config);
		assertEquals(2, m.profiles.size());
		m.profiles.switchTo(1);
		assertEquals("Geist", m.profiles.activeName());
	}
}

package dev.theredstonee.trsclient.core.halloween;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Event-Schalter: Launcher-Datei, API und Test-Property. */
class HalloweenTest {
	@AfterEach
	void clean() {
		Halloween.reset();
	}

	@Test
	void launcherHoldsUntilTheApiAnswersAndForceWins() {
		assertFalse(Halloween.active());
		Halloween.fromLauncher("Halloween");
		assertTrue(Halloween.active());
		int version = Halloween.version();
		Halloween.fromApi(null);
		assertTrue(Halloween.active(), "fehlendes Feld lässt den Launcher gelten");
		assertEquals(version, Halloween.version());
		Halloween.fromApi(Collections.<String>emptyList());
		assertFalse(Halloween.active(), "leere Liste heißt: die API sagt keins");
		Halloween.fromApi(Arrays.asList("HALLOWEEN", "nope!", "halloween", "x"));
		assertTrue(Halloween.active());
		// „nope!“ fällt weg, „x“ ist eine gültige ID ({1,32}), Großschreibung und Dubletten fallen zusammen.
		assertEquals(Arrays.asList("halloween", "x"), Halloween.activeEvents());
		Halloween.force(Boolean.FALSE);
		assertFalse(Halloween.active());
		Halloween.force(Boolean.TRUE);
		assertTrue(Halloween.active());
		Halloween.force(null);
		assertTrue(Halloween.active());
	}

	@Test
	void lookFollowsTheEventsUntilTheApiSendsALookList() {
		Halloween.fromLauncher("halloween");
		assertTrue(Halloween.lookActive(), "nur Launcher-Datei: Look = Event");
		Halloween.fromApi(Arrays.asList("halloween"), null);
		assertTrue(Halloween.lookActive(), "alte API ohne eventLook: Look = events");
		int version = Halloween.version();
		Halloween.fromApi(Arrays.asList("halloween"), Collections.<String>emptyList());
		assertTrue(Halloween.active(), "Funktionen bleiben");
		assertFalse(Halloween.lookActive(), "Team-Sperre: Look aus");
		assertTrue(Halloween.lookBlocked());
		assertTrue(Halloween.version() > version);
		Halloween.fromApi(null, Arrays.asList("halloween"));
		assertFalse(Halloween.lookActive(), "events fehlt: nichts ändert sich");
		Halloween.fromApi(Arrays.asList("halloween"), Arrays.asList("HALLOWEEN"));
		assertTrue(Halloween.lookActive());
		Halloween.fromApi(Collections.<String>emptyList(), Arrays.asList("halloween"));
		assertFalse(Halloween.active());
		assertFalse(Halloween.lookActive(), "nie Look ohne Event");
		assertFalse(Halloween.lookBlocked());
	}

	@Test
	void launcherFieldAndOwnSwitchTurnTheLookOff() {
		Halloween.fromLauncher("halloween");
		Halloween.lookFromLauncher(Boolean.FALSE);
		assertTrue(Halloween.active());
		assertFalse(Halloween.lookActive(), "Launcher: eventLook=false");
		assertTrue(Halloween.lookBlocked());
		Halloween.lookFromLauncher(null);
		assertTrue(Halloween.lookActive(), "Feld fehlt (alter Launcher) = an");
		final boolean[] own = {false};
		Halloween.ownLook(() -> own[0]);
		assertFalse(Halloween.lookActive(), "eigener Schalter aus");
		assertFalse(Halloween.lookBlocked(), "selbst ausgeschaltet ist keine Sperre");
		assertTrue(Halloween.active(), "Funktionen bleiben");
		own[0] = true;
		assertTrue(Halloween.lookActive());
		Halloween.ownLook(() -> {
			throw new IllegalStateException("kaputt");
		});
		assertTrue(Halloween.lookActive(), "Fehler im Schalter = an");
	}

	@Test
	void forceIgnoresServerAndLauncherButNotTheOwnSwitch() {
		Halloween.fromApi(Collections.<String>emptyList(), Collections.<String>emptyList());
		Halloween.lookFromLauncher(Boolean.FALSE);
		Halloween.force(Boolean.TRUE);
		assertTrue(Halloween.lookActive());
		Halloween.ownLook(() -> false);
		assertFalse(Halloween.lookActive());
		Halloween.force(Boolean.FALSE);
		Halloween.ownLook(null);
		assertFalse(Halloween.lookActive());
	}

	@Test
	void moduleSwitchDrivesSpookyEffects() {
		dev.theredstonee.trsclient.core.module.TrsModules m = new dev.theredstonee.trsclient.core.module.TrsModules();
		Halloween.fromLauncher("halloween");
		assertTrue(m.halloween.eventLook.get(), "ab Werk an");
		assertTrue(m.halloween.on());
		m.halloween.eventLook.set(false);
		assertFalse(Halloween.lookActive());
		assertFalse(m.halloween.on(), "Spuk-Effekte/Jumpscares aus");
		assertTrue(Halloween.active(), "Modul und Seite bleiben verfügbar");
		assertTrue(m.halloween.halloween.available());
	}

	@Test
	void eventsChangedCarriesTheLookList() {
		dev.theredstonee.trsclient.core.social.MeEvent e = dev.theredstonee.trsclient.core.social.MeEvent.parse("events_changed",
				"{\"events\":[\"halloween\"],\"look\":[]}", "e.1");
		assertEquals(Arrays.asList("halloween"), e.events);
		assertEquals(Collections.<String>emptyList(), e.look);
		Halloween.fromApi(e.events, e.look);
		assertTrue(Halloween.active());
		assertFalse(Halloween.lookActive());
		dev.theredstonee.trsclient.core.social.MeEvent old = dev.theredstonee.trsclient.core.social.MeEvent.parse("events_changed",
				"{\"events\":[\"halloween\"]}", "e.2");
		assertNull(old.look, "alter Server ohne look");
		Halloween.fromApi(old.events, old.look);
		assertTrue(Halloween.lookActive());
	}

	@Test
	void forcePropertyWords() {
		assertEquals(Boolean.TRUE, Halloween.parseForce("on"));
		assertEquals(Boolean.TRUE, Halloween.parseForce("1"));
		assertEquals(Boolean.FALSE, Halloween.parseForce("off"));
		assertEquals(Boolean.FALSE, Halloween.parseForce("0"));
		assertNull(Halloween.parseForce("maybe"));
		assertNull(Halloween.parseForce(null));
	}
}

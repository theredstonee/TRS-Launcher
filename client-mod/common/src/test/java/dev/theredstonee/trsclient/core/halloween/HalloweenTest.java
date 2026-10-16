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
	void forcePropertyWords() {
		assertEquals(Boolean.TRUE, Halloween.parseForce("on"));
		assertEquals(Boolean.TRUE, Halloween.parseForce("1"));
		assertEquals(Boolean.FALSE, Halloween.parseForce("off"));
		assertEquals(Boolean.FALSE, Halloween.parseForce("0"));
		assertNull(Halloween.parseForce("maybe"));
		assertNull(Halloween.parseForce(null));
	}
}

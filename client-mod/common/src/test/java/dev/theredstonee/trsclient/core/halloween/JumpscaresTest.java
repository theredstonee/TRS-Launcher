package dev.theredstonee.trsclient.core.halloween;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schutzregeln und Auslöser der Jumpscares (Vertrag: 10 s nach Schaden, 6 Herzen, 20 min, 2 min Anlauf). */
class JumpscaresTest {
	/** Würfelt immer 0 = trifft. */
	static final class Always extends Random {
		@Override
		public int nextInt(int bound) {
			return 0;
		}
	}

	static final class Switches implements Jumpscares.Settings {
		boolean on = true;
		boolean sounds;

		@Override
		public boolean enabled() {
			return on;
		}

		@Override
		public boolean sounds() {
			return sounds;
		}
	}

	private static final long T0 = 1_000_000L;

	private static Jumpscares scares(Switches s) {
		Jumpscares j = new Jumpscares(new Always(), s);
		j.sessionStartForTest(T0);
		return j;
	}

	private static Jumpscares.Input in(long now) {
		Jumpscares.Input i = new Jumpscares.Input();
		i.now = now;
		i.inWorld = true;
		i.health = 20f;
		return i;
	}

	/** Nachts von vorne halten, bis gewürfelt wurde. */
	private static void front(Jumpscares j, long from) {
		// Erst zurück in die Ego-Sicht (je Umschalten wird einmal gewürfelt).
		j.tick(in(from - 50));
		for (long t = from; t <= from + 1500; t += 50) {
			Jumpscares.Input i = in(t);
			i.front = true;
			i.night = true;
			j.tick(i);
		}
	}

	@Test
	void neverInTheFirstTwoMinutes() {
		Jumpscares j = scares(new Switches());
		front(j, T0 + 60_000L);
		assertNull(j.current(T0 + 61_400L));
		assertFalse(j.allowed(T0 + Jumpscares.WARMUP_MS - 1, true));
		assertTrue(j.allowed(T0 + Jumpscares.WARMUP_MS, true));
	}

	@Test
	void behindYouAtNightInFrontView() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		front(j, t);
		Jumpscares.Scene s = j.current(t + 1250);
		assertNotNull(s);
		assertEquals(Jumpscares.Type.BEHIND_YOU, s.type);
		assertNull(j.current(t + 1250 + Jumpscares.BEHIND_MS + 100), "nur einen Herzschlag lang");
	}

	@Test
	void notAtDayAndNotFromBehind() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		for (long x = t; x < t + 3000; x += 50) {
			Jumpscares.Input i = in(x);
			i.front = true;
			j.tick(i);
		}
		assertNull(j.current(t + 3000));
	}

	@Test
	void atMostOnePerTwentyMinutes() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		front(j, t);
		assertNotNull(j.current(t + 1250));
		long later = t + 10 * 60_000L;
		front(j, later);
		assertNull(j.current(later + 1250));
		long after = t + 1200 + Jumpscares.COOLDOWN_MS + 1000;
		front(j, after);
		assertNotNull(j.current(after + 1250));
	}

	@Test
	void neverRightAfterDamageTakenOrDealt() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		Jumpscares.Input hurt = in(t);
		hurt.hurt = true;
		j.tick(hurt);
		assertFalse(j.allowed(t + Jumpscares.DAMAGE_PAUSE_MS - 1, true));
		assertTrue(j.allowed(t + Jumpscares.DAMAGE_PAUSE_MS, true));
		j.onDamageDealt(t + 20_000L);
		assertFalse(j.allowed(t + 25_000L, true));
		assertTrue(j.allowed(t + 30_000L, true));
	}

	@Test
	void neverUnderSixHearts() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		Jumpscares.Input low = in(t);
		low.health = 11.5f;
		j.tick(low);
		assertFalse(j.allowed(t, true));
		assertTrue(j.allowed(t, false), "Spiegel/Laden kennen kein Leben");
		Jumpscares.Input ok = in(t + 50);
		ok.health = 12f;
		j.tick(ok);
		assertTrue(j.allowed(t + 50, true));
	}

	@Test
	void switchedOffMeansNever() {
		Switches s = new Switches();
		s.on = false;
		Jumpscares j = scares(s);
		long t = T0 + 3 * 60_000L;
		front(j, t);
		assertNull(j.current(t + 1250));
		assertFalse(j.mirrorGrin(t));
		assertEquals(0f, j.loading(0.95f, t).dark, 1e-6);
	}

	@Test
	void chestOnlyInTheDarkAndOncePerSession() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		Jumpscares.Input bright = in(t);
		bright.containerOpened = true;
		j.tick(bright);
		assertNull(j.current(t + 10));
		Jumpscares.Input dark = in(t + 50);
		dark.containerOpened = true;
		dark.skyLight = 0;
		dark.blockLight = 2;
		j.tick(dark);
		Jumpscares.Scene s = j.current(t + 60);
		assertNotNull(s);
		assertEquals(Jumpscares.Type.CHEST, s.type);
		long again = t + Jumpscares.COOLDOWN_MS + 60_000L;
		Jumpscares.Input dark2 = in(again);
		dark2.containerOpened = true;
		dark2.skyLight = 0;
		dark2.blockLight = 0;
		j.tick(dark2);
		assertNull(j.current(again + 10), "höchstens einmal je Sitzung");
	}

	@Test
	void nightSkyCountsAsDark() {
		Jumpscares.Input i = new Jumpscares.Input();
		i.skyLight = 15;
		i.blockLight = 0;
		i.night = true;
		assertEquals(4, i.light());
		i.night = false;
		assertEquals(15, i.light());
	}

	@Test
	void caveNeedsTenSecondsOfLightZero() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		for (long x = t; x <= t + 9_000L; x += 50) {
			Jumpscares.Input i = in(x);
			i.skyLight = 0;
			i.blockLight = 0;
			j.tick(i);
		}
		assertNull(j.current(t + 9_000L));
		for (long x = t + 9_050L; x <= t + 11_100L; x += 50) {
			Jumpscares.Input i = in(x);
			i.skyLight = 0;
			i.blockLight = 0;
			j.tick(i);
		}
		Jumpscares.Scene s = j.current(t + 11_100L);
		assertNotNull(s);
		assertEquals(Jumpscares.Type.CAVE, s.type);
	}

	@Test
	void caveAbortsOnDamage() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		j.forceForTest(Jumpscares.Type.CAVE, t);
		assertNotNull(j.current(t + 100));
		Jumpscares.Input hurt = in(t + 200);
		hurt.hurt = true;
		j.tick(hurt);
		assertNull(j.current(t + 250));
	}

	@Test
	void loadingStallsAtNinetyNinePercent() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		assertEquals(0.4f, j.loading(0.4f, t).progress, 1e-6);
		Jumpscares.LoadingView v = j.loading(0.95f, t + 100);
		assertEquals(0.99f, v.progress, 1e-6);
		assertEquals(0f, v.dark, 1e-6);
		v = j.loading(1f, t + 100 + Jumpscares.STALL_MS + Jumpscares.DARK_MS);
		assertEquals(0.99f, v.progress, 1e-6);
		assertEquals(1f, v.dark, 1e-6);
		v = j.loading(1f, t + 100 + Jumpscares.STALL_MS + Jumpscares.DARK_MS + Jumpscares.EYES_OPEN_MS);
		assertEquals(1f, v.eyes, 1e-6);
		v = j.loading(1f, t + 100 + Jumpscares.STALL_MS + Jumpscares.DARK_MS + Jumpscares.EYES_OPEN_MS + Jumpscares.EYES_HOLD_MS + 50);
		assertEquals(1f, v.progress, 1e-6);
		assertEquals(0f, v.dark, 1e-6);
		assertFalse(j.allowed(t + 10_000L, false), "zählt für die 20 Minuten");
	}

	@Test
	void mirrorGrinsOnceForASecond() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 3 * 60_000L;
		assertFalse(j.mirrorGrin(t));
		boolean seen = false;
		long end = -1;
		for (long x = t; x < t + 8000; x += 50) {
			boolean g = j.mirrorGrin(x);
			if (g) seen = true;
			if (seen && !g && end < 0) end = x;
		}
		assertTrue(seen);
		assertTrue(end > 0);
		assertFalse(j.allowed(t + 9000, false));
	}

	@Test
	void soundOnlyWithTheSetting() {
		Switches s = new Switches();
		Jumpscares j = scares(s);
		long t = T0 + 3 * 60_000L;
		front(j, t);
		assertNull(j.takeSound(), "ohne Grusel-Töne kein Ton");
		s.sounds = true;
		front(j, t + Jumpscares.COOLDOWN_MS + 5000);
		assertNotNull(j.takeSound());
		assertNull(j.takeSound());
	}

	@Test
	void ownerTriggerPlaysSoundRegardlessOfTheSetting() {
		Switches s = new Switches();
		s.sounds = false;
		Jumpscares j = scares(s);
		long t = T0 + 10_000L;
		// In der Welt und bei voller Gesundheit melden (triggerOwner liest den letzten Tick-Zustand).
		j.tick(in(t));
		assertTrue(j.triggerOwner(Jumpscares.Type.CHEST, t), "Besitzer-Auslöser kennt keinen 2-Minuten-Anlauf");
		Jumpscares.Scene sc = j.current(t + 50);
		assertNotNull(sc);
		assertEquals(Jumpscares.Type.CHEST, sc.type);
		assertEquals(Jumpscares.SOUND_SCREAM, j.takeSound(), "Besitzer-Ton trotz Schalter aus");
	}

	@Test
	void ownerTriggerRespectsSafety() {
		Switches s = new Switches();
		Jumpscares j = scares(s);
		long t = T0 + 10_000L;
		Jumpscares.Input low = in(t);
		low.health = 11f;
		j.tick(low);
		assertFalse(j.triggerOwner(Jumpscares.Type.CHEST, t), "unter 6 Herzen nie");
		Jumpscares.Input hurt = in(t + 50);
		hurt.hurt = true;
		j.tick(hurt);
		assertFalse(j.triggerOwner(Jumpscares.Type.CHEST, t + 100), "nicht im Kampf");
		j.tick(in(t + Jumpscares.DAMAGE_PAUSE_MS + 100));
		assertTrue(j.triggerOwner(Jumpscares.Type.CHEST, t + Jumpscares.DAMAGE_PAUSE_MS + 100));
	}

	@Test
	void kindMapping() {
		// Genau die vier Namen, die der Server schickt.
		assertEquals(Jumpscares.Type.FACE_PRESS, Jumpscares.typeFromKind("face"));
		assertEquals(Jumpscares.Type.WHISPER_CRAWL, Jumpscares.typeFromKind("whisper"));
		assertEquals(Jumpscares.Type.THIRTEENTH, Jumpscares.typeFromKind("eye"));
		assertEquals(Jumpscares.Type.FLASH, Jumpscares.typeFromKind("FLASH"));
		assertNull(Jumpscares.typeFromKind("mirror"), "Spiegel ist kein Fernauslöser");
		assertNull(Jumpscares.typeFromKind("nope"));
	}

	@Test
	void ownerTriggerHasSixtySecondCooldown() {
		Jumpscares j = scares(new Switches());
		long t = T0 + 10_000L;
		j.tick(in(t));
		assertTrue(j.triggerOwner(Jumpscares.Type.FACE_PRESS, t));
		// Szene läuft ab, aber der 60-s-Abstand hält.
		j.tick(in(t + 2000));
		assertFalse(j.triggerOwner(Jumpscares.Type.WHISPER_CRAWL, t + 2000));
		long later = t + Jumpscares.OWNER_COOLDOWN_MS + 10;
		j.tick(in(later));
		assertTrue(j.triggerOwner(Jumpscares.Type.WHISPER_CRAWL, later));
	}
}

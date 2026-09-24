package dev.theredstonee.trsclient.core.emote;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Icons;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Emote-Definitionen, Schlüsselbilder, Posen-Mathematik, Wiedergabe und Rad-Geometrie. */
class EmoteTest {
	private static final float EPS = 1e-4f;

	// --- Definitionen ---

	@Test
	void everyApiEmoteHasAnAnimationInApiOrder() {
		// api/server/lib/emotes.ts: IDs, Dauer und Schleife
		String[][] api = {
				{"winken", "2000", "false"}, {"klatschen", "2500", "false"}, {"jubeln", "2500", "false"},
				{"verbeugen", "2000", "false"}, {"facepalm", "2000", "false"}, {"schulterzucken", "1500", "false"},
				{"daumen_hoch", "1500", "false"}, {"tanzen", "6000", "true"}, {"salutieren", "2000", "false"},
				{"luftgitarre", "5000", "true"}, {"redstone_tanz", "6000", "true"}};
		assertEquals(api.length, Emotes.ALL.size());
		for (int i = 0; i < api.length; i++) {
			EmoteDef d = Emotes.ALL.get(i);
			assertEquals(api[i][0], d.id());
			assertEquals(Integer.parseInt(api[i][1]), d.durationMs(), d.id());
			assertEquals(Boolean.parseBoolean(api[i][2]), d.loop(), d.id());
			assertTrue(Icons.has(d.icon()), "Symbol fehlt: " + d.icon());
			assertTrue(d.cycleMs() > 0 && d.cycleMs() <= d.durationMs(), d.id());
			assertFalse(d.tracks().isEmpty());
			assertTrue(I18n.has(d.nameKey()), "Übersetzung fehlt: " + d.nameKey());
			assertTrue(Emotes.byId(d.id()) == d);
		}
		assertNull(Emotes.byId("gibt_es_nicht"), "unbekannte IDs werden ignoriert");
		assertNull(Emotes.byId(null));
	}

	@Test
	void animationsStayInAPlausibleRange() {
		float[] frame = new float[Channel.COUNT];
		for (EmoteDef d : Emotes.ALL) {
			for (int t = 0; t <= d.durationMs(); t += 25) {
				d.sample(t, frame);
				for (int ch = 0; ch < Channel.COUNT; ch++) {
					assertFalse(Float.isNaN(frame[ch]), d.id() + " Kanal " + ch);
					if (Channel.isOffset(ch)) assertTrue(Math.abs(frame[ch]) <= 4f, d.id() + " Verschiebung " + ch);
					else assertTrue(Math.abs(frame[ch]) <= Math.PI * 1.01, d.id() + " Winkel " + ch);
				}
			}
		}
	}

	@Test
	void loopsRepeatSeamlessly() {
		float[] a = new float[Channel.COUNT];
		float[] b = new float[Channel.COUNT];
		for (EmoteDef d : Emotes.ALL) {
			if (!d.loop()) continue;
			d.sample(0, a);
			d.sample(d.cycleMs(), b);
			assertArrayEquals(a, b, EPS, d.id() + ": Anfang = Ende eines Durchgangs");
			d.sample(d.cycleMs() - 1, b);
			for (int ch = 0; ch < Channel.COUNT; ch++) {
				assertTrue(Math.abs(a[ch] - b[ch]) < 0.1f, d.id() + ": kein Sprung am Schleifenende, Kanal " + ch);
			}
		}
	}

	@Test
	void masksFollowTheTracks() {
		EmoteDef wave = Emotes.byId("winken");
		assertEquals(Channel.MASK_RIGHT_ARM, wave.mask(), "Winken führt nur den rechten Arm");
		EmoteDef dance = Emotes.byId("tanzen");
		assertEquals(Channel.MASK_RIGHT_ARM | Channel.MASK_LEFT_ARM | Channel.MASK_RIGHT_LEG | Channel.MASK_LEFT_LEG,
				dance.mask());
	}

	// --- Schlüsselbilder ---

	@Test
	void tracksInterpolateSmoothLinearAndSnap() {
		Track smooth = new Track(0, new int[] {0, 100}, new float[] {0f, 1f}, Track.Ease.SMOOTH);
		assertEquals(0f, smooth.sample(-5, 0), EPS);
		assertEquals(0.5f, smooth.sample(50, 0), EPS);
		assertTrue(smooth.sample(10, 0) < 0.1f, "weich: langsamer Anfang");
		assertEquals(1f, smooth.sample(500, 0), EPS);
		Track linear = new Track(0, new int[] {0, 100}, new float[] {0f, 1f}, Track.Ease.LINEAR);
		assertEquals(0.1f, linear.sample(10, 0), EPS);
		Track snap = new Track(0, new int[] {0, 100}, new float[] {0f, 1f}, Track.Ease.SNAP);
		assertEquals(1f, snap.sample(40, 0), EPS, "zackig: nach 30 % am Ziel");
		// Schleife 200 ms: nach dem letzten Bild (100) zurück zum ersten.
		assertEquals(0.5f, linear.sample(150, 200), EPS);
		assertEquals(0f, linear.sample(200, 200), EPS);
		Track single = new Track(0, new int[] {0}, new float[] {3f}, Track.Ease.SMOOTH);
		assertEquals(3f, single.sample(1234, 500), EPS);
		assertThrows(IllegalArgumentException.class,
				() -> new Track(0, new int[] {10, 10}, new float[] {0f, 1f}, Track.Ease.SMOOTH));
	}

	@Test
	void builderConvertsDegreesAndRejectsDuplicates() {
		EmoteDef d = EmoteDef.builder("test", 1000, false, 0, "wave")
				.hold(Channel.R_ARM_X, -90)
				.hold(Channel.ROOT_Y, 2)
				.build();
		float[] f = new float[Channel.COUNT];
		d.sample(500, f);
		assertEquals((float) (-Math.PI / 2), f[Channel.R_ARM_X], EPS);
		assertEquals(2f, f[Channel.ROOT_Y], EPS, "Verschiebungen bleiben Pixel");
		assertThrows(IllegalArgumentException.class, () -> EmoteDef.builder("x", 1000, false, 0, "wave")
				.hold(Channel.HEAD_X, 1).hold(Channel.HEAD_X, 2));
	}

	@Test
	void weightFadesInAndOut() {
		assertEquals(0f, EmoteDef.weight(0, 2000), EPS);
		assertEquals(1f, EmoteDef.weight(1000, 2000), EPS);
		assertEquals(0f, EmoteDef.weight(2000, 2000), EPS);
		assertTrue(EmoteDef.weight(EmoteDef.FADE_IN_MS / 2f, 2000) > 0f);
		assertTrue(EmoteDef.weight(EmoteDef.FADE_IN_MS / 2f, 2000) < 1f);
		assertTrue(EmoteDef.weight(2000 - EmoteDef.FADE_OUT_MS / 2f, 2000) < 1f);
		float last = 0;
		for (int t = 0; t <= EmoteDef.FADE_IN_MS; t += 10) {
			float w = EmoteDef.weight(t, 2000);
			assertTrue(w >= last);
			last = w;
		}
	}

	// --- Posen-Mathematik ---

	/** Vanilla-Grundhaltung eines stehenden Spielers (Drehpunkte wie PlayerModel). */
	static float[] standing() {
		float[] p = new float[EmoteRig.PARTS * EmoteRig.STRIDE];
		set(p, EmoteRig.HEAD, 0, 0, 0);
		set(p, EmoteRig.BODY, 0, 0, 0);
		set(p, EmoteRig.RIGHT_ARM, -5, 2, 0);
		set(p, EmoteRig.LEFT_ARM, 5, 2, 0);
		set(p, EmoteRig.RIGHT_LEG, -1.9f, 12, 0);
		set(p, EmoteRig.LEFT_LEG, 1.9f, 12, 0);
		return p;
	}

	private static void set(float[] p, int part, float x, float y, float z) {
		p[part * EmoteRig.STRIDE] = x;
		p[part * EmoteRig.STRIDE + 1] = y;
		p[part * EmoteRig.STRIDE + 2] = z;
	}

	private static float v(float[] p, int part, int i) {
		return p[part * EmoteRig.STRIDE + i];
	}

	@Test
	void neutralPoseChangesNothing() {
		float[] parts = standing();
		parts[EmoteRig.HEAD * 6 + 4] = 0.4f; // Blick nach rechts
		parts[EmoteRig.RIGHT_ARM * 6 + 3] = 0.2f; // Arm schwingt
		float[] before = parts.clone();
		new EmoteRig().apply(parts, new float[Channel.COUNT], 0, 1f);
		assertArrayEquals(before, parts, EPS);
	}

	@Test
	void bowPivotsAtTheHips() {
		float[] parts = standing();
		float[] frame = new float[Channel.COUNT];
		frame[Channel.TORSO_LEAN] = (float) (Math.PI / 2);
		frame[Channel.R_ARM_X] = -0.5f;
		new EmoteRig().apply(parts, frame, Channel.MASK_RIGHT_ARM, 1f);
		// Hals wandert nach vorn (−z) auf Hüfthöhe, die Hüfte (0, 12, 0) bleibt stehen.
		assertEquals(0f, v(parts, EmoteRig.BODY, 0), EPS);
		assertEquals(12f, v(parts, EmoteRig.BODY, 1), EPS);
		assertEquals(-12f, v(parts, EmoteRig.BODY, 2), EPS);
		assertEquals((float) (Math.PI / 2), v(parts, EmoteRig.BODY, 3), EPS);
		// Kopf sitzt auf dem Hals und ist mit vorgebeugt.
		assertEquals(-12f, v(parts, EmoteRig.HEAD, 2), EPS);
		assertEquals((float) (Math.PI / 2), v(parts, EmoteRig.HEAD, 3), EPS);
		// Schulter (−5, 2) liegt jetzt 2 Pixel vor dem Hals, Arm-Winkel = Oberkörper + eigener Winkel.
		assertEquals(-5f, v(parts, EmoteRig.RIGHT_ARM, 0), EPS);
		assertEquals(12f, v(parts, EmoteRig.RIGHT_ARM, 1), EPS);
		assertEquals(-10f, v(parts, EmoteRig.RIGHT_ARM, 2), EPS);
		assertEquals((float) (Math.PI / 2 - 0.5), v(parts, EmoteRig.RIGHT_ARM, 3), EPS);
		// Beine bleiben.
		assertEquals(12f, v(parts, EmoteRig.LEFT_LEG, 1), EPS);
		assertEquals(0f, v(parts, EmoteRig.LEFT_LEG, 3), EPS);
	}

	@Test
	void armsNotLedByTheEmoteKeepVanillaAnglesButFollowTheTorso() {
		float[] parts = standing();
		parts[EmoteRig.LEFT_ARM * 6 + 3] = 0.3f;
		float[] frame = new float[Channel.COUNT];
		frame[Channel.R_ARM_X] = -2f;
		frame[Channel.L_ARM_X] = -1f; // wird ignoriert: linker Arm nicht in der Maske
		new EmoteRig().apply(parts, frame, Channel.MASK_RIGHT_ARM, 1f);
		assertEquals(-2f, v(parts, EmoteRig.RIGHT_ARM, 3), EPS);
		assertEquals(0.3f, v(parts, EmoteRig.LEFT_ARM, 3), EPS);
	}

	@Test
	void rootAndShouldersMoveParts() {
		float[] parts = standing();
		float[] frame = new float[Channel.COUNT];
		frame[Channel.ROOT_Y] = 2f;
		frame[Channel.SHOULDERS] = 1.5f;
		new EmoteRig().apply(parts, frame, 0, 1f);
		assertEquals(-2f, v(parts, EmoteRig.BODY, 1), EPS, "ROOT_Y positiv = nach oben (Modell-y nach unten)");
		assertEquals(10f, v(parts, EmoteRig.LEFT_LEG, 1), EPS);
		assertEquals(2f - 2f - 1.5f, v(parts, EmoteRig.RIGHT_ARM, 1), EPS, "Schultern hochgezogen");
	}

	@Test
	void weightBlendsBetweenVanillaAndEmote() {
		float[] parts = standing();
		float[] frame = new float[Channel.COUNT];
		frame[Channel.R_ARM_X] = -2f;
		frame[Channel.R_ARM_Z] = 0.4f;
		new EmoteRig().apply(parts, frame, Channel.MASK_RIGHT_ARM, 0.5f);
		assertEquals(-1f, v(parts, EmoteRig.RIGHT_ARM, 3), EPS);
		assertEquals(0.2f, v(parts, EmoteRig.RIGHT_ARM, 5), EPS);
		float[] untouched = standing();
		new EmoteRig().apply(untouched, frame, Channel.MASK_RIGHT_ARM, 0f);
		assertArrayEquals(standing(), untouched, EPS);
	}

	@Test
	void armsOverTheTopDoNotFlipWhenBlending() {
		float[] parts = standing();
		float[] frame = new float[Channel.COUNT];
		frame[Channel.R_ARM_X] = (float) -Math.PI; // senkrecht nach oben
		new EmoteRig().apply(parts, frame, Channel.MASK_RIGHT_ARM, 0.5f);
		assertEquals((float) (-Math.PI / 2), v(parts, EmoteRig.RIGHT_ARM, 3), 1e-3f, "vorn herum, nicht über den Rücken");
	}

	@Test
	void eulerRoundTripMatchesMinecraftOrder() {
		Random r = new Random(7);
		float[] m = new float[9];
		float[] back = new float[9];
		float[] e = new float[3];
		for (int i = 0; i < 500; i++) {
			float x = (r.nextFloat() - 0.5f) * 6f;
			float y = (r.nextFloat() - 0.5f) * 3f;
			float z = (r.nextFloat() - 0.5f) * 6f;
			EmoteRig.rotZYX(z, y, x, m);
			EmoteRig.toEuler(m, e);
			EmoteRig.rotZYX(e[2], e[1], e[0], back);
			assertArrayEquals(m, back, 1e-3f);
		}
		// Rz·Ry·Rx: erst um x, dann y, dann z – ein nach vorn (−z) gehobener Arm zeigt nach Ry(−90°) zur Mitte (+x).
		EmoteRig.rotZYX(0f, (float) (-Math.PI / 2), (float) (-Math.PI / 2), m);
		float[] out = new float[3];
		EmoteRig.mul(m, 0f, 1f, 0f, out);
		assertArrayEquals(new float[] {1f, 0f, 0f}, out, 1e-5f);
	}

	@Test
	void everyEmoteProducesFinitePoses() {
		EmoteRig rig = new EmoteRig();
		float[] frame = new float[Channel.COUNT];
		for (EmoteDef d : Emotes.ALL) {
			for (int t = 0; t < d.durationMs(); t += 50) {
				float[] parts = standing();
				d.sample(t, frame);
				rig.apply(parts, frame, d.mask(), EmoteDef.weight(t, d.durationMs()));
				for (float f : parts) assertTrue(Float.isFinite(f), d.id() + " @" + t);
				// Füße bleiben unten: Beine höchstens um die Sprunghöhe verschoben.
				assertTrue(Math.abs(v(parts, EmoteRig.LEFT_LEG, 1) - 12f) <= 4f, d.id());
			}
		}
	}

	// --- Wiedergabe ---

	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

	private static List<EmotePlayback.Mover> at(UUID u, double x, double z, boolean crouch) {
		return Collections.singletonList(new EmotePlayback.Mover(u, x, z, crouch, false));
	}

	@Test
	void playbackRunsForTheDurationThenEnds() {
		EmotePlayback p = new EmotePlayback();
		EmoteDef wave = Emotes.byId("winken");
		p.start(A, wave, 0, 1000);
		float[] frame = new float[Channel.COUNT];
		assertEquals(1f, p.sample(A, 2000, frame), EPS);
		assertTrue(frame[Channel.R_ARM_X] < -2f, "Arm oben");
		assertTrue(p.playing(A, 2000));
		assertEquals(0f, p.sample(B, 2000, frame), EPS);
		p.tick(3000, null);
		assertEquals(0, p.size(), "nach 2000 ms vorbei");
		assertFalse(p.playing(A, 3000));
		// Dauer aus der API hat Vorrang.
		p.start(A, wave, 500, 0);
		p.tick(600, null);
		assertEquals(0, p.size());
	}

	@Test
	void movingOrSneakingEndsTheEmoteAfterTheGracePeriod() {
		EmotePlayback p = new EmotePlayback();
		EmoteDef dance = Emotes.byId("tanzen");
		p.start(A, dance, 0, 0);
		p.tick(50, at(A, 0, 0, false));
		p.tick(100, at(A, 0.2, 0, false)); // Ausrollen innerhalb der Schonfrist
		assertTrue(p.playing(A, 100));
		p.tick(400, at(A, 0.23, 0.02, false)); // 0,036 Blöcke → noch stehen
		assertTrue(p.playing(A, 400));
		p.tick(450, at(A, 0.23, 0.1, false)); // 0,08 Blöcke/Tick → Abbruch
		assertFalse(p.playing(A, 450));
		float[] frame = new float[Channel.COUNT];
		assertTrue(p.sample(A, 500, frame) > 0f, "blendet kurz aus");
		p.tick(450 + EmotePlayback.STOP_FADE_MS, null);
		assertEquals(0, p.size());

		p.start(B, dance, 0, 0);
		p.tick(300, at(B, 5, 5, true));
		assertFalse(p.playing(B, 300), "Schleichen beendet das Emote");
	}

	@Test
	void stopRetainAndLimits() {
		EmotePlayback p = new EmotePlayback();
		EmoteDef bow = Emotes.byId("verbeugen");
		p.start(A, bow, 0, 0);
		p.start(B, bow, 0, 0);
		p.retainOnly(A);
		assertEquals(1, p.size());
		assertNotNull(p.current(A));
		p.stop(A, 100);
		assertFalse(p.playing(A, 100));
		p.start(null, bow, 0, 0);
		p.start(A, null, 0, 0);
		List<UUID> many = new ArrayList<>();
		for (int i = 0; i < EmotePlayback.MAX_ACTIVE + 20; i++) many.add(new UUID(1, i));
		for (UUID u : many) p.start(u, bow, 0, 200);
		assertTrue(p.size() <= EmotePlayback.MAX_ACTIVE);
	}

	// --- Rad ---

	@Test
	void wheelSelectsByDirection() {
		int n = 11;
		assertEquals(-1, WheelMath.select(3, 3, n, 10), "tote Zone");
		assertEquals(0, WheelMath.select(0, -50, n, 10), "oben = erstes Emote");
		assertEquals(0, WheelMath.select(-5, -80, n, 10), "knapp links von oben");
		assertEquals(3, WheelMath.select(50, 0, n, 10), "rechts ≈ Platz 3 (90° / 32,7°)");
		assertEquals(n - 1, WheelMath.select(-20, -50, n, 10), "links oben = letztes");
		assertEquals(-1, WheelMath.select(10, 10, 0, 1), "ohne Plätze nichts");
		// Jede Richtung zu einem Platz trifft genau diesen Platz.
		for (int i = 0; i < n; i++) {
			int x = WheelMath.slotX(0, 100, i, n);
			int y = WheelMath.slotY(0, 100, i, n);
			assertEquals(i, WheelMath.select(x, y, n, 10));
		}
		assertEquals(0, WheelMath.slotX(100, 50, 0, 4) - 100);
		assertEquals(50, WheelMath.slotY(100, 50, 0, 4));
		assertEquals(150, WheelMath.slotX(100, 50, 1, 4));
	}

	@Test
	void wheelRadiusFitsAndDoesNotOverlap() {
		int[][] screens = {{427, 240}, {854, 480}, {320, 240}, {1920, 1080}};
		for (int[] s : screens) {
			int slot = Math.min(s[0], s[1]) >= 300 ? 28 : 22;
			int r = WheelMath.radius(s[0], s[1], 11, slot);
			assertTrue(r + slot / 2 + 12 <= Math.min(s[0], s[1]) / 2 + 1, Arrays.toString(s) + " passt: r=" + r);
			double chord = 2 * r * Math.sin(Math.PI / 11);
			if (Math.min(s[0], s[1]) >= 240) assertTrue(chord >= slot, Arrays.toString(s) + " keine Überlappung: r=" + r);
		}
	}
}

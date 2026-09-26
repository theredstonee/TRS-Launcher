package dev.theredstonee.trsclient.core.cosmetic;

import dev.theredstonee.trsclient.core.online.HatInfo;
import dev.theredstonee.trsclient.core.online.OnlineConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quietscheente: Vorlage, Mesh (Umlaufsinn, UVs, Lage auf dem Kopf) und Verhalten des Rigs. */
class DuckTest {
	static final OnlineConfig CONFIG = new OnlineConfig(true, "https://trs-launcher.theredstonee.de", "https://sessionserver.mojang.com");

	static final class Quad {
		final float[] p = new float[12];
		final float[] uv = new float[8];
		final float[] n = new float[3];
	}

	static List<Quad> mesh(DuckRig.Pose pose, boolean helmet) {
		final List<Quad> out = new ArrayList<>();
		final int[] k = { 0 };
		new CosmeticMesh().emit(CosmeticModels.get("duck"), pose, 0L, helmet, (x, y, z, u, v, nx, ny, nz) -> {
			if (k[0] % 4 == 0) out.add(new Quad());
			Quad q = out.get(out.size() - 1);
			int i = k[0] % 4;
			q.p[i * 3] = x;
			q.p[i * 3 + 1] = y;
			q.p[i * 3 + 2] = z;
			q.uv[i * 2] = u;
			q.uv[i * 2 + 1] = v;
			q.n[0] = nx;
			q.n[1] = ny;
			q.n[2] = nz;
			k[0]++;
		});
		return out;
	}

	@Test
	void bundledTemplateParses() {
		CosmeticModel m = CosmeticModels.get("duck");
		assertNotNull(m);
		assertEquals("duck", m.rig);
		assertEquals(9, m.cubes.size());
		assertEquals(64, m.textureWidth);
		assertEquals(2f, m.neckZ);
		assertNull(CosmeticModels.get("crown"), "andere Kopf-Kosmetik bleibt vorerst unsichtbar");
	}

	@Test
	void rejectsBrokenTemplates() {
		String head = "{\"id\":\"x\",\"kind\":\"model\",\"slot\":\"hat\",\"textureWidth\":64,\"textureHeight\":32,";
		assertThrows(IllegalArgumentException.class, () -> CosmeticModel.parse(
				"{\"id\":\"x\",\"kind\":\"model\",\"slot\":\"wings\",\"textureWidth\":64,\"textureHeight\":32,\"cubes\":[]}"));
		assertThrows(IllegalArgumentException.class, () -> CosmeticModel.parse(head
				+ "\"cubes\":[{\"from\":[0,0,0],\"to\":[1,1,1],\"uv\":[0,0],\"attach\":\"head\",\"anim\":\"look\"}]}"));
		assertThrows(IllegalArgumentException.class, () -> CosmeticModel.parse(head
				+ "\"cubes\":[{\"from\":[0,0,0],\"to\":[1,1,1],\"uv\":[0,0],\"attach\":\"head\",\"anim\":\"dance\"}]}"));
	}

	@Test
	void hatInfoOnlyForKnownTemplatesAndApiUrls() {
		String url = "https://trs-launcher.theredstonee.de/v1/cosmetics/rubber_duck.png?v=1";
		assertNotNull(HatInfo.of("rubber_duck", "duck", url, 2, 1, null, CONFIG));
		assertNull(HatInfo.of("crown", "crown", url, 2, 1, null, CONFIG));
		assertNull(HatInfo.of("rubber_duck", "duck", "https://evil.example/duck.png", 2, 1, null, CONFIG));
		assertEquals("cos-rubber_duck", HatInfo.of("rubber_duck", "duck", url, 2, 1, null, CONFIG).texture.id);
	}

	@Test
	void meshSitsOnTheHeadWithOutwardQuadsAndValidUvs() {
		List<Quad> quads = mesh(null, false);
		assertEquals(9 * 6, quads.size());
		float minY = Float.MAX_VALUE;
		for (Quad q : quads) {
			for (int i = 0; i < 4; i++) {
				assertTrue(q.uv[i * 2] >= 0f && q.uv[i * 2] <= 1f && q.uv[i * 2 + 1] >= 0f && q.uv[i * 2 + 1] <= 1f);
				minY = Math.min(minY, -q.p[i * 3 + 1]);
			}
			// (p1 − p0) × (p2 − p0) zeigt nach außen (wie Vanilla)
			float ax = q.p[3] - q.p[0], ay = q.p[4] - q.p[1], az = q.p[5] - q.p[2];
			float bx = q.p[6] - q.p[0], by = q.p[7] - q.p[1], bz = q.p[8] - q.p[2];
			float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
			assertTrue(cx * q.n[0] + cy * q.n[1] + cz * q.n[2] > 0f);
		}
		// Unterseite auf der Hut-Schicht: Kopf oben = 8 px, +0,5 px
		assertEquals((8f + CosmeticMesh.HAT_LIFT) / 16f, minY, 1e-5f);
		float withHelmet = Float.MAX_VALUE;
		for (Quad q : mesh(null, true)) for (int i = 0; i < 4; i++) withHelmet = Math.min(withHelmet, -q.p[i * 3 + 1]);
		assertEquals((8f + CosmeticMesh.HELMET_LIFT) / 16f, withHelmet, 1e-5f);
	}

	@Test
	void beakFacesForward() {
		// Oberschnabel (Würfel 2), Fläche 0 = vorne: im ModelPart-Raum ist vorne −z.
		Quad beakFront = mesh(null, false).get(2 * 6);
		assertEquals(-6f / 16f, beakFront.p[2], 1e-5f);
		assertEquals(-1f, beakFront.n[2], 1e-5f);
	}

	static Wearer standing() {
		return new Wearer().set(1, 0f, 0f, true, false, false, false, 0f, 0f, false, false);
	}

	static DuckRig.State run(DuckRig.State s, Wearer w, float seconds) {
		for (float t = 0; t < seconds; t += 0.016f) DuckRig.step(s, w, 0.016f);
		return s;
	}

	@Test
	void waddlesWhileWalkingAndBobsWhenIdle() {
		DuckRig.State s = run(new DuckRig.State(1), standing(), 1f);
		float maxIdleRoll = 0f;
		for (int i = 0; i < 200; i++) {
			DuckRig.step(s, standing(), 0.016f);
			maxIdleRoll = Math.max(maxIdleRoll, Math.abs(s.pose.roll));
		}
		assertTrue(maxIdleRoll < 2f, "im Stand nur sanft");
		Wearer walking = standing();
		walking.speed = 0.215f;
		run(s, walking, 1f);
		float maxRoll = 0f;
		float maxBob = 0f;
		for (int i = 0; i < 100; i++) {
			DuckRig.step(s, walking, 0.016f);
			maxRoll = Math.max(maxRoll, Math.abs(s.pose.roll));
			maxBob = Math.max(maxBob, s.pose.bob);
		}
		assertTrue(maxRoll > 5f && maxRoll <= 7.01f, "watschelt: " + maxRoll);
		assertTrue(maxBob > 0.5f);
	}

	@Test
	void flapsInTheAirAndSquashesOnLanding() {
		DuckRig.State s = run(new DuckRig.State(1), standing(), 0.5f);
		assertEquals(0f, s.pose.wing, 0.5f);
		Wearer air = standing();
		air.onGround = false;
		air.vy = -0.5f;
		float maxWing = 0f;
		for (int i = 0; i < 40; i++) {
			DuckRig.step(s, air, 0.016f);
			maxWing = Math.max(maxWing, s.pose.wing);
		}
		assertTrue(maxWing > 30f, "Flügel: " + maxWing);
		float minSquash = 1f;
		for (int i = 0; i < 30; i++) {
			DuckRig.step(s, standing(), 0.016f);
			minSquash = Math.min(minSquash, s.pose.squash);
		}
		assertTrue(minSquash < 0.95f, "Landung staucht: " + minSquash);
		run(s, standing(), 2f);
		assertEquals(1f, s.pose.squash, 0.02f);
		assertEquals(0f, s.pose.wing, 0.5f);
	}

	@Test
	void lagsBehindFastTurnsAndSpringsBack() {
		Wearer w = standing();
		DuckRig rig = new DuckRig();
		rig.update(w, 0L);
		// Spieler dreht schlagartig 90° nach rechts
		w.headYaw = 90f;
		DuckRig.Pose p = rig.update(w, 16_000_000L);
		assertTrue(p.yaw > 10f, "Ente hängt links hinterher: " + p.yaw);
		long t = 16_000_000L;
		for (int i = 0; i < 120; i++) p = rig.update(w, t += 16_000_000L);
		assertTrue(Math.abs(p.yaw) < 3f, "federt zurück: " + p.yaw);
	}

	@Test
	void quacksWhenSneakingStartsAndBlinks() {
		DuckRig.State s = run(new DuckRig.State(3), standing(), 0.2f);
		Wearer sneak = standing();
		sneak.sneaking = true;
		float maxBeak = 0f;
		for (int i = 0; i < 25; i++) {
			DuckRig.step(s, sneak, 0.016f);
			maxBeak = Math.max(maxBeak, s.pose.beak);
		}
		assertTrue(maxBeak > 20f, "quakt: " + maxBeak);
		float minEye = 1f;
		for (int i = 0; i < 500; i++) {
			DuckRig.step(s, sneak, 0.016f);
			minEye = Math.min(minEye, s.pose.eyeOpen);
		}
		assertTrue(minEye < 0.3f, "blinzelt innerhalb von 8 s");
	}

	@Test
	void forgetsUnusedWearers() {
		DuckRig rig = new DuckRig();
		rig.update(standing(), 0L);
		Wearer other = standing();
		other.entityId = 2;
		rig.update(other, 5_000_000_000L);
		rig.update(other, DuckRig.FORGET_NANOS + 3_000_000_000L);
		assertEquals(1, rig.size());
	}
}

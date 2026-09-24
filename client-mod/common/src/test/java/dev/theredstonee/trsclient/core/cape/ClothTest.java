package dev.theredstonee.trsclient.core.cape;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Umhang-Physik: Stoff-Simulation, Detailstufen, Mesh mit UVs. */
class ClothTest {

	static ClothSim.Motion still() {
		return new ClothSim.Motion();
	}

	static void run(ClothSim sim, ClothSim.Motion m, int ticks) {
		ClothSim.Params p = new ClothSim.Params();
		for (int i = 0; i < ticks; i++) {
			m.time = i * 0.05f;
			sim.tick(m, p);
		}
	}

	/** Mittlerer Abstand der untersten Reihe vom Rücken (z). */
	static float hemZ(ClothSim sim) {
		float sum = 0;
		for (int i = 0; i <= sim.cols(); i++) sum += sim.point(i, sim.rows())[2];
		return sum / (sim.cols() + 1);
	}

	static float hemX(ClothSim sim) {
		float sum = 0;
		for (int i = 0; i <= sim.cols(); i++) sum += sim.point(i, sim.rows())[0];
		return sum / (sim.cols() + 1);
	}

	@Test
	void standingStillTheCapeHangsDownAndStaysBehindTheBack() {
		ClothSim sim = new ClothSim(10, 16);
		run(sim, still(), 200);
		for (int j = 0; j <= 16; j++) {
			for (int i = 0; i <= 10; i++) {
				float[] p = sim.point(i, j);
				assertTrue(p[2] >= ClothSim.HALF_THICKNESS - 1e-3f, "nie im Körper: " + p[2]);
			}
		}
		float[] hem = sim.point(5, 16);
		assertTrue(hem[1] > 14f, "hängt fast senkrecht (y=" + hem[1] + ")");
		assertTrue(hemZ(sim) < 4f, "liegt nah am Rücken (z=" + hemZ(sim) + ")");
		assertEquals(0f, hemX(sim), 0.3f, "symmetrisch");
		assertTrue(sim.healthy());
	}

	@Test
	void pinnedRowStaysAtTheShoulders() {
		ClothSim sim = new ClothSim(10, 16);
		ClothSim.Motion m = still();
		m.dz = -4f;
		m.dYaw = 0.3f;
		run(sim, m, 30);
		for (int i = 0; i <= 10; i++) {
			float[] p = sim.point(i, 0);
			assertEquals(5f - i, p[0], 1e-4f);
			assertEquals(0f, p[1], 1e-4f);
		}
	}

	@Test
	void runningForwardBlowsTheCapeBack() {
		ClothSim sim = new ClothSim(10, 16);
		run(sim, still(), 60);
		float rest = hemZ(sim);
		ClothSim.Motion run = still();
		run.dz = -0.28f * 16f; // Sprinten: ≈ 5,6 Blöcke/s nach vorn = −z
		run(sim, run, 60);
		float running = hemZ(sim);
		assertTrue(running > rest + 4f, "weht nach hinten: " + rest + " → " + running);
		ClothSim.Params calm = new ClothSim.Params();
		calm.lift = 0f;
		ClothSim noLift = new ClothSim(10, 16);
		for (int i = 0; i < 60; i++) noLift.tick(run, calm);
		assertTrue(hemZ(noLift) < running, "ohne Anhebung weniger Auftrieb");
	}

	@Test
	void turningSwingsTheCapeSideways() {
		ClothSim sim = new ClothSim(10, 16);
		run(sim, still(), 40);
		ClothSim.Motion turn = still();
		turn.dYaw = (float) Math.toRadians(20); // schnelle Rechtsdrehung
		ClothSim.Params p = new ClothSim.Params();
		for (int i = 0; i < 3; i++) sim.tick(turn, p);
		assertTrue(hemX(sim) < -0.5f, "Saum bleibt in der Welt zurück → rechts (−x): " + hemX(sim));
		run(sim, still(), 200);
		assertEquals(0f, hemX(sim), 0.5f, "pendelt zurück");
	}

	@Test
	void fallingMakesItFlutterUp() {
		ClothSim sim = new ClothSim(10, 16);
		run(sim, still(), 40);
		float restY = sim.point(5, 16)[1];
		ClothSim.Motion fall = still();
		fall.dy = 1.5f * 16f; // 1,5 Blöcke pro Tick nach unten
		run(sim, fall, 10);
		assertTrue(sim.point(5, 16)[1] < restY - 3f, "Saum hebt sich beim Fallen");
		assertTrue(sim.healthy());
	}

	@Test
	void sneakingBendsTheCapeAwayFromTheLegs() {
		ClothSim sim = new ClothSim(10, 16);
		ClothSim.Motion sneak = still();
		sneak.tilt = 0.5f;
		run(sim, sneak, 120);
		// Vorgebeugt liegt der Umhang auf dem Rücken und knickt an der Hüfte ab: darunter hängt er in der Welt
		// senkrecht, im gekippten Körper-System also nach vorn (−z) an den Beinen entlang.
		for (int j = 0; j <= 11; j++) assertEquals(ClothSim.HALF_THICKNESS, sim.point(5, j)[2], 0.6f, "Reihe " + j);
		float hip = sim.point(5, 12)[2];
		assertTrue(hemZ(sim) < hip - 1f, "knickt an der Hüfte: Hüfte " + hip + ", Saum " + hemZ(sim));
		ClothSim upright = new ClothSim(10, 16);
		run(upright, still(), 120);
		assertTrue(Math.abs(hemZ(sim) - hemZ(upright)) > 1f, "Schleichen verändert die Form");
	}

	@Test
	void teleportsAndGarbageResetTheCloth() {
		ClothSim sim = new ClothSim(10, 16);
		ClothSim.Motion tp = still();
		tp.dx = 500f;
		run(sim, tp, 1);
		assertEquals(0f, hemX(sim), 1e-3f);
		ClothSim.Motion nan = still();
		nan.dx = Float.NaN;
		run(sim, nan, 1);
		assertTrue(sim.healthy());
	}

	@Test
	void chaoticInputNeverExplodes() {
		ClothSim sim = new ClothSim(10, 16);
		Random r = new Random(42);
		ClothSim.Params p = new ClothSim.Params();
		p.strength = 2f;
		p.wind = 2f;
		for (int i = 0; i < 2000; i++) {
			ClothSim.Motion m = still();
			m.dx = (r.nextFloat() - 0.5f) * 20f;
			m.dy = (r.nextFloat() - 0.5f) * 30f;
			m.dz = (r.nextFloat() - 0.5f) * 20f;
			m.dYaw = (r.nextFloat() - 0.5f) * 1.2f;
			m.tilt = r.nextBoolean() ? 0.5f : 0f;
			m.legSwing = r.nextFloat() * 0.6f;
			m.time = i * 0.05f;
			sim.tick(m, p);
			assertTrue(sim.healthy(), "Schritt " + i);
			for (int j = 0; j <= 16; j++) {
				float[] q = sim.point(10, j);
				float d = (float) Math.sqrt((q[0] + 5) * (q[0] + 5) + q[1] * q[1] + (q[2] - 0.5f) * (q[2] - 0.5f));
				assertTrue(d <= j * 1f * ClothSim.MAX_STRETCH + 1e-3f, "gedehnt in Schritt " + i);
			}
		}
	}

	@Test
	void interpolationBlendsTheLastTwoTicks() {
		ClothSim sim = new ClothSim(4, 6);
		run(sim, still(), 5);
		ClothSim.Motion push = still();
		push.dz = -6f;
		sim.tick(push, new ClothSim.Params());
		float[] a = new float[sim.points() * 3];
		float[] b = new float[sim.points() * 3];
		float[] mid = new float[sim.points() * 3];
		sim.positions(0f, a);
		sim.positions(1f, b);
		sim.positions(0.5f, mid);
		int k = (sim.points() - 1) * 3 + 2;
		assertEquals((a[k] + b[k]) / 2f, mid[k], 1e-4f);
	}

	// --- Detailstufen ---

	static CapePhysics.Sample sample(int id, double dist, boolean self) {
		CapePhysics.Sample s = new CapePhysics.Sample();
		s.id = id;
		s.x = dist;
		s.distanceSq = dist * dist;
		s.self = self;
		s.hasCape = true;
		return s;
	}

	@Test
	void physicsCapsTheWorkAndSkipsFarPlayers() {
		CapePhysics physics = new CapePhysics();
		List<CapePhysics.Sample> samples = new ArrayList<>();
		samples.add(sample(0, 0, true));
		for (int i = 1; i <= 40; i++) samples.add(sample(i, i * 1.0, false));
		samples.add(sample(99, 100, false));
		CapePhysics.Sample noCape = sample(98, 2, false);
		noCape.hasCape = false;
		samples.add(noCape);
		physics.tick(samples, true, false, new CapeSettings());
		assertEquals(CapePhysics.MAX_TOTAL, physics.active());
		assertEquals(CapePhysics.FINE_COLS, physics.sim(0).cols(), "eigener Spieler immer fein");
		assertEquals(CapePhysics.FINE_COLS, physics.sim(3).cols());
		assertEquals(CapePhysics.COARSE_COLS, physics.sim(12).cols(), "ab dem 9. nur grob");
		assertNull(physics.sim(99), "zu weit weg → Vanilla");
		assertNull(physics.sim(98), "ohne Umhang keine Simulation");

		physics.tick(Collections.singletonList(sample(0, 0, true)), true, true, new CapeSettings());
		assertEquals(1, physics.active(), "Nur eigener + verschwundene Spieler aufgeräumt");
		assertNotNull(physics.sim(0));
		physics.tick(samples, false, false, new CapeSettings());
		assertEquals(0, physics.active(), "aus → alles weg");
	}

	@Test
	void walkingForwardInTheWorldIsForwardInTheBodyFrame() {
		CapePhysics physics = new CapePhysics();
		CapePhysics.Sample s = sample(1, 0, true);
		s.bodyYaw = 90f; // blickt nach Westen (−x)
		List<CapePhysics.Sample> list = Collections.singletonList(s);
		for (int i = 0; i < 20; i++) physics.tick(list, true, false, new CapeSettings());
		ClothSim sim = physics.sim(1);
		float rest = hemZ(sim);
		for (int i = 0; i < 40; i++) {
			s.x -= 0.28; // sprintet nach Westen = vorwärts
			physics.tick(list, true, false, new CapeSettings());
		}
		assertTrue(hemZ(sim) > rest + 4f, "weht nach hinten, nicht zur Seite");
		assertEquals(0f, hemX(sim), 1f);
	}

	@Test
	void yawDeltaWrapsAround() {
		assertEquals(-20f, CapePhysics.wrapDegrees(340f), 1e-4f);
		assertEquals(10f, CapePhysics.wrapDegrees(-350f), 1e-4f);
	}

	// --- Mesh ---

	@Test
	void meshHasVanillaUvsAndFacesPointOutward() {
		ClothSim sim = new ClothSim(10, 16);
		run(sim, still(), 20);
		final List<float[]> v = new ArrayList<>();
		new ClothMesh().emit(sim, 1f, (x, y, z, u, vv, nx, ny, nz) -> v.add(new float[]{x, y, z, u, vv, nx, ny, nz}));
		int quads = 2 * 10 * 16 + 2 * 16 + 2 * 10;
		assertEquals(quads * 4, v.size());
		// Erstes Viereck = Außenseite oben links: u in [1, 2]/64, v in [1, 2]/32, Normale nach hinten (+z)
		for (int k = 0; k < 4; k++) {
			float[] p = v.get(k);
			assertTrue(p[3] >= 1f / 64 - 1e-6f && p[3] <= 2f / 64 + 1e-6f, "u=" + p[3] * 64);
			assertTrue(p[4] >= 1f / 32 - 1e-6f && p[4] <= 2f / 32 + 1e-6f, "v=" + p[4] * 32);
			assertTrue(p[7] > 0.5f);
		}
		// Innenseite: u zwischen 12 und 22, Normale nach vorn
		for (int k = 4; k < 8; k++) {
			assertTrue(v.get(k)[3] * 64 >= 12f - 1e-4f && v.get(k)[3] * 64 <= 22f + 1e-4f);
			assertTrue(v.get(k)[7] < -0.5f);
		}
		// Jedes Viereck läuft gegen den Uhrzeigersinn um seine Normale.
		for (int q = 0; q < quads; q++) {
			float[] a = v.get(q * 4);
			float[] b = v.get(q * 4 + 1);
			float[] c = v.get(q * 4 + 2);
			float ax = b[0] - a[0], ay = b[1] - a[1], az = b[2] - a[2];
			float bx = c[0] - a[0], by = c[1] - a[1], bz = c[2] - a[2];
			float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
			float dot = cx * a[5] + cy * a[6] + cz * a[7];
			assertTrue(dot > 0f, "Viereck " + q + " verkehrt herum");
		}
		// Alle UVs innerhalb des Umhang-Bereichs (22 × 17 von 64 × 32)
		for (float[] p : v) {
			assertTrue(p[3] >= 0f && p[3] <= 22f / 64 + 1e-6f);
			assertTrue(p[4] >= 0f && p[4] <= 17f / 32 + 1e-6f);
		}
		assertFalse(v.isEmpty());
	}
}

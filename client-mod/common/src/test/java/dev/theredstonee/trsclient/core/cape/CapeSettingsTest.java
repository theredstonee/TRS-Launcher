package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Einstellungen der Umhang-Physik (WaveyCapes-artig): Stil, Wind, Bewegung, Schwerkraft, Anhebung, Steifheit, Detail. */
class CapeSettingsTest {

	static ClothSim settle(CapeSettings settings, ClothSim.Motion m, int ticks) {
		ClothSim sim = new ClothSim(settings.cols(true), settings.rows(true));
		ClothSim.Params p = new ClothSim.Params();
		settings.apply(p);
		for (int i = 0; i < ticks; i++) {
			m.time = i * 0.05f;
			sim.tick(m, p);
		}
		return sim;
	}

	static ClothSim.Motion running() {
		ClothSim.Motion m = new ClothSim.Motion();
		m.dz = -0.28f * 16f;
		return m;
	}

	@Test
	void defaultsAreTheOriginalBehaviour() {
		ClothSim.Params p = new ClothSim.Params();
		new CapeSettings().apply(p);
		ClothSim.Params original = new ClothSim.Params();
		assertEquals(original.strength, p.strength, 1e-6f);
		assertEquals(original.turn, p.turn, 1e-6f);
		assertEquals(original.wind, p.wind, 1e-6f);
		assertEquals(ClothSim.WIND_WAVES, p.windMode);
		assertEquals(original.gravity, p.gravity, 1e-6f);
		assertEquals(original.lift, p.lift, 1e-6f);
		assertEquals(original.stiffness, p.stiffness, 1e-6f);
		assertEquals(original.damping, p.damping, 1e-6f);
		assertEquals(original.waveSpeed, p.waveSpeed, 1e-6f);
		CapeSettings s = new CapeSettings();
		assertEquals(10, s.cols(true));
		assertEquals(16, s.rows(true));
		assertEquals(5, s.cols(false));
		assertEquals(8, s.rows(false));
		assertEquals(CapePhysics.MAX_TOTAL, s.maxTotal());
	}

	@Test
	void menuDefaultsMatchTheSettingsDefaults() {
		TrsModules modules = new TrsModules();
		CapeSettings fromMenu = modules.capeSettings(new CapeSettings());
		CapeSettings defaults = new CapeSettings();
		assertEquals(defaults.style, fromMenu.style);
		assertEquals(defaults.wind, fromMenu.wind);
		assertEquals(defaults.movement, fromMenu.movement);
		assertEquals(defaults.detail, fromMenu.detail);
		assertEquals(defaults.windStrength, fromMenu.windStrength, 1e-6f);
		assertEquals(defaults.gravity, fromMenu.gravity, 1e-6f);
		assertEquals(defaults.height, fromMenu.height, 1e-6f);
		assertEquals(defaults.stiffness, fromMenu.stiffness, 1e-6f);
		assertTrue(modules.capePhysics.inProfiles(), "Umhang-Einstellungen gehören zu den Profilen");
		assertTrue(modules.colors.inProfiles(), "Farben gehören zu den Profilen");
	}

	@Test
	void windOffKeepsAStandingCapeStill() {
		CapeSettings calm = new CapeSettings();
		calm.wind = CapeSettings.Wind.OFF;
		ClothSim sim = settle(calm, new ClothSim.Motion(), 200);
		float[] a = sim.point(5, 16);
		ClothSim.Params p = new ClothSim.Params();
		calm.apply(p);
		ClothSim.Motion m = new ClothSim.Motion();
		for (int i = 0; i < 40; i++) {
			m.time = 10f + i * 0.05f;
			sim.tick(m, p);
		}
		float[] b = sim.point(5, 16);
		assertEquals(a[2], b[2], 0.02f, "ohne Wind bewegt sich ein stehender Umhang nicht");
	}

	@Test
	void gustsLiftTheCapeEvenWhenStanding() {
		float max = 0f;
		float min = Float.MAX_VALUE;
		for (int i = 0; i < 400; i++) {
			float g = ClothSim.gust(i * 0.05f);
			assertTrue(g >= 0f && g <= 1f);
			max = Math.max(max, g);
			min = Math.min(min, g);
		}
		assertTrue(max > 0.6f, "es gibt kräftige Böen");
		assertEquals(0f, min, 1e-6f, "und Flauten");

		CapeSettings gusts = new CapeSettings();
		gusts.wind = CapeSettings.Wind.GUSTS;
		gusts.windStrength = 2f;
		ClothSim sim = new ClothSim(10, 16);
		ClothSim.Params p = new ClothSim.Params();
		gusts.apply(p);
		ClothSim.Motion m = new ClothSim.Motion();
		float lifted = 0f;
		for (int i = 0; i < 400; i++) {
			m.time = i * 0.05f;
			sim.tick(m, p);
			lifted = Math.max(lifted, ClothTest.hemZ(sim));
		}
		CapeSettings waves = new CapeSettings();
		ClothSim calm = settle(waves, new ClothSim.Motion(), 400);
		assertTrue(lifted > ClothTest.hemZ(calm) + 2f, "Böen heben den Saum: " + lifted + " vs " + ClothTest.hemZ(calm));
		assertTrue(sim.healthy());
	}

	@Test
	void heightMultiplierControlsTheLiftWhenRunning() {
		CapeSettings low = new CapeSettings();
		low.height = 0.2f;
		CapeSettings high = new CapeSettings();
		high.height = 2f;
		float lowZ = ClothTest.hemZ(settle(low, running(), 80));
		float highZ = ClothTest.hemZ(settle(high, running(), 80));
		assertTrue(highZ > lowZ + 1.5f, "mehr Anhebung → Saum weiter hinten: " + lowZ + " → " + highZ);
	}

	@Test
	void gravityPullsTheCapeDown() {
		CapeSettings light = new CapeSettings();
		light.gravity = 0.4f;
		CapeSettings heavy = new CapeSettings();
		heavy.gravity = 2f;
		float lightZ = ClothTest.hemZ(settle(light, running(), 80));
		float heavyZ = ClothTest.hemZ(settle(heavy, running(), 80));
		assertTrue(lightZ > heavyZ + 1f, "leichte Schwerkraft → weht höher: " + lightZ + " vs " + heavyZ);
	}

	@Test
	void stiffnessResistsBending() {
		// Beim Schleichen knickt der Umhang an der Hüfte; ein steifer Umhang knickt weniger scharf.
		ClothSim.Motion sneak = new ClothSim.Motion();
		sneak.tilt = 0.5f;
		CapeSettings soft = new CapeSettings();
		soft.stiffness = 0f;
		CapeSettings stiff = new CapeSettings();
		stiff.stiffness = 2f;
		ClothSim a = settle(soft, sneak, 150);
		ClothSim b = settle(stiff, sneak, 150);
		assertNotEquals(ClothTest.hemZ(a), ClothTest.hemZ(b), 0.05f, "Steifheit verändert die Form");
		assertTrue(a.healthy() && b.healthy());
	}

	@Test
	void movementPresetsDifferInInertia() {
		CapeSettings vanilla = new CapeSettings();
		vanilla.movement = CapeSettings.Movement.VANILLA;
		CapeSettings swinging = new CapeSettings();
		CapeSettings dungeons = new CapeSettings();
		dungeons.movement = CapeSettings.Movement.DUNGEONS;
		ClothSim.Params pv = new ClothSim.Params();
		ClothSim.Params ps = new ClothSim.Params();
		ClothSim.Params pd = new ClothSim.Params();
		vanilla.apply(pv);
		swinging.apply(ps);
		dungeons.apply(pd);
		assertTrue(pv.strength < ps.strength, "Vanilla folgt dem Körper enger");
		assertTrue(pv.damping < ps.damping && pd.damping < ps.damping, "Vanilla und Dungeons sind gedämpft");
		assertTrue(pd.waveSpeed < ps.waveSpeed, "Dungeons: langsamere Wellen");

		// Nach einer schnellen Drehung pendelt der schwingende Umhang weiter aus als der Vanilla-artige.
		float swingV = turnSwing(vanilla);
		float swingS = turnSwing(swinging);
		assertTrue(swingS > swingV, "schwingend pendelt stärker: " + swingS + " vs " + swingV);
	}

	private static float turnSwing(CapeSettings s) {
		ClothSim sim = settle(s, new ClothSim.Motion(), 60);
		ClothSim.Params p = new ClothSim.Params();
		s.apply(p);
		ClothSim.Motion turn = new ClothSim.Motion();
		turn.dYaw = (float) Math.toRadians(20);
		for (int i = 0; i < 3; i++) sim.tick(turn, p);
		ClothSim.Motion still = new ClothSim.Motion();
		float max = 0f;
		for (int i = 0; i < 20; i++) {
			sim.tick(still, p);
			max = Math.max(max, Math.abs(ClothTest.hemX(sim)));
		}
		return max;
	}

	@Test
	void styleAndDetailChangeTheGrid() {
		CapeSettings blocky = new CapeSettings();
		blocky.style = CapeSettings.Style.BLOCKY;
		assertEquals(1, blocky.cols(true));
		assertEquals(16, blocky.rows(true));
		CapeSettings low = new CapeSettings();
		low.detail = CapeSettings.Detail.LOW;
		assertTrue(low.cols(true) * low.rows(true) < new CapeSettings().cols(true) * 16);
		assertTrue(low.maxTotal() < CapePhysics.MAX_TOTAL);
		assertTrue(low.farBlocks() < CapePhysics.FAR_BLOCKS);

		// Umschalten im Menü baut die Simulation mit dem neuen Gitter neu.
		CapePhysics physics = new CapePhysics();
		List<CapePhysics.Sample> one = Collections.singletonList(ClothTest.sample(1, 0, true));
		physics.tick(one, true, false, new CapeSettings());
		assertEquals(10, physics.sim(1).cols());
		physics.tick(one, true, false, blocky);
		assertEquals(1, physics.sim(1).cols());
		assertTrue(physics.blocky());

		// Niedrige Detailstufe: weniger Umhänge, kleinere Reichweite.
		List<CapePhysics.Sample> many = new ArrayList<>();
		many.add(ClothTest.sample(0, 0, true));
		for (int i = 1; i <= 40; i++) many.add(ClothTest.sample(i, i * 0.5, false));
		physics.tick(many, true, false, low);
		assertEquals(low.maxTotal(), physics.active());
	}

	@Test
	void blockyMeshIsClosedStepsWithVanillaUvs() {
		CapeSettings blocky = new CapeSettings();
		blocky.style = CapeSettings.Style.BLOCKY;
		ClothSim sim = settle(blocky, running(), 40);
		final List<float[]> v = new ArrayList<>();
		new ClothMesh().emit(sim, 1f, true, (x, y, z, u, vv, nx, ny, nz) -> v.add(new float[]{x, y, z, u, vv, nx, ny, nz}));
		assertEquals(16 * 6 * 4, v.size(), "je Reihe ein Quader aus sechs Flächen");
		for (int q = 0; q < v.size() / 4; q++) {
			float[] a = v.get(q * 4);
			float[] b = v.get(q * 4 + 1);
			float[] c = v.get(q * 4 + 2);
			float ax = b[0] - a[0], ay = b[1] - a[1], az = b[2] - a[2];
			float bx = c[0] - a[0], by = c[1] - a[1], bz = c[2] - a[2];
			float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
			assertTrue(cx * a[5] + cy * a[6] + cz * a[7] > 0f, "Fläche " + q + " zeigt nach außen");
		}
		for (float[] p : v) {
			assertTrue(p[3] >= 0f && p[3] <= 22f / 64 + 1e-6f);
			assertTrue(p[4] >= 0f && p[4] <= 17f / 32 + 1e-6f);
		}
	}

	@Test
	void previewAlternatesStandingAndWalking() {
		CapePhysics physics = new CapePhysics();
		List<CapePhysics.Sample> one = Collections.singletonList(ClothTest.sample(1, 0, true));
		physics.tick(one, true, false, new CapeSettings());
		float rest = ClothTest.hemZ(physics.sim(1));
		boolean walked = false;
		float max = rest;
		for (int i = 0; i < 130; i++) {
			physics.preview();
			physics.tick(one, true, false, new CapeSettings());
			if (physics.previewWalking()) walked = true;
			max = Math.max(max, ClothTest.hemZ(physics.sim(1)));
		}
		assertTrue(walked, "die Vorschau geht zwischendurch");
		assertTrue(max > rest + 3f, "und der Umhang weht dabei nach hinten");
	}
}

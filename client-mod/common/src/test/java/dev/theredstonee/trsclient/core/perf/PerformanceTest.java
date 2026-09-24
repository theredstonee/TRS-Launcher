package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.Category;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceTest {
	@AfterEach
	void english() {
		I18n.use("en");
		GpuInfo.setAdapters(null);
	}

	// --- Dynamische FPS ---

	@Test
	void dynamicFpsLimitsInTheBackgroundAndComesBackAtOnce() {
		DynamicFps d = new DynamicFps();
		d.input(1000, 10, 10, false);
		assertEquals(DynamicFps.State.ACTIVE, d.update(1000, true, false, 3));
		assertEquals(0, DynamicFps.limit(DynamicFps.State.ACTIVE, 15, 1, 30));
		assertEquals(DynamicFps.State.UNFOCUSED, d.update(2000, false, false, 3));
		assertEquals(15, DynamicFps.limit(d.state(), 15, 1, 30));
		assertEquals(DynamicFps.State.MINIMIZED, d.update(3000, false, true, 3));
		assertEquals(1, DynamicFps.limit(d.state(), 15, 1, 30));
		// Zurück: sofort aktiv, AFK-Uhr beginnt neu.
		assertEquals(DynamicFps.State.ACTIVE, d.update(10 * 60_000L, true, false, 3));
		assertEquals(0, d.idleMillis(10 * 60_000L));
	}

	@Test
	void afkAfterMinutesWithoutInputAndBackOnMouseMove() {
		DynamicFps d = new DynamicFps();
		d.input(0 + 1, 100, 100, false);
		d.input(60_000, 100, 100, false);
		assertEquals(DynamicFps.State.ACTIVE, d.update(60_000, true, false, 3));
		d.input(3 * 60_000L + 5, 100, 100, false);
		assertEquals(DynamicFps.State.AFK, d.update(3 * 60_000L + 5, true, false, 3));
		assertEquals(30, DynamicFps.limit(d.state(), 15, 1, 30));
		assertEquals(0, DynamicFps.limit(d.state(), 15, 1, 0), "AFK-FPS 0 = aus");
		d.input(3 * 60_000L + 10, 140, 100, false);
		assertEquals(DynamicFps.State.ACTIVE, d.update(3 * 60_000L + 10, true, false, 3));
		// Eine gedrückte Taste zählt ebenfalls.
		d.input(9 * 60_000L, 140, 100, true);
		assertEquals(DynamicFps.State.ACTIVE, d.update(9 * 60_000L, true, false, 3));
	}

	@Test
	void volumeOnlyLowerInTheBackground() {
		assertEquals(1f, DynamicFps.volume(DynamicFps.State.ACTIVE, true, 30), 1e-6);
		assertEquals(1f, DynamicFps.volume(DynamicFps.State.AFK, true, 30), 1e-6);
		assertEquals(0.3f, DynamicFps.volume(DynamicFps.State.UNFOCUSED, true, 30), 1e-6);
		assertEquals(0f, DynamicFps.volume(DynamicFps.State.MINIMIZED, true, 0), 1e-6);
		assertEquals(1f, DynamicFps.volume(DynamicFps.State.MINIMIZED, false, 0), 1e-6);
	}

	/** Uhr, die beim Schlafen vorrückt. */
	private static final class FakeClock implements FramePacer.Clock {
		long now = 1_000_000_000L;
		long slept;
		int sleeps;

		@Override
		public long nanoTime() {
			return now;
		}

		@Override
		public void sleep(long nanos) {
			now += nanos;
			slept += nanos;
			sleeps++;
		}
	}

	@Test
	void framePacerWaitsInSlicesAndStopsWhenTheWindowComesBack() {
		FakeClock clock = new FakeClock();
		FramePacer pacer = new FramePacer(clock);
		pacer.pace(0, null);
		// 1 FPS: fast eine Sekunde warten, in 10-ms-Scheiben.
		clock.now += 1_000_000L;
		long waited = pacer.pace(1, null);
		assertTrue(waited >= 990_000_000L && waited <= 1_000_000_000L, "gewartet: " + waited);
		assertTrue(clock.sleeps >= 99, "Scheiben: " + clock.sleeps);
		// Fenster kommt nach 3 Scheiben zurück → sofort weiter.
		final int[] checks = {0};
		clock.sleeps = 0;
		waited = pacer.pace(1, new FramePacer.Wake() {
			@Override
			public boolean stillLimited() {
				return ++checks[0] < 3;
			}
		});
		assertEquals(3, clock.sleeps);
		assertTrue(waited <= 3 * FramePacer.SLICE_NANOS, "gewartet: " + waited);
		// Unbegrenzt: nie warten.
		clock.sleeps = 0;
		assertEquals(0, pacer.pace(0, null));
		assertEquals(0, clock.sleeps);
		// 60 FPS nach einem langsamen Bild: nicht warten.
		clock.now += 50_000_000L;
		assertEquals(0, pacer.waitNanos(clock.now, 60));
	}

	// --- Partikel ---

	@Test
	void particleGateHonoursKindLimitAndAmount() {
		ParticleGate g = new ParticleGate();
		assertFalse(g.allow(ParticleGate.Kind.RAIN, 0, 0, 1, true));
		assertTrue(g.allow(ParticleGate.Kind.OTHER, 99, 100, 1, false));
		assertFalse(g.allow(ParticleGate.Kind.OTHER, 100, 100, 1, false));
		int allowed = 0;
		for (int i = 0; i < 1000; i++) if (g.allow(ParticleGate.Kind.OTHER, 0, 0, 0.3, false)) allowed++;
		assertEquals(300, allowed, 1);
	}

	@Test
	void performanceRoutesParticleKindsToTheirSwitches() {
		TrsModules m = new TrsModules();
		Performance p = new Performance(m, PerfCompat.all());
		m.particles.setEnabled(true);
		m.particleNoExplosions.set(true);
		m.particleNoRain.set(false);
		m.particleLimit.set(500);
		p.refresh();
		assertFalse(p.allowParticle(ParticleGate.Kind.EXPLOSION, 0));
		assertTrue(p.allowParticle(ParticleGate.Kind.RAIN, 0));
		assertFalse(p.allowParticle(ParticleGate.Kind.OTHER, 500));
		// Hauptschalter aus → alles erlaubt.
		m.fpsBoost.setEnabled(false);
		p.refresh();
		assertTrue(p.allowParticle(ParticleGate.Kind.EXPLOSION, 99999));
		assertFalse(p.particlesActive());
		assertFalse(p.hidesParticle(ParticleGate.Kind.EXPLOSION));
		m.fpsBoost.setEnabled(true);
		p.refresh();
		assertTrue(p.hidesParticle(ParticleGate.Kind.EXPLOSION));
		assertFalse(p.hidesParticle(ParticleGate.Kind.RAIN));
	}

	// --- Wesen ---

	@Test
	void distanceCullingPerKindKeepsPlayers() {
		TrsModules m = new TrsModules();
		Performance p = new Performance(m, PerfCompat.all());
		m.entityCulling.setEnabled(true);
		m.cullEntities.set(64);
		m.cullItems.set(16);
		m.cullFrames.set(0);
		m.cullBlockEntities.set(32);
		m.cullNameTags.set(24);
		p.refresh();
		assertFalse(p.cullEntity(Performance.EntityKind.MOB, 60 * 60));
		assertTrue(p.cullEntity(Performance.EntityKind.MOB, 70 * 70));
		assertFalse(p.cullEntity(Performance.EntityKind.PLAYER, 200 * 200), "Spieler immer sichtbar");
		assertTrue(p.cullEntity(Performance.EntityKind.ITEM, 20 * 20));
		assertFalse(p.cullEntity(Performance.EntityKind.FRAME, 500 * 500), "0 = aus");
		assertTrue(p.cullBlockEntity(40 * 40));
		assertFalse(p.cullBlockEntity(30 * 30));
		assertTrue(p.hideNameTag(30 * 30));
		m.cullKeepPlayers.set(false);
		p.refresh();
		assertTrue(p.cullEntity(Performance.EntityKind.PLAYER, 200 * 200));
	}

	@Test
	void occlusionHidesBehindSolidWallsOnly() {
		// Wand aus vollen Blöcken bei x = 5 (y 0..10, z -5..5).
		Occlusion.Blocks wall = new Occlusion.Blocks() {
			@Override
			public boolean opaque(int x, int y, int z) {
				return x == 5 && y >= 0 && y <= 10 && z >= -5 && z <= 5;
			}
		};
		Occlusion o = new Occlusion();
		o.tick(1);
		assertFalse(o.visible(1, 0.5, 1.6, 0.5, 9.7, 0, 0.2, 10.3, 1.8, 0.8, wall), "hinter der Wand");
		assertTrue(o.visible(2, 0.5, 1.6, 0.5, 9.7, 0, 20.2, 10.3, 1.8, 20.8, wall), "neben der Wand");
		// Über die Wand hinaus ragend (Kopf sichtbar) → sichtbar.
		assertTrue(o.visible(3, 0.5, 11.6, 0.5, 9.7, 10, 0.2, 10.3, 12.8, 0.8, wall));
		// Ohne Wand frei, und das Ergebnis ist zwischengespeichert.
		assertTrue(Occlusion.clear(wall, 0.5, 1.5, 0.5, 4.5, 1.5, 0.5));
		assertFalse(Occlusion.clear(wall, 0.5, 1.5, 0.5, 8.5, 1.5, 0.5));
		assertFalse(o.visible(1, 0.5, 1.6, 0.5, 9.7, 0, 0.2, 10.3, 1.8, 0.8, new Occlusion.Blocks() {
			@Override
			public boolean opaque(int x, int y, int z) {
				return false;
			}
		}), "Ergebnis gilt noch im selben Tick");
		o.tick(10);
		assertTrue(o.visible(1, 0.5, 1.6, 0.5, 9.7, 0, 0.2, 10.3, 1.8, 0.8, new Occlusion.Blocks() {
			@Override
			public boolean opaque(int x, int y, int z) {
				return false;
			}
		}), "nach Ablauf neu gerechnet");
	}

	@Test
	void occlusionBudgetNeverHidesWrongly() {
		Occlusion.Blocks solid = new Occlusion.Blocks() {
			@Override
			public boolean opaque(int x, int y, int z) {
				return Math.abs(x) == 3;
			}
		};
		Occlusion o = new Occlusion();
		o.tick(1);
		int hidden = 0;
		for (int i = 0; i < 400; i++) {
			if (!o.visible(1000 + i, 0.5, 1.5, 0.5, 10, 0, i % 20, 10.6, 1.8, i % 20 + 0.6, solid)) hidden++;
		}
		// Das Budget reicht nicht für alle – der Rest bleibt sichtbar.
		assertTrue(hidden > 0 && hidden < 400, "versteckt: " + hidden);
	}

	// --- FPS vorher/nachher ---

	@Test
	void fpsMeterComparesBeforeAndAfter() {
		FpsMeter meter = new FpsMeter();
		long t = 0;
		// 100 FPS für 4 s
		for (int i = 0; i < 400; i++) meter.frame(t += 10, false);
		assertEquals(100, meter.recent(t, 3000), 3);
		meter.compare(t, "High");
		assertEquals(FpsMeter.Compare.MEASURING, meter.compareState());
		// 150 FPS danach
		for (int i = 0; i < 1000; i++) meter.frame(t += 6, false);
		assertEquals(FpsMeter.Compare.DONE, meter.compareState());
		assertEquals(100, meter.before(), 3);
		assertEquals(166, meter.after(), 4);
		assertTrue(meter.percent() > 55 && meter.percent() < 75, "Prozent: " + meter.percent());
		// Hänger direkt nach der Änderung (z. B. Ressourcen neu laden): Messung beginnt danach neu.
		meter.compare(t, "Mipmap");
		t += 7000;
		meter.frame(t, false);
		assertEquals(FpsMeter.Compare.MEASURING, meter.compareState());
		for (int i = 0; i < 1500; i++) meter.frame(t += 4, false);
		assertEquals(FpsMeter.Compare.DONE, meter.compareState());
		assertEquals(250, meter.after(), 5);
		// Gebremste Bilder (Dynamische FPS) zählen nicht.
		FpsMeter m2 = new FpsMeter();
		long u = 0;
		for (int i = 0; i < 100; i++) m2.frame(u += 1000, true);
		assertEquals(0, m2.recent(u, 10_000), 1e-9);
	}

	// --- Mods ---

	@Test
	void compatibleModsTakeOverTheirFeatures() {
		PerfCompat c = new PerfCompat(new PerfCompat.ModCheck() {
			@Override
			public boolean loaded(String id) {
				return id.equals("sodium") || id.equals("dynamic_fps") || id.equals("immediatelyfast");
			}
		}, false, PerfCompat.FABRIC, "1.21.11", EnumSet.allOf(PerfFeature.class));
		assertEquals(PerfMod.SODIUM, c.owner(PerfFeature.ENTITY_OCCLUSION));
		assertEquals(PerfMod.DYNAMIC_FPS, c.owner(PerfFeature.DYNAMIC_FPS));
		assertFalse(c.ours(PerfFeature.DYNAMIC_FPS));
		assertTrue(c.ours(PerfFeature.ENTITY_DISTANCE));
		assertTrue(c.detected().contains(PerfMod.IMMEDIATELY_FAST));
		assertEquals(Arrays.asList("EntityCulling", "FerriteCore"), c.missingRecommended());

		PerfCompat legacy = new PerfCompat(new PerfCompat.ModCheck() {
			@Override
			public boolean loaded(String id) {
				return id.equals("patcher");
			}
		}, true, PerfCompat.FORGE, "1.8.9", EnumSet.of(PerfFeature.DYNAMIC_FPS, PerfFeature.SKY));
		assertEquals(PerfMod.PATCHER, legacy.owner(PerfFeature.DYNAMIC_FPS));
		assertEquals(PerfMod.OPTIFINE, legacy.owner(PerfFeature.SKY));
		assertTrue(legacy.missingRecommended().isEmpty());
		assertFalse(legacy.supported(PerfFeature.FOG));

		// Übernommene Funktion läuft nie – auch nicht bei eingeschaltetem Modul.
		TrsModules m = new TrsModules();
		Performance p = new Performance(m, c);
		m.dynamicFps.setEnabled(true);
		p.refresh();
		assertFalse(p.active(PerfFeature.DYNAMIC_FPS));
		assertEquals(0, p.frameLimit(1000, false, true, 0, 0, false));
	}

	// --- Leistungs-Check, Boost, Rückgängig ---

	/** Vanilla-Optionen zum Testen. */
	static final class FakeOptions implements GameOptions {
		final Map<Opt, Integer> values = new EnumMap<Opt, Integer>(Opt.class);
		int saves;
		String renderer = "NVIDIA GeForce RTX 3060/PCIe/SSE2";
		boolean smoothBool;

		FakeOptions() {
			values.put(Opt.VSYNC, 1);
			values.put(Opt.VIEW_DISTANCE, 24);
			values.put(Opt.SIMULATION_DISTANCE, 24);
			values.put(Opt.GRAPHICS, 2);
			values.put(Opt.CLOUDS, 2);
			values.put(Opt.PARTICLES, 0);
			values.put(Opt.MIPMAP, 4);
			values.put(Opt.BIOME_BLEND, 5);
			values.put(Opt.ENTITY_DISTANCE, 150);
			values.put(Opt.SMOOTH_LIGHTING, 2);
			values.put(Opt.FULLSCREEN, 0);
		}

		@Override
		public int get(Opt opt) {
			Integer v = values.get(opt);
			return v == null ? NONE : v;
		}

		@Override
		public boolean set(Opt opt, int value) {
			if (!values.containsKey(opt)) return false;
			values.put(opt, opt.clamp(value));
			return true;
		}

		@Override
		public boolean smoothLightingIsBoolean() {
			return smoothBool;
		}

		@Override
		public void save() {
			saves++;
		}

		@Override
		public String renderer() {
			return renderer;
		}

		@Override
		public String vendor() {
			return "";
		}
	}

	@Test
	void checkFindsTheUsualFpsKillers() {
		FakeOptions o = new FakeOptions();
		List<PerfCheck.Finding> f = PerfCheck.run(o, 25, null, Arrays.asList("Sodium"));
		List<String> ids = new ArrayList<String>();
		for (PerfCheck.Finding x : f) ids.add(x.id);
		assertTrue(ids.containsAll(Arrays.asList("vsync", "simulation", "view", "fabulous", "clouds", "particles", "mipmap",
				"entityDistance", "biomeBlend", "smoothLighting", "fullscreen", "mods")), ids.toString());
		assertFalse(ids.contains("gpu"), "dedizierte Karte läuft schon");
		PerfCheck.Finding view = f.get(ids.indexOf("view"));
		assertEquals(Integer.valueOf(10), view.fix.get(GameOptions.Opt.VIEW_DISTANCE));
		PerfCheck.Finding sim = f.get(ids.indexOf("simulation"));
		assertEquals(Integer.valueOf(22), sim.fix.get(GameOptions.Opt.SIMULATION_DISTANCE));

		// Onboard-Grafik, obwohl eine RTX eingebaut ist.
		o.renderer = "Intel(R) UHD Graphics 620";
		f = PerfCheck.run(o, 200, "NVIDIA GeForce RTX 3060 Laptop GPU", null);
		assertEquals("gpu", f.get(0).id);
		assertEquals(PerfCheck.Level.HIGH, f.get(0).level);
		assertFalse(f.get(0).fixable(), "nur Hinweis – umstellen macht der Launcher");

		// Gute Einstellungen: nichts zu tun.
		FakeOptions good = new FakeOptions();
		good.values.put(GameOptions.Opt.VSYNC, 0);
		good.values.put(GameOptions.Opt.VIEW_DISTANCE, 12);
		good.values.put(GameOptions.Opt.SIMULATION_DISTANCE, 8);
		good.values.put(GameOptions.Opt.GRAPHICS, 0);
		good.values.put(GameOptions.Opt.CLOUDS, 1);
		good.values.put(GameOptions.Opt.PARTICLES, 1);
		good.values.put(GameOptions.Opt.MIPMAP, 2);
		good.values.put(GameOptions.Opt.BIOME_BLEND, 2);
		good.values.put(GameOptions.Opt.ENTITY_DISTANCE, 100);
		assertTrue(PerfCheck.run(good, 144, null, null).isEmpty());
	}

	@Test
	void presetsOnlyLowerAndUndoRestoresEverything() {
		TrsModules m = new TrsModules();
		Performance p = new Performance(m, PerfCompat.all());
		FakeOptions o = new FakeOptions();
		o.smoothBool = true;
		p.setGame(o);
		m.cullEntities.set(100);
		m.entityCulling.setEnabled(false);
		Map<GameOptions.Opt, Integer> before = new EnumMap<GameOptions.Opt, Integer>(o.values);

		p.applyPreset(BoostPreset.HIGH, 1000);
		assertTrue(m.entityCulling.isEnabled());
		assertEquals(48, m.cullEntities.getInt());
		assertTrue(m.worldDetails.isEnabled());
		assertEquals(0, o.get(GameOptions.Opt.VSYNC));
		assertEquals(12, o.get(GameOptions.Opt.VIEW_DISTANCE));
		assertEquals(10, o.get(GameOptions.Opt.SIMULATION_DISTANCE));
		assertEquals(0, o.get(GameOptions.Opt.GRAPHICS));
		assertEquals(0, o.get(GameOptions.Opt.CLOUDS));
		assertEquals(2, o.get(GameOptions.Opt.PARTICLES));
		assertEquals(75, o.get(GameOptions.Opt.ENTITY_DISTANCE));
		assertEquals(0, o.get(GameOptions.Opt.SMOOTH_LIGHTING));
		assertEquals(0, o.get(GameOptions.Opt.FULLSCREEN), "Vollbild wird nie umgeschaltet");
		assertTrue(o.saves > 0);
		assertTrue(p.canUndo());

		// Zweite Stufe danach: Ursprungswerte bleiben die von ganz vorher.
		p.applyPreset(BoostPreset.LOW, 2000);
		assertEquals(12, o.get(GameOptions.Opt.VIEW_DISTANCE), "Niedrig erhöht nichts");

		// Rückgängig über einen Neustart hinweg (Config schreiben und neu lesen).
		TrsConfig saved = m.registry.capture();
		TrsModules m2 = new TrsModules();
		m2.registry.apply(saved);
		Performance p2 = new Performance(m2, PerfCompat.all());
		p2.setGame(o);
		assertTrue(p2.canUndo());
		p2.undo(3000);
		assertEquals(before, o.values);
		assertFalse(m2.entityCulling.isEnabled());
		assertEquals(100, m2.cullEntities.getInt());
		assertFalse(p2.canUndo());
	}

	@Test
	void mediumKeepsBooleanSmoothLightingAndCustomGraphics() {
		FakeOptions o = new FakeOptions();
		o.values.put(GameOptions.Opt.GRAPHICS, 3);
		assertEquals(GameOptions.NONE, BoostPreset.MEDIUM.target(GameOptions.Opt.GRAPHICS, 3, 24, true));
		assertEquals(GameOptions.NONE, BoostPreset.MEDIUM.target(GameOptions.Opt.SMOOTH_LIGHTING, 2, 24, true));
		assertEquals(1, BoostPreset.MEDIUM.target(GameOptions.Opt.SMOOTH_LIGHTING, 2, 24, false));
		assertEquals(GameOptions.NONE, BoostPreset.LOW.target(GameOptions.Opt.PARTICLES, 0, 24, false));
		assertEquals(GameOptions.NONE, BoostPreset.HIGH.target(GameOptions.Opt.SIMULATION_DISTANCE, GameOptions.NONE, 24, false));
	}

	// --- Grafikkarte ---

	@Test
	void classifiesGraphicsCards() {
		assertEquals(GpuInfo.Kind.INTEGRATED, GpuInfo.classify("Intel(R) UHD Graphics 630"));
		assertEquals(GpuInfo.Kind.INTEGRATED, GpuInfo.classify("Intel(R) Iris(R) Xe Graphics"));
		assertEquals(GpuInfo.Kind.INTEGRATED, GpuInfo.classify("AMD Radeon(TM) Graphics"));
		assertEquals(GpuInfo.Kind.INTEGRATED, GpuInfo.classify("AMD Radeon 780M"));
		assertEquals(GpuInfo.Kind.DEDICATED, GpuInfo.classify("NVIDIA GeForce GTX 1650/PCIe/SSE2"));
		assertEquals(GpuInfo.Kind.DEDICATED, GpuInfo.classify("AMD Radeon RX 6700 XT"));
		assertEquals(GpuInfo.Kind.DEDICATED, GpuInfo.classify("Intel(R) Arc(TM) A770 Graphics"));
		assertEquals(GpuInfo.Kind.UNKNOWN, GpuInfo.classify("llvmpipe (LLVM 15.0.7, 256 bits)"));
		assertEquals(GpuInfo.Kind.UNKNOWN, GpuInfo.classify(""));
		assertEquals("NVIDIA GeForce RTX 4070", GpuInfo.parseRegLine("    DriverDesc    REG_SZ    NVIDIA GeForce RTX 4070"));
		assertNull(GpuInfo.parseRegLine("HKEY_LOCAL_MACHINE\\SYSTEM\\...\\0000"));
		GpuInfo.setAdapters(Arrays.asList("Intel(R) UHD Graphics 620", "NVIDIA GeForce MX250"));
		assertEquals("NVIDIA GeForce MX250", GpuInfo.dedicatedAdapter());
	}

	// --- Menü, Profile, Texte ---

	@Test
	void performanceModulesLiveInTheirCategoryAndInProfiles() {
		TrsModules m = new TrsModules();
		new Performance(m, PerfCompat.all());
		for (Module module : new Module[]{m.fpsBoost, m.dynamicFps, m.entityCulling, m.particles, m.worldDetails}) {
			assertEquals(Category.PERFORMANCE, module.category());
			assertTrue(module.inProfiles());
			assertNotNull(ModulePanel.Registry.of(module), module.id());
		}
		// Profil wechseln nimmt die Leistungs-Einstellungen mit.
		m.entityCulling.setEnabled(true);
		m.profiles.create("PvP");
		m.entityCulling.setEnabled(false);
		m.profiles.switchTo(0);
		assertTrue(m.entityCulling.isEnabled());
		m.profiles.switchTo(1);
		assertFalse(m.entityCulling.isEnabled());
	}

	@Test
	void everyFeatureAndStateIsTranslated() {
		for (String lang : I18n.LANGUAGES) {
			I18n.use(lang);
			for (PerfFeature f : PerfFeature.values()) assertTrue(I18n.has(f.key()), lang + " " + f.key());
			for (DynamicFps.State s : DynamicFps.State.values()) {
				assertTrue(I18n.has("perf.state." + s.name().toLowerCase(java.util.Locale.ROOT)), lang + " " + s);
			}
			for (BoostPreset b : BoostPreset.values()) assertFalse(b.label().equals(b.name()), lang + " " + b);
		}
	}
}

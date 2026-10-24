package dev.theredstonee.trsclient.core.cosmetic.pet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Begleiter am Boden (Pinguine): Folgen, Formation, Stufen, Wände, Wasser, Bauchrutschen, Teleport, Aufräumen, Zeichnen. */
class PetSimTest {
	static final UUID OWNER = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
	static final UUID OTHER = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");

	/** Kleine Testwelt: Boden aus Gras bei y = 64 (Oberkante), dazu einzelne Blöcke. */
	static final class FakeWorld implements PetWorld {
		final Map<Long, Integer> blocks = new HashMap<Long, Integer>();
		int floorY = 63;
		int floorCode = PetWorld.code(PetWorld.SOLID, 1.0);

		static long key(int x, int y, int z) {
			return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
		}

		FakeWorld set(int x, int y, int z, int code) {
			blocks.put(key(x, y, z), code);
			return this;
		}

		FakeWorld box(int x0, int y0, int z0, int x1, int y1, int z1, int code) {
			for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) set(x, y, z, code);
			return this;
		}

		@Override
		public int block(int x, int y, int z) {
			Integer c = blocks.get(key(x, y, z));
			if (c != null) return c;
			return y <= floorY ? floorCode : 0;
		}

		@Override
		public int light(int x, int y, int z) {
			return PetWorld.packLight(15, 3);
		}
	}

	static final int STONE = PetWorld.code(PetWorld.SOLID, 1.0);
	static final int AIR = 0;
	static final int WATER = PetWorld.WATER;
	static final int ICE = PetWorld.code(PetWorld.SOLID | PetWorld.SLIPPERY, 1.0);

	@AfterEach
	void reset() {
		PetSim.forceSlide = Double.NaN;
	}

	private static void run(PetSim sim, PetWorld w, int ticks, double ox, double oy, double oz, int count) {
		for (int i = 0; i < ticks; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OWNER, count, ox, oy, oz, 0f, false, true, w);
			sim.end();
		}
	}

	private static Pet only(PetSim sim) {
		List<Pet> pets = sim.pets(OWNER);
		assertEquals(1, pets.size());
		return pets.get(0);
	}

	@Test
	void spawnsBehindOwnerOnTheGroundAndFollows() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		run(sim, w, 1, 0.5, 64, 0.5, 1);
		Pet p = only(sim);
		assertEquals(64.0, p.y, 1e-6, "auf dem Boden");
		double d = Math.hypot(p.x - 0.5, p.z - 0.5);
		assertTrue(d > 1.0 && d < 3.0, "hinter dem Besitzer, Abstand " + d);
		assertTrue(p.z < 0.5, "Besitzer schaut nach Süden (+z) → Begleiter steht nördlich dahinter");
		// Besitzer läuft 12 Blöcke nach Süden
		PetSim.forceSlide = 0;
		for (int i = 0; i < 120; i++) {
			double oz = 0.5 + Math.min(12, i * 0.2);
			sim.begin(w, 1000 + i * 50L);
			sim.owner(OWNER, 1, 0.5, 64, oz, 0f, false, true, w);
			sim.end();
			if (i == 30) assertEquals(Pet.WALK, p.state(), "läuft hinterher");
		}
		double dist = Math.hypot(p.x - 0.5, p.z - 12.5);
		assertTrue(dist < 3.2, "wieder hinter dem Besitzer: " + dist);
		assertEquals(64.0, p.y, 1e-6);
	}

	@Test
	void formationKeepsFourPetsApartAndCountChanges() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		run(sim, w, 200, 0.5, 64, 0.5, 4);
		List<Pet> pets = sim.pets(OWNER);
		assertEquals(4, pets.size());
		for (int i = 0; i < 4; i++) {
			Pet a = pets.get(i);
			double d = Math.hypot(a.x - 0.5, a.z - 0.5);
			assertTrue(d > 0.6 && d < 4.5, "Abstand zum Besitzer " + d);
			for (int j = i + 1; j < 4; j++) {
				Pet b = pets.get(j);
				assertTrue(Math.hypot(a.x - b.x, a.z - b.z) > 0.35, "nicht aufeinander");
			}
		}
		assertNotEquals(pets.get(0).offsetMs, pets.get(1).offsetMs, "eigener Zeitversatz je Begleiter");
		run(sim, w, 1, 0.5, 64, 0.5, 2);
		assertEquals(2, sim.pets(OWNER).size());
		run(sim, w, 1, 0.5, 64, 0.5, 9);
		assertEquals(PetSim.MAX_PER_OWNER, sim.pets(OWNER).size(), "höchstens 4");
		run(sim, w, 1, 0.5, 64, 0.5, 0);
		assertTrue(sim.pets(OWNER).isEmpty(), "Begleiter abgelegt");
	}

	@Test
	void teleportsWhenFarAway() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		run(sim, w, 5, 0.5, 64, 0.5, 1);
		Pet p = only(sim);
		run(sim, w, 1, 40.5, 64, 0.5, 1);
		assertTrue(Math.hypot(p.x - 40.5, p.z - 0.5) < 3.5, "beim Besitzer abgesetzt");
		assertEquals(p.x, p.px, 1e-9, "kein Überblenden quer durch die Welt");
	}

	@Test
	void jumpsOneBlockStepButNotAWall() {
		PetSim sim = new PetSim();
		PetSim.forceSlide = 0;
		FakeWorld w = new FakeWorld();
		// Stufe: ab z = 4 liegt der Boden einen Block höher (y 64 → Oberkante 65)
		w.box(-6, 64, 4, 6, 64, 30, STONE);
		run(sim, w, 1, 0.5, 64, 0.5, 1);
		Pet p = only(sim);
		boolean sawAir = false;
		for (int i = 0; i < 200; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OWNER, 1, 0.5, 65, 10.5, 0f, false, true, w);
			sim.end();
			if (p.state() == Pet.AIR) sawAir = true;
		}
		assertEquals(65.0, p.y, 1e-6, "oben auf der Stufe");
		assertTrue(p.z > 5, "über die Stufe gekommen: z=" + p.z);
		assertTrue(sawAir, "Sprung als Zustand air");

		// Wand: zwei Blöcke hoch und breit – kommt nicht drüber, bleibt davor
		PetSim sim2 = new PetSim();
		FakeWorld w2 = new FakeWorld();
		w2.box(-30, 64, 4, 30, 65, 4, STONE);
		run(sim2, w2, 1, 0.5, 64, 0.5, 1);
		Pet q = sim2.pets(OWNER).get(0);
		for (int i = 0; i < 40; i++) {
			sim2.begin(w2, i * 50L);
			sim2.owner(OWNER, 1, 0.5, 64, 6.5, 0f, false, true, w2);
			sim2.end();
		}
		assertTrue(q.z < 4.0, "vor der Wand: z=" + q.z);
		assertEquals(64.0, q.y, 1e-6);
	}

	@Test
	void walksUnderLowCeilingAndStepsSlabs() {
		FakeWorld w = new FakeWorld();
		// Tunnel: Decke einen Block über dem Boden (Platz 1 Block > 0,8 hoch)
		w.box(-1, 65, 3, 2, 65, 12, STONE);
		// Stufe (halber Block) am Ende
		w.set(0, 64, 14, PetWorld.code(PetWorld.SOLID, 0.5));
		assertEquals(64.0, PetSim.moveSurface(w, 0.5, 6.5, 64), 1e-9, "unter der Decke durch");
		assertEquals(64.5, PetSim.moveSurface(w, 0.5, 14.5, 64), 1e-9, "auf die Stufe");
		assertEquals(PetSim.WALL, PetSim.moveSurface(new FakeWorld().box(0, 64, 0, 0, 66, 0, STONE), 0.5, 0.5, 64));
		assertTrue(PetSim.headroom(w, 0.5, 6.5, 64));
		assertFalse(PetSim.headroom(new FakeWorld().set(0, 64, 0, STONE), 0.5, 0.5, 63.5));
		// Lava ist eine Wand
		FakeWorld lava = new FakeWorld().set(0, 63, 0, PetWorld.DANGER);
		assertEquals(PetSim.WALL, PetSim.surface(lava, 0.5, 0.5, 64.2, 4));
		// Nicht geladen: unbekannt (stehen bleiben)
		FakeWorld unloaded = new FakeWorld().set(0, 64, 0, PetWorld.UNLOADED);
		assertEquals(PetSim.UNKNOWN, PetSim.surface(unloaded, 0.5, 0.5, 64.5, 4));
	}

	@Test
	void swimsAtTheSurfaceOfDeepWater() {
		PetSim sim = new PetSim();
		PetSim.forceSlide = 0;
		FakeWorld w = new FakeWorld();
		// Becken: Wasser y 61–63 (3 tief), Boden darunter bei Oberkante 61, Ufer bei z ≥ 12
		w.box(-10, 61, -2, 10, 63, 11, WATER);
		w.box(-10, 60, -2, 10, 60, 11, STONE);
		run(sim, w, 1, 0.5, 64, 14.5, 1);
		Pet p = only(sim);
		// Besitzer steht im Wasser (schwimmt) in der Mitte
		boolean swam = false;
		double ySwim = 0;
		for (int i = 0; i < 150; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OWNER, 1, 0.5, 63.4, 4.5, 180f, false, true, w);
			sim.end();
			if (p.state() == Pet.SWIM) {
				swam = true;
				ySwim = p.y;
			}
		}
		assertTrue(swam, "schwimmt");
		assertEquals(63.9 - PetSim.SWIM_DEPTH, ySwim, 0.15, "an der Oberfläche");
		// zurück an Land: wieder laufen
		for (int i = 0; i < 200; i++) {
			sim.begin(w, 10_000 + i * 50L);
			sim.owner(OWNER, 1, 0.5, 64, 18.5, 0f, false, true, w);
			sim.end();
		}
		assertEquals(64.0, p.y, 1e-6, "aus dem Wasser gestiegen");
		assertNotEquals(Pet.SWIM, p.state());
		assertEquals(63.9, PetSim.waterSurface(w, 0, 61, 0), 1e-9);
	}

	@Test
	void slidesOnTheBellyMoreOftenOnIce() {
		PetSim.forceSlide = 1.0;
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		run(sim, w, 1, 0.5, 64, 0.5, 1);
		Pet p = only(sim);
		double maxStep = 0;
		boolean slid = false;
		for (int i = 0; i < 40; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OWNER, 1, 0.5, 64, 15.5, 0f, false, true, w);
			sim.end();
			if (p.state() == Pet.SLIDE) {
				slid = true;
				maxStep = Math.max(maxStep, Math.hypot(p.x - p.px, p.z - p.pz));
			}
		}
		assertTrue(slid, "rutscht");
		assertTrue(maxStep >= 0.29, "Tempo-Schub beim Rutschen: " + maxStep);
		assertTrue(p.slideCooldown > 0 || p.slideTicks > 0);

		// Zufall normal: auf Eis deutlich öfter als auf Gras (gleicher Weg, gleiche Startwerte)
		PetSim.forceSlide = Double.NaN;
		int grass = slides(new FakeWorld());
		FakeWorld ice = new FakeWorld();
		ice.floorCode = ICE;
		int onIce = slides(ice);
		assertTrue(onIce > grass, "Eis " + onIce + " > Gras " + grass);
		assertTrue(onIce >= 3, "auf Eis mehrmals: " + onIce);
	}

	/** Wie oft ein Begleiter auf 400 Blöcken Weg zu rutschen beginnt (Besitzer läuft im Kreis). */
	private static int slides(FakeWorld w) {
		int total = 0;
		for (int seed = 0; seed < 8; seed++) {
			PetSim sim = new PetSim();
			UUID id = new UUID(seed * 977L, 4242L + seed);
			int prev = Pet.IDLE;
			for (int i = 0; i < 1500; i++) {
				double a = i * 0.012;
				sim.begin(w, i * 50L);
				sim.owner(id, 1, Math.cos(a) * 12, 64, Math.sin(a) * 12, (float) Math.toDegrees(a), false, false, w);
				sim.end();
				Pet p = sim.pets(id).get(0);
				if (p.state() == Pet.SLIDE && prev != Pet.SLIDE) total++;
				prev = p.state();
			}
		}
		return total;
	}

	@Test
	void forgetsOwnersAndClearsOnWorldChange() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		run(sim, w, 3, 0.5, 64, 0.5, 2);
		assertEquals(2, sim.size());
		for (int i = 0; i < PetSim.OWNER_TIMEOUT_TICKS + 2; i++) {
			sim.begin(w, i * 50L);
			sim.end();
		}
		assertEquals(0, sim.size(), "Besitzer lange nicht gesehen");
		run(sim, w, 1, 0.5, 64, 0.5, 1);
		assertEquals(1, sim.size());
		sim.begin(new FakeWorld(), 0);
		assertEquals(0, sim.size(), "andere Welt");
	}

	@Test
	void visibleCullsCapsAndPutsOwnPetsFirst() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		Pet[] out = new Pet[8];
		sim.begin(w, 0);
		sim.owner(OWNER, 2, 0.5, 64, 30.5, 0f, false, true, w);
		sim.owner(OTHER, 3, 0.5, 64, 5.5, 0f, false, false, w);
		sim.end();
		// Kamera bei z = 0 schaut nach Süden (+z): alle vor ihr
		int n = sim.visible(0.5, 65.6, 0, 0, 0, 1, 64, 8, 1f, out);
		assertEquals(5, n);
		assertEquals(OWNER, out[0].owner, "eigene zuerst");
		assertEquals(OWNER, out[1].owner);
		assertEquals(OTHER, out[2].owner);
		assertNull(out[5]);
		// Obergrenze gesamt
		assertEquals(3, sim.visible(0.5, 65.6, 0, 0, 0, 1, 64, 3, 1f, out));
		assertNull(out[3]);
		// Kamera schaut nach Norden: alles dahinter weg
		assertEquals(0, sim.visible(0.5, 65.6, 0, 0, 0, -1, 64, 8, 1f, out));
		// Entfernung
		assertEquals(3, sim.visible(0.5, 65.6, 0, 0, 0, 1, 12, 8, 1f, out));
		// Unsichtbare Besitzer
		sim.begin(w, 50);
		sim.owner(OWNER, 2, 0.5, 64, 30.5, 0f, true, true, w);
		sim.end();
		assertEquals(0, sim.visible(0.5, 65.6, 0, 0, 0, 1, 64, 8, 1f, out), "OTHER diesen Tick nicht gemeldet, OWNER unsichtbar");
	}

	/** Meer: Grund (Oberkante) bei y = 41, Wasser y 41–62 (22 tief, Oberfläche 62,9); ab x ≥ {@code shore} Land (y 64). */
	static final class Ocean implements PetWorld {
		final int shore;

		Ocean(int shore) {
			this.shore = shore;
		}

		@Override
		public int block(int x, int y, int z) {
			if (x >= shore) return y <= 63 ? STONE : 0;
			if (y <= 40) return STONE;
			return y <= 62 ? WATER : 0;
		}

		@Override
		public int light(int x, int y, int z) {
			return PetWorld.packLight(15, 0);
		}
	}

	/**
	 * Besitzer fährt {@code ticks} lang mit {@code speed} Blöcken je Tick nach Süden (+z) auf Höhe {@code y} (Boot:
	 * Unterseite des Boots); liefert den größten Abstand eines Begleiters zum Besitzer in der zweiten Hälfte.
	 */
	private static double ride(PetSim sim, PetWorld w, int count, double y, double speed, int ticks, boolean[] swam,
			boolean[] slid) {
		double worst = 0;
		for (int i = 0; i < ticks; i++) {
			double oz = 0.5 + i * speed;
			sim.begin(w, 100_000 + i * 50L);
			sim.owner(OWNER, count, 0.5, y, oz, 0f, false, true, w);
			sim.end();
			for (Pet p : sim.pets(OWNER)) {
				if (p.state() == Pet.SWIM) swam[0] = true;
				if (p.state() == Pet.SLIDE) slid[0] = true;
				if (i > ticks / 2) worst = Math.max(worst, Math.hypot(p.x - 0.5, p.z - oz));
			}
		}
		return worst;
	}

	private static int teleports(PetSim sim) {
		int n = 0;
		for (Pet p : sim.pets(OWNER)) n += p.teleports;
		return n;
	}

	@Test
	void swimsAlongsideABoatAt8BlocksPerSecondWithoutTeleporting() {
		PetSim.forceSlide = 0;
		PetSim sim = new PetSim();
		Ocean sea = new Ocean(Integer.MAX_VALUE);
		// Boot liegt auf der Oberfläche (Unterseite ≈ 62,6): Begleiter erscheinen schwimmend, nicht auf dem Meeresgrund
		run(sim, sea, 1, 0.5, 62.6, 0.5, 3);
		for (Pet p : sim.pets(OWNER)) assertEquals(62.9 - PetSim.SWIM_DEPTH, p.y, 0.05, "an der Oberfläche abgesetzt");
		boolean[] swam = { false }, slid = { false };
		// 8 Blöcke je Sekunde = 0,4 je Tick, 30 s lang (240 Blöcke weit)
		double worst = ride(sim, sea, 3, 62.6, 0.4, 600, swam, slid);
		assertTrue(swam[0], "schwimmen");
		assertEquals(0, teleports(sim), "kein Teleport – sie schwimmen mit");
		assertTrue(worst < 4.5, "bleiben neben/hinter dem Boot: größter Abstand " + worst);
		for (Pet p : sim.pets(OWNER)) {
			assertEquals(Pet.SWIM, p.state());
			assertEquals(62.9 - PetSim.SWIM_DEPTH, p.y, 0.15, "an der Oberfläche");
			assertTrue(p.z < 0.5 + 599 * 0.4, "hinter (nicht vor) dem Boot");
			// Blick in Fahrtrichtung (Süden, Gieren 0)
			assertEquals(0, Pet.wrap(p.yaw), 25);
		}
	}

	@Test
	void slidesOnTheBellyToKeepUpWithAHorseAndWalksAgainWhenSlow() {
		PetSim.forceSlide = 0;
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		run(sim, w, 1, 0.5, 64, 0.5, 2);
		boolean[] swam = { false }, slid = { false };
		// Pferd im Galopp: 0,6 Blöcke je Tick (12 je Sekunde)
		double worst = ride(sim, w, 2, 64, 0.6, 400, swam, slid);
		assertTrue(slid[0], "schneller Gang = Bauchrutschen");
		assertEquals(0, teleports(sim), "kein Teleport");
		assertTrue(worst < 6.0, "halten mit: größter Abstand " + worst);
		for (Pet p : sim.pets(OWNER)) assertEquals(Pet.SLIDE, p.state(), "rutschen während des Galopps");
		// Besitzer hält an: rutschen aus, wieder watscheln/stehen, ohne Teleport
		double z = 0.5 + 399 * 0.6;
		for (int i = 0; i < 160; i++) {
			sim.begin(w, 200_000 + i * 50L);
			sim.owner(OWNER, 2, 0.5, 64, z, 0f, false, true, w);
			sim.end();
		}
		for (Pet p : sim.pets(OWNER)) {
			assertTrue(p.state() == Pet.IDLE || p.state() == Pet.WALK, "nach dem Anhalten: " + p.stateName());
			assertTrue(Math.hypot(p.x - 0.5, p.z - z) < 4.0);
		}
		assertEquals(0, teleports(sim));
	}

	@Test
	void stuckDetectorIgnoresFastTargetsButTeleportsOnlyAsLastResort() {
		PetSim.forceSlide = 0;
		// Eis-Boot (2 Blöcke je Tick) ist schneller als jeder Pinguin: erst ab 20 Blöcken Abstand nachsetzen – selten
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		run(sim, w, 1, 0.5, 64, 0.5, 1);
		boolean[] swam = { false }, slid = { false };
		ride(sim, w, 1, 64, 2.0, 200, swam, slid);
		int n = teleports(sim);
		assertTrue(n >= 1 && n <= 40, "Teleport nur gelegentlich als letzter Ausweg: " + n);

		// Besitzer fliegt (Elytra) 40 Blöcke über dem Boden langsam weiter: kein Teleport nach oben, sie laufen unten nach
		PetSim sim2 = new PetSim();
		run(sim2, w, 1, 0.5, 64, 0.5, 1);
		for (int i = 0; i < 200; i++) {
			sim2.begin(w, i * 50L);
			sim2.owner(OWNER, 1, 0.5, 104, 0.5 + i * 0.05, 0f, false, true, w);
			sim2.end();
		}
		Pet q = sim2.pets(OWNER).get(0);
		assertEquals(0, q.teleports, "fliegender Besitzer: bleiben am Boden");
		assertEquals(64.0, q.y, 1e-6);
		assertTrue(Math.hypot(q.x - 0.5, q.z - 10.45) < 4.0, "laufen unter ihm nach");

		// Wirklich fest: Besitzer hinter einer hohen Mauer – nach ~3 s zu ihm gesetzt
		PetSim sim3 = new PetSim();
		FakeWorld walled = new FakeWorld();
		walled.box(-40, 64, 4, 40, 70, 4, STONE);
		run(sim3, walled, 1, 0.5, 64, 0.5, 1);
		for (int i = 0; i < 120; i++) {
			sim3.begin(walled, i * 50L);
			sim3.owner(OWNER, 1, 0.5, 64, 12.5, 0f, false, true, walled);
			sim3.end();
		}
		Pet s = sim3.pets(OWNER).get(0);
		assertEquals(1, s.teleports, "festgesteckt → einmal zum Besitzer");
		assertTrue(s.z > 5, "jetzt hinter der Mauer: " + s.z);
	}

	@Test
	void entersDeepWaterFromTheShoreAndSwimsOut() {
		PetSim.forceSlide = 0;
		PetSim sim = new PetSim();
		// Land ab x ≥ 0 (Oberkante 64), davor tiefes Meer. Besitzer steht an Land, fährt dann im Boot hinaus (−x).
		Ocean sea = new Ocean(0);
		sim.begin(sea, 0);
		sim.owner(OWNER, 1, 3.5, 64, 0.5, 90f, false, true, sea);
		sim.end();
		Pet p = sim.pets(OWNER).get(0);
		boolean swam = false;
		for (int i = 0; i < 300; i++) {
			double ox = 3.5 - Math.min(60, i * 0.25);
			double oy = ox < 0 ? 62.6 : 64;
			sim.begin(sea, 50 + i * 50L);
			sim.owner(OWNER, 1, ox, oy, 0.5, 90f, false, true, sea);
			sim.end();
			if (p.state() == Pet.SWIM) swam = true;
		}
		assertTrue(swam, "ins Wasser gegangen");
		assertEquals(0, p.teleports, "nicht ans Ufer geklemmt und dann teleportiert");
		assertTrue(p.x < -50, "mit hinausgeschwommen: x=" + p.x);
	}

	@Test
	void stateBlendAndDrawTransform() {
		Pet p = new Pet(OWNER, 0, 1L);
		p.setState(Pet.WALK, 1000);
		assertEquals(0f, p.blend(1000), 1e-6);
		assertTrue(p.blend(1125) > 0.4f && p.blend(1125) < 0.6f);
		assertEquals(1f, p.blend(1250), 1e-6);
		assertEquals(Pet.IDLE, p.prevState());
		assertEquals("walk", p.stateName());
		assertEquals(dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2.DRIVER_SLIDE, Pet.driver(Pet.SLIDE));
		assertEquals(dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2.DRIVER_SWIM, Pet.driver(Pet.SWIM));

		PetDraw<String> d = new PetDraw<String>();
		// Begleiter 2 Blöcke südlich der Kamera, schaut nach Westen (Gieren 90 → vorne = −x)
		d.set(0, -1, 2, 90);
		final float[] v = new float[6];
		d.wrap((x, y, z, u, vv, nx, ny, nz, argb) -> {
			v[0] = x;
			v[1] = y;
			v[2] = z;
			v[3] = nx;
			v[4] = ny;
			v[5] = nz;
		}).vertex(0, -1f, -1f, 0, 0, 0, 0, -1, -1); // Modell (0, 16, 16): 1 hoch, 1 vorne; Normale vorne
		assertEquals(-1, v[0], 1e-5, "vorne = Westen");
		assertEquals(0, v[1], 1e-5, "1 Block über dem Boden (Boden 1 unter der Kamera)");
		assertEquals(2, v[2], 1e-5);
		assertEquals(-1, v[3], 1e-5);
		assertEquals(0, v[5], 1e-5);
		// Kamera im Modellraum: 2 Blöcke nördlich = rechts vom Begleiter (−x), 1 Block höher
		assertEquals(-32, d.eye[0], 1e-6);
		assertEquals(16, d.eye[1], 1e-6);
		assertEquals(0, d.eye[2], 1e-6);
		assertNotNull(d.wrap((x, y, z, u, vv, nx, ny, nz, argb) -> { }));
		d.light = PetWorld.packLight(13, 7);
		assertEquals(13, d.skyLight());
		assertEquals(7, d.blockLight());
	}
}

package dev.theredstonee.trsclient.core.cosmetic.pet;

import dev.theredstonee.trsclient.core.cosmetic.pet.PetSimTest.FakeWorld;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Überfall-Begleiter (Anhang E, „Schwarzes Schaf“): neue Treiber/Grenze im Format, Schleichen, Beute schnappen mit Flucht,
 * Hände hoch beim Ansehen, Vorrang der Zustände und Rückfall ohne die passenden Animationen.
 */
class PetHeistTest {
	static final UUID OWNER = PetSimTest.OWNER;
	static final UUID OTHER = PetSimTest.OTHER;
	static final int ALL = Pet.CAN_SNEAK | Pet.CAN_GRAB | Pet.CAN_FLEE | Pet.CAN_HANDSUP;

	@AfterEach
	void reset() {
		PetSim.forceSlide = Double.NaN;
		PetSim.forceGrab = false;
	}

	/** Ein Tick mit einem Besitzer (ein Begleiter, Fähigkeiten {@code can}). */
	private static void tick(PetSim sim, FakeWorld w, int i, double ox, double oz, int can, boolean sneaking) {
		sim.begin(w, i * 50L);
		sim.owner(OWNER, 1, 1.0, null, new int[] { can }, ox, 64, oz, 0f, sneaking, false, true, w);
		sim.end();
	}

	private static Pet pet(PetSim sim) {
		return sim.pets(OWNER).get(0);
	}

	// --- Format -----------------------------------------------------------------------------------------------

	static String model(String... drivers) {
		StringBuilder a = new StringBuilder();
		for (int i = 0; i < drivers.length; i++) {
			if (i > 0) a.append(',');
			a.append("{\"id\":\"a").append(i).append("\",\"driver\":\"").append(drivers[i]).append("\",\"lengthMs\":1000,\"tracks\":[")
					.append("{\"bone\":\"body\",\"channel\":\"rotation\",\"keys\":[{\"t\":0,\"v\":[0,0,0]},{\"t\":1000,\"v\":[0,10,0]}]}]}");
		}
		return "{\"format\":2,\"id\":\"test_heist\",\"name\":\"Test\",\"slot\":\"pet\",\"attach\":\"ground\","
				+ "\"texture\":{\"file\":\"t.png\",\"width\":16,\"height\":16,\"scale\":1},"
				+ "\"bones\":[{\"id\":\"body\",\"pivot\":[0,1,0]}],"
				+ "\"cubes\":[{\"id\":\"b\",\"bone\":\"body\",\"from\":[-2,0,-2],\"to\":[2,6,2],\"faces\":{\"south\":{\"uv\":[0,0,4,6]}}}],"
				+ "\"animations\":[" + a + "]}";
	}

	@Test
	void formatAcceptsTheNewDriversAndTwelveAnimations() {
		String[] nine = { "idle", "walk", "slide", "swim", "air", "sneak", "grab", "flee", "handsup" };
		CosmeticV2.Check c = CosmeticV2.check(model(nine), -1, -1, -1, -1);
		assertTrue(c.ok(), c.errors.toString());
		assertEquals(ALL, Pet.abilitiesOf(c.model));
		assertEquals(7, CosmeticV2.driverIndex("grab"));
		assertEquals(8, CosmeticV2.driverIndex("flee"));
		assertEquals(9, CosmeticV2.driverIndex("handsup"));
		String[] twelve = { "idle", "walk", "slide", "swim", "air", "sneak", "grab", "flee", "handsup", "idle", "walk", "jump" };
		assertTrue(CosmeticV2.check(model(twelve), -1, -1, -1, -1).ok(), "12 Animationen erlaubt");
		String[] thirteen = { "idle", "walk", "slide", "swim", "air", "sneak", "grab", "flee", "handsup", "idle", "walk", "jump",
				"air" };
		assertFalse(CosmeticV2.check(model(thirteen), -1, -1, -1, -1).ok(), "13 zu viele");
		assertFalse(CosmeticV2.check(model("idle", "dance"), -1, -1, -1, -1).ok(), "unbekannter Treiber");
		// normaler Pinguin: kann nichts davon
		CosmeticV2.Check plain = CosmeticV2.check(model("idle", "walk", "slide", "swim", "air"), -1, -1, -1, -1);
		assertEquals(0, Pet.abilitiesOf(plain.model));
		assertEquals(CosmeticV2.DRIVER_GRAB, Pet.driver(Pet.GRAB));
		assertEquals(CosmeticV2.DRIVER_HANDSUP, Pet.driver(Pet.HANDSUP));
		assertEquals("handsup", Pet.stateNameOf(Pet.HANDSUP));
		assertTrue(Pet.fromStateStart(Pet.GRAB));
		assertFalse(Pet.fromStateStart(Pet.FLEE));
	}

	// --- Schleichen -------------------------------------------------------------------------------------------

	@Test
	void sneaksSlowlyBehindASneakingOwnerOnlyWhenTheModelCan() {
		for (int can : new int[] { ALL, 0 }) {
			PetSim sim = new PetSim();
			FakeWorld w = new FakeWorld();
			PetSim.forceSlide = 0;
			for (int i = 0; i < 20; i++) tick(sim, w, i, 0.5, 0.5, can, false);
			Pet p = pet(sim);
			double maxStep = 0;
			boolean sneakSeen = false;
			// Besitzer schleicht 4 Blöcke nach Süden (0,065 je Tick)
			for (int i = 0; i < 80; i++) {
				tick(sim, w, 20 + i, 0.5, 0.5 + i * 0.05, can, true);
				if (i > 30) maxStep = Math.max(maxStep, Math.hypot(p.x - p.px, p.z - p.pz));
				sneakSeen |= p.state() == Pet.SNEAK;
			}
			if (can == ALL) {
				assertTrue(sneakSeen, "schleicht");
				assertTrue(maxStep < 0.08, "langsam: " + maxStep);
			} else {
				assertFalse(sneakSeen, "ohne sneak-Animation kein Schleichen");
			}
		}
	}

	// --- Beute schnappen + Flucht -----------------------------------------------------------------------------

	@Test
	void grabsOnceFacingANearbyPlayerThenFleesPastTheOwner() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		PetSim.forceSlide = 0;
		for (int i = 0; i < 60; i++) tick(sim, w, i, 0.5, 0.5, ALL, false);
		Pet p = pet(sim);
		assertEquals(Pet.IDLE, p.state());
		// anderer Spieler 3 Blöcke östlich des Begleiters (ohne eigene Begleiter)
		double ox = p.x + 3, oz = p.z;
		PetSim.forceGrab = true;
		int grabTicks = 0, fleeTicks = 0, i = 60;
		double startX = p.x, startZ = p.z;
		double fleeSpeed = 0;
		boolean grabbed = false, fled = false;
		for (; i < 260; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OTHER, 0, 1.0, null, null, ox, 64, oz, 0f, false, false, false, w);
			sim.owner(OWNER, 1, 1.0, null, new int[] { ALL }, 0.5, 64, 0.5, 0f, false, false, true, w);
			sim.end();
			if (p.state() == Pet.GRAB) {
				grabbed = true;
				PetSim.forceGrab = false;
				grabTicks++;
			}
			if (p.state() == Pet.FLEE) {
				fled = true;
				fleeTicks++;
				fleeSpeed = Math.max(fleeSpeed, Math.hypot(p.x - p.px, p.z - p.pz));
			}
			if (fled && p.state() != Pet.FLEE) break;
		}
		assertTrue(grabbed && fled, "schnappt und flieht");
		assertEquals(PetSim.GRAB_TICKS, grabTicks, 1, "einmal 2400 ms");
		assertTrue(fleeTicks >= PetSim.FLEE_MIN - 2 && fleeTicks <= PetSim.FLEE_MAX, "Flucht 2,5–4 s: " + fleeTicks);
		assertTrue(fleeSpeed > PetSim.WALK_SPEED * 1.4, "Sprint: " + fleeSpeed);
		assertTrue(Math.hypot(startX - p.x, startZ - p.z) > 0.1, "hat sich bewegt");
		// danach wieder normal, nächste Gelegenheit erst nach 45–120 s
		assertTrue(p.grabTimer >= PetSim.GRAB_MIN && p.grabTimer <= PetSim.GRAB_MAX, "neue Wartezeit " + p.grabTimer);
	}

	@Test
	void backRowEscortMemberRestsAndCanGrab() {
		// PixelCST: Pinguin + Kaiser + Mütze + Sonnenbrille + Schwarzes Schaf (5. Platz, 3. Reihe)
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		PetSim.forceSlide = 0;
		int[] can = { 0, 0, 0, 0, ALL };
		double[] extras = { 1.25, 1, 1, 1 };
		for (int i = 0; i < 120; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OWNER, 1, 1.0, extras, can, 0.5, 64, 0.5, 0f, false, false, true, w);
			sim.end();
		}
		Pet heist = sim.pets(OWNER).get(4);
		assertEquals(4, heist.kind);
		assertEquals(ALL, heist.abilities());
		assertEquals(Pet.IDLE, heist.state(), "hinterer Platz kommt zur Ruhe");
		PetSim.forceGrab = true;
		boolean grabbed = false;
		for (int i = 120; i < 140 && !grabbed; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OWNER, 1, 1.0, extras, can, 0.5, 64, 0.5, 0f, false, false, true, w);
			sim.end();
			grabbed = heist.state() == Pet.GRAB;
			for (int k = 0; k < 4; k++) assertFalse(sim.pets(OWNER).get(k).state() == Pet.GRAB, "nur wer es kann");
		}
		assertTrue(grabbed);
	}

	@Test
	void turnsTowardsTheNearbyPlayerWhileGrabbing() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		PetSim.forceSlide = 0;
		for (int i = 0; i < 60; i++) tick(sim, w, i, 0.5, 0.5, ALL, false);
		Pet p = pet(sim);
		double ox = p.x + 3, oz = p.z; // Osten = +x → Gieren −90
		PetSim.forceGrab = true;
		for (int i = 60; i < 80; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OTHER, 0, 1.0, null, null, ox, 64, oz, 0f, false, false, false, w);
			sim.owner(OWNER, 1, 1.0, null, new int[] { ALL }, 0.5, 64, 0.5, 0f, false, false, true, w);
			sim.end();
			if (p.state() == Pet.GRAB) PetSim.forceGrab = false;
		}
		assertEquals(Pet.GRAB, p.state());
		// (vor dem Schnappen kann er noch ein Stück gewatschelt sein – Richtung von seinem jetzigen Platz)
		double want = Math.toDegrees(Math.atan2(-(ox - p.x), oz - p.z));
		assertEquals(want, p.yaw, 8.0, "zum anderen Spieler gedreht");
	}

	@Test
	void neverGrabsWithoutTheAnimationAndFleesAsWalkWithoutFlee() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		PetSim.forceSlide = 0;
		PetSim.forceGrab = true;
		for (int i = 0; i < 200; i++) {
			tick(sim, w, i, 0.5, 0.5, Pet.CAN_SNEAK | Pet.CAN_FLEE | Pet.CAN_HANDSUP, false);
			assertFalse(pet(sim).state() == Pet.GRAB || pet(sim).state() == Pet.FLEE, "ohne grab nie schnappen/fliehen");
		}
		PetSim sim2 = new PetSim();
		boolean grabbed = false, walkedAway = false;
		for (int i = 0; i < 200; i++) {
			tick(sim2, w, i, 0.5, 0.5, Pet.CAN_GRAB, false);
			Pet p = pet(sim2);
			assertFalse(p.state() == Pet.FLEE, "ohne flee-Animation kein FLEE-Zustand");
			if (p.state() == Pet.GRAB) grabbed = true;
			if (grabbed && p.state() == Pet.WALK) walkedAway = true;
		}
		assertTrue(grabbed && walkedAway, "Flucht im Lauf-Zustand");
	}

	// --- Hände hoch -------------------------------------------------------------------------------------------

	/** Kamera 3 Blöcke vor dem Begleiter (Süden), Blick auf seine Mitte bzw. daneben. */
	private static void look(PetSim sim, Pet p, boolean at) {
		double cx = p.x, cy = p.y + 1.2, cz = p.z + 3.0;
		double tx = at ? p.x : p.x + 2.0, ty = p.y + 0.4, tz = p.z;
		double dx = tx - cx, dy = ty - cy, dz = tz - cz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		sim.viewer(cx, cy, cz, dx / d, dy / d, dz / d);
	}

	@Test
	void raisesFlippersWhenLookedAtHoldsOneSecondThenCoolsDown() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		PetSim.forceSlide = 0;
		for (int i = 0; i < 60; i++) tick(sim, w, i, 0.5, 0.5, ALL, false);
		Pet p = pet(sim);
		int i = 60;
		int firstUp = -1;
		for (; i < 100; i++) {
			look(sim, p, true);
			tick(sim, w, i, 0.5, 0.5, ALL, false);
			if (p.state() == Pet.HANDSUP && firstUp < 0) firstUp = i - 60;
		}
		assertTrue(firstUp >= PetSim.HANDSUP_LOOK - 1 && firstUp <= PetSim.HANDSUP_LOOK + 1, "nach 0,4 s: " + firstUp);
		assertEquals(Pet.HANDSUP, p.state(), "solange angesehen");
		// wegsehen: noch ~1 s oben, dann runter
		int down = -1;
		for (int k = 0; k < 40; k++, i++) {
			look(sim, p, false);
			tick(sim, w, i, 0.5, 0.5, ALL, false);
			if (p.state() != Pet.HANDSUP && down < 0) down = k;
		}
		assertTrue(down >= PetSim.HANDSUP_HOLD - 2 && down <= PetSim.HANDSUP_HOLD + 1, "+1 s: " + down);
		// sofort wieder ansehen: Abklingzeit 8 s
		for (int k = 0; k < 40; k++, i++) {
			look(sim, p, true);
			tick(sim, w, i, 0.5, 0.5, ALL, false);
			assertFalse(p.state() == Pet.HANDSUP, "Abklingzeit");
		}
	}

	@Test
	void handsUpInterruptsGrabbingAndNeedsTheAnimation() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		PetSim.forceSlide = 0;
		for (int i = 0; i < 60; i++) tick(sim, w, i, 0.5, 0.5, ALL, false);
		Pet p = pet(sim);
		PetSim.forceGrab = true;
		int i = 60;
		for (; i < 70; i++) {
			tick(sim, w, i, 0.5, 0.5, ALL, false);
			if (p.state() == Pet.GRAB) PetSim.forceGrab = false;
		}
		assertEquals(Pet.GRAB, p.state());
		for (int k = 0; k < 12; k++, i++) {
			look(sim, p, true);
			tick(sim, w, i, 0.5, 0.5, ALL, false);
		}
		assertEquals(Pet.HANDSUP, p.state(), "Hände hoch unterbricht das Schnappen");
		assertEquals(0, p.grabTicks);
		// ohne handsup-Animation: nie
		PetSim sim2 = new PetSim();
		for (int k = 0; k < 60; k++) tick(sim2, w, k, 0.5, 0.5, Pet.CAN_SNEAK, false);
		Pet q = pet(sim2);
		for (int k = 60; k < 100; k++) {
			look(sim2, q, true);
			tick(sim2, w, k, 0.5, 0.5, Pet.CAN_SNEAK, false);
			assertFalse(q.state() == Pet.HANDSUP);
		}
	}

	@Test
	void swimmingBeatsHandsUp() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		w.box(-8, 60, -8, 8, 63, 8, PetSimTest.WATER);
		w.box(-8, 59, -8, 8, 59, 8, PetSimTest.STONE);
		PetSim.forceSlide = 0;
		for (int i = 0; i < 120; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OWNER, 1, 1.0, null, new int[] { ALL }, 0.5, 63.6, 0.5, 0f, false, false, true, w);
			sim.end();
		}
		Pet p = pet(sim);
		for (int i = 120; i < 160; i++) {
			look(sim, p, true);
			sim.begin(w, i * 50L);
			sim.owner(OWNER, 1, 1.0, null, new int[] { ALL }, 0.5, 63.6, 0.5, 0f, false, false, true, w);
			sim.end();
			assertEquals(Pet.SWIM, p.state());
		}
	}
}

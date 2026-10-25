package dev.theredstonee.trsclient.core.cosmetic.pet;

import dev.theredstonee.trsclient.core.cosmetic.pet.PetSimTest.FakeWorld;
import dev.theredstonee.trsclient.core.cosmetic.v2.CosmeticV2Renderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gefolge eines Creators (Haupt-Teil × Anzahl + bis zu 4 Extras, je eigene Größe, z. B. Kaiserpinguin 1,25): Formation
 * mit Abstand nach Größe, eigener Zeitversatz, Obergrenze 8, Bodenkontakt, Schwimmtiefe, Stufen, Kopffreiheit, Wechsel
 * des Gefolges und das Zeichnen in Größe.
 */
class PetEscortTest {
	static final UUID OWNER = PetSimTest.OWNER;
	static final int STONE = PetSimTest.STONE;
	static final int WATER = PetSimTest.WATER;
	/** PixelCST: 1 Pinguin (nach Fans) + Kaiserpinguin (1,25) + Mütze + Sonnenbrille. */
	static final double[] PIXELCST = { 1.25, 1.0, 1.0 };

	@AfterEach
	void reset() {
		PetSim.forceSlide = Double.NaN;
	}

	private static void run(PetSim sim, PetWorld w, int from, int ticks, double ox, double oy, double oz, int count,
			double scale, double[] extras) {
		for (int i = from; i < from + ticks; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OWNER, count, scale, extras, ox, oy, oz, 0f, false, true, w);
			sim.end();
		}
	}

	@Test
	void mainCountPlusEachExtraWithOwnKindScaleAndTime() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		PetSim.forceSlide = 0;
		run(sim, w, 0, 200, 0.5, 64, 0.5, 1, 1.0, PIXELCST);
		List<Pet> pets = sim.pets(OWNER);
		assertEquals(4, pets.size(), "1 × Haupt-Teil + 3 Extras");
		assertEquals(0, pets.get(0).kind);
		assertEquals(1, pets.get(1).kind);
		assertEquals(2, pets.get(2).kind);
		assertEquals(3, pets.get(3).kind);
		assertEquals(1.25, pets.get(1).scale, 1e-9, "Kaiserpinguin");
		Set<Long> offsets = new HashSet<Long>();
		for (Pet p : pets) offsets.add(p.offsetMs);
		assertEquals(4, offsets.size(), "eigener Zeitversatz je Begleiter");
		for (Pet p : pets) assertEquals(64.0, p.y, 1e-6, "alle mit den Füßen am Boden (auch der große)");
		// Abstand nach Größe: nie ineinander (Summe der Radien, etwas Spiel fürs sanfte Auseinanderschieben)
		for (int i = 0; i < pets.size(); i++) {
			for (int j = i + 1; j < pets.size(); j++) {
				Pet a = pets.get(i), b = pets.get(j);
				double d = Math.hypot(a.x - b.x, a.z - b.z);
				assertTrue(d > 0.9 * (a.radius + b.radius), "Abstand " + i + "/" + j + " = " + d);
			}
			Pet a = pets.get(i);
			double toOwner = Math.hypot(a.x - 0.5, a.z - 0.5);
			assertTrue(toOwner > 0.6 && toOwner < 5.5, "beim Besitzer: " + toOwner);
		}
	}

	@Test
	void fourMainPlusFourExtrasIsTheLimit() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		run(sim, w, 0, 5, 0.5, 64, 0.5, 4, 1.0, new double[] { 1.25, 1, 1, 1, 0.8 });
		List<Pet> pets = sim.pets(OWNER);
		assertEquals(PetSim.MAX_PER_OWNER, pets.size(), "höchstens 8 je Besitzer");
		assertEquals(8, PetSim.MAX_PER_OWNER);
		for (int i = 0; i < 4; i++) assertEquals(0, pets.get(i).kind);
		assertEquals(4, pets.get(7).kind, "das 5. Extra fällt weg");
		// Ohne Haupt-Teil kein Gefolge
		run(sim, w, 5, 1, 0.5, 64, 0.5, 0, 1.0, PIXELCST);
		assertTrue(sim.pets(OWNER).isEmpty());
	}

	@Test
	void oldCallWithoutExtrasStillWorks() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		for (int i = 0; i < 20; i++) {
			sim.begin(w, i * 50L);
			sim.owner(OWNER, 2, 0.5, 64, 0.5, 0f, false, true, w);
			sim.end();
		}
		List<Pet> pets = sim.pets(OWNER);
		assertEquals(2, pets.size());
		for (Pet p : pets) {
			assertEquals(0, p.kind);
			assertEquals(1.0, p.scale, 0);
		}
	}

	@Test
	void layoutMovesBigPetsOutwardAndLaterRowsBack() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		// 2 normale, dahinter der Kaiserpinguin, dann noch einer, dann zwei weitere
		run(sim, w, 0, 1, 0.5, 64, 0.5, 2, 1.0, new double[] { 1.25, 1, 1, 1 });
		List<Pet> pets = sim.pets(OWNER);
		double[][] slots = PetSim.layout(pets);
		assertEquals(6, slots.length);
		assertEquals(PetSim.SLOTS[0][0], slots[0][0], 1e-9, "Größe 1: Plätze wie bisher");
		assertEquals(PetSim.SLOTS[1][1], slots[1][1], 1e-9);
		assertTrue(slots[2][0] < PetSim.SLOTS[2][0], "der große rückt nach außen");
		assertTrue(slots[2][1] > PetSim.SLOTS[2][1], "… und etwas zurück");
		assertEquals(PetSim.SLOTS[3][1], slots[3][1], 1e-9, "sein Nachbar in der Reihe bleibt");
		assertTrue(slots[4][1] > PetSim.SLOTS[4][1] + 0.2, "Reihe dahinter rückt zurück");
		assertTrue(slots[5][1] > PetSim.SLOTS[5][1] + 0.2);
	}

	@Test
	void changingTheEscortKeepsPlacesAndReplacesOnlyChangedOnes() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		PetSim.forceSlide = 0;
		run(sim, w, 0, 60, 0.5, 64, 0.5, 1, 1.0, PIXELCST);
		List<Pet> before = new java.util.ArrayList<Pet>(sim.pets(OWNER));
		// Gefolge ausgeblendet („Gefolge zeigen“ aus) → nur der eigene Pinguin bleibt, unverändert
		run(sim, w, 60, 1, 0.5, 64, 0.5, 1, 1.0, null);
		assertEquals(1, sim.pets(OWNER).size());
		assertSame(before.get(0), sim.pets(OWNER).get(0), "Haupt-Begleiter bleibt derselbe");
		// wieder an, aber andere Reihenfolge: Platz 1 ist jetzt ein normaler, Platz 2 der Kaiser
		run(sim, w, 61, 1, 0.5, 64, 0.5, 1, 1.0, PIXELCST);
		Pet emperor = sim.pets(OWNER).get(1);
		run(sim, w, 62, 1, 0.5, 64, 0.5, 1, 1.0, new double[] { 1.0, 1.25, 1.0 });
		List<Pet> now = sim.pets(OWNER);
		assertEquals(4, now.size());
		assertFalse(now.get(1) == emperor, "anderes Teil am Platz → neuer Begleiter");
		assertEquals(1.0, now.get(1).scale, 0);
		assertEquals(emperor.x, now.get(1).px, 1e-9, "… der dort weitermacht, wo der alte stand");
		assertEquals(emperor.z, now.get(1).pz, 1e-9);
		assertEquals(1.25, now.get(2).scale, 0);
	}

	@Test
	void emperorSwimsDeeperThanTheOthers() {
		PetSim sim = new PetSim();
		FakeWorld w = new FakeWorld();
		// Becken 4 tief um den Besitzer: Wasser y 60…63 (Oberfläche 63,9), Grund 59
		w.box(-8, 60, -8, 8, 63, 8, WATER);
		w.box(-8, 59, -8, 8, 59, 8, STONE);
		PetSim.forceSlide = 0;
		run(sim, w, 0, 200, 0.5, 63.6, 0.5, 1, 1.0, new double[] { 1.25 });
		List<Pet> pets = sim.pets(OWNER);
		Pet normal = pets.get(0), emperor = pets.get(1);
		assertEquals(Pet.SWIM, normal.state());
		assertEquals(Pet.SWIM, emperor.state());
		assertEquals(63.9 - PetSim.SWIM_DEPTH, normal.y, 0.03);
		assertEquals(63.9 - PetSim.SWIM_DEPTH * 1.25, emperor.y, 0.03, "tiefer im Wasser, weil größer");
	}

	@Test
	void stepAndHeadroomGrowWithSize() {
		FakeWorld w = new FakeWorld();
		// Absatz 0,6875 hoch bei x = 1 (11/16, z. B. Schnee in Schichten), sonst Boden 64
		w.set(1, 64, 0, PetWorld.code(PetWorld.SOLID, 0.6875));
		PetSim sim = new PetSim();
		Pet small = new Pet(OWNER, 0, 1L, 0, 1.0);
		Pet big = new Pet(OWNER, 1, 2L, 1, 1.25);
		for (Pet p : new Pet[] { small, big }) {
			p.x = 0.7;
			p.y = 64;
			p.z = 0.5;
			p.onGround = true;
		}
		sim.move(small, w, 0.4, 0, false);
		sim.move(big, w, 0.4, 0, false);
		assertEquals(64.0, small.y, 1e-9, "normal: zu hoch ohne Sprung");
		assertTrue(small.vy > 0, "… springt");
		assertEquals(64.6875, big.y, 1e-9, "Kaiser: geht den Absatz einfach hoch");
		assertEquals(1.1, big.x, 1e-9);
		// Kopffreiheit: Decke 0,9 über dem Boden reicht für Größe 1 (0,8), nicht für 1,25 (1,0)
		// Boden = Platte 0,15 in y 64, Decke = ganzer Block bei y 65
		FakeWorld cave = new FakeWorld();
		cave.set(3, 64, 0, PetWorld.code(PetWorld.SOLID, 0.15));
		cave.set(3, 65, 0, STONE);
		assertTrue(PetSim.headroom(cave, 3.5, 0.5, 64.15, small.height), "0,85 Platz reicht für 0,8");
		assertFalse(PetSim.headroom(cave, 3.5, 0.5, 64.15, big.height), "… nicht für 1,0");
	}

	@Test
	void drawScalesAroundTheGroundAndTheEyeShrinks() {
		PetDraw<Object> d = new PetDraw<Object>();
		d.set(2.0, -1.0, 3.0, 0.0, 1.25);
		final float[] got = new float[3];
		CosmeticV2Renderer.VertexSink sink = d.wrap(new CosmeticV2Renderer.VertexSink() {
			@Override
			public void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int argb) {
				got[0] = x;
				got[1] = y;
				got[2] = z;
			}
		});
		// Ursprung bleibt am Boden
		sink.vertex(0, 0, 0, 0, 0, 0, 1, 0, -1);
		assertEquals(2.0, got[0], 1e-6);
		assertEquals(-1.0, got[1], 1e-6);
		assertEquals(3.0, got[2], 1e-6);
		// 0,5 Blöcke über dem Boden (ModelPart-Raum y nach unten) → 0,625 bei Größe 1,25
		sink.vertex(0, -0.5f, 0, 0, 0, 0, 1, 0, -1);
		assertEquals(-1.0 + 0.625, got[1], 1e-6);
		PetDraw<Object> plain = new PetDraw<Object>();
		plain.set(2.0, -1.0, 3.0, 0.0);
		assertEquals(1.0, plain.scale, 0);
		assertEquals(plain.eye[2] / 1.25, d.eye[2], 1e-9, "Kamera im Modell-Raum: näher, weil größer");
	}
}

package dev.theredstonee.trsclient.core.circuit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Aufbau der Bibliothek, Texte, Drehen/Spiegeln, Abgleich mit der Welt (modern + 1.8.9-Namen), Speichern. */
class CircuitLibraryTest {
	private static final CircuitLibrary LIB = CircuitLibrary.load(true);

	@Test
	void libraryIsCompleteAndPlausible() {
		assertTrue(LIB.all().size() >= 20 && LIB.all().size() <= 30, "Anzahl: " + LIB.all().size());
		EnumSet<Circuit.Category> cats = EnumSet.noneOf(Circuit.Category.class);
		Set<String> ids = new HashSet<String>();
		for (Circuit c : LIB.all()) {
			assertTrue(ids.add(c.id), "doppelt: " + c.id);
			cats.add(c.category);
			assertTrue(c.sizeX <= 16 && c.sizeY <= 16 && c.sizeZ <= 16, c.id);
			assertTrue(c.blockCount() > 0, c.id);
			assertTrue(c.difficulty >= 1 && c.difficulty <= 3, c.id);
			assertFalse(c.materials().isEmpty(), c.id);
			// jeder Test-Anschluss existiert
			for (com.google.gson.JsonElement t : c.tests) {
				String s = t.toString();
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(?:in|out|mark|press|bump)\":\\[?\"([A-Za-z0-9]+)\"").matcher(s);
				while (m.find()) assertNotNull(c.marker(m.group(1)), c.id + ": Anschluss " + m.group(1));
			}
		}
		assertEquals(EnumSet.allOf(Circuit.Category.class), cats);
		assertTrue(LIB.errors().isEmpty(), LIB.errors().toString());
	}

	@Test
	void versionsAreDerivedFromBlocks() {
		assertEquals("1.11", LIB.byId("observer_clock").since);
		assertEquals("1.11", LIB.byId("edge_observer").since);
		assertEquals("1.21", LIB.byId("t_flipflop_copper").since);
		assertEquals("1.13", LIB.byId("item_elevator").since);
		assertEquals("1.8", LIB.byId("t_flipflop").since);
		assertFalse(LIB.byId("t_flipflop_copper").runsIn("1.20.6"));
		assertTrue(LIB.byId("t_flipflop_copper").runsIn("1.21.11"));
		assertTrue(LIB.byId("t_flipflop_copper").runsIn("26.3"));
		int in189 = LIB.filter(null, null, "1.8.9", null).size();
		assertTrue(in189 >= 20 && in189 < LIB.all().size(), "1.8.9: " + in189);
		assertEquals(LIB.all().size(), LIB.filter("", null, "26.3", null).size());
	}

	@Test
	void textsExistInFullLanguages() {
		for (String lang : new String[] {"en", "de", "es"}) {
			CircuitTexts t = CircuitTexts.load(lang);
			for (Circuit c : LIB.all()) {
				assertTrue(c.hasText(lang, "name"), lang + " " + c.id + ".name");
				assertTrue(c.hasText(lang, "desc"), lang + " " + c.id + ".desc");
				if (!c.serverOk) assertTrue(c.hasText(lang, "note"), lang + " " + c.id + ".note");
			}
			for (BlockDef d : LIB.catalog().all()) assertTrue(t.hasOwn(d.labelKey()), lang + " " + d.labelKey());
			for (Circuit.Category cat : Circuit.Category.values()) assertTrue(t.hasOwn("category." + cat.id), lang + " " + cat);
		}
		// Beta-Sprachen: Rückfall auf Englisch
		CircuitTexts fr = CircuitTexts.load("fr");
		assertEquals(CircuitTexts.load("en").name(LIB.byId("not_gate")), fr.name(LIB.byId("not_gate")));
		assertTrue(CircuitTexts.load("de").name(LIB.byId("not_gate")).startsWith("NICHT"));
	}

	@Test
	void searchFindsByNameAndBlock() {
		CircuitTexts de = CircuitTexts.load("de");
		assertTrue(LIB.filter("xor", null, null, de).contains(LIB.byId("xor_gate")));
		assertTrue(LIB.filter("Beobachter", null, null, de).contains(LIB.byId("observer_clock")));
		assertEquals(0, LIB.filter("gibtsnicht", null, null, de).size());
		for (Circuit c : LIB.filter(null, Circuit.Category.MEMORY, null, de)) assertEquals(Circuit.Category.MEMORY, c.category);
	}

	@Test
	void rotationAndMirror() {
		Circuit c = LIB.byId("xor_gate"); // 5 × 1 × 7
		int[] out = new int[3];
		Placement p0 = new Placement(100, 64, 200, 0, false);
		p0.toWorld(c, 0, 0, 0, out);
		assertEquals(100, out[0]);
		assertEquals(200, out[2]);
		Placement p1 = new Placement(0, 0, 0, 1, false);
		// Norden (z=0) wandert nach Osten (x = Tiefe-1)
		p1.toWorld(c, 0, 0, 0, out);
		assertEquals(c.sizeZ - 1, out[0]);
		assertEquals(0, out[2]);
		assertEquals("east", p1.direction("north"));
		assertEquals("south", p1.direction("east"));
		assertEquals("up", p1.direction("up"));
		assertEquals(c.sizeZ, p1.width(c));
		assertEquals(c.sizeX, p1.depth(c));
		Placement m = new Placement(0, 0, 0, 0, true);
		m.toWorld(c, 0, 0, 0, out);
		assertEquals(c.sizeX - 1, out[0]);
		assertEquals("west", m.direction("east"));
		assertEquals("north", m.direction("north"));
		// vier Vierteldrehungen = Ausgangslage
		for (Circuit.Cell cell : c.cells) {
			int[] a = new int[3];
			new Placement(5, 6, 7, 0, false).toWorld(c, cell.x, cell.y, cell.z, a);
			int[] b = new int[3];
			new Placement(5, 6, 7, 4, false).toWorld(c, cell.x, cell.y, cell.z, b);
			assertEquals(a[0], b[0]);
			assertEquals(a[2], b[2]);
		}
		Placement centered = Placement.centered(c, 10, 64, 10, 0, false);
		assertEquals(10 - (c.sizeX - 1) / 2, centered.x);
		assertEquals(0, Circuits.rotationFromYaw(180f)); // Blick nach Norden
		assertEquals(1, Circuits.rotationFromYaw(270f)); // Osten
		assertEquals(2, Circuits.rotationFromYaw(0f));   // Süden
		assertEquals(3, Circuits.rotationFromYaw(90f));  // Westen
		assertEquals(0, Circuits.rotationFromYaw(-180f));
	}

	/** Einfache Welt: Position → (ID, Eigenschaften, leitend). */
	static final class FakeWorld implements CircuitWorld {
		final Map<Long, String> ids = new HashMap<Long, String>();
		final Map<Long, Map<String, String>> props = new HashMap<Long, Map<String, String>>();
		final Set<Long> conductors = new HashSet<Long>();
		final boolean legacy;

		FakeWorld(boolean legacy) {
			this.legacy = legacy;
		}

		void set(int x, int y, int z, String id, Map<String, String> p, boolean conductor) {
			long k = dev.theredstonee.trsclient.core.redstone.Dir.pack(x, y, z);
			ids.put(k, id);
			props.put(k, p == null ? new HashMap<String, String>() : new HashMap<String, String>(p));
			if (conductor) conductors.add(k);
			else conductors.remove(k);
		}

		@Override
		public String block(int x, int y, int z, Map<String, String> out) {
			long k = dev.theredstonee.trsclient.core.redstone.Dir.pack(x, y, z);
			String id = ids.get(k);
			if (id == null) return "minecraft:air";
			out.putAll(props.get(k));
			return id;
		}

		@Override
		public boolean conductor(int x, int y, int z) {
			return conductors.contains(dev.theredstonee.trsclient.core.redstone.Dir.pack(x, y, z));
		}

		@Override
		public boolean legacy() {
			return legacy;
		}
	}

	/** Baut eine Schaltung so in die Welt, wie ein Spieler es tun würde (modern bzw. mit 1.8.9-Namen). */
	static void build(FakeWorld w, Circuit c, Placement p) {
		int[] pos = new int[3];
		for (Circuit.Cell cell : c.cells) {
			if (cell.spec.optional) continue;
			p.toWorld(c, cell.x, cell.y, cell.z, pos);
			Map<String, String> props = new HashMap<String, String>(p.props(cell.spec));
			String key = cell.spec.def.key;
			String id = "minecraft:" + (cell.spec.def.anySolid() ? "stone" : key);
			if (w.legacy) {
				if ("repeater".equals(key)) id = "minecraft:unpowered_repeater";
				else if ("comparator".equals(key)) id = "minecraft:powered_comparator";
				else if ("redstone_lamp".equals(key)) id = "minecraft:lit_redstone_lamp";
				else if ("redstone_torch".equals(key)) {
					id = "minecraft:unlit_redstone_torch";
					props.put("facing", "up");
				} else if ("redstone_wall_torch".equals(key)) id = "minecraft:redstone_torch";
				else if ("lever".equals(key) || "stone_button".equals(key)) {
					String face = props.remove("face");
					if ("floor".equals(face)) props.put("facing", "lever".equals(key) ? "up_z" : "up");
				} else if ("sugar_cane".equals(key)) id = "minecraft:reeds";
			}
			w.set(pos[0], pos[1], pos[2], id, props, cell.spec.def.anySolid());
		}
	}

	@Test
	void checkAgainstModernAndLegacyWorlds() {
		for (boolean legacy : new boolean[] {false, true}) {
			for (String id : new String[] {"not_gate", "xor_gate", "and_gate", "d_latch", "piston_door_2x2", "rs_latch"}) {
				for (int rot = 0; rot < 4; rot++) {
					Circuit c = LIB.byId(id);
					Placement p = new Placement(-20, 70, 33, rot, rot == 3);
					FakeWorld w = new FakeWorld(legacy);
					build(w, c, p);
					CircuitCheck check = new CircuitCheck(c, p);
					check.run(w, LIB.catalog());
					assertEquals(c.blockCount(), check.correct(), id + " legacy=" + legacy + " rot=" + rot);
					assertTrue(check.complete());
				}
			}
		}
	}

	@Test
	void checkReportsMissingWrongAndWrongState() {
		Circuit c = LIB.byId("not_gate"); // A - # t L
		Placement p = new Placement(0, 64, 0, 1, false);
		FakeWorld w = new FakeWorld(false);
		build(w, c, p);
		int[] pos = new int[3];
		// Lampe fehlt
		p.toWorld(c, 4, 0, 0, pos);
		w.ids.remove(dev.theredstonee.trsclient.core.redstone.Dir.pack(pos[0], pos[1], pos[2]));
		// Fackel falsch herum
		p.toWorld(c, 3, 0, 0, pos);
		Map<String, String> wrongFacing = new HashMap<String, String>();
		wrongFacing.put("facing", "north");
		w.set(pos[0], pos[1], pos[2], "minecraft:redstone_wall_torch", wrongFacing, false);
		// Glas statt festem Block
		p.toWorld(c, 2, 0, 0, pos);
		w.set(pos[0], pos[1], pos[2], "minecraft:glass", null, false);
		CircuitCheck check = new CircuitCheck(c, p);
		check.run(w, LIB.catalog());
		assertEquals(2, check.correct());
		assertEquals(2, check.wrong());
		assertEquals(1, check.missing());
		assertEquals(CircuitCheck.WRONG, check.status(3));
		assertTrue(check.stateOnly(3));
		assertFalse(check.stateOnly(2));
		assertEquals(CircuitCheck.MISSING, check.status(4));
		// nicht geladener Chunk
		CircuitCheck none = new CircuitCheck(c, p);
		none.run(null, LIB.catalog());
		assertEquals(0, none.correct());
		assertEquals(CircuitCheck.UNKNOWN, none.status(0));
	}

	@Test
	void movableDoorBlocksAcceptPistonHeads() {
		Circuit c = LIB.byId("piston_door_2x2");
		Placement p = new Placement(0, 60, 0, 0, false);
		FakeWorld w = new FakeWorld(false);
		build(w, c, p);
		int moved = 0;
		int[] pos = new int[3];
		for (Circuit.Cell cell : c.cells) {
			if (!cell.spec.movable) continue;
			p.toWorld(c, cell.x, cell.y, cell.z, pos);
			w.set(pos[0], pos[1], pos[2], "minecraft:piston_head", null, false);
			moved++;
		}
		assertEquals(4, moved);
		CircuitCheck check = new CircuitCheck(c, p);
		check.run(w, LIB.catalog());
		assertTrue(check.complete());
	}

	@Test
	void legacyNamesAreNormalized() {
		BlockCatalog cat = LIB.catalog();
		Map<String, String> props = new HashMap<String, String>();
		props.put("facing", "EAST");
		BlockCatalog.Normalized n = cat.normalize("minecraft:redstone_torch", props, true, null);
		assertEquals("redstone_wall_torch", n.key);
		assertEquals("east", n.props.get("facing"));
		props.put("facing", "up");
		assertEquals("redstone_torch", cat.normalize("minecraft:unlit_redstone_torch", props, true, null).key);
		props.put("facing", "up_x");
		n = cat.normalize("minecraft:lever", props, true, null);
		assertEquals("floor", n.props.get("face"));
		props.put("facing", "north");
		n = cat.normalize("minecraft:stone_button", props, true, null);
		assertEquals("wall", n.props.get("face"));
		assertEquals("north", n.props.get("facing"));
		assertEquals("true", cat.normalize("minecraft:daylight_detector_inverted", null, true, null).props.get("inverted"));
		assertEquals("copper_bulb", cat.normalize("minecraft:waxed_oxidized_copper_bulb", null, false, null).key);
		assertEquals("water", cat.normalize("minecraft:bubble_column", null, false, null).key);
		assertEquals("sugar_cane", cat.normalize("minecraft:reeds", null, true, null).key);
		assertTrue(cat.normalize("minecraft:air", null, false, null).isAir());
	}

	@Test
	void storeKeepsTemplatesPerWorld(@TempDir Path dir) {
		CircuitStore store = new CircuitStore(dir.resolve("trsclient").resolve("circuits.json"));
		CircuitStore.Entry e = new CircuitStore.Entry();
		e.circuit = "xor_gate";
		e.x = -5;
		e.y = 70;
		e.z = 12;
		e.rotation = 3;
		e.mirror = true;
		e.layer = 0;
		store.setActive("mp:play.example.net|minecraft:overworld", e);
		store.setAnchor("mp:play.example.net|minecraft:overworld", e);
		store.save();
		CircuitStore again = new CircuitStore(dir.resolve("trsclient").resolve("circuits.json"));
		again.load();
		CircuitStore.Entry r = again.active("mp:play.example.net|minecraft:overworld");
		assertNotNull(r);
		assertEquals("xor_gate", r.circuit);
		assertEquals(-5, r.x);
		assertEquals(3, r.rotation);
		assertTrue(r.mirror);
		assertNotNull(again.anchor("mp:play.example.net|minecraft:overworld"));
		assertEquals(null, again.active("sp:Neue Welt|minecraft:overworld"));
	}

	@Test
	void worldKeySeparatesServersAndDimensions() {
		Circuits.Context a = new Circuits.Context();
		a.inWorld = true;
		a.serverAddress = "Play.Example.net";
		a.dimension = "minecraft:overworld";
		Circuits.Context b = new Circuits.Context();
		b.inWorld = true;
		b.serverAddress = "play.example.net";
		b.dimension = "minecraft:the_nether";
		assertEquals("mp:play.example.net|minecraft:overworld", a.worldKey());
		assertFalse(a.worldKey().equals(b.worldKey()));
		Circuits.Context sp = new Circuits.Context();
		sp.inWorld = true;
		sp.singleplayer = true;
		sp.levelName = "Neue Welt";
		assertEquals("sp:Neue Welt|", sp.worldKey());
		assertEquals(null, new Circuits.Context().worldKey());
	}
}

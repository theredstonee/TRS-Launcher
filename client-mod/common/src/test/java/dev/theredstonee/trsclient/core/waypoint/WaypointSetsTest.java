package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.map.WaypointFilter;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.render.Projection;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sets, Todespunkte, Ziel, Filter, Teleport-Befehle und Kästchen der neuen Wegpunkte. */
class WaypointSetsTest {
	private static final String WORLD = "mp:play.example.net";
	private static final String DIM = "minecraft:overworld";

	private static String names(List<WaypointFilter.Row> rows) {
		List<String> out = new ArrayList<String>();
		for (WaypointFilter.Row r : rows) out.add(r.waypoint.name);
		return String.join(",", out);
	}

	// --- Alte Dateien ---

	/** Datei wie von TRS Client 0.24.x (ohne Kennung, Set, Kürzel, Symbol): nichts geht verloren, alles im Standard-Set. */
	@Test
	void oldFileLoadsWithoutLossAndGetsTheDefaultSet(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("trsclient-waypoints.json");
		String old = "{\"mp:play.example.net\":["
				+ "{\"name\":\"Basis\",\"x\":10,\"y\":64,\"z\":-20,\"dimension\":\"minecraft:overworld\",\"color\":4119620,\"visible\":true,\"death\":false,\"created\":111},"
				+ "{\"name\":\"Versteckt\",\"x\":1,\"y\":2,\"z\":3,\"dimension\":\"minecraft:the_nether\",\"color\":1,\"visible\":false,\"death\":false,\"created\":222},"
				+ "{\"name\":\"Tod\",\"x\":7,\"y\":8,\"z\":9,\"dimension\":\"minecraft:overworld\",\"color\":14690334,\"visible\":true,\"death\":true,\"created\":333}]}";
		Files.write(file, old.getBytes(StandardCharsets.UTF_8));

		WaypointStore store = new WaypointStore(file);
		store.load();
		List<Waypoint> all = store.all(WORLD);
		assertEquals(3, all.size());
		Waypoint basis = all.get(0);
		assertEquals("Basis", basis.name);
		assertEquals(10, basis.x);
		assertEquals(64, basis.y);
		assertEquals(-20, basis.z);
		assertEquals(4119620, basis.color);
		assertEquals(111, basis.created);
		assertFalse(all.get(1).visible);
		assertEquals(WaypointStore.SET_DEFAULT, basis.setId());
		assertEquals(WaypointStore.SET_DEFAULT, all.get(1).setId());
		assertEquals(WaypointStore.SET_DEATHS, all.get(2).setId());
		assertEquals("", basis.symbol);
		assertEquals("B", basis.badgeText());
		assertEquals(WaypointSymbols.SKULL, all.get(2).badgeSymbol());
		// Sets: Standard zuerst, Todespunkte zuletzt.
		List<WaypointSet> sets = store.sets(WORLD);
		assertEquals(2, sets.size());
		assertEquals(WaypointStore.SET_DEFAULT, sets.get(0).id);
		assertEquals(WaypointStore.SET_DEATHS, sets.get(1).id);
		// Die Kennungen wurden sofort gespeichert und das Format bleibt eine Karte Welt → Liste.
		WaypointStore again = new WaypointStore(file);
		again.load();
		assertEquals(basis.id, again.all(WORLD).get(0).id);
		assertEquals(3, again.all(WORLD).size());
		assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim().startsWith("{\n  \"mp:play.example.net\": ["));
	}

	@Test
	void brokenSetFileKeepsTheWaypoints(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("trsclient-waypoints.json");
		WaypointStore store = new WaypointStore(file);
		store.load();
		WaypointSet farms = store.addSet(WORLD, "Farmen");
		Waypoint w = store.add(WORLD, new Waypoint("Weizen", 1, 2, 3, DIM, 0));
		w.set = farms.id;
		store.touch();
		store.save();
		Files.write(store.metaFile(), "{kaputt".getBytes(StandardCharsets.UTF_8));
		WaypointStore again = new WaypointStore(file);
		again.load();
		assertEquals(1, again.all(WORLD).size());
		// Die Gruppe bleibt erhalten, auch wenn ihr Name fehlt.
		assertEquals(farms.id, again.all(WORLD).get(0).setId());
		assertEquals(2, again.sets(WORLD).size());
	}

	// --- Sets ---

	@Test
	void setsSurviveReloadAndHideTheirWaypoints(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("trsclient-waypoints.json");
		WaypointStore store = new WaypointStore(file);
		store.load();
		WaypointSet basis = store.addSet(WORLD, "  Basis  ");
		assertEquals("Basis", basis.name);
		assertEquals(basis.id, store.addSet(WORLD, "basis").id, "gleicher Name = dasselbe Set");
		assertNull(store.addSet(WORLD, "   "));
		Waypoint a = store.add(WORLD, new Waypoint("A", 0, 64, 0, DIM, 0));
		a.set = basis.id;
		Waypoint b = store.add(WORLD, new Waypoint("B", 5, 64, 0, DIM, 0));
		assertEquals(2, store.visible(WORLD, DIM).size());
		store.setSetVisible(WORLD, basis.id, false);
		assertFalse(store.shown(WORLD, a));
		assertTrue(store.shown(WORLD, b));
		assertEquals(1, store.visible(WORLD, DIM).size());
		store.save();

		WaypointStore again = new WaypointStore(file);
		again.load();
		assertEquals(1, again.visible(WORLD, DIM).size());
		assertEquals("B", again.visible(WORLD, DIM).get(0).name);
		assertEquals(2, again.sets(WORLD).size());
		assertEquals("Basis", again.sets(WORLD).get(1).label());
		assertTrue(again.renameSet(WORLD, basis.id, "Zuhause"));
		assertEquals("Zuhause", again.sets(WORLD).get(1).label());
		// Löschen behält die Wegpunkte (sie wandern ins Standard-Set und sind wieder sichtbar).
		assertTrue(again.removeSet(WORLD, basis.id));
		assertEquals(2, again.all(WORLD).size());
		assertEquals(2, again.visible(WORLD, DIM).size());
		assertFalse(again.removeSet(WORLD, WaypointStore.SET_DEFAULT));
		assertFalse(again.renameSet(WORLD, WaypointStore.SET_DEATHS, "x"));
		// Das Standard-Set lässt sich ebenfalls ausblenden.
		again.setSetVisible(WORLD, WaypointStore.SET_DEFAULT, false);
		assertEquals(0, again.visible(WORLD, DIM).size());
	}

	@Test
	void setsBelongToOneWorld(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("trsclient-waypoints.json"));
		store.addSet(WORLD, "Farmen");
		assertEquals(1, store.sets("sp:andere").size());
		assertEquals(2, store.sets(WORLD).size());
		store.setSetVisible("sp:andere", WaypointStore.SET_DEFAULT, false);
		assertTrue(store.setVisible(WORLD, WaypointStore.SET_DEFAULT));
	}

	// --- Todespunkte und Ziel ---

	@Test
	void deathsAreLimitedAndTheOldestGoesFirst(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("trsclient-waypoints.json"));
		store.add(WORLD, new Waypoint("Basis", 0, 64, 0, DIM, 0));
		for (int i = 0; i < 5; i++) store.addDeath(WORLD, i, 64, 0, DIM, 0, 3);
		List<Integer> xs = new ArrayList<Integer>();
		for (Waypoint w : store.all(WORLD)) if (w.death) xs.add(w.x);
		assertEquals(Arrays.asList(2, 3, 4), xs);
		assertEquals(4, store.lastDeath(WORLD).x);
		assertEquals("Basis", store.all(WORLD).get(0).name);
		// Grenze 1 entspricht dem alten Verhalten.
		store.setDeath(WORLD, 9, 64, 0, DIM, 0);
		xs.clear();
		for (Waypoint w : store.all(WORLD)) if (w.death) xs.add(w.x);
		assertEquals(Arrays.asList(9), xs);
	}

	@Test
	void fullWorldDropsDeathPointsBeforeOwnWaypoints(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("trsclient-waypoints.json"));
		store.addDeath(WORLD, 1, 64, 1, DIM, 0, 5);
		for (int i = 0; i < WaypointStore.MAX_PER_WORLD; i++) store.add(WORLD, new Waypoint("W" + i, i, 64, 0, DIM, 0));
		assertEquals(WaypointStore.MAX_PER_WORLD, store.all(WORLD).size());
		assertNull(store.lastDeath(WORLD), "zuerst fällt der Todespunkt");
		assertEquals("W0", store.all(WORLD).get(0).name);
	}

	@Test
	void targetSurvivesReloadAndEndsWithItsWaypoint(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("trsclient-waypoints.json");
		WaypointStore store = new WaypointStore(file);
		Waypoint w = store.add(WORLD, new Waypoint("Mine", 0, 64, 0, DIM, 0));
		store.setTarget(WORLD, w, false);
		store.save();
		WaypointStore again = new WaypointStore(file);
		again.load();
		Waypoint t = again.target(WORLD);
		assertNotNull(t);
		assertEquals("Mine", t.name);
		again.remove(WORLD, t);
		assertNull(again.target(WORLD));
	}

	@Test
	void guideRecordsDeathTargetsItAndStopsOnArrival(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("trsclient-waypoints.json"));
		WaypointGuide guide = new WaypointGuide();
		assertNull(guide.tick(store, WORLD, 0, 64, 0, DIM, false, true, 5, true, true).message);
		WaypointGuide.Event died = guide.tick(store, WORLD, 100.7, 64, -3.2, DIM, true, true, 5, true, true);
		assertTrue(died.changed);
		assertNotNull(died.message);
		Waypoint death = store.lastDeath(WORLD);
		assertEquals(100, death.x);
		assertEquals(-4, death.z);
		assertEquals(death, store.target(WORLD), "der Weg zurück");
		assertTrue(store.targetAuto(WORLD));
		// Noch tot: kein zweiter Punkt.
		guide.tick(store, WORLD, 100, 64, -3, DIM, true, true, 5, true, true);
		assertEquals(1, store.sets(WORLD).size() - 1);
		assertEquals(death, store.lastDeath(WORLD));
		// Unterwegs nichts, am Ziel Schluss.
		assertFalse(guide.tick(store, WORLD, 0, 64, 0, DIM, false, true, 5, true, true).changed);
		assertEquals(death, store.target(WORLD));
		// In einer anderen Dimension gilt das Ziel nicht als erreicht.
		store.setTarget(WORLD, death, false);
		guide.tick(store, WORLD, 100.5, 65, -3.5, "minecraft:the_nether", false, true, 5, true, true);
		assertEquals(death, store.target(WORLD));
		WaypointGuide.Event there = guide.tick(store, WORLD, 100.5, 65, -3.5, DIM, false, true, 5, true, true);
		assertTrue(there.changed);
		assertNull(store.target(WORLD));
		assertNull(store.lastDeath(WORLD), "Todespunkt wird bei Ankunft entfernt");
		Waypoint normal = store.add(WORLD, new Waypoint("Mine", 100, 64, -4, DIM, 0));
		store.setTarget(WORLD, normal, false);
		assertTrue(guide.tick(store, WORLD, 100.5, 65, -3.5, DIM, false, true, 5, true, true).changed);
		assertNull(store.target(WORLD));
		assertEquals(normal, store.byId(WORLD, normal.id), "normale Wegpunkte bleiben erhalten");
		Waypoint retainedDeath = store.setDeath(WORLD, 20, 64, 20, DIM, 0xE0281E);
		store.setTarget(WORLD, retainedDeath, true);
		WaypointGuide.Event kept = guide.tick(store, WORLD, 20.5, 65, 20.5, DIM, false, true, 5, true, false);
		assertTrue(kept.changed);
		assertNull(store.target(WORLD));
		assertEquals(retainedDeath, store.lastDeath(WORLD), "deaktiviertes automatisches Entfernen");
		// Abgeschaltet: kein Todespunkt.
		WaypointGuide off = new WaypointGuide();
		off.tick(store, "sp:aus", 1, 2, 3, DIM, true, false, 5, true, true);
		assertNull(store.lastDeath("sp:aus"));
	}

	// --- Filter und Sortierung ---

	private static Waypoint wp(String name, int x, long created, String set) {
		Waypoint w = new Waypoint(name, x, 64, 0, DIM, 0);
		w.created = created;
		w.set = set;
		return w;
	}

	@Test
	void filterAndSortChoices() {
		Waypoint alpha = wp("Alpha", 300, 2, "farms");
		Waypoint beta = wp("Beta", 100, 3, "");
		Waypoint gamma = wp("Gamma", 200, 1, "farms");
		gamma.visible = false;
		Waypoint tod = wp("Tod", 50, 4, "");
		tod.death = true;
		beta.shared = true;
		List<Waypoint> all = Arrays.asList(alpha, beta, gamma, tod);
		WaypointFilter.Query q = new WaypointFilter.Query();
		q.sort = WaypointSort.DISTANCE;
		assertEquals("Tod,Beta,Gamma,Alpha", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
		q.sort = WaypointSort.NAME;
		assertEquals("Alpha,Beta,Gamma,Tod", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
		q.sort = WaypointSort.CREATED;
		assertEquals("Tod,Beta,Alpha,Gamma", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
		q.set = "farms";
		assertEquals("Alpha,Gamma", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
		q.set = WaypointStore.SET_DEFAULT;
		assertEquals("Beta", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
		q.set = WaypointStore.SET_DEATHS;
		assertEquals("Tod", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
		q.set = WaypointFilter.ALL;
		q.kind = WaypointFilter.Kind.HIDDEN;
		assertEquals("Gamma", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
		q.kind = WaypointFilter.Kind.DEATHS;
		assertEquals("Tod", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
		q.kind = WaypointFilter.Kind.SHARED;
		assertEquals("Beta", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
		q.kind = WaypointFilter.Kind.SHOWN;
		q.text = "al";
		assertEquals("Alpha", names(WaypointFilter.apply(all, q, w -> w.visible, DIM, 0, 0)));
	}

	// --- Teleport ---

	@Test
	void teleportCommandDependsOnRights() {
		int all = WaypointTeleport.TP | WaypointTeleport.EXECUTE_IN;
		assertNull(WaypointTeleport.command(0, 1, 2, 3, DIM, DIM), "ohne Rechte kein Befehl");
		assertEquals("tp 1 2 3", WaypointTeleport.command(WaypointTeleport.TP, 1, 2, 3, DIM, DIM));
		assertEquals("tp -1 70 -3", WaypointTeleport.command(all, -1, 70, -3, "", DIM));
		assertEquals("tp 1 2 3", WaypointTeleport.command(WaypointTeleport.TP, 1, 2, 3, null, "dim0"), "1.8.9: ohne @s");
		assertFalse(WaypointTeleport.command(WaypointTeleport.TP, 1, 2, 3, null, "dim0").contains("@"));
		assertEquals("execute in minecraft:the_nether run tp 1 2 3", WaypointTeleport.command(all, 1, 2, 3, "minecraft:the_nether", DIM));
		assertEquals("execute in mymod:deep_caves run tp 1 2 3", WaypointTeleport.command(all, 1, 2, 3, "mymod:deep_caves", DIM));
		assertNull(WaypointTeleport.command(WaypointTeleport.TP, 1, 2, 3, "minecraft:the_nether", DIM), "unter 1.13 nur die eigene Dimension");
		assertNull(WaypointTeleport.command(WaypointTeleport.TP, 1, 2, 3, "dim-1", "dim0"));
		// Übernommene Wegpunkte können alles enthalten – nichts davon kommt in einen Befehl.
		assertNull(WaypointTeleport.command(all, 1, 2, 3, "minecraft:nether run kill @a", DIM));
		assertNull(WaypointTeleport.command(all, 1, 2, 3, "dim-1", DIM));
		assertNull(WaypointTeleport.command(all, Integer.MIN_VALUE, 2, 3, DIM, DIM));
		assertNull(WaypointTeleport.command(all, 1, 99_999, 3, DIM, DIM));
	}

	/** Attrappe: kennt nur, was der Teleport braucht. */
	private static MapPlatform platform(final int access, final List<String> sent) {
		return (MapPlatform) java.lang.reflect.Proxy.newProxyInstance(MapPlatform.class.getClassLoader(), new Class<?>[]{MapPlatform.class},
				(proxy, m, args) -> {
					switch (m.getName()) {
						case "commandAccess":
							return access;
						case "dimension":
							return DIM;
						case "sendChat":
							sent.add((String) args[0]);
							return null;
						default:
							return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
					}
				});
	}

	@Test
	void teleportEntryFollowsRightsAndTheServerRule() {
		TrsModules m = new TrsModules();
		List<String> sent = new ArrayList<String>();
		MapPlatform op = platform(WaypointTeleport.TP | WaypointTeleport.EXECUTE_IN, sent);
		MapPlatform normal = platform(0, sent);
		MapPlatform old = platform(WaypointTeleport.TP, sent);
		assertTrue(WaypointTeleport.possible(m, op, DIM));
		assertTrue(WaypointTeleport.possible(m, op, "minecraft:the_end"));
		assertFalse(WaypointTeleport.possible(m, normal, DIM));
		assertTrue(WaypointTeleport.possible(m, old, DIM));
		assertFalse(WaypointTeleport.possible(m, old, "minecraft:the_end"));
		assertFalse(WaypointTeleport.send(m, normal, 1, 2, 3, DIM));
		assertTrue(sent.isEmpty(), "ohne Rechte wird nichts gesendet");
		assertTrue(WaypointTeleport.send(m, op, 1, 2, 3, "minecraft:the_end"));
		assertEquals(Arrays.asList("/execute in minecraft:the_end run tp 1 2 3"), sent);
		// Server-Regel wie in der Doku: settings: waypoints.teleport: false
		ServerRules rules = new ServerRules(m.registry, "0", "1.21.11", "fabric", new ServerRules.SilentPlatform());
		rules.onJoin();
		assertTrue(rules.onRules("{\"v\":1,\"modules\":{\"waypoints\":{\"settings\":{\"teleport\":{\"value\":false}}}}}".getBytes(StandardCharsets.UTF_8)));
		assertFalse(WaypointTeleport.possible(m, op, DIM), "der Server sperrt den Eintrag");
		assertFalse(WaypointTeleport.send(m, op, 1, 2, 3, DIM));
		assertEquals(1, sent.size());
		rules.onDisconnect();
		assertTrue(WaypointTeleport.possible(m, op, DIM));
	}

	// --- Chat, Kästchen, Symbole, Anzeige ---

	@Test
	void chatLineIsPlainAndNeverACommand() {
		assertEquals("Basis: 1 2 3", WaypointMenu.chatLine("Basis", 1, 2, 3, DIM, DIM));
		assertEquals("1 2 3", WaypointMenu.chatLine("", 1, 2, 3, DIM, DIM));
		assertEquals("op me: 1 2 3", WaypointMenu.chatLine("/op me", 1, 2, 3, DIM, DIM));
		assertEquals("rot: 1 2 3", WaypointMenu.chatLine((char) 0xA7 + "rot\n", 1, 2, 3, DIM, DIM).replace("  ", " "));
		assertTrue(WaypointMenu.chatLine("Tor", 1, 2, 3, "minecraft:the_nether", DIM).startsWith("Tor: 1 2 3 ("));
		assertEquals("1 2 3", WaypointMenu.coordinates(1, 2, 3));
	}

	@Test
	void mapModLinesCarryNameColorAndDimension() {
		assertEquals("xaero-waypoint:Basis:B:1:2:3:12:false:0:Internal-the-nether",
				WaypointMenu.mapModLine(WaypointMenu.CHAT_XAERO, "Basis", "B", 1, 2, 3, 0xE0281E, "minecraft:the_nether", DIM));
		assertEquals("[name:\"Basis\", x:1, y:2, z:3, dim:minecraft:overworld]",
				WaypointMenu.mapModLine(WaypointMenu.CHAT_JOURNEYMAP, "Basis", "B", 1, 2, 3, 0xE0281E, "", DIM));
		// Bis 1.12.2: Zahlen statt Kennungen für JourneyMap, Xaero bleibt bei seinen Namen.
		assertEquals("[name:\"Tor\", x:1, y:2, z:3, dim:-1]",
				WaypointMenu.mapModLine(WaypointMenu.CHAT_JOURNEYMAP, "Tor", "T", 1, 2, 3, 0, "dim-1", "dim0"));
		assertEquals("xaero-waypoint:Tor:T:1:2:3:0:false:0:Internal-the-nether",
				WaypointMenu.mapModLine(WaypointMenu.CHAT_XAERO, "Tor", "T", 1, 2, 3, 0, "dim-1", "dim0"));
		// Nie ein Befehl, auch wenn der Name mit „/“ beginnt.
		assertFalse(WaypointMenu.mapModLine(WaypointMenu.CHAT_XAERO, "/op me", "", 1, 2, 3, 0, DIM, DIM).startsWith("/"));
		assertFalse(WaypointMenu.mapModLine(WaypointMenu.CHAT_JOURNEYMAP, "/op me", "", 1, 2, 3, 0, DIM, DIM).startsWith("/"));
	}

	@Test
	void markIsAtMostTwoCharacters() {
		assertEquals("AB", Waypoint.cleanMark("ABC"));
		assertEquals("A", Waypoint.cleanMark(" A "));
		assertEquals("", Waypoint.cleanMark(null));
		assertEquals("X", Waypoint.cleanMark((char) 0xA7 + "X"));
		Waypoint w = new Waypoint("basis", 0, 0, 0, DIM, 0);
		assertEquals("B", w.badgeText());
		w.mark = "Hi";
		assertEquals("Hi", w.badgeText());
		w.symbol = "nicht-da";
		assertEquals("", w.normalized().symbol);
		w.symbol = "bed";
		assertEquals("bed", w.badgeSymbol());
	}

	@Test
	void symbolsAreEightByEightAndDistinct() {
		assertTrue(WaypointSymbols.ids().size() >= 12);
		for (String wanted : new String[]{"bed", "chest", "sword", "portal"}) assertTrue(WaypointSymbols.known(wanted), wanted);
		HashSet<String> shapes = new HashSet<String>();
		for (String id : WaypointSymbols.ids()) {
			String[] rows = WaypointSymbols.rows(id);
			assertEquals(8, rows.length, id);
			for (String row : rows) assertEquals(8, row.length(), id);
			assertTrue(shapes.add(String.join("", rows)), "doppelt: " + id);
		}
	}

	@Test
	void sizeAndLabelRules() {
		assertEquals(1.3f, WaypointHud.scaleFor(2), 0.01f);
		assertEquals(0.7f, WaypointHud.scaleFor(5000), 0.01f);
		assertTrue(WaypointHud.scaleFor(50) > WaypointHud.scaleFor(200));
		assertEquals(0, WaypointHud.nearAlpha(1));
		assertEquals(255, WaypointHud.nearAlpha(10));
		assertTrue(WaypointHud.labelWanted(WaypointLabels.LOOK, false, true));
		assertFalse(WaypointHud.labelWanted(WaypointLabels.LOOK, false, false));
		assertTrue(WaypointHud.labelWanted(WaypointLabels.LOOK, true, false), "das Ziel ist immer beschriftet");
		assertTrue(WaypointHud.labelWanted(WaypointLabels.ALWAYS, false, false));
		assertFalse(WaypointHud.labelWanted(WaypointLabels.NEVER, true, true));
	}

	@Test
	void screenDirectionPointsToTheSideEvenBehindTheCamera() {
		// Yaw 0 blickt nach +Z; rechts ist -X.
		double[] right = Projection.screenDirection(0, 0, 0, 0f, 0f, -10, 0, 1);
		assertTrue(right[0] > 0.9);
		double[] behindRight = Projection.screenDirection(0, 0, 0, 0f, 0f, -10, 0, -30);
		assertTrue(behindRight[0] > 0.9);
		double[] above = Projection.screenDirection(0, 0, 0, 0f, 0f, 0, 10, 1);
		assertTrue(above[1] < -0.9);
		double[] exactlyBehind = Projection.screenDirection(0, 0, 0, 0f, 0f, 0, 0, -10);
		assertEquals(1.0, Math.hypot(exactlyBehind[0], exactlyBehind[1]), 1e-6);
	}

	@Test
	void hexColorField() {
		assertEquals(0x12AB34, WaypointDialog.parseHex("#12ab34"));
		assertEquals(0xFFFFFF, WaypointDialog.parseHex("FFFFFF"));
		assertEquals(-1, WaypointDialog.parseHex("#12"));
		assertEquals(-1, WaypointDialog.parseHex("zzzzzz"));
		assertEquals(-1, WaypointDialog.parseHex(null));
	}
}

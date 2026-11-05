package dev.theredstonee.trsclient.core.waypoint.live;

import com.google.gson.JsonArray;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.social.MeEvent;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveShareTest {
	static final String SELF = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	static final String FRIEND = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	static final String OTHER = "cccccccccccccccccccccccccccccccc";

	// --- LiveWorld ---

	@Test
	void serverWorldsAreNormalizedAndLocalOnesSkipped() {
		LiveWorld w = LiveWorld.server("Play.Example.NET:25565");
		assertNotNull(w);
		assertEquals("{\"type\":\"server\",\"address\":\"play.example.net\"}", w.json());
		assertEquals(w, LiveWorld.fromStoreKey("mp:play.example.net"));
		assertNotNull(LiveWorld.server("mc.example.org:25570"));
		assertNull(LiveWorld.server("localhost:25565"));
		assertNull(LiveWorld.server("192.168.1.20"));
		assertNull(LiveWorld.server("10.0.0.5:25566"));
		assertNull(LiveWorld.server("127.0.0.1:54321"));
		assertNull(LiveWorld.fromStoreKey("sp:Neue Welt"));
		assertNull(LiveWorld.server("not an address!"));
	}

	@Test
	void hostedWorldIdIsTheSameForHostAndGuest() {
		LiveWorld host = LiveWorld.hosted(SELF, "Meine Welt");
		LiveWorld guest = LiveWorld.hosted(SELF, " meine welt ");
		assertEquals(host, guest);
		assertTrue(host.id.matches("[0-9a-f]{16}"));
		assertFalse(host.json().contains("Welt"), "der Weltname reist nie zur API");
		assertNotEquals(host, LiveWorld.hosted(FRIEND, "Meine Welt"));
		assertNull(LiveWorld.hosted(null, "x"));
		assertNull(LiveWorld.hosted(SELF, " "));
	}

	// --- JSON ---

	@Test
	void ownersAreParsedTolerantly() {
		String json = "{\"owners\":[{\"uuid\":\"" + FRIEND + "\",\"name\":\"Alex\",\"rev\":4,\"extra\":1,\"waypoints\":["
				+ "{\"id\":\"k3J9x_base\",\"name\":\"Base\",\"x\":100,\"y\":64,\"z\":-20,\"dimension\":\"minecraft:overworld\",\"color\":14690334},"
				+ "{\"id\":\"death\",\"name\":\"Death\",\"x\":-411,\"y\":12,\"z\":980,\"dimension\":\"minecraft:the_nether\",\"death\":true,\"color\":null},"
				+ "{\"id\":\"bad id!\",\"name\":\"X\",\"x\":0,\"y\":0,\"z\":0,\"dimension\":\"minecraft:overworld\"},"
				+ "{\"id\":\"far\",\"name\":\"Far\",\"x\":99999999,\"y\":0,\"z\":0,\"dimension\":\"minecraft:overworld\"},"
				+ "{\"id\":\"nodim\",\"name\":\"NoDim\",\"x\":0,\"y\":0,\"z\":0},"
				+ "\"kaputt\"],"
				+ "\"position\":{\"x\":120.53,\"y\":64.0,\"z\":-33.25,\"dimension\":\"minecraft:overworld\",\"yaw\":-90.5,\"t\":\"2026-10-31T12:00:00Z\"}},"
				+ "{\"uuid\":\"kaputt\"},{\"uuid\":\"" + OTHER + "\",\"rev\":0,\"waypoints\":[]}]}";
		List<Owner> owners = LiveJson.owners(json, 1000L);
		assertEquals(2, owners.size());
		Owner a = owners.get(0);
		assertEquals("Alex", a.name());
		assertEquals(4, a.rev());
		assertEquals(2, a.waypoints().size());
		FriendWaypoint base = a.waypoints().get(0);
		assertEquals("Base", base.name);
		assertEquals(14690334, base.color);
		assertFalse(base.death);
		FriendWaypoint death = a.waypoints().get(1);
		assertTrue(death.death);
		assertEquals(0x3D7BFF, death.color, "color null → Standardfarbe");
		assertNotNull(a.position());
		assertEquals(269.5f, a.position().yaw, 0.01f);
		assertEquals(1000L, a.position().at);
		Owner b = owners.get(1);
		assertTrue(b.waypoints().isEmpty());
		assertEquals(OTHER.substring(0, 8), b.name(), "ohne Namen: Anfang der UUID");
		assertTrue(LiveJson.owners("kein json", 0).isEmpty());
		assertTrue(LiveJson.owners("{\"owners\":5}", 0).isEmpty());
	}

	@Test
	void peersGrantsAndRevsAreRead() {
		LiveJson.Peers p = LiveJson.peers(LiveJson.object("{\"peers\":{\"send\":2,\"receive\":[\"" + FRIEND + "\",\"x\"]},\"expiresInSec\":180}"));
		assertEquals(2, p.send);
		assertEquals(Collections.singleton(FRIEND), p.receive);
		LiveJson.Peers ev = LiveJson.peers(LiveJson.object("{\"type\":\"wpshare_peers\",\"send\":0,\"receive\":[]}"));
		assertEquals(0, ev.send);
		assertTrue(ev.receive.isEmpty());
		assertNull(LiveJson.peers(LiveJson.object("{}")));
		LiveJson.Grants g = LiveJson.grants("{\"grants\":[\"" + FRIEND + "\"],\"grantedToMe\":[\"" + OTHER + "\"]}");
		assertTrue(g.grants.contains(FRIEND));
		assertTrue(g.grantedToMe.contains(OTHER));
		assertEquals(4, LiveJson.rev("{\"rev\":4,\"waypoints\":[]}"));
		assertEquals(-1, LiveJson.rev("{}"));
		assertEquals(7, LiveJson.errorRev("{\"error\":{\"code\":\"rev_mismatch\",\"message\":\"x\",\"rev\":7}}"));
		assertEquals(-1, LiveJson.errorRev("{\"error\":{\"code\":\"rev_mismatch\"}}"));
	}

	@Test
	void sharedListHoldsMarkedWaypointsAndTheDeathPoint() {
		List<Waypoint> all = new ArrayList<Waypoint>();
		Waypoint a = wp("Basis", 1, 64, 2, true);
		Waypoint b = wp("Geheim", 5, 64, 5, false);
		Waypoint tmp = wp("Notiz", 9, 64, 9, true);
		tmp.temporary = true;
		Waypoint death = wp("Tod", -4, 12, 8, false);
		death.death = true;
		Waypoint legacy = new Waypoint("Nether-Tor", 3, 40, 3, "dim-1", 0x112233);
		legacy.shared = true;
		Waypoint noDim = new Waypoint("Überall", 0, 70, 0, "", 0x445566);
		noDim.shared = true;
		all.add(a);
		all.add(b);
		all.add(tmp);
		all.add(legacy);
		all.add(noDim);
		all.add(death);
		JsonArray with = LiveJson.sharedList(all, true);
		assertEquals(4, with.size());
		assertEquals("Tod", with.get(0).getAsJsonObject().get("name").getAsString(), "Todespunkt zuerst");
		assertTrue(with.get(0).getAsJsonObject().get("death").getAsBoolean());
		assertEquals("minecraft:the_nether", with.get(2).getAsJsonObject().get("dimension").getAsString());
		assertEquals("minecraft:overworld", with.get(3).getAsJsonObject().get("dimension").getAsString());
		assertEquals(3, LiveJson.sharedList(all, false).size(), "ohne shareDeath kein Todespunkt");
		// Höchstens 100.
		List<Waypoint> many = new ArrayList<Waypoint>();
		for (int i = 0; i < 150; i++) many.add(wp("P" + i, i, 64, i, true));
		assertEquals(LiveJson.MAX_SHARED, LiveJson.sharedList(many, true).size());
		assertTrue(LiveJson.positionBody(1.23456, 64, -2.04, "minecraft:overworld", -90f).contains("\"x\":1.2"));
	}

	// --- Wegpunkte: Kennung + Schalter ---

	@Test
	void oldFilesGetPersistentIds(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("trsclient-waypoints.json");
		Files.write(file, ("{\"mp:play.example.net\":[{\"name\":\"Alt\",\"x\":1,\"y\":2,\"z\":3,\"dimension\":\"minecraft:overworld\","
				+ "\"color\":255,\"visible\":true},{\"id\":\"same\",\"name\":\"A\"},{\"id\":\"same\",\"name\":\"B\",\"shared\":true}]}")
				.getBytes(StandardCharsets.UTF_8));
		WaypointStore store = new WaypointStore(file);
		store.load();
		List<Waypoint> list = store.all("mp:play.example.net");
		assertEquals(3, list.size());
		String id = list.get(0).id;
		assertTrue(Waypoint.validId(id));
		assertFalse(list.get(0).shared);
		assertEquals("same", list.get(1).id);
		assertNotEquals("same", list.get(2).id, "doppelte Kennung wird ersetzt");
		assertTrue(list.get(2).shared);
		WaypointStore again = new WaypointStore(file);
		again.load();
		assertEquals(id, again.all("mp:play.example.net").get(0).id, "Kennung wurde gespeichert");
		assertEquals(list.get(2).id, again.all("mp:play.example.net").get(2).id);
		assertTrue(again.all("mp:play.example.net").get(2).shared);
	}

	@Test
	void adoptingAFriendWaypointCopiesItOnce(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("w.json"));
		FriendWaypoint fw = new FriendWaypoint(FRIEND, "base1", "Basis", 10, 64, -5, "minecraft:the_nether", 0x123456, false);
		assertEquals(WaypointShare.Result.ADDED, WaypointShare.adopt(store, "mp:x.net", "dim0", fw));
		Waypoint w = store.all("mp:x.net").get(0);
		assertEquals("dim-1", w.dimension, "Legacy-Schreibweise der Version");
		assertFalse(w.shared);
		assertFalse(w.death);
		assertEquals(WaypointShare.Result.ALREADY, WaypointShare.adopt(store, "mp:x.net", "dim0", fw));
		assertEquals(WaypointShare.Result.NO_WORLD, WaypointShare.adopt(store, "", "dim0", fw));
	}

	// --- Positionen ---

	@Test
	void positionGateThrottles() {
		PositionGate g = new PositionGate();
		assertTrue(g.due(0, 0, 64, 0, "minecraft:overworld", 0));
		g.sent(0, 0, 64, 0, "minecraft:overworld", 0);
		assertFalse(g.due(1000, 50, 64, 0, "minecraft:overworld", 0), "höchstens alle 3 s");
		assertFalse(g.due(3500, 1, 64, 1, "minecraft:overworld", 10), "kaum bewegt");
		assertTrue(g.due(3500, 3, 64, 0, "minecraft:overworld", 0), "mehr als 2 Blöcke");
		assertTrue(g.due(3500, 0, 64, 0, "minecraft:the_nether", 0), "Dimensionswechsel");
		assertTrue(g.due(3500, 0, 64, 0, "minecraft:overworld", 90), "deutlich gedreht");
		assertTrue(g.due(15_000, 0, 64, 0, "minecraft:overworld", 0), "Lebenszeichen");
		assertEquals(20f, PositionGate.turn(350, 10), 0.01f);
	}

	// --- Dienst (mit Attrappe) ---

	private final List<String> calls = new ArrayList<String>();
	private final List<String> bodies = new ArrayList<String>();
	private Function<String, Http.Response> routes;
	private FakeEnv env;
	private TrsModules modules;
	private LiveShare live;
	private final List<String> unauthorized = new ArrayList<String>();

	@BeforeEach
	void setUp(@TempDir Path dir) {
		env = new FakeEnv(new WaypointStore(dir.resolve("w.json")));
		modules = new TrsModules();
		modules.waypoints.setEnabled(true);
		routes = this::defaultRoutes;
		Http http = request -> {
			String key = request.method + " " + request.url.replace("http://api/v1/waypoint-share", "");
			calls.add(key);
			bodies.add(request.body == null ? "" : new String(request.body, StandardCharsets.UTF_8));
			return routes.apply(key);
		};
		live = new LiveShare(new LiveApi(http, "http://api"), () -> modules, env, Runnable::run, t -> unauthorized.add(t));
		LiveShare.testWorld(null);
	}

	private Http.Response defaultRoutes(String key) {
		if (key.startsWith("PUT /session")) return ok("{\"peers\":{\"send\":1,\"receive\":[\"" + FRIEND + "\"]},\"expiresInSec\":180}");
		if (key.startsWith("GET /waypoints")) return ok("{\"rev\":0,\"waypoints\":[]}");
		if (key.startsWith("PUT /waypoints")) return ok("{\"rev\":1,\"waypoints\":[]}");
		if (key.startsWith("GET /friends")) {
			return ok("{\"owners\":[{\"uuid\":\"" + FRIEND + "\",\"name\":\"Alex\",\"rev\":2,\"waypoints\":[{\"id\":\"w1\",\"name\":\"Turm\","
					+ "\"x\":5,\"y\":70,\"z\":5,\"dimension\":\"minecraft:overworld\"}]}]}");
		}
		if (key.startsWith("GET /grants")) return ok("{\"grants\":[],\"grantedToMe\":[\"" + FRIEND + "\"]}");
		return new Http.Response(204, Collections.<String, String>emptyMap(), new byte[0]);
	}

	static Http.Response ok(String json) {
		return new Http.Response(200, Collections.<String, String>emptyMap(), json.getBytes(StandardCharsets.UTF_8));
	}

	static Http.Response error(int status, String code, String extra) {
		String body = "{\"error\":{\"code\":\"" + code + "\",\"message\":\"x\"" + extra + "}}";
		return new Http.Response(status, Collections.<String, String>emptyMap(), body.getBytes(StandardCharsets.UTF_8));
	}

	/** Mehrere Takte (Ergebnisse kommen einen Takt später an). */
	private long run(long from, int ticks) {
		long t = from;
		for (int i = 0; i < ticks; i++) {
			live.tick(t, "tok", SELF);
			t += 50;
		}
		return t;
	}

	private int count(String prefix) {
		int n = 0;
		for (String c : calls) if (c.startsWith(prefix)) n++;
		return n;
	}

	@Test
	void entersServerOpensSessionAndLoadsFriends() {
		long t = run(1000, 5);
		assertTrue(live.active());
		assertEquals(1, count("PUT /session"));
		assertTrue(bodies.get(calls.indexOf("PUT /session")).contains("\"address\":\"play.example.net\""));
		assertEquals(1, count("GET /friends?world="), "erst nach der Sitzung, einmal");
		assertEquals(1, live.peersSend());
		assertTrue(live.wantsStream());
		Owner o = live.owners().get(0);
		assertEquals("Alex", o.name());
		assertEquals(1, live.friendWaypoints("minecraft:overworld").size());
		assertTrue(live.friendWaypoints("minecraft:the_nether").isEmpty());
		// Herzschlag nach 60 s.
		run(t + 61_000, 2);
		assertEquals(2, count("PUT /session"));
	}

	@Test
	void sendsPositionsOnlyWithWatchersAndThrottled() {
		long t = run(1000, 5);
		assertEquals(1, count("POST /position"));
		env.x = 1;
		t = run(t, 10);
		assertEquals(1, count("POST /position"), "nicht öfter als alle 3 s / ohne Bewegung");
		env.x = 30;
		t = run(t + 3000, 2);
		assertEquals(2, count("POST /position"));
		// Niemand mehr da → keine Positionen.
		live.event(MeEvent.wpshare("wpshare_peers", "{\"type\":\"wpshare_peers\",\"send\":0,\"receive\":[]}"));
		env.x = 80;
		run(t + 5000, 3);
		assertEquals(2, count("POST /position"));
		// Eigene Position nicht teilen → auch mit Zuschauern nichts.
		live.event(MeEvent.wpshare("wpshare_peers", "{\"send\":1,\"receive\":[]}"));
		modules.waypointSharePosition.set(false);
		env.x = 200;
		run(t + 20_000, 3);
		assertEquals(2, count("POST /position"));
	}

	@Test
	void ownSharedListIsUploadedDebouncedWithRevRetry() {
		long t = run(1000, 5);
		assertEquals(1, count("GET /waypoints?world="));
		assertEquals(0, count("PUT /waypoints"), "nichts geteilt, Server leer → kein Upload");
		Waypoint w = env.store.add(env.storeKey(), new Waypoint("Basis", 1, 64, 1, "minecraft:overworld", 0xFF0000));
		w.shared = true;
		t = run(t, 10);
		assertEquals(0, count("PUT /waypoints"), "1 s entprellt");
		t = run(t + 1000, 4);
		assertEquals(1, count("PUT /waypoints"));
		String body = bodies.get(calls.lastIndexOf("PUT /waypoints"));
		assertTrue(body.contains("\"rev\":0") && body.contains("\"Basis\""), body);
		// Stand anderswo geändert → 409 mit aktuellem rev → einmal erneut.
		routes = key -> key.startsWith("PUT /waypoints") && count("PUT /waypoints") == 2
				? error(409, "rev_mismatch", ",\"rev\":5") : defaultRoutes(key);
		w.name = "Basis 2";
		t = run(t + 2000, 4);
		t = run(t + 1000, 6);
		assertEquals(3, count("PUT /waypoints"));
		assertTrue(bodies.get(calls.lastIndexOf("PUT /waypoints")).contains("\"rev\":5"));
		// Abgelehnt (Namensfilter) → erst nach der nächsten Änderung wieder.
		routes = key -> key.startsWith("PUT /waypoints") ? error(422, "message_blocked", "") : defaultRoutes(key);
		w.name = "Schimpfwort";
		t = run(t + 2000, 4);
		t = run(t + 1000, 6);
		int after = count("PUT /waypoints");
		run(t + 10_000, 10);
		assertEquals(after, count("PUT /waypoints"));
		assertEquals("waypoint.live.rejected", live.takeNotice());
	}

	@Test
	void restoresSharedWaypointsAfterReinstall() {
		routes = key -> key.startsWith("GET /waypoints")
				? ok("{\"rev\":3,\"waypoints\":[{\"id\":\"k3J9x_base\",\"name\":\"Base\",\"x\":100,\"y\":64,\"z\":-20,\"dimension\":\"minecraft:overworld\"}]}")
				: defaultRoutes(key);
		long t = run(1000, 6);
		List<Waypoint> local = env.store.all(env.storeKey());
		assertEquals(1, local.size());
		assertEquals("k3J9x_base", local.get(0).id);
		assertTrue(local.get(0).shared);
		run(t + 3000, 10);
		assertEquals(0, count("PUT /waypoints"), "gleich wie auf dem Server");
	}

	@Test
	void eventsUpdateFriendsState() {
		long t = run(1000, 5);
		int fetches = count("GET /friends");
		live.event(MeEvent.wpshare("wpshare_position", "{\"type\":\"wpshare_position\",\"uuid\":\"" + FRIEND
				+ "\",\"x\":10.5,\"y\":64,\"z\":-3,\"dimension\":\"minecraft:overworld\",\"yaw\":45,\"t\":\"x\"}"));
		FriendPosition p = live.owner(FRIEND).position();
		assertNotNull(p);
		assertEquals(10.5, p.x, 1e-9);
		// Unbekannter Freund → Liste neu holen.
		live.event(MeEvent.wpshare("wpshare_position", "{\"uuid\":\"" + OTHER + "\",\"x\":1,\"y\":2,\"z\":3,\"dimension\":\"minecraft:overworld\",\"yaw\":0}"));
		assertNotNull(live.owner(OTHER));
		t = run(t, 3);
		assertEquals(fetches + 1, count("GET /friends"));
		// Gleicher rev → nichts, anderer → neu holen.
		live.event(MeEvent.wpshare("wpshare_changed", "{\"uuid\":\"" + FRIEND + "\",\"rev\":2}"));
		t = run(t, 3);
		assertEquals(fetches + 1, count("GET /friends"));
		live.event(MeEvent.wpshare("wpshare_changed", "{\"uuid\":\"" + FRIEND + "\",\"rev\":3}"));
		t = run(t, 3);
		assertEquals(fetches + 2, count("GET /friends"));
		// Wer die Welt verlässt, verliert die Live-Position (Wegpunkte bleiben).
		live.event(MeEvent.wpshare("wpshare_position", "{\"uuid\":\"" + FRIEND + "\",\"x\":1,\"y\":2,\"z\":3,\"dimension\":\"minecraft:overworld\"}"));
		live.event(MeEvent.wpshare("wpshare_peers", "{\"send\":1,\"receive\":[]}"));
		assertNull(live.owner(FRIEND).position());
		assertEquals(1, live.owner(FRIEND).waypoints().size());
		// Freigabe entzogen → alles weg.
		live.event(MeEvent.wpshare("wpshare_revoked", "{\"uuid\":\"" + FRIEND + "\"}"));
		assertNull(live.owner(FRIEND));
		// Stream-Lücke → Sitzung + Freunde neu.
		int sessions = count("PUT /session");
		live.event(MeEvent.stream("resync", false));
		run(t, 4);
		assertEquals(sessions + 1, count("PUT /session"));
		assertEquals(fetches + 3, count("GET /friends"));
	}

	@Test
	void singleplayerSharesNothingAndLeavingEndsTheSession() {
		env.key = "sp:Neue Welt";
		run(1000, 10);
		assertTrue(calls.isEmpty(), calls.toString());
		assertFalse(live.active());
		env.key = "mp:play.example.net";
		long t = run(2000, 5);
		assertTrue(live.active());
		env.inWorld = false;
		run(t, 2);
		assertEquals(1, count("DELETE /session"));
		assertFalse(live.active());
		// Hauptschalter aus → nichts.
		calls.clear();
		env.inWorld = true;
		modules.waypointShareLive.set(false);
		run(t + 1000, 10);
		assertTrue(calls.isEmpty(), calls.toString());
	}

	@Test
	void hostedWorldsUseTheRoomIdentity() {
		env.key = "sp:Meine Welt";
		env.hosted = LiveWorld.hosted(SELF, "Meine Welt");
		run(1000, 5);
		assertTrue(live.active());
		assertTrue(bodies.get(calls.indexOf("PUT /session")).contains("\"type\":\"world\""));
	}

	@Test
	void unauthorizedAndUnknownRoutesBackOffQuietly() {
		routes = key -> key.startsWith("PUT /session") ? error(401, "unauthorized", "") : defaultRoutes(key);
		long t = run(1000, 5);
		assertEquals(1, count("PUT /session"));
		assertEquals(Collections.singletonList("tok"), unauthorized);
		run(t, 20);
		assertEquals(1, count("PUT /session"), "Backoff");
		routes = key -> new Http.Response(404, Collections.<String, String>emptyMap(), "<html>".getBytes(StandardCharsets.UTF_8));
		calls.clear();
		run(t + 10_000, 5);
		int n = calls.size();
		run(t + 60_000, 20);
		assertEquals(n, calls.size(), "Server ohne §41: Ruhe");
	}

	/** So antwortet die echte API heute auf die noch fehlende Route (gemessen 2026-11-05). */
	@Test
	void serverWithoutTheApiStaysSilentAndAsksOncePerHour() {
		routes = key -> error(404, "not_found", "");
		live.wantGrants();
		long t = run(1000, 5);
		assertEquals(1, count("PUT /session"), "genau ein Versuch");
		assertEquals(null, live.takeNotice(), "keine Meldung");
		assertTrue(live.apiMissing(), "keine Oberfläche");
		assertFalse(live.wantsStream(), "kein Konto-Stream nur dafür");
		int n = calls.size();
		run(t + 50 * 60_000L, 20);
		assertEquals(n, calls.size(), "eine Stunde Ruhe");
		live.setGrant(FRIEND, true);
		run(t + 50 * 60_000L + 1000, 3);
		assertFalse(live.granted(FRIEND), "zurückgenommen");
		assertEquals(null, live.takeNotice(), "auch hier keine Meldung");
		routes = key -> defaultRoutes(key);
		run(t + 2 * 60 * 60_000L, 5);
		assertFalse(live.apiMissing(), "Sobald der Server die API hat, läuft sie wieder");
		assertTrue(live.wantsStream());
	}

	@Test
	void grantsLoadAndToggleWithRevert() {
		live.wantGrants();
		run(1000, 3);
		assertTrue(live.grantsLoaded());
		assertTrue(live.grantedToMe(FRIEND));
		assertFalse(live.granted(FRIEND));
		live.setGrant(FRIEND, true);
		assertTrue(live.granted(FRIEND));
		run(2000, 2);
		assertEquals(1, count("PUT /grants/" + FRIEND));
		routes = key -> key.startsWith("PUT /grants") ? error(403, "not_friends", "") : defaultRoutes(key);
		live.setGrant(OTHER, true);
		assertTrue(live.granted(OTHER));
		run(3000, 2);
		assertFalse(live.granted(OTHER), "zurückgenommen");
		assertEquals("waypoint.live.notFriends", live.takeNotice());
		live.setGrant(FRIEND, false);
		run(4000, 2);
		assertEquals(1, count("DELETE /grants/" + FRIEND));
		assertFalse(live.granted(FRIEND));
	}

	private static Waypoint wp(String name, int x, int y, int z, boolean shared) {
		Waypoint w = new Waypoint(name, x, y, z, "minecraft:overworld", 0xABCDEF);
		w.shared = shared;
		return w;
	}

	static final class FakeEnv implements LiveShare.Env {
		final WaypointStore store;
		boolean inWorld = true;
		String key = "mp:play.example.net";
		String dim = "minecraft:overworld";
		double x, y = 64, z;
		LiveWorld hosted;

		FakeEnv(WaypointStore store) {
			this.store = store;
		}

		@Override
		public boolean inWorld() {
			return inWorld;
		}

		@Override
		public String storeKey() {
			return key;
		}

		@Override
		public String dimension() {
			return dim;
		}

		@Override
		public double x() {
			return x;
		}

		@Override
		public double y() {
			return y;
		}

		@Override
		public double z() {
			return z;
		}

		@Override
		public float yaw() {
			return 0;
		}

		@Override
		public WaypointStore store() {
			return store;
		}

		@Override
		public void waypointsChanged() {
		}

		@Override
		public LiveWorld hostedWorld(String self) {
			return hosted;
		}
	}
}

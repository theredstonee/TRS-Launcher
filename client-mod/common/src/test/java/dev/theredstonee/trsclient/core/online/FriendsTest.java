package dev.theredstonee.trsclient.core.online;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Freunde im Spiel: API-Antworten bereinigen, höflicher Abfragetakt, Aktionen mit Meldungen. */
class FriendsTest {
	static final String TOKEN = "trs_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	static final String BOB = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";
	static final String ALEX = "a1e1a1e1a1e1a1e1a1e1a1e1a1e1a1e1";

	static final String FRIENDS_JSON = "{\"friends\":["
			+ "{\"uuid\":\"" + BOB + "\",\"name\":\"Bob\",\"since\":\"2026-09-23T18:05:10.000Z\","
			+ "\"presence\":{\"state\":\"in-game\",\"game\":{\"version\":\"1.21.1\",\"loader\":\"fabric\",\"server\":\"Play.Example.net:25565\"},\"updatedAt\":\"2026-09-23T18:06:00.000Z\"}},"
			+ "{\"uuid\":\"c0c0c0c0-c0c0-c0c0-c0c0-c0c0c0c0c0c0\",\"name\":\"Carl\",\"presence\":{\"state\":\"online\",\"game\":null}},"
			+ "{\"uuid\":\"kaputt\",\"name\":\"Bad\"},"
			+ "{\"uuid\":\"d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0\",\"name\":\"<script>\"},"
			+ "{\"uuid\":\"e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0\",\"name\":\"Anna\",\"presence\":{\"state\":\"away\",\"game\":{\"loader\":\"rift\"}}}"
			+ "],\"requests\":{\"incoming\":[{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\",\"createdAt\":\"2026-09-23T18:00:00.000Z\"}],"
			+ "\"outgoing\":[]}}";

	/** Hintergrund-Thread und Ergebnis-Warteschlange synchron nachgebaut. */
	static final class Backend implements Friends.Backend {
		final Deque<Runnable> tasks = new ArrayDeque<>();
		final Deque<Runnable> results = new ArrayDeque<>();
		String unauthorized;

		@Override
		public boolean submit(Runnable task) {
			tasks.add(task);
			return true;
		}

		@Override
		public void post(Runnable onGameThread) {
			results.add(onGameThread);
		}

		@Override
		public void unauthorized(String rejectedToken) {
			unauthorized = rejectedToken;
		}

		/** Alles abarbeiten (erst Netz, dann Spiel-Thread). */
		void run() {
			while (!tasks.isEmpty() || !results.isEmpty()) {
				while (!tasks.isEmpty()) tasks.poll().run();
				while (!results.isEmpty()) results.poll().run();
			}
		}
	}

	private static Friends friends(FakeHttp http, Backend backend) {
		return new Friends(new TrsApi(http, OnlineTest.CONFIG), backend);
	}

	@Test
	void parsesAndCleansTheFriendsList() throws Exception {
		FakeHttp http = new FakeHttp().json("GET /v1/friends", 200, FRIENDS_JSON);
		FriendsView view = new TrsApi(http, OnlineTest.CONFIG).friends(TOKEN);
		assertEquals(3, view.friends.size(), "ungültige UUID/Name fallen weg");
		FriendsView.Friend bob = view.friends.get(0);
		assertEquals("Bob", bob.name, "im Spiel zuerst");
		assertTrue(bob.inGame());
		assertEquals("fabric", bob.loader);
		assertEquals("play.example.net:25565", bob.server, "Adresse klein");
		assertEquals("Carl", view.friends.get(1).name);
		assertTrue(view.friends.get(1).online());
		assertFalse(view.friends.get(1).inGame());
		FriendsView.Friend anna = view.friends.get(2);
		assertNull(anna.state, "unbekannter Status = offline");
		assertEquals(2, view.onlineCount(), "Bob + Carl online");
		assertEquals(1, view.incoming.size());
		assertEquals(ALEX, view.incoming.get(0).uuid);
		assertEquals(1, view.onServer("play.example.net").size(), "Standard-Port egal");
		assertEquals(0, view.onServer("other.net").size());
		assertEquals("Bearer " + TOKEN, http.requests.get(0).headers.get("Authorization"));
	}

	@Test
	void pollsOnlyWhileSomebodyLooks() {
		FakeHttp http = new FakeHttp().json("GET /v1/friends", 200, FRIENDS_JSON);
		Backend backend = new Backend();
		Friends f = friends(http, backend);
		long now = System.currentTimeMillis();
		f.tick(now, TOKEN);
		backend.run();
		assertEquals(0, http.requests.size(), "ohne Interesse keine Anfrage");

		f.want(Friends.Interest.FOREGROUND, false);
		f.tick(now, TOKEN);
		backend.run();
		assertEquals(1, http.requests.size());
		assertNotNull(f.snapshot().view);
		assertEquals(1, f.snapshot().incoming());

		f.tick(now + 10_000, TOKEN);
		backend.run();
		assertEquals(1, http.requests.size(), "nicht vor 30 s");
		f.want(Friends.Interest.FOREGROUND, false, now + Friends.FOREGROUND_MS);
		f.tick(now + Friends.FOREGROUND_MS + 1, TOKEN);
		backend.run();
		assertEquals(2, http.requests.size(), "nach 30 s erneut");
	}

	@Test
	void rateLimitWaitsForRetryAfter() {
		Map<String, String> headers = new HashMap<>();
		headers.put("retry-after", "20");
		headers.put("content-type", "application/json");
		FakeHttp http = new FakeHttp().on("GET /v1/friends", r -> new Http.Response(429, headers,
				"{\"error\":{\"code\":\"rate_limited\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		Backend backend = new Backend();
		Friends f = friends(http, backend);
		f.want(Friends.Interest.FOREGROUND, false);
		long now = System.currentTimeMillis();
		f.tick(now, TOKEN);
		backend.run();
		assertEquals("friends.error.rate_limited", f.snapshot().error);
		f.refresh();
		f.tick(System.currentTimeMillis() + 5_000, TOKEN);
		backend.run();
		assertEquals(1, http.requests.size(), "Retry-After wird eingehalten");
	}

	@Test
	void actionsSendTheRightRequestsAndRefresh() {
		FakeHttp http = new FakeHttp()
				.json("GET /v1/friends", 200, FRIENDS_JSON)
				.json("POST /v1/friends/requests/" + ALEX + "/accept", 200, "{\"friend\":{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\"}}")
				.on("DELETE /v1/friends/" + BOB, r -> FakeHttp.response(204, null))
				.json("POST /v1/friends/requests", 201, "{\"status\":\"sent\",\"user\":{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\"}}")
				.json("POST /v1/blocks", 404, "{\"error\":{\"code\":\"player_not_found\"}}");
		Backend backend = new Backend();
		Friends f = friends(http, backend);
		f.tick(System.currentTimeMillis(), TOKEN);

		assertTrue(f.act(Friends.Action.ACCEPT, ALEX, "Alex"));
		assertFalse(f.act(Friends.Action.REMOVE, BOB, "Bob"), "eine Aktion nach der anderen");
		backend.run();
		assertEquals("friends.msg.nowFriends", f.snapshot().message);
		assertFalse(f.snapshot().messageError);
		f.tick(System.currentTimeMillis() + Friends.MIN_GAP_MS + 1, TOKEN);
		backend.run();
		assertTrue(http.calls().contains("GET /v1/friends"), "nach der Aktion neu geladen");

		assertTrue(f.act(Friends.Action.REMOVE, BOB, "Bob"));
		backend.run();
		assertEquals("friends.msg.removed", f.snapshot().message);

		assertTrue(f.act(Friends.Action.REQUEST, " Alex ", "Alex"));
		backend.run();
		assertEquals("friends.msg.sent", f.snapshot().message);
		assertTrue(FakeHttp.body(http.requests.get(http.requests.size() - 1)).contains("\"target\":\"Alex\""));

		assertTrue(f.act(Friends.Action.BLOCK, "Nobody", "Nobody"));
		backend.run();
		assertEquals("friends.error.player_not_found", f.snapshot().message);
		assertTrue(f.snapshot().messageError);

		assertFalse(f.act(Friends.Action.REQUEST, "no spaces allowed!", null));
		assertEquals("friends.error.invalid_name", f.snapshot().message);
		assertFalse(f.act(Friends.Action.REMOVE, "../../v1/me", null), "nur UUIDs im Pfad");
	}

	@Test
	void accountChangeForgetsEverything() {
		FakeHttp http = new FakeHttp().json("GET /v1/friends", 200, FRIENDS_JSON);
		Backend backend = new Backend();
		Friends f = friends(http, backend);
		f.want(Friends.Interest.BACKGROUND, false);
		f.tick(System.currentTimeMillis(), TOKEN);
		backend.run();
		assertNotNull(f.snapshot().view);
		f.reset();
		assertNull(f.snapshot().view);
		assertFalse(f.act(Friends.Action.REMOVE, BOB, "Bob"), "ohne Token keine Aktion");
		assertEquals("friends.error.offline", f.snapshot().message);
	}

	@Test
	void targetsAndErrorKeys() {
		assertEquals("Steve_1", FriendsView.target(" Steve_1 "));
		assertEquals(BOB, FriendsView.target("B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0"));
		assertNull(FriendsView.target("a b"));
		assertNull(FriendsView.target("abcdefghijklmnopq"));
		assertEquals("friends.error.already_friends", Friends.errorKey("already_friends", 409));
		assertEquals("friends.error.generic", Friends.errorKey("something_new", 400));
		assertEquals("friends.error.rate_limited", Friends.errorKey("whatever", 429));
		List<FriendsView.Friend> sorted = FriendsView.sorted(java.util.Arrays.asList(
				new FriendsView.Friend(BOB, "zed", null, null, null, null),
				new FriendsView.Friend(ALEX, "Amy", "online", null, null, null)));
		assertEquals("Amy", sorted.get(0).name);
	}
}

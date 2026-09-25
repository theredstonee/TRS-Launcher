package dev.theredstonee.trsclient.core.online;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static dev.theredstonee.trsclient.core.online.EmoteOnlineTest.OTHER;
import static dev.theredstonee.trsclient.core.online.EmoteOnlineTest.waitFor;
import static dev.theredstonee.trsclient.core.online.OnlineTest.CONFIG;
import static dev.theredstonee.trsclient.core.online.OnlineTest.loginRoutes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-TRS-Abzeichen (API.md §4.1/§4.2): Der Mod meldet "in-game" (via client) nur in einer Welt, fragt danach alle
 * neu, übernimmt Abzeichen-Ereignisse und fragt neu Aufgetauchte einmal nach.
 */
class LiveBadgeTest {
	static final UUID OTHER_ID = Uuids.toUuid(OTHER);

	@TempDir
	Path dir;

	@Test
	void badgeEventsAreParsedStrictly() {
		PlayerEvent on = PlayerEvent.parse("badge", "{\"type\":\"badge\",\"uuid\":\"" + OTHER + "\",\"badge\":true}");
		assertEquals("badge", on.type);
		assertTrue(on.badge);
		assertFalse(PlayerEvent.parse("badge", "{\"uuid\":\"" + OTHER + "\",\"badge\":false}").badge);
		assertNull(PlayerEvent.parse("badge", "{\"uuid\":\"" + OTHER + "\"}"), "ohne Wert → ignoriert");
		assertNull(PlayerEvent.parse("badge", "{\"uuid\":\"nope\",\"badge\":true}"));
	}

	@Test
	void newPlayersWithoutBadgeAreAskedOnceMore() {
		PlayerDirectory d = new PlayerDirectory();
		List<String> one = Collections.singletonList(OTHER);
		d.observe(one, 0);
		assertEquals(one, d.nextBatch(0));
		d.complete(one, Collections.<String, PlayerInfo>emptyMap(), 500);
		assertTrue(d.nextBatch(5_000).isEmpty(), "noch nicht");
		assertEquals(one, d.nextBatch(500 + PlayerDirectory.JOIN_RECHECK_MS), "einmal nachfragen");
		d.complete(one, Collections.<String, PlayerInfo>emptyMap(), 11_000);
		assertTrue(d.nextBatch(30_000).isEmpty(), "nur einmal – danach gilt wieder der normale Cache");

		// Wer beim ersten Mal schon ein Abzeichen hat, wird nicht nachgefragt.
		PlayerDirectory d2 = new PlayerDirectory();
		d2.observe(one, 0);
		d2.nextBatch(0);
		d2.complete(one, Collections.singletonMap(OTHER, new PlayerInfo(true, null)), 500);
		assertTrue(d2.nextBatch(500 + PlayerDirectory.JOIN_RECHECK_MS).isEmpty());
	}

	@Test
	void badgeEventsUpdateKnownPlayersAndKeepThemWatched() {
		PlayerDirectory d = new PlayerDirectory();
		List<String> one = Collections.singletonList(OTHER);
		d.observe(one, 0);
		d.nextBatch(0);
		d.complete(one, Collections.<String, PlayerInfo>emptyMap(), 100);
		assertTrue(d.visibleUsers().isEmpty());
		d.applyBadge(OTHER, true);
		assertTrue(d.get(OTHER_ID).badge);
		d.applyBadge(OTHER, false);
		assertFalse(d.get(OTHER_ID).badge);
		assertEquals(one, d.visibleUsers(), "bleibt im Stream, damit ein neues Abzeichen sofort ankommt");
		d.applyBadge(repeatHex('c'), true);
		assertEquals(PlayerInfo.NONE, d.get(repeatHex('c')), "Unbekannte werden ignoriert");

		// Alle neu fragen (z. B. sobald man selbst im Spiel gemeldet ist).
		d.invalidateAll();
		assertEquals(one, d.nextBatch(60_000));
	}

	@Test
	void presenceOnlyInAWorldAndBeforeTheLookupsThere() throws Exception {
		FakeHttp http = loginRoutes()
				.json("POST /v1/players/lookup", 200, "{\"players\":[]}")
				.json("POST /v1/presence", 200, "{\"state\":\"in-game\",\"expiresInSec\":180}")
				.json("GET /v1/me", 200, "{\"settings\":{\"shareServer\":false}}");
		OnlineTest.Platform platform = new OnlineTest.Platform();
		platform.inWorld = false;
		TrsOnline online = new TrsOnline(CONFIG, platform, http, dir, new EmoteOnlineTest.FakeOpener());

		// Titelbildschirm: angemeldet und das eigene Konto nachgeschlagen, aber kein "in-game".
		OnlineTest.tickUntil(online, Collections.<UUID>emptyList(), () -> http.calls().contains("POST /v1/players/lookup"));
		assertFalse(http.calls().contains("POST /v1/presence"));

		// Welt betreten: sofort "in-game" (via client), danach alle neu nachschlagen.
		platform.inWorld = true;
		List<UUID> visible = Collections.singletonList(OTHER_ID);
		int before = http.calls().size();
		OnlineTest.tickUntil(online, visible, () -> countAfter(http, before, "POST /v1/players/lookup") >= 1
				&& http.calls().contains("POST /v1/presence"));
		List<String> calls = http.calls().subList(before, http.calls().size());
		assertTrue(calls.indexOf("POST /v1/presence") < calls.indexOf("POST /v1/players/lookup"),
				"erst die eigene Meldung, dann die Lookups: " + calls);
		String body = lastBody(http, "/v1/presence");
		assertTrue(body.contains("\"state\":\"in-game\"") && body.contains("\"via\":\"client\""), body);

		// Welt verlassen: nach kurzer Wartezeit "offline" (via client).
		platform.inWorld = false;
		long now = System.currentTimeMillis();
		online.tick(now, Collections.<UUID>emptyList(), true);
		assertEquals(1, count(http, "POST /v1/presence"), "noch innerhalb der Wartezeit");
		long later = now + TrsOnline.LEAVE_GRACE_MS + 1;
		waitFor(() -> {
			online.tick(later, null, true);
			return count(http, "POST /v1/presence") == 2;
		});
		String offline = lastBody(http, "/v1/presence");
		assertTrue(offline.contains("\"state\":\"offline\"") && offline.contains("\"via\":\"client\""), offline);
	}

	@Test
	void moduleOffRetractsTheReport() throws Exception {
		FakeHttp http = loginRoutes()
				.json("POST /v1/players/lookup", 200, "{\"players\":[]}")
				.json("POST /v1/presence", 200, "{}")
				.json("GET /v1/me", 200, "{\"settings\":{\"shareServer\":false}}");
		OnlineTest.Platform platform = new OnlineTest.Platform();
		TrsOnline online = new TrsOnline(CONFIG, platform, http, dir, new EmoteOnlineTest.FakeOpener());
		OnlineTest.tickUntil(online, Collections.<UUID>emptyList(), () -> http.calls().contains("POST /v1/presence"));
		// Bestätigung abwarten, dann Modul aus.
		waitFor(() -> {
			online.tick(System.currentTimeMillis(), null, true);
			return online.reportedInGame();
		});
		online.tick(System.currentTimeMillis(), null, false);
		waitFor(() -> count(http, "POST /v1/presence") == 2);
		assertTrue(lastBody(http, "/v1/presence").contains("\"state\":\"offline\""));
		online.tick(System.currentTimeMillis(), null, false);
		Thread.sleep(50);
		assertEquals(2, count(http, "POST /v1/presence"), "nur einmal");
	}

	@Test
	void badgeEventsArriveOverTheStreamWithoutEmotes() throws Exception {
		FakeHttp http = loginRoutes()
				.json("POST /v1/players/lookup", 200, "{\"players\":[]}")
				.json("POST /v1/presence", 200, "{}")
				.json("GET /v1/me", 200, "{\"settings\":{\"shareServer\":false}}");
		EmoteOnlineTest.FakeOpener opener = new EmoteOnlineTest.FakeOpener();
		TrsOnline online = new TrsOnline(CONFIG, new OnlineTest.Platform(), http, dir, opener);
		online.wantEmotes(false, false);
		online.wantBadges(true);
		List<UUID> visible = new ArrayList<>(Collections.singletonList(OTHER_ID));
		OnlineTest.tickUntil(online, visible, () -> !opener.connections.isEmpty());
		opener.last().send("hello", "{\"type\":\"hello\",\"keepaliveSec\":25,\"watching\":1}");
		opener.last().send("badge", "{\"type\":\"badge\",\"uuid\":\"" + OTHER + "\",\"badge\":true}");
		OnlineTest.tickUntil(online, null, () -> online.info(OTHER_ID).badge);
		assertTrue(online.pollEmoteEvents().isEmpty());
	}

	private static String repeatHex(char c) {
		return OnlineTest.repeat(c, 32);
	}

	private static int count(FakeHttp http, String call) {
		return Collections.frequency(http.calls(), call);
	}

	private static int countAfter(FakeHttp http, int from, String call) {
		List<String> calls = http.calls();
		return Collections.frequency(calls.subList(Math.min(from, calls.size()), calls.size()), call);
	}

	private static String lastBody(FakeHttp http, String path) {
		String body = "";
		for (Http.Request r : new ArrayList<>(http.requests)) {
			if (r.url.endsWith(path)) body = FakeHttp.body(r);
		}
		return body;
	}
}

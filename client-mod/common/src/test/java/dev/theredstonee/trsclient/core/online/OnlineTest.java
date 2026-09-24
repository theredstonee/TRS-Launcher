package dev.theredstonee.trsclient.core.online;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TRS API im Mod: Konfiguration, Endpunkte, Lookup-Cache und der Ablauf in TrsOnline. */
class OnlineTest {
	static final String OWN = "75c1a6f3112240abbdb57b9d21c64232";
	static final String TOKEN = "trs_" + repeat('A', 43);
	static final OnlineConfig CONFIG = new OnlineConfig(true, "https://api.theredstonee.de", "https://sessionserver.mojang.com");

	@TempDir
	Path dir;

	static String repeat(char c, int n) {
		char[] a = new char[n];
		Arrays.fill(a, c);
		return new String(a);
	}

	// --- Konfiguration ---

	@Test
	void launcherFileSwitchesTheApiOff() throws IOException {
		assertTrue(OnlineConfig.readEnabled(dir), "ohne Datei an");
		Path file = dir.resolve("trsclient").resolve("trs-api.json");
		Files.createDirectories(file.getParent());
		Files.write(file, "{\"version\":1,\"enabled\":false}".getBytes(StandardCharsets.UTF_8));
		assertFalse(OnlineConfig.readEnabled(dir));
		Files.write(file, "{\"version\":1,\"enabled\":true}".getBytes(StandardCharsets.UTF_8));
		assertTrue(OnlineConfig.readEnabled(dir));
		Files.write(file, "kaputt{".getBytes(StandardCharsets.UTF_8));
		assertTrue(OnlineConfig.readEnabled(dir), "kaputte Datei → an (abschalten geht im Menü)");
	}

	@Test
	void baseUrlsAreValidated() {
		String d = OnlineConfig.DEFAULT_API;
		assertEquals("https://example.org", OnlineConfig.baseUrl("https://example.org/", d));
		assertEquals("http://127.0.0.1:8787", OnlineConfig.baseUrl("http://127.0.0.1:8787", d));
		assertEquals(d, OnlineConfig.baseUrl("http://example.org", d), "http nur für localhost");
		assertEquals(d, OnlineConfig.baseUrl("https://example.org/pfad", d));
		assertEquals(d, OnlineConfig.baseUrl("ftp://localhost", d));
		assertEquals(d, OnlineConfig.baseUrl("", d));
		assertTrue(CONFIG.isApiUrl("https://api.theredstonee.de/v1/capes/team.png?v=1"));
		assertFalse(CONFIG.isApiUrl("https://api.theredstonee.de.evil.com/v1/capes/team.png"));
		assertFalse(CONFIG.isApiUrl("https://evil.com/cape.png"));
	}

	@Test
	void uuidsAreNormalized() {
		assertEquals(OWN, Uuids.normalize("75C1A6F3-1122-40AB-BDB5-7B9D21C64232"));
		assertNull(Uuids.normalize("zz"));
		assertEquals(OWN, Uuids.of(UUID.fromString("75c1a6f3-1122-40ab-bdb5-7b9d21c64232")));
	}

	@Test
	void capeInfoIsStrictAndPicksFramesByWallClock() {
		CapeInfo team = CapeInfo.of("team", "https://api.theredstonee.de/v1/capes/team.png?v=6", 2, 8, 150, CONFIG);
		assertNotNull(team);
		assertEquals(0, team.frameAt(0));
		assertEquals(1, team.frameAt(150));
		assertEquals(7, team.frameAt(150 * 7 + 149));
		assertEquals(0, team.frameAt(150 * 8));
		assertNull(CapeInfo.of("team", "https://evil.com/x.png", 2, 1, null, CONFIG), "fremde URL");
		assertNull(CapeInfo.of("Team!", "https://api.theredstonee.de/v1/capes/x.png", 1, 1, null, CONFIG));
		assertNull(CapeInfo.of("x", "https://api.theredstonee.de/v1/capes/x.png", 9, 1, null, CONFIG), "scale 9");
		assertNull(CapeInfo.of("x", "https://api.theredstonee.de/v1/capes/x.png", 1, 3, null, CONFIG),
				"animiert ohne Bilddauer");
		CapeInfo still = CapeInfo.of("x", "https://api.theredstonee.de/v1/capes/x.png", 1, 1, null, CONFIG);
		assertFalse(still.animated());
		assertEquals(0, still.frameAt(123456));
	}

	// --- Endpunkte ---

	static FakeHttp loginRoutes() {
		return new FakeHttp()
				.json("POST /v1/auth/challenge", 201, "{\"serverId\":\"" + repeat('a', 40) + "\",\"expiresAt\":\"x\"}")
				.on("POST /session/minecraft/join", r -> FakeHttp.response(204, null))
				.json("POST /v1/auth/verify", 200, "{\"token\":\"" + TOKEN + "\",\"expiresAt\":\"x\",\"user\":{\"uuid\":\""
						+ OWN + "\",\"name\":\"Theredstonee\",\"settings\":{\"shareServer\":false}}}");
	}

	@Test
	void loginRunsChallengeJoinVerifyWithUnhashedServerId() throws Exception {
		FakeHttp http = loginRoutes();
		TrsApi.Session s = new TrsApi(http, CONFIG).login(new GameSession(OWN, "Theredstonee", "ey.secret.token"));
		assertEquals(TOKEN, s.token);
		assertEquals(Arrays.asList("POST /v1/auth/challenge", "POST /session/minecraft/join", "POST /v1/auth/verify"),
				http.calls());
		String join = FakeHttp.body(http.requests.get(1));
		assertTrue(join.contains("\"serverId\":\"" + repeat('a', 40) + "\""), join);
		assertTrue(join.contains("\"selectedProfile\":\"" + OWN + "\""));
		assertTrue(FakeHttp.body(http.requests.get(2)).contains("\"username\":\"Theredstonee\""));
		assertNull(http.requests.get(2).headers.get("Authorization"));
	}

	@Test
	void mojangRejectionIsAnApiError() {
		FakeHttp http = loginRoutes().on("POST /session/minecraft/join", r -> FakeHttp.response(403, "{}"));
		ApiException e = assertThrows(ApiException.class,
				() -> new TrsApi(http, CONFIG).login(new GameSession(OWN, "Theredstonee", "ey.secret.token")));
		assertEquals(403, e.status());
		assertFalse(e.banned());
	}

	@Test
	void lookupParsesPlayersAndDropsInvalidCapes() throws Exception {
		FakeHttp http = new FakeHttp().json("POST /v1/players/lookup", 200, "{\"players\":["
				+ "{\"uuid\":\"" + OWN + "\",\"badge\":true,\"cape\":{\"id\":\"team\",\"url\":\"https://api.theredstonee.de/v1/capes/team.png?v=1\",\"scale\":2,\"animated\":true,\"frames\":8,\"frameTimeMs\":150}},"
				+ "{\"uuid\":\"ffffffffffffffffffffffffffffffff\",\"badge\":false,\"cape\":{\"id\":\"evil\",\"url\":\"https://evil.com/a.png\",\"scale\":1,\"frames\":1}},"
				+ "{\"uuid\":\"kaputt\",\"badge\":true}]}");
		Map<String, PlayerInfo> out = new TrsApi(http, CONFIG).lookup(TOKEN,
				Arrays.asList(OWN, "ffffffffffffffffffffffffffffffff"));
		assertEquals(2, out.size());
		assertTrue(out.get(OWN).badge);
		assertEquals("team", out.get(OWN).cape.id);
		assertEquals(8, out.get(OWN).cape.frames);
		assertNull(out.get("ffffffffffffffffffffffffffffffff").cape, "fremde Textur-URL wird verworfen");
		assertEquals("Bearer " + TOKEN, http.requests.get(0).headers.get("Authorization"));
	}

	@Test
	void rateLimitCarriesRetryAfter() {
		FakeHttp http = new FakeHttp().on("POST /v1/players/lookup", r -> {
			Http.Response resp = FakeHttp.response(429, "{\"error\":{\"code\":\"rate_limited\",\"retryAfter\":12}}");
			resp.headers.put("retry-after", "12");
			return resp;
		});
		ApiException e = assertThrows(ApiException.class,
				() -> new TrsApi(http, CONFIG).lookup(TOKEN, Collections.singletonList(OWN)));
		assertTrue(e.rateLimited());
		assertEquals("rate_limited", e.code());
		assertEquals(12_000L, e.retryAfterMs());
	}

	@Test
	void presenceFieldsAreCleaned() {
		assertEquals("play.example.net:25565", TrsOnline.cleanServer(" Play.Example.NET:25565 "));
		assertEquals("10.0.0.1", TrsOnline.cleanServer("10.0.0.1"));
		assertNull(TrsOnline.cleanServer("http://x.de"));
		assertNull(TrsOnline.cleanServer("a b"));
		assertNull(TrsOnline.cleanServer(null));
		assertEquals("1.21.1", TrsOnline.cleanVersion("1.21.1"));
		assertEquals("unknown", TrsOnline.cleanVersion("1.21/../x"));
	}

	// --- Lookup-Cache ---

	static List<String> uuids(int from, int n) {
		List<String> out = new ArrayList<>();
		for (int i = from; i < from + n; i++) out.add(String.format("%032x", i));
		return out;
	}

	@Test
	void directoryBatchesAtMostHundredAndRespectsInterval() {
		PlayerDirectory d = new PlayerDirectory();
		d.observe(uuids(1, 150), 0);
		List<String> first = d.nextBatch(0);
		assertEquals(100, first.size());
		assertTrue(d.nextBatch(10_000).isEmpty(), "während eine Anfrage läuft, keine zweite");
		d.complete(first, new HashMap<String, PlayerInfo>(), 100);
		assertTrue(d.nextBatch(1000).isEmpty(), "Mindestabstand 2 s");
		List<String> second = d.nextBatch(2100);
		assertEquals(50, second.size());
		d.complete(second, Collections.singletonMap(second.get(0), new PlayerInfo(true, null)), 2200);
		assertTrue(d.get(second.get(0)).badge);
		assertFalse(d.get(first.get(0)).badge, "ohne Eintrag = kein TRS");
		assertTrue(d.nextBatch(10_000).isEmpty(), "alles frisch");
		assertEquals(100, d.nextBatch(2200 + PlayerDirectory.TTL_MS).size(), "nach TTL erneut");
	}

	@Test
	void directoryRequeriesPlayersWhoRejoin() {
		PlayerDirectory d = new PlayerDirectory();
		List<String> one = uuids(7, 1);
		d.observe(one, 0);
		d.complete(d.nextBatch(0), new HashMap<String, PlayerInfo>(), 0);
		d.observe(Collections.<String>emptyList(), 10_000);
		d.observe(one, 20_000);
		assertTrue(d.nextBatch(20_000).isEmpty(), "zu früh wieder da: Cache reicht");
		d.observe(Collections.<String>emptyList(), 30_000);
		d.observe(one, 40_000);
		assertEquals(one, d.nextBatch(40_000), "Wiederbeitritt nach 30 s → neu fragen");
	}

	@Test
	void directoryBacksOffOnRateLimit() {
		PlayerDirectory d = new PlayerDirectory();
		d.observe(uuids(1, 3), 0);
		assertEquals(3, d.nextBatch(0).size());
		d.failed(10, 60_000);
		assertTrue(d.nextBatch(30_000).isEmpty());
		assertEquals(3, d.nextBatch(60_010).size());
	}

	// --- TrsOnline (echte Hintergrund-Threads, Attrappe als Netz) ---

	static final class Platform implements OnlinePlatform {
		volatile GameSession session = new GameSession(OWN, "Theredstonee", "ey.secret.token");
		volatile String server = "Play.Example.net:25565";
		final List<String> log = Collections.synchronizedList(new ArrayList<String>());

		@Override
		public GameSession session() {
			return session;
		}

		@Override
		public String minecraftVersion() {
			return "1.21.1";
		}

		@Override
		public String loader() {
			return "fabric";
		}

		@Override
		public String serverAddress() {
			return server;
		}

		@Override
		public void log(String message) {
			log.add(message);
		}
	}

	/** Tickt, bis {@code done} gilt (höchstens 5 s). */
	static void tickUntil(TrsOnline online, List<UUID> visible, java.util.function.BooleanSupplier done)
			throws InterruptedException {
		long end = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < end) {
			online.tick(System.currentTimeMillis(), visible, true);
			if (done.getAsBoolean()) return;
			Thread.sleep(5);
		}
		throw new AssertionError("Zeitüberschreitung");
	}

	@Test
	void onlineLogsInLooksUpAndSendsPresenceWithoutServer() throws Exception {
		FakeHttp http = loginRoutes()
				.json("POST /v1/players/lookup", 200, "{\"players\":[{\"uuid\":\"" + OWN + "\",\"badge\":true}]}")
				.json("POST /v1/presence", 200, "{\"state\":\"in-game\",\"expiresInSec\":180}")
				.json("GET /v1/me", 200, "{\"settings\":{\"shareServer\":false}}");
		TrsOnline online = new TrsOnline(CONFIG, new Platform(), http, dir);
		UUID own = UUID.fromString("75c1a6f3-1122-40ab-bdb5-7b9d21c64232");
		tickUntil(online, Collections.<UUID>emptyList(), () -> online.info(own).badge);
		tickUntil(online, Collections.<UUID>emptyList(), () -> http.calls().contains("POST /v1/presence"));
		assertEquals(TrsOnline.Status.ONLINE, online.status());
		for (Http.Request r : new ArrayList<>(http.requests)) {
			if (r.url.endsWith("/v1/presence")) {
				String body = FakeHttp.body(r);
				assertTrue(body.contains("\"state\":\"in-game\""), body);
				assertTrue(body.contains("\"loader\":\"fabric\""), body);
				assertFalse(body.contains("server"), "shareServer=false → keine Adresse: " + body);
			}
		}
	}

	@Test
	void presenceIncludesServerOnlyWhenShared() throws Exception {
		FakeHttp http = loginRoutes()
				.json("POST /v1/players/lookup", 200, "{\"players\":[]}")
				.json("POST /v1/presence", 200, "{}")
				.json("GET /v1/me", 200, "{\"settings\":{\"shareServer\":true}}");
		http.json("POST /v1/auth/verify", 200, "{\"token\":\"" + TOKEN + "\",\"user\":{\"settings\":{\"shareServer\":true}}}");
		TrsOnline online = new TrsOnline(CONFIG, new Platform(), http, dir);
		tickUntil(online, Collections.<UUID>emptyList(), () -> http.calls().contains("POST /v1/presence"));
		String body = "";
		for (Http.Request r : new ArrayList<>(http.requests)) {
			if (r.url.endsWith("/v1/presence")) body = FakeHttp.body(r);
		}
		assertTrue(body.contains("\"server\":\"play.example.net:25565\""), body);
	}

	@Test
	void launcherOptOutMeansNoCallsAtAll() throws Exception {
		FakeHttp http = loginRoutes();
		TrsOnline online = new TrsOnline(new OnlineConfig(false, CONFIG.apiBase(), CONFIG.sessionBase()),
				new Platform(), http, dir);
		for (int i = 0; i < 20; i++) {
			online.tick(System.currentTimeMillis(), Collections.<UUID>emptyList(), true);
			Thread.sleep(2);
		}
		assertTrue(http.requests.isEmpty());
		assertEquals(TrsOnline.Status.LAUNCHER_OFF, online.status());
	}

	@Test
	void unauthorizedTriggersANewLogin() throws Exception {
		final int[] lookups = {0};
		FakeHttp http = loginRoutes()
				.on("POST /v1/players/lookup", r -> {
					lookups[0]++;
					if (lookups[0] == 1) return FakeHttp.response(401, "{\"error\":{\"code\":\"unauthorized\"}}");
					return FakeHttp.response(200, "{\"players\":[{\"uuid\":\"" + OWN + "\",\"badge\":true}]}");
				})
				.json("POST /v1/presence", 200, "{}")
				.json("GET /v1/me", 200, "{}");
		TrsOnline online = new TrsOnline(CONFIG, new Platform(), http, dir);
		UUID own = UUID.fromString("75c1a6f3-1122-40ab-bdb5-7b9d21c64232");
		tickUntil(online, Collections.<UUID>emptyList(), () -> online.info(own).badge);
		int challenges = 0;
		for (String c : http.calls()) if (c.equals("POST /v1/auth/challenge")) challenges++;
		assertEquals(2, challenges, "nach 401 genau einmal neu angemeldet");
	}

	@Test
	void offlineAccountsNeverContactMojang() throws Exception {
		FakeHttp http = loginRoutes();
		Platform p = new Platform();
		p.session = new GameSession(OWN, "Player123", "0");
		TrsOnline online = new TrsOnline(CONFIG, p, http, dir);
		for (int i = 0; i < 10; i++) online.tick(System.currentTimeMillis(), Collections.<UUID>emptyList(), true);
		assertTrue(http.requests.isEmpty());
		assertEquals(TrsOnline.Status.NO_ACCOUNT, online.status());
	}
}

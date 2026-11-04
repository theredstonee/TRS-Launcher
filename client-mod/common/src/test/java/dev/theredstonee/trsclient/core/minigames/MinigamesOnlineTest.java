package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.Http;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rangliste ohne Netz, Rekorde, Texte. */
class MinigamesOnlineTest {
	private static final Executor DIRECT = new Executor() {
		@Override
		public void execute(Runnable command) {
			command.run();
		}
	};

	/** Antwortet je Pfad mit fester Antwort und merkt sich die Anfragen. */
	private static final class FakeHttp implements Http {
		final List<Request> requests = new ArrayList<Request>();
		int status = 200;
		String json = "{}";
		boolean fail;

		@Override
		public Response send(Request request) throws IOException {
			requests.add(request);
			if (fail) throw new IOException("offline");
			return new Response(status, Collections.<String, String>emptyMap(), json.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static final class Login implements MinigamesOnline.Account {
		String token = "secret";
		String rejected;

		@Override
		public String token() {
			return token;
		}

		@Override
		public String apiBase() {
			return "https://api.test";
		}

		@Override
		public void rejected(String t) {
			rejected = t;
		}
	}

	private static MinigameSession.Finished run(String runId) {
		return new MinigameSession.Finished(Replay.STAPLER, 7, runId, new int[] {3, 1, 2, 0}, 1234, 99);
	}

	@Test
	void startAsksTheServerAndKeepsLargeSeeds() {
		FakeHttp http = new FakeHttp();
		http.json = "{\"runId\":\"abc-1\",\"seed\":4294967295}";
		MinigamesOnline online = new MinigamesOnline(new Login(), http, DIRECT);
		MinigamesOnline.Pending<MinigamesApi.Start> start = online.start(Replay.LOREN);
		assertTrue(start.done());
		assertEquals("abc-1", start.value().runId);
		assertEquals(4294967295L, start.value().seed);
		Http.Request request = http.requests.get(0);
		assertEquals("POST", request.method);
		assertEquals("https://api.test/v1/minigames/loren/start", request.url);
		assertEquals("Bearer secret", request.headers.get("Authorization"));
	}

	@Test
	void withoutLoginOrServerARunStaysLocal() {
		Login login = new Login();
		login.token = null;
		FakeHttp http = new FakeHttp();
		MinigamesOnline online = new MinigamesOnline(login, http, DIRECT);
		assertNull(online.start(Replay.STAPLER).value());
		assertEquals(MinigamesOnline.BoardStatus.NEEDS_TRS, online.leaderboard(Replay.STAPLER).value().status);
		assertTrue(http.requests.isEmpty(), "ohne Anmeldung keine Anfrage");

		FakeHttp offline = new FakeHttp();
		offline.fail = true;
		MinigamesOnline noServer = new MinigamesOnline(new Login(), offline, DIRECT);
		assertTrue(noServer.start(Replay.STAPLER).done());
		assertNull(noServer.start(Replay.STAPLER).value());
		assertEquals(MinigamesOnline.BoardStatus.ERROR, noServer.leaderboard(Replay.STAPLER).value().status);
	}

	@Test
	void badAnswersDoNotStartARankedRun() {
		FakeHttp http = new FakeHttp();
		MinigamesOnline online = new MinigamesOnline(new Login(), http, DIRECT);
		for (String body : new String[] {"{}", "not json", "{\"runId\":\"a/b\",\"seed\":1}", "{\"runId\":\"ok\",\"seed\":-1}",
				"{\"runId\":\"ok\",\"seed\":4294967296}", "{\"runId\":\"ok\",\"seed\":\"x\"}"}) {
			http.json = body;
			assertNull(online.start(Replay.STAPLER).value(), body);
		}
	}

	@Test
	void submitSendsInputsAndScore() {
		FakeHttp http = new FakeHttp();
		http.json = "{\"accepted\":true,\"rank\":3,\"best\":5000,\"improved\":false}";
		MinigamesOnline online = new MinigamesOnline(new Login(), http, DIRECT);
		MinigamesOnline.Submit submit = online.submit(run("run-1")).value();
		assertEquals(MinigamesOnline.SubmitKind.RANKED, submit.kind);
		assertEquals(3, submit.rank);
		assertEquals(5000, submit.best);
		assertFalse(submit.improved);
		Http.Request request = http.requests.get(0);
		assertEquals("https://api.test/v1/minigames/stapler/runs", request.url);
		assertEquals("{\"runId\":\"run-1\",\"inputs\":[3,1,2,0],\"score\":1234}", new String(request.body, StandardCharsets.UTF_8));
	}

	@Test
	void submitOutcomes() {
		FakeHttp http = new FakeHttp();
		Login login = new Login();
		MinigamesOnline online = new MinigamesOnline(login, http, DIRECT);
		assertEquals(MinigamesOnline.SubmitKind.LOCAL, online.submit(run(null)).value().kind, "ohne Kennung nur lokal");
		assertTrue(http.requests.isEmpty());
		http.json = "{\"accepted\":false}";
		assertEquals(MinigamesOnline.SubmitKind.REJECTED, online.submit(run("r")).value().kind);
		http.status = 401;
		assertEquals(MinigamesOnline.SubmitKind.FAILED, online.submit(run("r")).value().kind);
		assertEquals("secret", login.rejected, "abgelehntes Token neu anmelden");
		http.status = 200;
		http.fail = true;
		assertEquals(MinigamesOnline.SubmitKind.FAILED, online.submit(run("r")).value().kind);
	}

	@Test
	void leaderboardIsParsed() {
		FakeHttp http = new FakeHttp();
		http.json = "{\"entries\":[{\"rank\":1,\"uuid\":\"u\",\"name\":\"Ada\",\"score\":900,\"at\":\"x\"},"
				+ "{\"rank\":2,\"uuid\":\"v\",\"score\":800}],\"me\":{\"rank\":5,\"score\":100},\"total\":12}";
		MinigamesOnline online = new MinigamesOnline(new Login(), http, DIRECT);
		MinigamesOnline.BoardResult result = online.leaderboard(Replay.STAPLER).value();
		assertEquals(MinigamesOnline.BoardStatus.OK, result.status);
		assertEquals(2, result.board.entries.size());
		assertEquals("Ada", result.board.entries.get(0).name);
		assertEquals(900, result.board.entries.get(0).score);
		assertEquals("?", result.board.entries.get(1).name);
		assertEquals(5, result.board.meRank);
		assertEquals(12, result.board.total);
		assertEquals("GET", http.requests.get(0).method);

		http.json = "{\"entries\":[],\"me\":null,\"total\":0}";
		assertEquals(0, online.leaderboard(Replay.LOREN).value().board.meRank);
	}

	@Test
	void personalBestOnlyGoesUp() {
		MinigameBest best = new MinigameBest();
		assertTrue(best.record(Replay.STAPLER, 10));
		assertFalse(best.record(Replay.STAPLER, 10));
		assertFalse(best.record(Replay.STAPLER, 9));
		assertTrue(best.record(Replay.LOREN, 3));
		assertEquals(10, best.get(Replay.STAPLER));
		assertEquals(3, best.get(Replay.LOREN));
		dev.theredstonee.trsclient.core.config.TrsConfig config = new dev.theredstonee.trsclient.core.config.TrsConfig();
		best.write(config);
		MinigameBest loaded = new MinigameBest();
		loaded.read(config);
		assertEquals(10, loaded.get(Replay.STAPLER));
		assertEquals(3, loaded.get(Replay.LOREN));
		MinigameBest empty = new MinigameBest();
		empty.read(new dev.theredstonee.trsclient.core.config.TrsConfig());
		assertEquals(0, empty.get(Replay.STAPLER));
		dev.theredstonee.trsclient.core.config.TrsConfig clean = new dev.theredstonee.trsclient.core.config.TrsConfig();
		empty.write(clean);
		assertNull(clean.minigames, "ohne Rekord kein Eintrag in der Datei");
	}

	/** Der Name des Vorbilds darf nirgends stehen (Markenrisiko). */
	@Test
	void theModelsNameAppearsNowhere() throws IOException {
		String forbidden = new String(new char[] {'t', 'e', 't', 'r', 'i', 's'});
		for (String lang : I18n.LANGUAGES) {
			for (Map.Entry<String, String> e : I18n.raw(lang).entrySet()) {
				if (!e.getKey().contains("minigame")) continue;
				assertFalse(e.getValue().toLowerCase(Locale.ROOT).contains(forbidden), lang + " " + e.getKey());
			}
			assertNotNull(I18n.raw(lang).get("minigames.stapler.name"), lang);
			assertNotNull(I18n.raw(lang).get("minigames.loren.name"), lang);
		}
		Path dir = Paths.get("src/main/java/dev/theredstonee/trsclient/core/minigames");
		try (Stream<Path> files = Files.list(dir)) {
			files.forEach(file -> {
				try {
					String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
					assertFalse(text.contains(forbidden), file.toString());
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			});
		}
	}

	@Test
	void gameNamesInEnglishAndGerman() {
		assertEquals("Redstone Stacker", I18n.raw("en").get("minigames.stapler.name"));
		assertEquals("Minecart Rush", I18n.raw("en").get("minigames.loren.name"));
		assertEquals("Redstone-Stapler", I18n.raw("de").get("minigames.stapler.name"));
		assertEquals("Loren-Rausch", I18n.raw("de").get("minigames.loren.name"));
	}
}

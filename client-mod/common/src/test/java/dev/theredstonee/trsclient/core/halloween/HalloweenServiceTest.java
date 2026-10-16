package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Halloween im Konto: Zustand, Kürbis-Funde, Süßes oder Saures, Flackern-Standard des Launchers. */
class HalloweenServiceTest {
	private static final String STATE = "{\"active\":true,\"endsAt\":\"2026-11-02T23:00:00Z\","
			+ "\"pumpkins\":{\"total\":13,\"found\":[\"launcher-titlebar\",\"web-home\"],\"nextHint\":{\"de\":\"Wo die Fenster schließen\",\"en\":\"Where windows close\"}},"
			+ "\"trick\":{\"usedToday\":false,\"next\":\"2026-10-31T23:00:00Z\"},\"badges\":[\"halloween2026\"],\"effects\":{\"hits\":true,\"trail\":false}}";

	private final HalloweenService s = HalloweenService.get();

	@BeforeEach
	void setUp() {
		s.resetForTest();
		HalloweenToast.clear();
		Halloween.force(Boolean.TRUE);
	}

	@AfterEach
	void tearDown() {
		s.resetForTest();
		HalloweenToast.clear();
		Halloween.reset();
	}

	@Test
	void stateFromTheEventStreamDrivesVisibilityAndHints() {
		assertFalse(s.pumpkinVisible(Pumpkins.CLIENT_TITLE), "ohne Zustand keine Kürbisse");
		s.fromEvent(STATE);
		assertTrue(s.pumpkinVisible(Pumpkins.CLIENT_TITLE));
		assertFalse(s.pumpkinVisible("web-home"), "gefunden");
		assertEquals(2, s.foundCount());
		assertEquals(13, s.total());
		assertEquals(Arrays.asList("halloween2026"), s.badges());
		assertNotNull(s.nextHint());
		assertEquals(HalloweenService.TrickPhase.IDLE, s.trickPhase());
		s.fromEvent("{kaputt");
		assertEquals(2, s.foundCount(), "kaputtes Ereignis lässt den Zustand stehen");
	}

	@Test
	void findAddsThePumpkinAndShowsAToast() {
		s.fromEvent(STATE);
		HalloweenApi.FindResult r = new HalloweenApi.FindResult();
		r.found = Arrays.asList("launcher-titlebar", "web-home", "client-hud");
		r.count = 3;
		r.complete = Boolean.FALSE;
		s.applyFind("client-hud", r);
		assertFalse(s.pumpkinVisible("client-hud"));
		assertEquals(3, s.foundCount());
		assertTrue(HalloweenToast.any());
	}

	@Test
	void inactiveEventHidesPumpkins() {
		s.fromEvent(STATE.replace("\"active\":true", "\"active\":false"));
		assertFalse(s.featuresActive());
		assertFalse(s.pumpkinVisible(Pumpkins.CLIENT_TITLE));
	}

	@Test
	void trickOutcomeReadsSweetAndSour() {
		HalloweenApi.Reward reward = new HalloweenApi.Reward();
		reward.kind = "emote";
		reward.id = "ghost_float";
		Map<String, String> names = new HashMap<String, String>();
		names.put("en", "Ghost Float");
		names.put("de", "Spuk-Schweben");
		reward.name = names;
		HalloweenService.TrickOutcome sweet = HalloweenService.outcome("sweet", reward);
		assertTrue(sweet.sweet);
		assertEquals("emote", sweet.kind);
		assertEquals("Ghost Float", sweet.name);
		assertFalse(HalloweenService.outcome("sour", null).sweet);
		assertFalse(HalloweenService.outcome("sweet", null).sweet, "Süßes ohne Gewinn zählt als Saures");
	}

	@Test
	void apiCallsAndTrickUsed() throws Exception {
		final Map<String, String[]> routes = new HashMap<String, String[]>();
		routes.put("GET /v1/me/halloween", new String[]{"200", STATE});
		routes.put("POST /v1/me/halloween/pumpkins/client-title", new String[]{"200", "{\"found\":[\"client-title\"],\"count\":1,\"complete\":false}"});
		routes.put("POST /v1/me/halloween/trick", new String[]{"409", "{\"error\":{\"code\":\"trick_used\"},\"next\":\"2026-11-01T23:00:00Z\"}"});
		Http http = new Http() {
			@Override
			public Response send(Request request) throws IOException {
				String path = request.url.replaceFirst("^https?://[^/]+", "");
				String[] r = routes.get(request.method + " " + path);
				if (r == null) throw new IOException("keine Route " + path);
				return new Response(Integer.parseInt(r[0]), new HashMap<String, String>(), r[1].getBytes(StandardCharsets.UTF_8));
			}
		};
		HalloweenApi api = new HalloweenApi(http, "https://example.test");
		HalloweenApi.State st = api.state("t");
		assertEquals(2, st.pumpkins.found.size());
		assertEquals(Boolean.FALSE, st.effects.trail);
		assertEquals(1, api.find("t", "client-title").count.intValue());
		HalloweenApi.TrickUsed used = assertThrows(HalloweenApi.TrickUsed.class, () -> api.trick("t"));
		assertEquals("2026-11-01T23:00:00Z", used.next);
		ApiException unknown = assertThrows(ApiException.class, () -> api.find("t", "nope"));
		assertEquals("pumpkin_unknown", unknown.code());
	}

	@Test
	void namesFallBackToEnglish() {
		Map<String, String> names = new HashMap<String, String>();
		names.put("en", "Moon Howl");
		names.put("de", "Mondheulen");
		assertEquals("Mondheulen", HalloweenApi.name(names, "de", "x"));
		assertEquals("Moon Howl", HalloweenApi.name(names, "pt-BR", "x"));
		assertEquals("x", HalloweenApi.name(null, "de", "x"));
	}

	@Test
	void flickerFollowsTheLauncherUntilChosenInTheClient() {
		HalloweenLocal local = new HalloweenLocal();
		assertTrue(local.flicker(), "Standard an");
		Halloween.flickerFromLauncher(Boolean.FALSE);
		assertFalse(local.flicker(), "Launcher sagt aus");
		local.setFlicker(true);
		assertTrue(local.flicker(), "eigene Wahl gewinnt");
		TrsConfig config = new TrsConfig();
		local.markScareInfoShown();
		local.write(config);
		HalloweenLocal again = new HalloweenLocal();
		again.read(config);
		assertTrue(again.flickerChosen());
		assertTrue(again.scareInfoShown());
		assertFalse(again.flickerHintShown());
		TrsConfig empty = new TrsConfig();
		new HalloweenLocal().write(empty);
		assertNull(empty.halloween, "nichts zu speichern → Teil fehlt");
	}

	@Test
	void pumpkinIdsMatchTheContract() {
		assertEquals(13, Pumpkins.ALL.size());
		assertTrue(Pumpkins.valid("client-modules"));
		assertFalse(Pumpkins.valid("client-other"));
		assertEquals(Pumpkins.CLIENT_TITLE, Pumpkins.ALL.get(9));
	}
}

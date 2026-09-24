package dev.theredstonee.trsclient.core.online;

import dev.theredstonee.trsclient.core.emote.EmoteController;
import dev.theredstonee.trsclient.core.emote.EmotePlayback;
import dev.theredstonee.trsclient.core.emote.EmoteRig;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dev.theredstonee.trsclient.core.online.OnlineTest.CONFIG;
import static dev.theredstonee.trsclient.core.online.OnlineTest.OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Emotes über die TRS API: Endpunkte, Server-Sent Events, Ereignis-Stream und der Ablauf im EmoteController. */
class EmoteOnlineTest {
	static final String OTHER = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

	@TempDir
	Path dir;

	// --- SSE + Ereignisse ---

	@Test
	void sseParserJoinsDataLinesAndSkipsComments() {
		SseParser p = new SseParser();
		assertNull(p.line(": keep-alive"));
		assertNull(p.line("event: emote"));
		assertNull(p.line("data: {\"a\":1,"));
		assertNull(p.line("data:\"b\":2}"));
		assertNull(p.line("id: 7"));
		SseParser.Raw raw = p.line("");
		assertNotNull(raw);
		assertEquals("emote", raw.event);
		assertEquals("{\"a\":1,\n\"b\":2}", raw.data);
		assertNull(p.line(""), "Leerzeile ohne Inhalt = kein Ereignis");
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < SseParser.MAX_DATA + 10; i++) big.append('x');
		p.line("data: " + big);
		assertNull(p.line(""), "übergroß → verworfen");
	}

	@Test
	void playerEventsAreParsedStrictly() {
		PlayerEvent e = PlayerEvent.parse("emote",
				"{\"type\":\"emote\",\"uuid\":\"B0B0B0B0-b0b0-b0b0-b0b0-b0b0b0b0b0b0\",\"emote\":\"winken\",\"durationMs\":2000,\"at\":\"x\"}");
		assertNotNull(e);
		assertEquals("emote", e.type);
		assertEquals(OTHER, e.uuid);
		assertEquals("winken", e.emote);
		assertEquals(2000, e.durationMs);
		assertEquals("hello", PlayerEvent.parse("hello", "{\"type\":\"hello\",\"keepaliveSec\":25,\"watching\":2}").type);
		assertEquals("cape", PlayerEvent.parse("cape", "{\"type\":\"cape\",\"uuid\":\"" + OTHER + "\",\"cape\":null}").type);
		assertEquals("emote", PlayerEvent.parse(null, "{\"type\":\"emote\",\"uuid\":\"" + OTHER + "\",\"emote\":\"tanzen\"}").type,
				"Typ auch aus den Daten");
		assertEquals(0, PlayerEvent.parse("emote", "{\"uuid\":\"" + OTHER + "\",\"emote\":\"tanzen\",\"durationMs\":-5}").durationMs);
		assertNull(PlayerEvent.parse("emote", "{\"uuid\":\"kaputt\",\"emote\":\"winken\"}"));
		assertNull(PlayerEvent.parse("emote", "{\"uuid\":\"" + OTHER + "\",\"emote\":\"../x\"}"));
		assertNull(PlayerEvent.parse("emote", "{kein json"));
		assertNull(PlayerEvent.parse("friend_request", "{\"uuid\":\"" + OTHER + "\"}"), "unbekannte Typen ignorieren");
	}

	@Test
	void linesAreReadWithCrLfAndLimit() throws IOException {
		ByteArrayInputStream in = new ByteArrayInputStream("a\r\nb\n\nc".getBytes(StandardCharsets.UTF_8));
		assertEquals("a", PlayerEventStream.readLimitedLine(in, 100));
		assertEquals("b", PlayerEventStream.readLimitedLine(in, 100));
		assertEquals("", PlayerEventStream.readLimitedLine(in, 100));
		assertEquals("c", PlayerEventStream.readLimitedLine(in, 100));
		assertNull(PlayerEventStream.readLimitedLine(in, 100));
		assertThrows(IOException.class, () -> PlayerEventStream.readLimitedLine(
				new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)), 5));
	}

	// --- Endpunkte ---

	@Test
	void emotesAndPlayUseTheContract() throws Exception {
		FakeHttp http = new FakeHttp()
				.json("GET /v1/me/cosmetics", 200, "{\"equipped\":{\"hat\":null},\"emotes\":[\"winken\",\"tanzen\",\"UNGÜLTIG\",\"neu_2027\",\"winken\"]}")
				.json("POST /v1/emotes/play", 200, "{\"emote\":\"tanzen\",\"durationMs\":6000,\"at\":\"x\"}");
		TrsApi api = new TrsApi(http, CONFIG);
		assertEquals(Arrays.asList("winken", "tanzen", "neu_2027"), api.emotes(OnlineTest.TOKEN));
		assertEquals(6000, api.playEmote(OnlineTest.TOKEN, "tanzen"));
		Http.Request play = http.requests.get(1);
		assertEquals("{\"emote\":\"tanzen\"}", FakeHttp.body(play));
		assertEquals("Bearer " + OnlineTest.TOKEN, play.headers.get("Authorization"));

		FakeHttp limited = new FakeHttp().on("POST /v1/emotes/play", r -> {
			Http.Response res = FakeHttp.response(429, "{\"error\":{\"code\":\"rate_limited\"}}");
			res.headers.put("retry-after", "2");
			return res;
		});
		ApiException e = assertThrows(ApiException.class, () -> new TrsApi(limited, CONFIG).playEmote("t", "winken"));
		assertTrue(e.rateLimited());
		assertEquals(2000, e.retryAfterMs());
		FakeHttp locked = new FakeHttp().json("POST /v1/emotes/play", 403, "{\"error\":{\"code\":\"emote_locked\"}}");
		assertEquals("emote_locked", assertThrows(ApiException.class,
				() -> new TrsApi(locked, CONFIG).playEmote("t", "tanzen")).code());
	}

	// --- Ereignis-Stream (Attrappe statt Netz) ---

	/** Stream-Attrappe: Zeilen kommen aus einer Warteschlange, close() beendet das Lesen. */
	static final class FakeOpener implements PlayerEventStream.Opener {
		final List<String> urls = new CopyOnWriteArrayList<>();
		final List<FakeConnection> connections = new CopyOnWriteArrayList<>();
		volatile int status = 200;

		@Override
		public PlayerEventStream.Connection open(String url, String token) {
			urls.add(url);
			FakeConnection c = new FakeConnection(status);
			connections.add(c);
			return c;
		}

		FakeConnection last() {
			return connections.get(connections.size() - 1);
		}
	}

	static final class FakeConnection implements PlayerEventStream.Connection {
		static final String EOF = "\u0000EOF";
		final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
		final int status;
		volatile boolean closed;

		FakeConnection(int status) {
			this.status = status;
		}

		void send(String event, String data) {
			lines.add("event: " + event);
			lines.add("data: " + data);
			lines.add("");
		}

		@Override
		public int status() {
			return status;
		}

		@Override
		public String header(String name) {
			return null;
		}

		@Override
		public String readLine() throws IOException {
			try {
				String l = lines.poll(5, TimeUnit.SECONDS);
				if (l == null) throw new IOException("Zeitüberschreitung");
				return l.equals(EOF) ? null : l;
			} catch (InterruptedException e) {
				throw new IOException(e);
			}
		}

		@Override
		public void close() {
			closed = true;
			lines.add(EOF);
		}
	}

	static void waitFor(BooleanSupplier done) throws InterruptedException {
		long end = System.currentTimeMillis() + 5000;
		while (!done.getAsBoolean()) {
			if (System.currentTimeMillis() > end) throw new AssertionError("Zeitüberschreitung");
			Thread.sleep(5);
		}
	}

	@Test
	void streamSwapsAfterHelloAndDebouncesChanges() throws Exception {
		FakeOpener opener = new FakeOpener();
		PlayerEventStream s = new PlayerEventStream(opener, "https://trs-launcher.theredstonee.de");
		List<String> watch = new ArrayList<>(Collections.singletonList(OWN));
		s.update(0, "tok", watch);
		waitFor(() -> opener.connections.size() == 1);
		assertEquals("https://trs-launcher.theredstonee.de/v1/events/players?uuids=" + OWN, opener.urls.get(0));
		opener.last().send("hello", "{\"type\":\"hello\",\"keepaliveSec\":25,\"watching\":1}");
		waitFor(() -> {
			s.update(10, "tok", watch);
			return s.connected();
		});
		opener.last().send("emote", "{\"type\":\"emote\",\"uuid\":\"" + OWN + "\",\"emote\":\"winken\",\"durationMs\":2000}");
		List<PlayerEvent> got = new ArrayList<>();
		waitFor(() -> {
			s.drain(got);
			return !got.isEmpty();
		});
		assertEquals("winken", got.get(0).emote);

		// Neuer Spieler: erst nach der Entprellzeit ein neuer Stream, der alte bleibt bis zum hello.
		watch.add(OTHER);
		s.update(1_000, "tok", watch);
		assertEquals(1, s.connects());
		s.update(PlayerEventStream.DEBOUNCE_MS + 20, "tok", watch);
		assertEquals(2, s.connects());
		waitFor(() -> opener.connections.size() == 2);
		FakeConnection first = opener.connections.get(0);
		assertFalse(first.closed, "alter Stream offen, bis der neue steht");
		assertTrue(opener.urls.get(1).endsWith(OWN + "," + OTHER));
		opener.last().send("hello", "{\"type\":\"hello\"}");
		waitFor(() -> {
			s.update(PlayerEventStream.DEBOUNCE_MS + 30, "tok", watch);
			return first.closed;
		});
		assertTrue(s.watching().contains(OTHER));
		// Weniger Spieler → kein neuer Stream.
		s.update(PlayerEventStream.DEBOUNCE_MS * 3, "tok", Collections.singletonList(OWN));
		assertEquals(2, s.connects());
		s.stop();
		assertFalse(s.connected());
		assertTrue(opener.last().closed);
	}

	@Test
	void streamBacksOffAndReportsUnauthorized() throws Exception {
		FakeOpener opener = new FakeOpener();
		opener.status = 401;
		PlayerEventStream s = new PlayerEventStream(opener, "https://trs-launcher.theredstonee.de");
		List<String> watch = Collections.singletonList(OWN);
		s.update(0, "tok", watch);
		waitFor(() -> {
			s.update(1, "tok", watch);
			return s.takeUnauthorized();
		});
		opener.status = 500;
		s.update(2_000, "tok", watch);
		int before = s.connects();
		waitFor(() -> opener.connections.size() == before);
		// Fehlschlag → Wartezeit; innerhalb der Wartezeit kein neuer Versuch.
		long t = 2_001;
		for (int i = 0; i < 20 && s.connects() == before; i++) {
			s.update(t, "tok", watch);
			Thread.sleep(5);
		}
		int afterFail = s.connects();
		s.update(t + 100, "tok", watch);
		assertEquals(afterFail, s.connects(), "Backoff wird eingehalten");
		s.update(0, null, watch);
		assertFalse(s.connected());
	}

	// --- EmoteController über TrsOnline (echte Hintergrund-Threads, Attrappen als Netz) ---

	@Test
	void controllerPlaysOnlyUnlockedEmotesWithCooldownAndReceivesOthers() throws Exception {
		List<String> played = new CopyOnWriteArrayList<>();
		FakeHttp http = OnlineTest.loginRoutes()
				.json("POST /v1/players/lookup", 200, "{\"players\":[{\"uuid\":\"" + OTHER + "\",\"badge\":true}]}")
				.json("POST /v1/presence", 200, "{}")
				.json("GET /v1/me", 200, "{}")
				.json("GET /v1/me/cosmetics", 200, "{\"emotes\":[\"winken\",\"klatschen\"]}")
				.on("POST /v1/emotes/play", r -> {
					played.add(FakeHttp.body(r));
					return FakeHttp.response(200, "{\"emote\":\"winken\",\"durationMs\":2000}");
				});
		FakeOpener opener = new FakeOpener();
		TrsOnline online = new TrsOnline(CONFIG, new OnlineTest.Platform(), http, dir, opener);
		TrsModules modules = new TrsModules();
		EmoteController emotes = new EmoteController(modules, online);
		UUID self = Uuids.toUuid(OWN);
		UUID other = Uuids.toUuid(OTHER);
		List<UUID> visible = Arrays.asList(self, other);
		List<EmotePlayback.Mover> movers = Arrays.asList(new EmotePlayback.Mover(self, 0, 0, false, true),
				new EmotePlayback.Mover(other, 3, 3, false, false));
		int[] camera = {EmoteController.Camera.FIRST_PERSON};
		EmoteController.Camera cam = new EmoteController.Camera() {
			@Override
			public int get() {
				return camera[0];
			}

			@Override
			public void set(int mode) {
				camera[0] = mode;
			}
		};

		online.tick(System.currentTimeMillis(), visible, true);
		assertEquals(EmoteController.State.CONNECTING, emotes.state());
		waitFor(() -> {
			long now = System.currentTimeMillis();
			online.tick(now, visible, true);
			emotes.tick(now, movers, cam);
			return emotes.state() == EmoteController.State.READY && opener.connections.size() > 0;
		});
		assertTrue(emotes.unlocked("winken"));
		assertFalse(emotes.unlocked("tanzen"));

		long now = System.currentTimeMillis();
		assertEquals(EmoteController.Result.LOCKED, emotes.play("tanzen", now));
		assertEquals(EmoteController.Result.UNAVAILABLE, emotes.play("gibt_es_nicht", now));
		assertEquals(EmoteController.Result.PLAYED, emotes.play("winken", now));
		assertEquals(EmoteController.Result.COOLDOWN, emotes.play("klatschen", now + 500), "höchstens 1 / 2 s");
		assertTrue(emotes.cooldownLeft(now + 500) > 1000);
		assertTrue(emotes.selfPlaying(now + 100), "startet sofort lokal");
		waitFor(() -> {
			online.tick(System.currentTimeMillis(), visible, true);
			return !played.isEmpty();
		});
		assertEquals("{\"emote\":\"winken\"}", played.get(0));

		// Kamera: eigenes Emote → 3. Person von vorn, danach zurück.
		emotes.tick(now + 200, movers, cam);
		assertEquals(EmoteController.Camera.FRONT, camera[0]);
		float[] parts = new float[EmoteRig.PARTS * EmoteRig.STRIDE];
		assertTrue(emotes.apply(self, now + 1000, parts));
		assertFalse(emotes.apply(other, now + 1000, parts));
		emotes.onAttack(now + 1100);
		emotes.tick(now + 1150, movers, cam);
		assertEquals(EmoteController.Camera.FIRST_PERSON, camera[0], "Angriff beendet das Emote, Kamera zurück");

		// Ereignisse: Echo des eigenen Emotes wird ignoriert, das eines anderen Spielers startet dort.
		FakeConnection c = opener.last();
		c.send("hello", "{\"type\":\"hello\"}");
		c.send("emote", "{\"type\":\"emote\",\"uuid\":\"" + OWN + "\",\"emote\":\"winken\",\"durationMs\":2000}");
		c.send("emote", "{\"type\":\"emote\",\"uuid\":\"" + OTHER + "\",\"emote\":\"tanzen\",\"durationMs\":6000}");
		c.send("emote", "{\"type\":\"emote\",\"uuid\":\"" + OTHER + "\",\"emote\":\"zukunft\",\"durationMs\":6000}");
		waitFor(() -> {
			long t = System.currentTimeMillis();
			online.tick(t, visible, true);
			emotes.tick(t, movers, cam);
			return emotes.animating(other);
		});
		assertFalse(emotes.selfPlaying(System.currentTimeMillis()), "Echo startet das abgebrochene Emote nicht neu");
		assertTrue(opener.urls.get(0).contains(OWN), "eigene UUID wird beobachtet");

		// Andere ausblenden → deren Emotes verschwinden, der Stream wird geschlossen.
		modules.emoteOthers.set(false);
		long t = System.currentTimeMillis();
		online.tick(t, visible, true);
		emotes.tick(t, movers, cam);
		online.tick(t + 50, visible, true);
		assertFalse(emotes.animating(other));
		assertFalse(online.eventsConnected());
	}

	@Test
	void withoutConsentOrModuleTheWheelOnlyShowsAHint() throws Exception {
		FakeHttp http = OnlineTest.loginRoutes();
		TrsOnline off = new TrsOnline(new OnlineConfig(false, CONFIG.apiBase(), CONFIG.sessionBase()),
				new OnlineTest.Platform(), http, dir, new FakeOpener());
		TrsModules modules = new TrsModules();
		EmoteController emotes = new EmoteController(modules, off);
		for (int i = 0; i < 10; i++) {
			off.tick(System.currentTimeMillis(), Collections.<UUID>emptyList(), true);
			emotes.tick(System.currentTimeMillis(), Collections.<EmotePlayback.Mover>emptyList(), null);
		}
		assertEquals(EmoteController.State.OFF_LAUNCHER, emotes.state());
		assertEquals(EmoteController.Result.UNAVAILABLE, emotes.play("winken", System.currentTimeMillis()));
		assertTrue(http.requests.isEmpty(), "ohne Einwilligung keine einzige Anfrage");

		TrsOnline on = new TrsOnline(CONFIG, new OnlineTest.Platform(), OnlineTest.loginRoutes(), dir, new FakeOpener());
		modules.trsOnline.setEnabled(false);
		EmoteController disabled = new EmoteController(modules, on);
		assertEquals(EmoteController.State.OFF_MODULE, disabled.state());
	}
}

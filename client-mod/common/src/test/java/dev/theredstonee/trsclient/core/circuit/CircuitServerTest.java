package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.theredstonee.trsclient.core.online.Http;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bibliothek vom Server (Attrappe der TRS API im Test): Index mit ETag/304, nur geänderte Schaltungen laden,
 * entfernte löschen, strenge Prüfung, Cache; Bereich auslesen → Format; Einreichen + „Meine Einreichungen“.
 */
class CircuitServerTest {
	private static final CircuitLibrary SOURCE = CircuitLibrary.load(true);

	private HttpServer server;
	private String base;
	/** id → rev, Reihenfolge = Index. */
	private final Map<String, String> index = new LinkedHashMap<String, String>();
	/** id → JSON (Antwort je rev). */
	private final Map<String, String> bodies = new LinkedHashMap<String, String>();
	private String indexVersion = "v1";
	private final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
	private volatile String lastIfNoneMatch;
	private volatile String lastSubmission;
	private volatile int submitStatus = 201;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", new HttpHandler() {
			@Override
			public void handle(HttpExchange ex) throws IOException {
				handleRequest(ex);
			}
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private void handleRequest(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		String query = ex.getRequestURI().getRawQuery();
		requests.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		if (path.equals("/v1/circuits/index")) {
			lastIfNoneMatch = ex.getRequestHeaders().getFirst("If-None-Match");
			String etag = "\"" + indexVersion + "\"";
			if (etag.equals(lastIfNoneMatch)) {
				send(ex, 304, "", etag);
				return;
			}
			JsonObject o = new JsonObject();
			o.addProperty("version", indexVersion);
			JsonArray arr = new JsonArray();
			for (Map.Entry<String, String> e : index.entrySet()) {
				JsonObject en = new JsonObject();
				en.addProperty("id", e.getKey());
				en.addProperty("rev", e.getValue());
				en.addProperty("updatedAt", "2026-09-27T12:00:00Z");
				if (e.getKey().equals("not_gate")) en.addProperty("minVersion", "1.12");
				arr.add(en);
			}
			o.add("circuits", arr);
			send(ex, 200, o.toString(), etag);
			return;
		}
		if (path.startsWith("/v1/circuits/") && ex.getRequestMethod().equals("GET")) {
			String id = path.substring("/v1/circuits/".length());
			String body = bodies.get(id);
			if (body == null) {
				send(ex, 404, "{\"error\":{\"code\":\"not_found\"}}", null);
				return;
			}
			send(ex, 200, body, null);
			return;
		}
		if (path.equals("/v1/circuits/submissions") && ex.getRequestMethod().equals("POST")) {
			if (!"Bearer good".equals(auth)) {
				send(ex, 401, "{\"error\":{\"code\":\"unauthorized\"}}", null);
				return;
			}
			lastSubmission = read(ex.getRequestBody());
			if (submitStatus == 201) send(ex, 201, "{\"id\":\"sub_1\",\"status\":\"pending\"}", null);
			else if (submitStatus == 409) send(ex, 409, "{\"error\":{\"code\":\"circuit_duplicate\"}}", null);
			else if (submitStatus == 429) send(ex, 429, "{\"error\":{\"code\":\"rate_limited\"}}", null);
			else send(ex, submitStatus, "{\"error\":{\"code\":\"sanctioned\"}}", null);
			return;
		}
		if (path.equals("/v1/me/circuit-submissions")) {
			if (!"Bearer good".equals(auth)) {
				send(ex, 401, "{\"error\":{\"code\":\"unauthorized\"}}", null);
				return;
			}
			send(ex, 200, "{\"submissions\":[{\"id\":\"sub_1\",\"name\":\"Meine Uhr\",\"status\":\"pending\"},"
					+ "{\"id\":\"sub_0\",\"name\":\"Alt\",\"status\":\"rejected\",\"reason\":\"Gibt es schon\"}]}", null);
			return;
		}
		send(ex, 404, "{}", null);
	}

	private static String read(InputStream in) throws IOException {
		byte[] buf = new byte[65536];
		int n, total = 0;
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		while ((n = in.read(buf)) > 0) {
			out.write(buf, 0, n);
			total += n;
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	private static void send(HttpExchange ex, int status, String body, String etag) throws IOException {
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		if (etag != null) ex.getResponseHeaders().add("ETag", etag);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		if (status == 304) {
			ex.sendResponseHeaders(304, -1);
		} else {
			ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
			if (b.length > 0) {
				OutputStream out = ex.getResponseBody();
				out.write(b);
				out.close();
			}
		}
		ex.close();
	}

	private static String sourceJson(String id) throws IOException {
		try (InputStream in = CircuitServerTest.class.getResourceAsStream("/circuits/" + id + ".json")) {
			return read(in);
		}
	}

	private void offer(String id, String rev) throws IOException {
		index.put(id, rev);
		bodies.put(id, sourceJson(id));
	}

	private static Http http() {
		return new Http.UrlConnection("TRS-Client-Test");
	}

	private int count(String prefix) {
		int n = 0;
		synchronized (requests) {
			for (String r : requests) if (r.startsWith(prefix)) n++;
		}
		return n;
	}

	@Test
	void syncUsesEtagDeltaAndRemoval(@TempDir Path dir) throws IOException {
		CircuitCache cache = new CircuitCache(dir.resolve("circuits"));
		BlockCatalog cat = CircuitLibrary.blockCatalog();
		offer("not_gate", "r1");
		offer("xor_gate", "r1");
		offer("rs_latch", "r1");

		// 1. Start: alles laden
		CircuitSync.Result r1 = CircuitSync.run(http(), base, cache, cat);
		assertEquals(CircuitSync.Status.UPDATED, r1.status);
		assertEquals(3, r1.fetched);
		assertNull(lastIfNoneMatch);
		CircuitLibrary lib = cache.load(cat);
		assertEquals(3, lib.all().size());
		assertEquals("not_gate", lib.all().get(0).id);
		assertEquals("1.12", lib.byId("not_gate").since); // minVersion aus dem Index
		assertFalse(lib.byId("not_gate").runsIn("1.8.9"));
		assertTrue(lib.byId("xor_gate").simulated());

		// 2. Start: nichts geändert → 304, keine Schaltung geladen
		requests.clear();
		CircuitSync.Result r2 = CircuitSync.run(http(), base, cache, cat);
		assertEquals(CircuitSync.Status.UP_TO_DATE, r2.status);
		assertEquals("\"v1\"", lastIfNoneMatch);
		assertEquals(0, count("GET /v1/circuits/not_gate"));
		assertEquals(1, requests.size());

		// 3. Start: xor neue rev, rs_latch entfernt, monoflop neu → nur zwei Schaltungen laden
		indexVersion = "v2";
		index.put("xor_gate", "r2");
		index.remove("rs_latch");
		offer("monoflop", "r1");
		requests.clear();
		CircuitSync.Result r3 = CircuitSync.run(http(), base, cache, cat);
		assertEquals(CircuitSync.Status.UPDATED, r3.status);
		assertEquals(2, r3.fetched);
		assertEquals(1, r3.removed);
		assertEquals(0, count("GET /v1/circuits/not_gate"));
		assertEquals(1, count("GET /v1/circuits/xor_gate?rev=r2"));
		assertFalse(Files.exists(dir.resolve("circuits").resolve("rs_latch.json")));
		assertEquals(3, cache.load(cat).all().size());

		// 4. Offline: Cache bleibt nutzbar
		server.stop(0);
		CircuitSync.Result r4 = CircuitSync.run(http(), base, cache, cat);
		assertEquals(CircuitSync.Status.OFFLINE, r4.status);
		assertEquals(3, cache.load(cat).all().size());
	}

	@Test
	void invalidCircuitsAreNeverCached(@TempDir Path dir) throws IOException {
		CircuitCache cache = new CircuitCache(dir.resolve("circuits"));
		BlockCatalog cat = CircuitLibrary.blockCatalog();
		offer("not_gate", "r1");
		// zu groß (17 breit), unbekannter Block, NBT-artige Eigenschaft, falsche id
		index.put("too_big", "r1");
		bodies.put("too_big", "{\"id\":\"too_big\",\"category\":\"basics\",\"palette\":{\"#\":\"solid\"},\"layers\":[[\"#################\"]]}");
		index.put("bad_block", "r1");
		bodies.put("bad_block", "{\"id\":\"bad_block\",\"category\":\"basics\",\"palette\":{\"#\":\"tnt\"},\"layers\":[[\"#\"]]}");
		index.put("bad_prop", "r1");
		bodies.put("bad_prop", "{\"id\":\"bad_prop\",\"category\":\"basics\",\"palette\":{\"#\":\"chest[items=64]\"},\"layers\":[[\"#\"]]}");
		index.put("other_id", "r1");
		bodies.put("other_id", sourceJson("or_gate"));
		index.put("../evil", "r1");
		CircuitSync.Result r = CircuitSync.run(http(), base, cache, cat);
		assertEquals(CircuitSync.Status.UPDATED, r.status);
		assertEquals(1, r.fetched);
		assertEquals(5, r.invalid);
		assertEquals(1, cache.load(cat).all().size());
		assertFalse(Files.exists(dir.resolve("circuits").resolve("too_big.json")));
		// Index ohne ETag, damit der nächste Start erneut prüft
		assertNull(cache.readIndex().etag);
	}

	@Test
	void emptyWithoutCache(@TempDir Path dir) {
		CircuitCache cache = new CircuitCache(dir.resolve("nothing"));
		CircuitLibrary lib = cache.load(CircuitLibrary.blockCatalog());
		assertTrue(lib.isEmpty());
		assertFalse(lib.loaded());
	}

	@Test
	void captureTurnsTheWorldIntoTheFormat() {
		for (boolean legacy : new boolean[] {false, true}) {
			for (String id : new String[] {"xor_gate", "piston_door_2x2", "t_flipflop", "d_latch"}) {
				Circuit c = SOURCE.byId(id);
				Placement p = new Placement(100, 64, -30, 0, false);
				CircuitLibraryTest.FakeWorld w = new CircuitLibraryTest.FakeWorld(legacy);
				CircuitLibraryTest.build(w, c, p);
				// etwas Unbeteiligtes daneben stört nicht, wenn es außerhalb liegt
				w.set(90, 64, -30, "minecraft:dandelion", null, false);
				CircuitCapture.Result r = CircuitCapture.capture(w, CircuitLibrary.blockCatalog(), 100, 64, -30, 100 + c.sizeX - 1,
						64 + c.sizeY - 1, -30 + c.sizeZ - 1, "mein_test");
				assertNull(r.error, id + " " + r.error + " " + r.unsupported);
				assertNotNull(r.parsed);
				assertEquals(c.blockCount(), r.parsed.blockCount(), id);
				// Die ausgelesene Schaltung passt wieder genau auf die Welt
				CircuitCheck check = new CircuitCheck(r.parsed, new Placement(100 + minX(c), 64 + minY(c), -30 + minZ(c), 0, false));
				check.run(w, CircuitLibrary.blockCatalog());
				assertTrue(check.complete(), id + " legacy=" + legacy + ": " + check.correct() + "/" + check.total());
			}
		}
	}

	private static int minX(Circuit c) {
		int m = 99;
		for (Circuit.Cell cell : c.cells) if (!cell.spec.optional) m = Math.min(m, cell.x);
		return m;
	}

	private static int minY(Circuit c) {
		int m = 99;
		for (Circuit.Cell cell : c.cells) if (!cell.spec.optional) m = Math.min(m, cell.y);
		return m;
	}

	private static int minZ(Circuit c) {
		int m = 99;
		for (Circuit.Cell cell : c.cells) if (!cell.spec.optional) m = Math.min(m, cell.z);
		return m;
	}

	@Test
	void captureRejectsBadAreas() {
		CircuitLibraryTest.FakeWorld w = new CircuitLibraryTest.FakeWorld(false);
		assertEquals("too_big", CircuitCapture.capture(w, CircuitLibrary.blockCatalog(), 0, 0, 0, 16, 0, 0, "x").error);
		assertEquals("empty", CircuitCapture.capture(w, CircuitLibrary.blockCatalog(), 0, 0, 0, 3, 3, 3, "x").error);
		w.set(1, 1, 1, "minecraft:poppy", null, false);
		w.set(2, 1, 1, "minecraft:oak_planks", null, true);
		CircuitCapture.Result r = CircuitCapture.capture(w, CircuitLibrary.blockCatalog(), 0, 0, 0, 3, 3, 3, "x");
		assertEquals("unsupported", r.error);
		assertEquals(Collections.singletonList("poppy"), r.unsupported);
		assertEquals("mein_xor_gatter_2", CircuitCapture.slug("Mein XOR-Gatter #2"));
		assertEquals("uebergroesse", CircuitCapture.slug("Übergröße"));
	}

	@Test
	void submitAndListOwnSubmissions() {
		CircuitSubmissions api = new CircuitSubmissions(http(), base);
		final String[] token = {"good"};
		final List<String> rejected = new ArrayList<String>();
		api.tokens(new CircuitSubmissions.Tokens() {
			@Override
			public String token() {
				return token[0];
			}

			@Override
			public void rejected(String t) {
				rejected.add(t);
			}
		});
		JsonObject circuit = new JsonParser().parse(sourceJsonUnchecked("not_gate")).getAsJsonObject();
		CircuitSubmissions.Result ok = api.submit(circuit, "Mein Inverter", "basics", "Kurz erklärt", "de");
		assertTrue(ok.ok);
		assertEquals("sub_1", ok.id);
		assertEquals("pending", ok.status);
		JsonObject sent = new JsonParser().parse(lastSubmission).getAsJsonObject();
		assertEquals("Mein Inverter", sent.get("name").getAsString());
		assertEquals("basics", sent.get("category").getAsString());
		assertEquals("de", sent.get("lang").getAsString());
		assertEquals("not_gate", sent.getAsJsonObject("circuit").get("id").getAsString());

		submitStatus = 409;
		assertEquals("circuit_duplicate", api.submit(circuit, "x", "basics", "", "en").error);
		submitStatus = 429;
		assertEquals("rate_limited", api.submit(circuit, "x", "basics", "", "en").error);
		submitStatus = 403;
		assertEquals("sanctioned", api.submit(circuit, "x", "basics", "", "en").error);

		List<CircuitSubmissions.Submission> mine = api.loadMine();
		assertEquals(2, mine.size());
		assertEquals("rejected", mine.get(1).status);
		assertEquals("Gibt es schon", mine.get(1).reason);

		token[0] = "bad";
		assertEquals("unauthorized", api.submit(circuit, "x", "basics", "", "en").error);
		assertEquals(Collections.singletonList("bad"), rejected);
		token[0] = null;
		assertFalse(api.loggedIn());
		assertEquals("unauthorized", api.submit(circuit, "x", "basics", "", "en").error);
	}

	private static String sourceJsonUnchecked(String id) {
		try {
			return sourceJson(id);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}

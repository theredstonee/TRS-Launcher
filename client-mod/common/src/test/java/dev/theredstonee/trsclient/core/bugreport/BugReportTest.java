package dev.theredstonee.trsclient.core.bugreport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.ui.bugreport.BugReportPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** „Bug melden“: Anfrage-Körper, Antworten, Link-Prüfung, Sammeln der Anhänge und Senden gegen eine Attrappe. */
class BugReportTest {
	private static final String UPLOAD_ID = "AbCdEfGhIjKlMnOpQrStUv";

	@AfterEach
	void english() {
		I18n.use("en");
	}

	// --- Grenzen ---

	@Test
	void titleAndDescriptionLimits() {
		assertEquals("bugreport.error.titleShort", BugReportBody.titleError("  abcd "));
		assertNull(BugReportBody.titleError("abcde"));
		assertNull(BugReportBody.titleError(repeat('a', 120)));
		assertEquals("bugreport.error.titleLong", BugReportBody.titleError(repeat('a', 121)));
		assertEquals("bugreport.error.titleShort", BugReportBody.titleError(null));
		assertEquals("bugreport.error.descriptionShort", BugReportBody.descriptionError("too short"));
		assertNull(BugReportBody.descriptionError("long enough"));
		assertNull(BugReportBody.descriptionError(repeat('b', 8000)));
		assertEquals("bugreport.error.descriptionLong", BugReportBody.descriptionError(repeat('b', 8001)));
	}

	// --- Anfrage ---

	@Test
	void bodyFollowsTheContract() {
		BugReportBody.Meta meta = new BugReportBody.Meta();
		meta.modVersion = "0.13.0";
		meta.mcVersion = "1.21.1";
		meta.loader = "fabric";
		meta.mods = Arrays.asList("sodium-fabric-0.6.jar", "trsclient-fabric-1.21.1.jar");
		meta.log = "line 1\nline 2";
		JsonObject o = BugReportBody.build("  Minimap flickers  ", " In the Nether it flickers. ",
				Arrays.asList(UPLOAD_ID, "bad id", null), meta);
		assertEquals("bug", o.get("type").getAsString());
		assertEquals("client", o.get("area").getAsString());
		assertEquals("Minimap flickers", o.get("title").getAsString());
		assertEquals("In the Nether it flickers.", o.get("description").getAsString());
		assertEquals(1, o.getAsJsonArray("attachments").size());
		assertEquals(UPLOAD_ID, o.getAsJsonArray("attachments").get(0).getAsString());
		JsonObject m = o.getAsJsonObject("meta");
		assertEquals("0.13.0", m.get("modVersion").getAsString());
		assertEquals("1.21.1", m.get("mcVersion").getAsString());
		assertEquals("fabric", m.get("loader").getAsString());
		assertEquals(2, m.getAsJsonArray("mods").size());
		assertEquals("line 1\nline 2", m.get("log").getAsString());
	}

	@Test
	void bodyOnlyContainsTickedParts() {
		JsonObject o = BugReportBody.build("Title!", "Description here", null, new BugReportBody.Meta());
		assertEquals(0, o.getAsJsonArray("attachments").size());
		assertEquals(0, o.getAsJsonObject("meta").size());
		JsonObject o2 = BugReportBody.build("Title!", "Description here", null, null);
		assertEquals(0, o2.getAsJsonObject("meta").size());
	}

	@Test
	void bodyClampsEverythingToTheServerLimits() {
		BugReportBody.Meta meta = new BugReportBody.Meta();
		meta.modVersion = "0.13.0-beta+build.1234567890123456789";
		meta.mcVersion = "1.21.1<script>";
		meta.loader = "fabric";
		List<String> mods = new ArrayList<String>();
		for (int i = 0; i < 400; i++) mods.add("mod-" + i + "-" + repeat('x', 120) + ".jar");
		meta.mods = mods;
		StringBuilder log = new StringBuilder();
		for (int i = 0; i < 3000; i++) log.append("log line number ").append(i).append('\n');
		meta.log = log.toString();
		List<String> ids = new ArrayList<String>();
		for (int i = 0; i < 9; i++) ids.add(UPLOAD_ID);
		JsonObject o = BugReportBody.build(repeat('t', 300), repeat('d', 9000), ids, meta);
		assertEquals(120, o.get("title").getAsString().length());
		assertEquals(8000, o.get("description").getAsString().length());
		assertEquals(6, o.getAsJsonArray("attachments").size());
		JsonObject m = o.getAsJsonObject("meta");
		assertTrue(m.get("modVersion").getAsString().length() <= 32, m.get("modVersion").getAsString());
		assertEquals("1.21.1script", m.get("mcVersion").getAsString());
		assertEquals(300, m.getAsJsonArray("mods").size());
		for (int i = 0; i < 300; i++) assertTrue(m.getAsJsonArray("mods").get(i).getAsString().length() <= 100);
		String sentLog = m.get("log").getAsString();
		assertTrue(sentLog.length() <= 20000, "log " + sentLog.length());
		assertTrue(sentLog.endsWith("log line number 2999"), "Ende bleibt");
	}

	@Test
	void clipNeverSplitsEmoji() {
		String s = repeat('a', 119) + "\uD83D\uDE00";
		String t = BugReportBody.clip(s, 120);
		assertEquals(119, t.length());
	}

	@Test
	void metaStringsAreHarmless() {
		assertEquals("1.21.1", BugReportBody.metaString(" 1.21.1 "));
		assertEquals("26.3-pre1", BugReportBody.metaString("26.3-pre1"));
		assertNull(BugReportBody.metaString("<<>>"));
		assertNull(BugReportBody.metaString(null));
	}

	@Test
	void modsAreSortedDedupedAndClipped() {
		List<String> out = BugReportBody.cleanMods(Arrays.asList("b.jar", "A.jar", "a.jar", " ", null, repeat('z', 150) + ".jar"));
		assertEquals(3, out.size());
		assertEquals("A.jar", out.get(0));
		assertEquals("b.jar", out.get(1));
		assertEquals(100, out.get(2).length());
	}

	// --- Antworten ---

	@Test
	void createdResponseIsParsedAndLinkChecked() {
		BugReportBody.Created c = BugReportBody.created(
				"{\"issue\":{\"number\":123,\"url\":\"https://trs-launcher.theredstonee.de/issues/123\",\"title\":\"x\"}}");
		assertNotNull(c);
		assertEquals(123, c.number);
		assertEquals("https://trs-launcher.theredstonee.de/issues/123", c.url);
		assertNull(BugReportBody.created("{\"issue\":{\"number\":5,\"url\":\"https://evil.example/issues/5\"}}").url);
		assertEquals(5, BugReportBody.created("{\"issue\":{\"number\":5,\"url\":\"https://evil.example/issues/5\"}}").number);
		assertNull(BugReportBody.created("{\"issue\":{\"number\":0}}"));
		assertNull(BugReportBody.created("{\"issue\":{\"number\":\"12\"}}"));
		assertNull(BugReportBody.created("{\"number\":12}"));
		assertNull(BugReportBody.created("<html>Bad Gateway</html>"));
		assertNull(BugReportBody.created("{\"issue\":{\"number\":7,\"url\":null}}").url);
	}

	@Test
	void onlyTrsWebsiteLinksMayBeOpened() {
		assertTrue(BugReportBody.siteLink("https://trs-launcher.theredstonee.de/issues/123"));
		assertTrue(BugReportBody.siteLink("https://trs-launcher.theredstonee.de/de/issues/123?x=1#c"));
		assertFalse(BugReportBody.siteLink("http://trs-launcher.theredstonee.de/issues/123"));
		assertFalse(BugReportBody.siteLink("https://trs-launcher.theredstonee.de.evil.com/issues/1"));
		assertFalse(BugReportBody.siteLink("https://trs-launcher.theredstonee.de@evil.com/"));
		assertFalse(BugReportBody.siteLink("https://trs-launcher.theredstonee.de/@evil.com"));
		assertFalse(BugReportBody.siteLink("https://trs-launcher.theredstonee.de//evil.com"));
		assertFalse(BugReportBody.siteLink("https://trs-launcher.theredstonee.de/issues/1 x"));
		assertFalse(BugReportBody.siteLink("https://trs-launcher.theredstonee.de/../x"));
		assertFalse(BugReportBody.siteLink("javascript:alert(1)"));
		assertFalse(BugReportBody.siteLink(null));
	}

	@Test
	void uploadIdIsValidated() {
		assertEquals(UPLOAD_ID, BugReportBody.uploadId("{\"upload\":{\"id\":\"" + UPLOAD_ID + "\"}}"));
		assertNull(BugReportBody.uploadId("{\"upload\":{\"id\":\"../../etc\"}}"));
		assertNull(BugReportBody.uploadId("{\"upload\":{}}"));
		assertNull(BugReportBody.uploadId("nope"));
	}

	// --- Sammeln ---

	@Test
	void collectsModsLogCrashAndScreenshotsAlreadyCleaned(@TempDir Path game) throws Exception {
		Path mods = Files.createDirectories(game.resolve("mods"));
		Files.write(mods.resolve("sodium.jar"), new byte[]{1});
		Files.write(mods.resolve("Alpha.JAR"), new byte[]{1});
		Files.write(mods.resolve("old.jar.disabled"), new byte[]{1});
		Files.write(mods.resolve("readme.txt"), new byte[]{1});
		Files.write(Files.createDirectories(mods.resolve("1.8.9")).resolve("optifine.jar"), new byte[]{1});
		Files.createDirectories(mods.resolve("config-stuff")).resolve("x.jar");
		Path logs = Files.createDirectories(game.resolve("logs"));
		StringBuilder log = new StringBuilder("[09:00:00] [main/INFO]: Setting user: SecretSteve\n");
		for (int i = 0; i < 400; i++) log.append("[09:00:01] [main/INFO]: line ").append(i).append('\n');
		log.append("[09:10:00] [main/INFO]: SecretSteve opened C:\\Users\\Max\\x with --accessToken abc123def456\n");
		Files.write(logs.resolve("latest.log"), log.toString().getBytes(StandardCharsets.UTF_8));
		Path crashes = Files.createDirectories(game.resolve("crash-reports"));
		Files.write(crashes.resolve("crash-2026-09-28_10.00.00-client.txt"),
				"---- Minecraft Crash Report ----\nDescription: Rendering overlay\nplayer SecretSteve at 10.0.0.5\n"
						.getBytes(StandardCharsets.UTF_8));
		Path old = crashes.resolve("crash-2020-01-01_10.00.00-client.txt");
		Files.write(old, "old".getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 30L * 24 * 3600 * 1000));
		Path shots = Files.createDirectories(game.resolve("screenshots"));
		Path a = shots.resolve("2026-09-27_10.00.00.png");
		Path b = shots.resolve("2026-09-28_10.00.00.png");
		Files.write(a, png());
		Files.write(b, png());
		Files.write(shots.resolve("evil;rm.png"), png());
		Files.setLastModifiedTime(a, FileTime.fromMillis(1_000_000_000_000L));
		Files.setLastModifiedTime(b, FileTime.fromMillis(1_100_000_000_000L));

		BugReports.Data d = BugReports.collect(game, Collections.<String>emptyList(), null, null);
		assertEquals(Arrays.asList("1.8.9/optifine.jar", "Alpha.JAR", "sodium.jar"), d.mods);
		assertEquals(3, d.modsTotal);
		assertNotNull(d.log);
		assertEquals(BugReports.LOG_LINES, d.logLines);
		assertFalse(d.log.contains("SecretSteve"), "Name aus „Setting user“ am Anfang des Logs");
		assertFalse(d.log.contains("abc123def456"));
		assertFalse(d.log.contains("\\Max\\"));
		assertTrue(d.log.endsWith("<player> opened C:\\Users\\<user>\\x with --accessToken <token>"), d.log);
		assertTrue(d.logScrub.total() >= 3);
		assertNotNull(d.crash);
		assertEquals("crash-2026-09-28_10.00.00-client.txt", d.crashName);
		assertTrue(d.crash.startsWith("---- Minecraft Crash Report ----"), d.crash);
		assertFalse(d.crash.contains("10.0.0.5"));
		assertEquals(2, d.screenshots.size());
		assertEquals(b, d.screenshots.get(0).path);
	}

	@Test
	void collectWithoutGameFolderIsEmpty(@TempDir Path game) {
		BugReports.Data d = BugReports.collect(game, Collections.<String>emptyList(), null, null);
		assertTrue(d.mods.isEmpty());
		assertNull(d.log);
		assertNull(d.crash);
		assertTrue(d.screenshots.isEmpty());
		assertTrue(BugReports.collect(null, Collections.<String>emptyList(), null, null).mods.isEmpty());
	}

	@Test
	void readTailDropsTheCutLine(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("x.log");
		Files.write(f, "first line\nsecond\nthird".getBytes(StandardCharsets.UTF_8));
		assertEquals("second\nthird", BugReports.readTail(f, 15));
		assertEquals("first line\nsecond\nthird", BugReports.readTail(f, 1000));
	}

	// --- Häkchen ---

	@Test
	void defaultsSendVersionsAndModsButNotLogOrScreenshot(@TempDir Path game) {
		BugReports br = service(game);
		assertTrue(br.includeModVersion);
		assertTrue(br.includeGame);
		assertTrue(br.includeMods);
		assertFalse(br.includeLog);
		assertFalse(br.includeScreenshot);
		br.collectAsync(true);
		BugReportBody.Meta m = br.meta();
		assertEquals("0.13.0", m.modVersion);
		assertEquals("1.21.1", m.mcVersion);
		assertEquals("fabric", m.loader);
		assertNotNull(m.mods);
		assertNull(m.log);
		br.includeGame = false;
		br.includeModVersion = false;
		br.includeMods = false;
		m = br.meta();
		assertNull(m.modVersion);
		assertNull(m.mcVersion);
		assertNull(m.loader);
		assertNull(m.mods);
	}

	// --- Senden ---

	@Test
	void sendsScreenshotThenIssue(@TempDir Path game) throws Exception {
				Path shots = Files.createDirectories(game.resolve("screenshots"));
		Files.write(shots.resolve("shot.png"), png());
		FakeApi api = new FakeApi();
		BugReports br = service(game);
		br.http(api);
		fill(br);
		br.includeScreenshot = true;
		br.includeLog = true;
		assertTrue(br.sendAsync());
		assertEquals(BugReports.Phase.DONE, br.phase(), br.error());
		assertEquals(42, br.created().number);
		assertEquals("https://trs-launcher.theredstonee.de/issues/42", br.created().url);
		assertEquals(Arrays.asList("POST /v1/issues/uploads", "POST /v1/issues"), api.calls);
		assertEquals("image/png", api.types.get(0));
		assertEquals("Bearer trs_test", api.auth.get(1));
		JsonObject body = new JsonParser().parse(api.bodies.get(1)).getAsJsonObject();
		assertEquals(UPLOAD_ID, body.getAsJsonArray("attachments").get(0).getAsString());
		assertEquals("Minimap flickers", body.get("title").getAsString());
	}

	@Test
	void serverErrorsBecomeReadableCodes(@TempDir Path game) {
		String[][] cases = {
				{"429", "issue_daily_limit"}, {"403", "sanctioned"}, {"400", "invalid_request"}, {"502", "http_502"},
		};
		for (String[] c : cases) {
			FakeApi api = new FakeApi();
			api.createStatus.add(Integer.parseInt(c[0]));
			api.createCode = c[0].equals("502") ? null : c[1];
			BugReports br = service(game);
			br.http(api);
			fill(br);
			br.sendAsync();
			assertEquals(BugReports.Phase.FAILED, br.phase(), c[1]);
			assertEquals(c[1], br.error());
			String text = BugReportPage.errorText(br.error(), 0);
			assertFalse(text.startsWith("bugreport."), text);
		}
	}

	@Test
	void unauthorizedDropsTheToken(@TempDir Path game) {
		FakeApi api = new FakeApi();
		api.createStatus.add(401);
		BugReports br = service(game);
		final List<String> rejected = new ArrayList<String>();
		br.auth(new TestAuth(BugReports.Access.READY, "trs_test") {
			@Override
			public void rejected(String token) {
				rejected.add(token);
			}
		});
		br.http(api);
		fill(br);
		br.sendAsync();
		assertEquals("unauthorized", br.error());
		assertEquals(Collections.singletonList("trs_test"), rejected);
	}

	@Test
	void lostUploadIsUploadedAgainOnce(@TempDir Path game) throws Exception {
		Files.write(Files.createDirectories(game.resolve("screenshots")).resolve("s.png"), png());
		FakeApi api = new FakeApi();
		api.createStatus.add(404);
		api.createCode = "upload_not_found";
		BugReports br = service(game);
		br.http(api);
		fill(br);
		br.includeScreenshot = true;
		br.sendAsync();
		assertEquals(BugReports.Phase.DONE, br.phase(), br.error());
		assertEquals(Arrays.asList("POST /v1/issues/uploads", "POST /v1/issues", "POST /v1/issues/uploads", "POST /v1/issues"),
				api.calls);
	}

	@Test
	void offlineAndMissingConsentAreReported(@TempDir Path game) {
		BugReports br = service(game);
		br.http(new Http() {
			@Override
			public Response send(Request request) throws IOException {
				throw new IOException("keine Verbindung");
			}
		});
		fill(br);
		br.sendAsync();
		assertEquals("offline", br.error());

		BugReports off = service(game);
		off.auth(new TestAuth(BugReports.Access.LAUNCHER_OFF, null));
		fill(off);
		off.sendAsync();
		assertEquals("launcher_off", off.error());
		assertFalse(BugReportPage.errorText("launcher_off", 0).startsWith("bugreport."));
	}

	@Test
	void invalidTextIsNotSent(@TempDir Path game) {
		BugReports br = service(game);
		br.collectAsync(true);
		br.title.setText("abc");
		br.description.setText("long enough text");
		assertFalse(br.sendAsync());
		assertEquals(BugReports.Phase.IDLE, br.phase());
	}

	@Test
	void screenshotSelectionSteps(@TempDir Path game) throws Exception {
		Path shots = Files.createDirectories(game.resolve("screenshots"));
		for (int i = 0; i < 3; i++) {
			Path p = shots.resolve("s" + i + ".png");
			Files.write(p, png());
			Files.setLastModifiedTime(p, FileTime.fromMillis(1_000_000_000_000L + i * 1000L));
		}
		BugReports br = service(game);
		br.collectAsync(true);
		assertEquals("s2.png", br.selectedScreenshot().name);
		br.stepScreenshot(1);
		assertEquals("s1.png", br.selectedScreenshot().name);
		br.stepScreenshot(-2);
		assertEquals("s0.png", br.selectedScreenshot().name);
	}

	@Test
	void everyErrorAndAccessTextExistsInAllLanguages() {
		String[] codes = {"launcher_off", "module_off", "no_account", "banned", "issue_daily_limit", "sanctioned", "invalid_request",
				"upload_not_found", "payload_too_large", "unsupported_media_type", "image_unreadable", "unauthorized", "offline",
				"rate_limited", "busy", "something_new"};
		for (String lang : I18n.LANGUAGES) {
			I18n.use(lang);
			for (String code : codes) {
				String text = BugReportPage.errorText(code, 5000);
				assertFalse(text.startsWith("bugreport."), lang + " " + code);
			}
			for (BugReports.Access a : BugReports.Access.values()) {
				String key = "bugreport.access." + a.name().toLowerCase(java.util.Locale.ROOT);
				assertFalse(I18n.tr(key, "x").startsWith("bugreport."), lang + " " + key);
			}
		}
	}

	// --- Hilfen ---

	/** Dienst mit Spielordner {@code game} (config = game/config), synchron, Test-Anmeldung. */
	private static BugReports service(Path game) {
		BugReports br = new BugReports(game.resolve("config"), "0.13.0", "1.21.1", "fabric", new java.util.concurrent.Executor() {
			@Override
			public void execute(Runnable command) {
				command.run();
			}
		});
		br.auth(new TestAuth(BugReports.Access.READY, "trs_test"));
		br.http(new FakeApi());
		return br;
	}

	private static void fill(BugReports br) {
		br.collectAsync(true);
		br.title.setText("Minimap flickers");
		br.description.setText("In the Nether the minimap flickers every second.");
	}

	static byte[] png() throws IOException {
		BufferedImage img = new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	private static String repeat(char c, int n) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++) b.append(c);
		return b.toString();
	}

	static class TestAuth implements BugReports.Auth {
		private final BugReports.Access access;
		private final String token;

		TestAuth(BugReports.Access access, String token) {
			this.access = access;
			this.token = token;
		}

		@Override
		public BugReports.Access access() {
			return access;
		}

		@Override
		public String token() throws IOException, ApiException {
			return token;
		}

		@Override
		public void rejected(String token) {
		}
	}

	/** Attrappe des Vertrags: Upload → ID, Issue → Nummer 42; Fehler per Warteschlange. */
	static final class FakeApi implements Http {
		final List<String> calls = new ArrayList<String>();
		final List<String> types = new ArrayList<String>();
		final List<String> auth = new ArrayList<String>();
		final List<String> bodies = new ArrayList<String>();
		final LinkedList<Integer> createStatus = new LinkedList<Integer>();
		String createCode;

		@Override
		public Response send(Request request) {
			String path = request.url.replaceFirst("^https?://[^/]+", "");
			calls.add(request.method + " " + path);
			types.add(request.headers.get("Content-Type"));
			auth.add(request.headers.get("Authorization"));
			bodies.add(request.body == null ? null : new String(request.body, StandardCharsets.UTF_8));
			Map<String, String> headers = new HashMap<String, String>();
			if (path.equals("/v1/issues/uploads")) {
				return new Response(201, headers, ("{\"upload\":{\"id\":\"" + UPLOAD_ID + "\"}}").getBytes(StandardCharsets.UTF_8));
			}
			if (path.equals("/v1/issues")) {
				Integer status = createStatus.poll();
				if (status != null) {
					String body = createCode == null ? "<html>502</html>"
							: "{\"error\":{\"code\":\"" + createCode + "\",\"message\":\"x\"}}";
					return new Response(status, headers, body.getBytes(StandardCharsets.UTF_8));
				}
				return new Response(201, headers,
						"{\"issue\":{\"number\":42,\"url\":\"https://trs-launcher.theredstonee.de/issues/42\"}}".getBytes(StandardCharsets.UTF_8));
			}
			return new Response(404, headers, "{}".getBytes(StandardCharsets.UTF_8));
		}
	}
}

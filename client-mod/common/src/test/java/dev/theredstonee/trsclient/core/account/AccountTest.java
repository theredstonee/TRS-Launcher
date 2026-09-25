package dev.theredstonee.trsclient.core.account;

import static org.junit.jupiter.api.Assertions.*;

import dev.theredstonee.trsclient.core.link.FakeLauncher;
import dev.theredstonee.trsclient.core.link.TrsLink;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountTest {
	// --- Attrappen ------------------------------------------------------------------------------

	/** HTTP-Attrappe: Antworten je "METHODE url-ohne-query". */
	static final class FakeHttp implements Http {
		final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
		final Map<String, Function<Request, Response>> routes = new HashMap<>();

		FakeHttp json(String key, int status, String body) {
			routes.put(key, r -> response(status, body));
			return this;
		}

		static Response response(int status, String body) {
			return new Response(status, new HashMap<String, String>(), body.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public Response send(Request request) throws IOException {
			requests.add(request);
			int q = request.url.indexOf('?');
			String bare = q >= 0 ? request.url.substring(0, q) : request.url;
			Function<Request, Response> h = routes.get(request.method + " " + bare);
			if (h == null) throw new IOException("keine Route: " + request.method + " " + bare);
			return h.apply(request);
		}

		String bodyOf(String urlPart) {
			synchronized (requests) {
				for (Request r : requests) {
					if (r.url.contains(urlPart) && r.body != null) return new String(r.body, StandardCharsets.UTF_8);
				}
			}
			return null;
		}
	}

	static String jwt(String payload) {
		return "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".s";
	}

	/** Die ganze Kette Microsoft → Xbox → XSTS → Minecraft für ein Konto. */
	static FakeHttp xboxChain(String uuid, String name) {
		String mcToken = jwt("{\"xuid\":\"2535412345678901\"}");
		return new FakeHttp()
				.json("POST " + MsAuth.TOKEN_URL, 200, "{\"access_token\":\"ms-access\",\"refresh_token\":\"refresh-2\"}")
				.json("POST " + MsAuth.XBL_URL, 200, "{\"Token\":\"xbl\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"uhs1\"}]}}")
				.json("POST " + MsAuth.XSTS_URL, 200, "{\"Token\":\"xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"uhs1\"}]}}")
				.json("POST " + MsAuth.MC_LOGIN_URL, 200, "{\"access_token\":\"" + mcToken + "\",\"expires_in\":86400}")
				.json("GET " + MsAuth.MC_ENTITLEMENTS_URL, 200, "{\"items\":[{\"name\":\"game_minecraft\"}]}")
				.json("GET " + MsAuth.MC_PROFILE_URL, 200, "{\"id\":\"" + uuid + "\",\"name\":\"" + name
						+ "\",\"skins\":[{\"state\":\"ACTIVE\",\"url\":\"http://textures.minecraft.net/texture/abc\"}]}");
	}

	/** Spiel-Attrappe: merkt sich die eingesetzte Sitzung. */
	static final class FakePlatform implements AccountPlatform {
		final Path config;
		volatile SessionData current = new SessionData("33333333333333333333333333333333", "Starter", "start-token", null);
		volatile boolean inWorld;
		final List<String> opened = Collections.synchronizedList(new ArrayList<String>());
		final List<SessionData> applied = Collections.synchronizedList(new ArrayList<SessionData>());

		FakePlatform(Path config) {
			this.config = config;
		}

		@Override
		public SessionData current() {
			return current;
		}

		@Override
		public boolean inWorld() {
			return inWorld;
		}

		@Override
		public void execute(Runnable task) {
			new Thread(task, "fake-game").start();
		}

		@Override
		public void apply(SessionData session, Object prepared) {
			applied.add(session);
			current = session;
		}

		@Override
		public void openUrl(String url) {
			opened.add(url);
		}

		@Override
		public Path configDir() {
			return config;
		}

		@Override
		public String userAgent() {
			return "test";
		}

		@Override
		public void log(String message) {
		}
	}

	// --- Microsoft-Anmeldung -------------------------------------------------------------------

	@Test
	void xboxKetteLiefertProfilUndPrueftDaten() throws Exception {
		FakeHttp http = xboxChain("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Alex");
		MsAuth auth = new MsAuth(http);
		MsAuth.McProfile p = auth.minecraftLogin("ms-access");
		assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", p.uuid);
		assertEquals("Alex", p.name);
		assertEquals("https://textures.minecraft.net/texture/abc", p.skinUrl);
		assertEquals("2535412345678901", p.xuid);
		assertTrue(http.bodyOf("login_with_xbox").contains("XBL3.0 x=uhs1;xsts"));
		assertTrue(http.bodyOf("user.auth.xboxlive.com").contains("d=ms-access"));

		http.json("POST " + MsAuth.XSTS_URL, 401, "{\"XErr\":2148916233}");
		MsAuth.AuthException e = assertThrows(MsAuth.AuthException.class, () -> auth.minecraftLogin("ms-access"));
		assertEquals("xboxNoProfile", e.code);

		FakeHttp noGame = xboxChain("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Alex").json("GET " + MsAuth.MC_ENTITLEMENTS_URL, 200, "{\"items\":[]}");
		assertEquals("noJavaEdition", assertThrows(MsAuth.AuthException.class, () -> new MsAuth(noGame).minecraftLogin("x")).code);
		FakeHttp badName = xboxChain("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "<b>");
		assertEquals("profileInvalid", assertThrows(MsAuth.AuthException.class, () -> new MsAuth(badName).minecraftLogin("x")).code);
		FakeHttp denied = xboxChain("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Alex").json("POST " + MsAuth.MC_LOGIN_URL, 403, "{}");
		assertEquals("notApproved", assertThrows(MsAuth.AuthException.class, () -> new MsAuth(denied).minecraftLogin("x")).code);
	}

	@Test
	void deviceCodeWartetUndNutztNurDieEigeneApp() throws Exception {
		final int[] polls = {0};
		FakeHttp http = new FakeHttp()
				.json("POST " + MsAuth.DEVICE_CODE_URL, 200, "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\","
						+ "\"verification_uri\":\"https://www.microsoft.com/link\",\"expires_in\":900,\"interval\":1}");
		http.routes.put("POST " + MsAuth.TOKEN_URL, r -> ++polls[0] < 3
				? FakeHttp.response(400, "{\"error\":\"authorization_pending\"}")
				: FakeHttp.response(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\"}"));
		MsAuth auth = new MsAuth(http);
		MsAuth.DeviceCode code = auth.deviceCodeStart();
		assertEquals("ABCD-EFGH", code.userCode);
		MsAuth.Tokens t = auth.deviceCodePoll(code, () -> false, (ms, c) -> { });
		assertEquals("r", t.refreshToken);
		assertEquals(3, polls[0]);
		assertTrue(http.bodyOf("devicecode").contains("client_id=" + MsAuth.CLIENT_ID));
		// Fremde Prüfseite → abgelehnt.
		FakeHttp evil = new FakeHttp().json("POST " + MsAuth.DEVICE_CODE_URL, 200, "{\"device_code\":\"dc\",\"user_code\":\"X\","
				+ "\"verification_uri\":\"https://evil.example/link\",\"expires_in\":900,\"interval\":1}");
		assertThrows(MsAuth.AuthException.class, () -> new MsAuth(evil).deviceCodeStart());
	}

	@Test
	void browserAnmeldungPrueftStateUndPkce() throws Exception {
		FakeHttp http = new FakeHttp().json("POST " + MsAuth.TOKEN_URL, 200, "{\"access_token\":\"a\",\"refresh_token\":\"r\"}");
		MsAuth auth = new MsAuth(http);
		try (MsAuth.BrowserLogin login = auth.startBrowser()) {
			assertTrue(login.authorizeUrl.startsWith(MsAuth.AUTHORIZE_URL + "?client_id=" + MsAuth.CLIENT_ID));
			assertTrue(login.authorizeUrl.contains("code_challenge_method=S256"));
			assertTrue(login.redirectUri.startsWith("http://localhost:"));
			int port = login.server.getLocalPort();
			Thread browser = new Thread(() -> {
				request(port, "/favicon.ico");
				request(port, "/login?code=evil&state=falsch");
				request(port, "/login?code=M.C5_abc%2Bdef&state=" + login.state);
			});
			browser.start();
			MsAuth.Tokens t = auth.finishBrowser(login, () -> false);
			browser.join(5000);
			assertEquals("r", t.refreshToken);
			String body = http.bodyOf("oauth2/v2.0/token");
			assertTrue(body.contains("code=M.C5_abc%2Bdef"), body);
			assertTrue(body.contains("code_verifier=" + login.verifier));
		}
		assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", MsAuth.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
	}

	private static void request(int port, String path) {
		try (Socket s = new Socket("127.0.0.1", port)) {
			OutputStream out = s.getOutputStream();
			out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			out.flush();
			InputStream in = s.getInputStream();
			while (in.read() >= 0) {
				// Antwort lesen, bis der Server schließt
			}
		} catch (IOException ignored) {
			// egal
		}
	}

	@Test
	void skinAdressenNurVonMojang() {
		assertEquals("https://textures.minecraft.net/texture/abc", MsAuth.safeSkinUrl("http://textures.minecraft.net/texture/abc"));
		assertNull(MsAuth.safeSkinUrl("https://evil.example/texture/abc"));
		assertNull(MsAuth.safeSkinUrl("https://textures.minecraft.net/texture/a?x=<script>"));
		assertNull(MsAuth.safeSkinUrl("javascript:alert(1)"));
	}

	// --- Speicher ------------------------------------------------------------------------------

	@Test
	void tresorVerschluesseltUndEntferntKonten(@TempDir Path dir) throws Exception {
		Path config = dir.resolve("config");
		Path keys = dir.resolve("keys");
		AccountVault vault = new AccountVault(config, keys);
		vault.put("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Alex", "http://textures.minecraft.net/texture/abc", "1", "M.R3_geheim-refresh");
		String raw = new String(Files.readAllBytes(vault.file()), StandardCharsets.UTF_8);
		assertFalse(raw.contains("geheim"), "Refresh-Token nie im Klartext");
		assertEquals("M.R3_geheim-refresh", vault.refreshToken("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
		assertEquals(1, vault.list().size());
		assertEquals("https://textures.minecraft.net/texture/abc", vault.list().get(0).skinUrl);
		// Erneut anmelden ersetzt, kein Duplikat.
		vault.put("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Alex2", null, null, "neu");
		assertEquals(1, vault.list().size());
		assertEquals("neu", vault.refreshToken("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
		assertTrue(vault.remove("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
		assertTrue(vault.list().isEmpty());
	}

	@Test
	void schluesseldateiIstNoetigZumLesen(@TempDir Path dir) throws Exception {
		SecretBox.KeyFile box = new SecretBox.KeyFile(dir.resolve("k").resolve("account-key.bin"));
		byte[] sealed = box.seal("geheim".getBytes(StandardCharsets.UTF_8));
		assertEquals("geheim", new String(box.open(sealed), StandardCharsets.UTF_8));
		sealed[sealed.length - 1] ^= 1;
		assertNull(box.open(sealed), "manipuliert");
		// Andere Schlüsseldatei (z. B. Instanz auf anderen PC kopiert) → nicht lesbar.
		SecretBox.KeyFile other = new SecretBox.KeyFile(dir.resolve("other").resolve("account-key.bin"));
		other.seal(new byte[] {1});
		assertNull(other.open(box.seal("x".getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void dpapiUeberJnaUnterWindows() throws Exception {
		if (!SecretBox.isWindows()) return;
		SecretBox dpapi = SecretBox.Dpapi.create();
		assertNotNull(dpapi, "JNA liegt im Testpfad – DPAPI muss gehen");
		byte[] sealed = dpapi.seal("M.R3_refresh".getBytes(StandardCharsets.UTF_8));
		assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains("refresh"));
		assertEquals("M.R3_refresh", new String(dpapi.open(sealed), StandardCharsets.UTF_8));
		assertNull(dpapi.open(new byte[] {1, 2, 3}));
	}

	// --- Kontoverwaltung -----------------------------------------------------------------------

	private static void waitFor(java.util.function.BooleanSupplier ok, String what) throws InterruptedException {
		long until = System.currentTimeMillis() + 20000;
		while (!ok.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(20);
		assertTrue(ok.getAsBoolean(), what);
	}

	@Test
	void mitLauncherWechselnOhneNeustart(@TempDir Path dir) throws Exception {
		try (FakeLauncher launcher = new FakeLauncher()) {
			TrsLink link = new TrsLink(dir, launcher.env());
			FakePlatform platform = new FakePlatform(dir);
			AccountManager m = new AccountManager(platform, link, new FakeHttp(), dir.resolve("keys"),
					Executors.newSingleThreadExecutor());
			m.open();
			m.drain();
			assertEquals(AccountManager.Mode.CONNECTING, m.state().mode, "vom Launcher gestartet, noch nicht verbunden");
			link.start();
			try {
				waitFor(() -> m.state().mode == AccountManager.Mode.LAUNCHER && m.state().accounts.size() == 3, "Launcher-Konten");
				List<GameAccount> list = m.state().accounts;
				// Startkonto zuerst, dann die gültigen Launcher-Konten; kaputte fallen weg, fremde Skin-Adresse auch.
				assertEquals(GameAccount.Source.STARTUP, list.get(0).source);
				assertEquals("Alex", list.get(1).name);
				assertTrue(list.get(1).launcherDefault);
				assertNull(list.get(2).skinUrl);
				assertEquals("33333333333333333333333333333333", m.state().current);

				// In der Welt: kein Wechsel.
				platform.inWorld = true;
				m.switchTo(FakeLauncher.STEVE);
				assertEquals("accounts.error.inWorld", m.state().message);
				assertTrue(platform.applied.isEmpty());
				platform.inWorld = false;

				m.switchTo(FakeLauncher.STEVE);
				waitFor(() -> !platform.applied.isEmpty() && m.state().task == AccountManager.Task.NONE, "gewechselt");
				SessionData s = platform.applied.get(0);
				assertEquals("Steve", s.name);
				assertEquals("mc-token-Steve", s.accessToken);
				assertEquals(FakeLauncher.STEVE, m.state().current);
				assertEquals("accounts.switched", m.state().message);
				assertFalse(s.toString().contains("mc-token"), "Token nie in toString");

				// Zurück zum Startkonto geht ohne Launcher-Anfrage.
				m.switchTo("33333333333333333333333333333333");
				waitFor(() -> platform.applied.size() == 2 && m.state().task == AccountManager.Task.NONE, "zurück");
				assertEquals("start-token", platform.applied.get(1).accessToken);

				// Hinzufügen im Launcher: der Launcher meldet "busy".
				m.add();
				waitFor(() -> m.state().task == AccountManager.Task.NONE, "fertig");
				assertEquals("accounts.error.busy", m.state().message);
				assertTrue(m.state().error);
			} finally {
				link.stop();
			}
		}
	}

	@Test
	void ohneLauncherKontoImSpielHinzufuegenUndWechseln(@TempDir Path dir) throws Exception {
		FakeHttp http = xboxChain("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Alex");
		http.json("POST " + MsAuth.DEVICE_CODE_URL, 200, "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\","
				+ "\"verification_uri\":\"https://www.microsoft.com/link\",\"expires_in\":900,\"interval\":1}");
		FakePlatform platform = new FakePlatform(dir);
		AccountManager m = new AccountManager(platform, new TrsLink(dir, null), http, dir.resolve("keys"),
				Executors.newSingleThreadExecutor());
		m.open();
		m.drain();
		assertEquals(AccountManager.Mode.LOCAL, m.state().mode);
		assertEquals(1, m.state().accounts.size(), "nur das Startkonto");

		m.addWithCode();
		waitFor(() -> m.state().task == AccountManager.Task.NONE, "angemeldet");
		assertEquals("accounts.switched", m.state().message, String.valueOf(m.state().message));
		assertEquals("Alex", platform.current.name);
		assertEquals(2, m.state().accounts.size());
		String raw = new String(Files.readAllBytes(dir.resolve("trsclient").resolve("accounts.json")), StandardCharsets.UTF_8);
		assertFalse(raw.contains("refresh-2"), "Refresh-Token verschlüsselt");
		assertFalse(raw.contains("ms-access") || raw.contains("xsts"), "keine Zugangs-Tokens auf der Platte");

		// Zurück zum Startkonto, dann wieder zu Alex (Refresh + Kette).
		m.switchTo("33333333333333333333333333333333");
		waitFor(() -> "Starter".equals(platform.current.name) && m.state().task == AccountManager.Task.NONE, "Startkonto");
		m.switchTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		waitFor(() -> "Alex".equals(platform.current.name) && m.state().task == AccountManager.Task.NONE, "Alex");
		assertTrue(http.bodyOf("oauth2/v2.0/token").contains("client_id=" + MsAuth.CLIENT_ID));

		// Entfernen (nur eigene Konten).
		final AtomicReference<GameAccount> alex = new AtomicReference<GameAccount>();
		for (GameAccount g : m.state().accounts) if (g.name.equals("Alex")) alex.set(g);
		assertTrue(alex.get().removable());
		m.remove(alex.get().uuid);
		m.drain();
		assertEquals(1, m.state().accounts.size());
	}

	/** Jeder Fehlercode, den Launcher, Microsoft-Kette oder Verwaltung liefern können, hat einen Text. */
	@Test
	void jederFehlercodeHatEinenText() {
		String[] codes = {"rate_limited", "unknown_account", "not_allowed", "busy", "cancelled", "auth_failed", "offline",
				"unknown_op", "timeout", "error", "inWorld", "locked", "network", "swapFailed", "storage", "accountMismatch",
				"microsoftRejected", "codeExpired", "sessionExpired", "xboxFailed", "xboxBanned", "xboxNoProfile", "xboxRegion",
				"xboxAge", "xboxChild", "notApproved", "minecraftFailed", "entitlementFailed", "noJavaEdition", "noProfile",
				"profileFailed", "profileInvalid"};
		for (String lang : dev.theredstonee.trsclient.core.i18n.I18n.LANGUAGES) {
			Map<String, String> raw = dev.theredstonee.trsclient.core.i18n.I18n.raw(lang);
			for (String code : codes) assertNotNull(raw.get("accounts.error." + code), lang + ": accounts.error." + code);
		}
		for (String c : TrsLinkErrors.ALL) assertNotNull(dev.theredstonee.trsclient.core.i18n.I18n.raw("en").get("accounts.error." + c), c);
		assertEquals("error", TrsLink.safeError("<script>"));
	}

	/** Fehlercodes des Launchers laut Vertrag. */
	static final class TrsLinkErrors {
		static final String[] ALL = {"rate_limited", "unknown_account", "not_allowed", "busy", "cancelled", "auth_failed",
				"offline", "unknown_op", "timeout", "error"};
	}

	/** Nachbau von Minecraft/Session der Forge-1.7.10–1.13.2-Bäume. */
	public static final class FakeLegacySession {
		final String username;
		final String playerId;
		final String token;
		final String type;

		public FakeLegacySession(String username, String playerId, String token, String type) {
			this.username = username;
			this.playerId = playerId;
			this.token = token;
			this.type = type;
		}
	}

	static final class FakeLegacyMinecraft {
		private final FakeLegacySession session = new FakeLegacySession("Old", "33333333333333333333333333333333", "old", "mojang");
	}

	@Test
	void legacySitzungWirdUeberDenFeldtypGetauscht(@TempDir Path dir) throws Exception {
		final FakeLegacyMinecraft mc = new FakeLegacyMinecraft();
		LegacySessionSwap swap = new LegacySessionSwap(() -> mc, FakeLegacySession.class,
				() -> new SessionData(mc.session.playerId, mc.session.username, mc.session.token, null), () -> false, dir, "t", m -> { });
		assertEquals("Old", swap.current().name);
		final boolean[] ran = {false};
		swap.execute(() -> ran[0] = true);
		assertFalse(ran[0], "erst im Client-Tick");
		swap.drain();
		assertTrue(ran[0]);
		SessionData alex = new SessionData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Alex", "neu", null);
		swap.apply(alex, swap.prepare(alex));
		assertEquals("Alex", mc.session.username);
		assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", mc.session.playerId);
		assertEquals("neu", mc.session.token);
		assertEquals("msa", mc.session.type);
	}

	@Test
	void gesichtOhneDeckendeHutEbene() {
		int[] px = new int[64 * 32];
		java.util.Arrays.fill(px, 0xFF000000);
		for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) px[y * 64 + x] = 0xFFC08060;
		int[] face = FaceCache.extract(new dev.theredstonee.trsclient.core.cape.PngDecoder.Image(64, 32, px));
		assertEquals(0xFFC08060, face[0]);
		assertEquals(0, face[64], "schwarze, deckende Hut-Ebene eines alten Skins zählt als leer");
		assertNull(FaceCache.extract(new dev.theredstonee.trsclient.core.cape.PngDecoder.Image(32, 32, new int[32 * 32])));
	}
}

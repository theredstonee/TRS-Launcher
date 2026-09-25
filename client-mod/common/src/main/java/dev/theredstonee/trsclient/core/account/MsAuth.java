package dev.theredstonee.trsclient.core.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Microsoft-Anmeldung direkt im Spiel (wenn das Spiel NICHT über den TRS Launcher läuft) – derselbe Weg wie
 * der Launcher ({@code auth/microsoft.rs}): Microsoft → Xbox Live → XSTS → Minecraft.
 *
 * <p>Nur mit der eigenen Azure-App des TRS Launchers (öffentlicher Client, kein Geheimnis). Zwei Wege:
 * Browser mit Auth-Code + PKCE über einen Loopback-Server ({@code http://localhost:<port>/login}, Microsoft
 * ignoriert bei localhost den Port) und Device-Code (Code auf microsoft.com/link eingeben). Das
 * Minecraft-Zugangs-Token bleibt nur im Speicher; gespeichert wird nur das (verschlüsselte) Refresh-Token.
 */
public final class MsAuth {
	/** Öffentlicher Client des TRS Launchers – nicht geheim. */
	public static final String CLIENT_ID = "ac3d320e-d0a2-4910-8e3c-b425883984a9";
	static final String SCOPE = "XboxLive.signin offline_access";
	static final String AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
	static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
	static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
	static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
	static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
	static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
	static final String MC_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore";
	static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
	/** Wie in der Azure-App eingetragen. */
	static final int REDIRECT_PORT = 28443;
	static final String REDIRECT_PATH = "/login";
	static final long BROWSER_TIMEOUT_MS = 5 * 60_000L;

	/** Fehler mit festem Code (für die Übersetzung {@code accounts.error.<code>}). */
	public static final class AuthException extends Exception {
		private static final long serialVersionUID = 1L;
		public final String code;

		public AuthException(String code) {
			super(code);
			this.code = code;
		}
	}

	/** Abbruch von außen (Knopf „Abbrechen“). */
	public interface Cancel {
		boolean cancelled();
	}

	public static final class Tokens {
		public final String accessToken;
		public final String refreshToken;

		Tokens(String accessToken, String refreshToken) {
			this.accessToken = accessToken;
			this.refreshToken = refreshToken;
		}
	}

	public static final class DeviceCode {
		final String deviceCode;
		public final String userCode;
		public final String verificationUri;
		final long expiresInSec;
		final long intervalSec;

		DeviceCode(String deviceCode, String userCode, String verificationUri, long expiresInSec, long intervalSec) {
			this.deviceCode = deviceCode;
			this.userCode = userCode;
			this.verificationUri = verificationUri;
			this.expiresInSec = expiresInSec;
			this.intervalSec = intervalSec;
		}
	}

	/** Ergebnis der Minecraft-Anmeldung (Token nie loggen). */
	public static final class McProfile {
		public final String uuid;
		public final String name;
		public final String accessToken;
		public final String skinUrl;
		public final String xuid;

		McProfile(String uuid, String name, String accessToken, String skinUrl, String xuid) {
			this.uuid = uuid;
			this.name = name;
			this.accessToken = accessToken;
			this.skinUrl = skinUrl;
			this.xuid = xuid;
		}
	}

	private final Http http;

	public MsAuth(Http http) {
		this.http = http;
	}

	// --- Token-Endpunkt -----------------------------------------------------------------------

	private static String form(Map<String, String> fields) {
		StringBuilder b = new StringBuilder();
		try {
			for (Map.Entry<String, String> e : fields.entrySet()) {
				if (b.length() > 0) b.append('&');
				b.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
			}
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
		return b.toString();
	}

	private Http.Response post(String url, Map<String, String> fields) throws IOException {
		Http.Request r = new Http.Request("POST", url);
		r.header("Content-Type", "application/x-www-form-urlencoded");
		r.header("Accept", "application/json");
		r.body = form(fields).getBytes(StandardCharsets.UTF_8);
		return http.send(r);
	}

	private Http.Response postJson(String url, JsonObject body, String bearer) throws IOException {
		Http.Request r = new Http.Request("POST", url);
		r.header("Content-Type", "application/json");
		r.header("Accept", "application/json");
		if (bearer != null) r.header("Authorization", "Bearer " + bearer);
		r.body = body.toString().getBytes(StandardCharsets.UTF_8);
		return http.send(r);
	}

	private Http.Response get(String url, String bearer) throws IOException {
		Http.Request r = new Http.Request("GET", url);
		r.header("Accept", "application/json");
		r.header("Authorization", "Bearer " + bearer);
		return http.send(r);
	}

	static JsonObject json(Http.Response response) throws AuthException {
		try {
			JsonElement e = new JsonParser().parse(response.text());
			if (e != null && e.isJsonObject()) return e.getAsJsonObject();
		} catch (RuntimeException ignored) {
			// unten
		}
		throw new AuthException("microsoftRejected");
	}

	static String str(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private Tokens tokens(JsonObject o) throws AuthException {
		String access = str(o, "access_token");
		String refresh = str(o, "refresh_token");
		if (access == null || refresh == null) throw new AuthException("microsoftRejected");
		return new Tokens(access, refresh);
	}

	// --- Device-Code ----------------------------------------------------------------------------

	public DeviceCode deviceCodeStart() throws IOException, AuthException {
		Map<String, String> f = new LinkedHashMap<String, String>();
		f.put("client_id", CLIENT_ID);
		f.put("scope", SCOPE);
		Http.Response r = post(DEVICE_CODE_URL, f);
		if (r.status / 100 != 2) throw new AuthException("microsoftRejected");
		JsonObject o = json(r);
		String device = str(o, "device_code");
		String user = str(o, "user_code");
		String uri = str(o, "verification_uri");
		if (device == null || user == null || uri == null || !trustedVerificationUri(uri)) throw new AuthException("microsoftRejected");
		long expires = number(o, "expires_in", 900);
		long interval = number(o, "interval", 5);
		return new DeviceCode(device, user, uri, expires, interval);
	}

	/** Nur Microsoft-Seiten öffnen (die URL kommt von außen). */
	static boolean trustedVerificationUri(String uri) {
		return uri.startsWith("https://www.microsoft.com/") || uri.startsWith("https://microsoft.com/")
				|| uri.startsWith("https://login.microsoftonline.com/") || uri.startsWith("https://login.live.com/");
	}

	private static long number(JsonObject o, String key, long fallback) {
		try {
			JsonElement e = o.get(key);
			return e != null && e.isJsonPrimitive() ? e.getAsLong() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** Wartet, bis der Code eingegeben wurde (blockiert; {@code sleeper} nur für Tests austauschbar). */
	public Tokens deviceCodePoll(DeviceCode code, Cancel cancel, Sleeper sleeper) throws IOException, AuthException {
		long interval = Math.max(1, Math.min(30, code.intervalSec));
		long deadline = System.currentTimeMillis() + Math.min(code.expiresInSec, 30 * 60) * 1000L;
		while (true) {
			sleeper.sleep(interval * 1000L, cancel);
			if (cancel.cancelled()) throw new AuthException("cancelled");
			if (System.currentTimeMillis() >= deadline) throw new AuthException("codeExpired");
			Map<String, String> f = new LinkedHashMap<String, String>();
			f.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
			f.put("client_id", CLIENT_ID);
			f.put("device_code", code.deviceCode);
			Http.Response r = post(TOKEN_URL, f);
			JsonObject o = json(r);
			if (r.status / 100 == 2) return tokens(o);
			String error = str(o, "error");
			if ("authorization_pending".equals(error)) continue;
			if ("slow_down".equals(error)) {
				interval += 5;
				continue;
			}
			if ("authorization_declined".equals(error)) throw new AuthException("cancelled");
			if ("expired_token".equals(error)) throw new AuthException("codeExpired");
			throw new AuthException("microsoftRejected");
		}
	}

	/** Warten mit Abbruch. */
	public interface Sleeper {
		void sleep(long ms, Cancel cancel);
	}

	public static final Sleeper REAL_SLEEP = new Sleeper() {
		@Override
		public void sleep(long ms, Cancel cancel) {
			long until = System.currentTimeMillis() + ms;
			while (!cancel.cancelled() && System.currentTimeMillis() < until) {
				try {
					Thread.sleep(Math.min(200, Math.max(1, until - System.currentTimeMillis())));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	};

	// --- Browser (Auth-Code + PKCE über Loopback) -------------------------------------------------

	/** Ein laufender Browser-Login: Loopback-Server + Parameter. */
	public static final class BrowserLogin implements AutoCloseable {
		final ServerSocket server;
		public final String authorizeUrl;
		final String redirectUri;
		final String state;
		final String verifier;

		BrowserLogin(ServerSocket server, String authorizeUrl, String redirectUri, String state, String verifier) {
			this.server = server;
			this.authorizeUrl = authorizeUrl;
			this.redirectUri = redirectUri;
			this.state = state;
			this.verifier = verifier;
		}

		@Override
		public void close() {
			try {
				server.close();
			} catch (IOException ignored) {
				// egal
			}
		}
	}

	/** Öffnet den Loopback-Server (nur 127.0.0.1) und baut die Anmelde-URL. */
	public BrowserLogin startBrowser() throws IOException {
		ServerSocket server;
		try {
			server = new ServerSocket(REDIRECT_PORT, 8, InetAddress.getByName("127.0.0.1"));
		} catch (IOException e) {
			// Port belegt (z. B. Launcher meldet gerade an): irgendein freier tut es auch.
			server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
		}
		String redirect = "http://localhost:" + server.getLocalPort() + REDIRECT_PATH;
		String state = randomToken();
		String verifier = randomToken();
		Map<String, String> q = new LinkedHashMap<String, String>();
		q.put("client_id", CLIENT_ID);
		q.put("response_type", "code");
		q.put("redirect_uri", redirect);
		q.put("response_mode", "query");
		q.put("scope", SCOPE);
		q.put("state", state);
		q.put("code_challenge", pkceChallenge(verifier));
		q.put("code_challenge_method", "S256");
		q.put("prompt", "select_account");
		return new BrowserLogin(server, AUTHORIZE_URL + "?" + form(q), redirect, state, verifier);
	}

	/** Wartet auf die Umleitung und löst den Code ein (blockiert). */
	public Tokens finishBrowser(BrowserLogin login, Cancel cancel) throws IOException, AuthException {
		String code = awaitRedirect(login, cancel, BROWSER_TIMEOUT_MS);
		Map<String, String> f = new LinkedHashMap<String, String>();
		f.put("grant_type", "authorization_code");
		f.put("client_id", CLIENT_ID);
		f.put("code", code);
		f.put("redirect_uri", login.redirectUri);
		f.put("code_verifier", login.verifier);
		f.put("scope", SCOPE);
		Http.Response r = post(TOKEN_URL, f);
		if (r.status / 100 != 2) throw new AuthException("microsoftRejected");
		return tokens(json(r));
	}

	static String awaitRedirect(BrowserLogin login, Cancel cancel, long timeoutMs) throws IOException, AuthException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		login.server.setSoTimeout(500);
		while (true) {
			if (cancel.cancelled()) throw new AuthException("cancelled");
			if (System.currentTimeMillis() > deadline) throw new AuthException("timeout");
			Socket s;
			try {
				s = login.server.accept();
			} catch (SocketTimeoutException e) {
				continue;
			} catch (IOException e) {
				if (cancel.cancelled()) throw new AuthException("cancelled");
				throw e;
			}
			try {
				s.setSoTimeout(5000);
				String target = requestTarget(s.getInputStream());
				if (target == null) {
					respond(s, "400 Bad Request", FAILED_PAGE);
					continue;
				}
				int q = target.indexOf('?');
				String path = q >= 0 ? target.substring(0, q) : target;
				Map<String, String> params = query(q >= 0 ? target.substring(q + 1) : "");
				if (!REDIRECT_PATH.equals(path)) {
					respond(s, "404 Not Found", "");
					continue;
				}
				// Falscher State: nicht unsere Anmeldung – weiter warten.
				if (!login.state.equals(params.get("state"))) {
					respond(s, "400 Bad Request", FAILED_PAGE);
					continue;
				}
				String error = params.get("error");
				if (error != null) {
					respond(s, "200 OK", FAILED_PAGE);
					throw new AuthException("access_denied".equals(error) ? "cancelled" : "microsoftRejected");
				}
				String code = params.get("code");
				if (code != null && !code.isEmpty()) {
					respond(s, "200 OK", DONE_PAGE);
					return code;
				}
				respond(s, "400 Bad Request", FAILED_PAGE);
			} finally {
				try {
					s.close();
				} catch (IOException ignored) {
					// egal
				}
			}
		}
	}

	/** Liest nur die Request-Zeile ("GET /login?… HTTP/1.1"), höchstens 8 KB. */
	static String requestTarget(InputStream in) throws IOException {
		StringBuilder line = new StringBuilder();
		int n = 0;
		while (n < 8192) {
			int b;
			try {
				b = in.read();
			} catch (SocketTimeoutException e) {
				return null;
			}
			if (b < 0 || b == '\n') break;
			if (b != '\r') line.append((char) b);
			n++;
		}
		String l = line.toString();
		if (!l.startsWith("GET ")) return null;
		int end = l.indexOf(' ', 4);
		return end < 0 ? null : l.substring(4, end);
	}

	static Map<String, String> query(String q) {
		Map<String, String> out = new LinkedHashMap<String, String>();
		if (q.isEmpty()) return out;
		for (String part : q.split("&")) {
			int eq = part.indexOf('=');
			String k = eq < 0 ? part : part.substring(0, eq);
			String v = eq < 0 ? "" : part.substring(eq + 1);
			try {
				out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
			} catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
				// kaputter Parameter
			}
		}
		return out;
	}

	private static final String PAGE_HEAD = "<!doctype html><html><meta charset=\"utf-8\"><title>TRS Client</title>"
			+ "<body style=\"font-family:Segoe UI,sans-serif;background:#0b0c0f;color:#f2f4f8;display:grid;place-items:center;"
			+ "height:100vh;margin:0\"><div style=\"text-align:center\">";
	static final String DONE_PAGE = PAGE_HEAD + "<h1 style=\"font-size:20px\">Sign-in complete &middot; Anmeldung abgeschlossen</h1>"
			+ "<p style=\"color:#7d8699\">You can close this window and return to Minecraft.</p></div>";
	static final String FAILED_PAGE = PAGE_HEAD + "<h1 style=\"font-size:20px\">Sign-in failed &middot; Anmeldung fehlgeschlagen</h1>"
			+ "<p style=\"color:#7d8699\">Please try again in Minecraft.</p></div>";

	private static void respond(Socket s, String status, String body) {
		try {
			byte[] b = body.getBytes(StandardCharsets.UTF_8);
			String head = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
					+ "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n"
					+ "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nConnection: close\r\n\r\n";
			OutputStream out = s.getOutputStream();
			out.write(head.getBytes(StandardCharsets.US_ASCII));
			out.write(b);
			out.flush();
		} catch (IOException ignored) {
			// Browser schon weg
		}
	}

	static String randomToken() {
		// 64 Zeichen [0-9a-f] = gültiger PKCE-Verifier (43–128 Zeichen).
		return dev.theredstonee.trsclient.core.link.LinkCrypto.randomHex(32);
	}

	static String pkceChallenge(String verifier) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// --- Refresh ---------------------------------------------------------------------------------

	public Tokens refresh(String refreshToken) throws IOException, AuthException {
		Map<String, String> f = new LinkedHashMap<String, String>();
		f.put("grant_type", "refresh_token");
		f.put("client_id", CLIENT_ID);
		f.put("refresh_token", refreshToken);
		f.put("scope", SCOPE);
		Http.Response r = post(TOKEN_URL, f);
		if (r.status / 100 != 2) throw new AuthException("sessionExpired");
		return tokens(json(r));
	}

	// --- Xbox Live → Minecraft --------------------------------------------------------------------

	public McProfile minecraftLogin(String msAccessToken) throws IOException, AuthException {
		JsonObject xblBody = new JsonObject();
		JsonObject props = new JsonObject();
		props.addProperty("AuthMethod", "RPS");
		props.addProperty("SiteName", "user.auth.xboxlive.com");
		props.addProperty("RpsTicket", "d=" + msAccessToken);
		xblBody.add("Properties", props);
		xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
		xblBody.addProperty("TokenType", "JWT");
		Http.Response xblRes = postJson(XBL_URL, xblBody, null);
		if (xblRes.status / 100 != 2) throw new AuthException("xboxFailed");
		JsonObject xbl = json(xblRes);
		String xblToken = str(xbl, "Token");
		String uhs = uhs(xbl);
		if (xblToken == null) throw new AuthException("xboxFailed");

		JsonObject xstsBody = new JsonObject();
		JsonObject xp = new JsonObject();
		xp.addProperty("SandboxId", "RETAIL");
		JsonArray tokens = new JsonArray();
		tokens.add(new com.google.gson.JsonPrimitive(xblToken));
		xp.add("UserTokens", tokens);
		xstsBody.add("Properties", xp);
		xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		xstsBody.addProperty("TokenType", "JWT");
		Http.Response xstsRes = postJson(XSTS_URL, xstsBody, null);
		if (xstsRes.status == 401) {
			long xerr = 0;
			try {
				xerr = number(json(xstsRes), "XErr", 0);
			} catch (AuthException ignored) {
				// ohne Code
			}
			throw new AuthException(xstsError(xerr));
		}
		if (xstsRes.status / 100 != 2) throw new AuthException("xboxFailed");
		JsonObject xsts = json(xstsRes);
		String xstsToken = str(xsts, "Token");
		if (xstsToken == null) throw new AuthException("xboxFailed");
		if (uhs == null) uhs = uhs(xsts);

		JsonObject login = new JsonObject();
		login.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
		Http.Response mcRes = postJson(MC_LOGIN_URL, login, null);
		if (mcRes.status == 403) throw new AuthException("notApproved");
		if (mcRes.status / 100 != 2) throw new AuthException("minecraftFailed");
		String mcToken = str(json(mcRes), "access_token");
		if (mcToken == null) throw new AuthException("minecraftFailed");

		Http.Response ent = get(MC_ENTITLEMENTS_URL, mcToken);
		if (ent.status / 100 != 2) throw new AuthException("entitlementFailed");
		JsonElement items = json(ent).get("items");
		if (items == null || !items.isJsonArray() || items.getAsJsonArray().size() == 0) throw new AuthException("noJavaEdition");

		Http.Response prof = get(MC_PROFILE_URL, mcToken);
		if (prof.status == 404) throw new AuthException("noProfile");
		if (prof.status / 100 != 2) throw new AuthException("profileFailed");
		JsonObject p = json(prof);
		String id = str(p, "id");
		String name = str(p, "name");
		if (id == null || !id.matches("[0-9a-fA-F]{32}") || name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
			throw new AuthException("profileInvalid");
		}
		String skin = null;
		JsonElement skins = p.get("skins");
		if (skins != null && skins.isJsonArray()) {
			for (JsonElement e : skins.getAsJsonArray()) {
				if (!e.isJsonObject()) continue;
				if ("ACTIVE".equals(str(e.getAsJsonObject(), "state"))) {
					skin = safeSkinUrl(str(e.getAsJsonObject(), "url"));
					break;
				}
			}
		}
		return new McProfile(id.toLowerCase(java.util.Locale.ROOT), name, mcToken, skin, xuidFromJwt(mcToken));
	}

	private static String uhs(JsonObject response) {
		try {
			JsonObject claims = response.getAsJsonObject("DisplayClaims");
			JsonArray xui = claims.getAsJsonArray("xui");
			return str(xui.get(0).getAsJsonObject(), "uhs");
		} catch (RuntimeException e) {
			return null;
		}
	}

	static String xstsError(long xerr) {
		if (xerr == 2148916227L) return "xboxBanned";
		if (xerr == 2148916233L) return "xboxNoProfile";
		if (xerr == 2148916235L) return "xboxRegion";
		if (xerr == 2148916236L || xerr == 2148916237L) return "xboxAge";
		if (xerr == 2148916238L) return "xboxChild";
		return "xboxFailed";
	}

	/** Mojang liefert {@code http://textures.minecraft.net/…}; nur dieser Host, nur HTTPS. */
	public static String safeSkinUrl(String url) {
		if (url == null) return null;
		String rest;
		if (url.startsWith("http://")) rest = url.substring(7);
		else if (url.startsWith("https://")) rest = url.substring(8);
		else return null;
		int slash = rest.indexOf('/');
		if (slash < 0) return null;
		String host = rest.substring(0, slash);
		String path = rest.substring(slash + 1);
		if (!"textures.minecraft.net".equals(host) || path.isEmpty() || path.length() > 200) return null;
		for (int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);
			if (!(Character.isLetterOrDigit(c) && c < 128) && c != '/') return null;
		}
		return "https://" + host + "/" + path;
	}

	/** XUID aus dem Minecraft-Token (nur gelesen, nicht geprüft – kommt über TLS direkt von Mojang). */
	static String xuidFromJwt(String token) {
		try {
			String[] parts = token.split("\\.");
			if (parts.length < 2) return null;
			String payload = new String(Base64.getUrlDecoder().decode(parts[1].replace("=", "")), StandardCharsets.UTF_8);
			String xuid = str(new JsonParser().parse(payload).getAsJsonObject(), "xuid");
			return xuid != null && xuid.matches("[0-9]{1,20}") ? xuid : null;
		} catch (RuntimeException e) {
			return null;
		}
	}
}

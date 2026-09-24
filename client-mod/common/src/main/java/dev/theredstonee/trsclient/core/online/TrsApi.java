package dev.theredstonee.trsclient.core.online;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Endpunkte der TRS API, die der Mod braucht (Vertrag: api/API.md), plus der Mojang-Join.
 * Blockierend – nur aus dem Hintergrund-Thread von {@link TrsOnline} aufrufen.
 * Das Token wird nie geloggt und nie gespeichert (nur im Speicher).
 */
public final class TrsApi {
	private static final Gson GSON = new Gson();
	private static final String JSON = "application/json";

	private final Http http;
	private final OnlineConfig config;

	public TrsApi(Http http, OnlineConfig config) {
		this.http = http;
		this.config = config;
	}

	// --- DTOs (Gson; alle Felder optional) ---

	static final class Challenge {
		String serverId;
	}

	static final class Join {
		String accessToken;
		String selectedProfile;
		String serverId;
	}

	static final class Verify {
		String username;
		String serverId;
	}

	static final class Login {
		String token;
		Me user;
	}

	/** Ausschnitt von {@code GET /v1/me}. */
	public static final class Me {
		public String uuid;
		public String name;
		public Settings settings;
	}

	public static final class Settings {
		public Boolean showBadge;
		public Boolean showCapeToOthers;
		public Boolean shareServer;
	}

	static final class LookupRequest {
		List<String> uuids;
	}

	static final class LookupResponse {
		List<LookupPlayer> players;
	}

	static final class LookupPlayer {
		String uuid;
		Boolean badge;
		LookupCape cape;
	}

	static final class LookupCape {
		String id;
		String url;
		Integer scale;
		Integer frames;
		Integer frameTimeMs;
	}

	static final class Presence {
		String state;
		Game game;
	}

	static final class Game {
		String version;
		String loader;
		String server;
	}

	static final class MeCosmetics {
		List<String> emotes;
	}

	static final class PlayRequest {
		String emote;
	}

	static final class PlayResponse {
		String emote;
		Integer durationMs;
	}

	static final class ErrorBody {
		ErrorInfo error;
	}

	static final class ErrorInfo {
		String code;
	}

	/** Ergebnis der Anmeldung. */
	public static final class Session {
		public final String token;
		public final Me me;

		Session(String token, Me me) {
			this.token = token;
			this.me = me;
		}
	}

	// --- Anmeldung ---

	/**
	 * Anmeldung wie ein Minecraft-Server: Challenge holen, bei Mojang "joinen", verifizieren.
	 *
	 * @throws ApiException Mojang lehnt ab (z. B. 403 bei Offline-/Demo-Konto) oder die API sagt nein
	 */
	public Session login(GameSession session) throws IOException, ApiException {
		Challenge challenge = parse(call("POST", "/v1/auth/challenge", "{}", null, 201), Challenge.class);
		if (challenge == null || challenge.serverId == null || !challenge.serverId.matches("[0-9a-f]{40}")) {
			throw new ApiException(0, "bad_challenge", 0);
		}
		mojangJoin(session, challenge.serverId);
		Verify verify = new Verify();
		verify.username = session.name;
		verify.serverId = challenge.serverId;
		Login login = parse(call("POST", "/v1/auth/verify", GSON.toJson(verify), null, 200), Login.class);
		if (login == null || login.token == null || !login.token.matches("trs_[A-Za-z0-9_-]{43}")) {
			throw new ApiException(0, "bad_token", 0);
		}
		return new Session(login.token, login.user);
	}

	/** {@code POST sessionserver/session/minecraft/join} – serverId unverändert (kein Hash). */
	void mojangJoin(GameSession session, String serverId) throws IOException, ApiException {
		Join join = new Join();
		join.accessToken = session.accessToken;
		join.selectedProfile = session.uuid;
		join.serverId = serverId;
		Http.Request request = new Http.Request("POST", config.sessionBase() + "/session/minecraft/join")
				.header("Content-Type", JSON);
		request.body = GSON.toJson(join).getBytes(StandardCharsets.UTF_8);
		Http.Response response = http.send(request);
		if (response.status != 204 && response.status != 200) {
			throw new ApiException(response.status, "mojang_join_failed", retryAfter(response));
		}
	}

	// --- Profil, Lookup, Presence ---

	public Me me(String token) throws IOException, ApiException {
		return parse(call("GET", "/v1/me", null, token, 200), Me.class);
	}

	/**
	 * {@code POST /v1/players/lookup} für 1–100 UUIDs. Ergebnis: nur Spieler, die TRS nutzen und etwas
	 * zeigen; alle anderen fehlen (= Vanilla).
	 */
	public Map<String, PlayerInfo> lookup(String token, Collection<String> uuids) throws IOException, ApiException {
		if (uuids.isEmpty() || uuids.size() > 100) throw new IllegalArgumentException("1–100 UUIDs");
		LookupRequest body = new LookupRequest();
		body.uuids = new ArrayList<>(uuids);
		LookupResponse response = parse(call("POST", "/v1/players/lookup", GSON.toJson(body), token, 200),
				LookupResponse.class);
		Map<String, PlayerInfo> out = new LinkedHashMap<>();
		if (response == null || response.players == null) return out;
		for (LookupPlayer p : response.players) {
			if (p == null) continue;
			String uuid = Uuids.normalize(p.uuid);
			if (uuid == null) continue;
			CapeInfo cape = null;
			if (p.cape != null) {
				cape = CapeInfo.of(p.cape.id, p.cape.url, p.cape.scale, p.cape.frames, p.cape.frameTimeMs, config);
			}
			out.put(uuid, new PlayerInfo(Boolean.TRUE.equals(p.badge), cape));
		}
		return out;
	}

	/** {@code POST /v1/presence}: "in-game" mit Version/Loader, Server nur wenn übergeben. */
	public void presence(String token, String version, String loader, String server) throws IOException, ApiException {
		Presence body = new Presence();
		body.state = "in-game";
		Game game = new Game();
		game.version = version;
		game.loader = loader;
		game.server = server;
		body.game = game;
		call("POST", "/v1/presence", GSON.toJson(body), token, 200);
	}

	// --- Emotes (API.md §12) ---

	/**
	 * {@code GET /v1/me/cosmetics} → die IDs der Emotes, die dieses Konto abspielen darf (in Listen-Reihenfolge).
	 * Ungültige Einträge fallen weg; unbekannte IDs bleiben drin (der Aufrufer ignoriert sie).
	 */
	public List<String> emotes(String token) throws IOException, ApiException {
		MeCosmetics body = parse(call("GET", "/v1/me/cosmetics", null, token, 200), MeCosmetics.class);
		List<String> out = new ArrayList<>();
		if (body == null || body.emotes == null) return out;
		for (String id : body.emotes) {
			if (id != null && id.matches("[a-z0-9][a-z0-9_-]{0,39}") && !out.contains(id) && out.size() < 256) out.add(id);
		}
		return out;
	}

	/**
	 * {@code POST /v1/emotes/play}. Rückgabe: Dauer laut Server (ms, 0 = keine Angabe).
	 *
	 * @throws ApiException 403 {@code emote_locked}, 404 {@code emote_not_found}, 429 (höchstens 1 / 2 s)
	 */
	public int playEmote(String token, String emote) throws IOException, ApiException {
		PlayRequest body = new PlayRequest();
		body.emote = emote;
		PlayResponse response = parse(call("POST", "/v1/emotes/play", GSON.toJson(body), token, 200), PlayResponse.class);
		if (response == null || response.durationMs == null) return 0;
		return Math.max(0, Math.min(60_000, response.durationMs));
	}

	/** Umhang-PNG laden (mit ETag). 304 → Response mit leerem Körper. */
	public Http.Response texture(String url, String etag, String token) throws IOException, ApiException {
		if (!config.isApiUrl(url)) throw new ApiException(0, "foreign_url", 0);
		Http.Request request = new Http.Request("GET", url).header("Accept", "image/png");
		if (etag != null) request.header("If-None-Match", etag);
		// Eigene, noch nicht freigegebene Uploads liefert die API nur mit Token aus (sonst egal).
		if (token != null) request.header("Authorization", "Bearer " + token);
		request.maxBytes = 1024 * 1024;
		Http.Response response = http.send(request);
		if (response.status == 200 || response.status == 304) return response;
		throw new ApiException(response.status, errorCode(response), retryAfter(response));
	}

	// --- Hilfen ---

	private Http.Response call(String method, String path, String json, String token, int expected)
			throws IOException, ApiException {
		Http.Request request = new Http.Request(method, config.apiBase() + path).header("Accept", JSON);
		if (json != null) {
			request.header("Content-Type", JSON);
			request.body = json.getBytes(StandardCharsets.UTF_8);
		}
		if (token != null) request.header("Authorization", "Bearer " + token);
		Http.Response response = http.send(request);
		if (response.status != expected) {
			throw new ApiException(response.status, errorCode(response), retryAfter(response));
		}
		return response;
	}

	private static <T> T parse(Http.Response response, Class<T> type) throws ApiException {
		try {
			return GSON.fromJson(response.text(), type);
		} catch (JsonSyntaxException | IllegalStateException e) {
			throw new ApiException(response.status, "invalid_json", 0);
		}
	}

	static String errorCode(Http.Response response) {
		try {
			ErrorBody body = GSON.fromJson(response.text(), ErrorBody.class);
			if (body != null && body.error != null && body.error.code != null) return body.error.code;
		} catch (RuntimeException ignored) {
			// kein JSON (z. B. Cloudflare-Fehlerseite)
		}
		return "http_" + response.status;
	}

	/** {@code Retry-After} in Sekunden → ms, auf 1 s bis 15 min begrenzt; fehlt → 0. */
	static long retryAfter(Http.Response response) {
		String raw = response.header("Retry-After");
		if (raw == null) return 0;
		try {
			long seconds = Long.parseLong(raw.trim());
			return Math.max(1, Math.min(900, seconds)) * 1000L;
		} catch (NumberFormatException e) {
			return 60_000L;
		}
	}
}

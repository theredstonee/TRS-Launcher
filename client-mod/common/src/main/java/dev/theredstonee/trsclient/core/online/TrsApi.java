package dev.theredstonee.trsclient.core.online;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

	/**
	 * Größte Umhang-Textur: eigene Uploads bis 5 MB (512×256 je Frame, bis 16 Frames),
	 * mitgelieferte HD-Streifen ähnlich groß – passend zum {@code CapeDiskCache}.
	 */
	public static final int MAX_TEXTURE_BYTES = 8 * 1024 * 1024;

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
		LookupCosmetics cosmetics;
	}

	static final class LookupCosmetics {
		LookupCosmetic hat;
	}

	static final class LookupCosmetic {
		String id;
		String template;
		String url;
		Integer scale;
		Integer frames;
		Integer frameTimeMs;
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
		/** Quelle der Meldung: immer {@code client} (API.md §4.2). */
		String via;
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

	// Freunde (API.md §6)

	static final class FriendsResponse {
		List<FriendDto> friends;
		RequestsDto requests;
		/** Offene Umhang-Angebote an mich (API.md §5.10; fehlt bei alten APIs). */
		Integer capeOffers;
	}

	// Umhänge teilen (API.md §5.10)

	static final class OffersResponse {
		List<OfferDto> incoming;
	}

	static final class OfferDto {
		OfferCapeDto cape;
		UserDto from;
		UserDto creator;
		String createdAt;
	}

	static final class OfferCapeDto {
		String id;
		String name;
		String url;
		Integer width;
		Integer height;
		Integer frames;
	}

	static final class OfferRequest {
		String capeId;
		String friend;
	}

	static final class HoldersResponse {
		List<HolderDto> holders;
		Integer count;
		Integer limit;
	}

	static final class HolderDto {
		String uuid;
		String name;
		String status;
		UserDto grantedBy;
	}

	static final class FriendDto {
		String uuid;
		String name;
		String since;
		PresenceDto presence;
	}

	static final class PresenceDto {
		String state;
		Game game;
	}

	static final class RequestsDto {
		List<UserDto> incoming;
		List<UserDto> outgoing;
	}

	static final class UserDto {
		String uuid;
		String name;
		String createdAt;
		String since;
	}

	static final class BlocksResponse {
		List<UserDto> blocked;
	}

	static final class TargetRequest {
		String target;
	}

	static final class RequestResult {
		String status;
		UserDto user;
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
			HatInfo hat = null;
			if (p.cosmetics != null && p.cosmetics.hat != null) {
				LookupCosmetic h = p.cosmetics.hat;
				hat = HatInfo.of(h.id, h.template, h.url, h.scale, h.frames, h.frameTimeMs, config);
			}
			out.put(uuid, new PlayerInfo(Boolean.TRUE.equals(p.badge), cape, hat));
		}
		return out;
	}

	/**
	 * {@code POST /v1/presence}: "in-game" (via client) mit Version/Loader, Server nur wenn übergeben. Solange diese
	 * Meldung gilt, zeigen andere Spieler (die selbst im Spiel sind) das Live-TRS-Abzeichen.
	 */
	public void presence(String token, String version, String loader, String server) throws IOException, ApiException {
		Presence body = new Presence();
		body.state = "in-game";
		body.via = "client";
		Game game = new Game();
		game.version = version;
		game.loader = loader;
		game.server = server;
		body.game = game;
		call("POST", "/v1/presence", GSON.toJson(body), token, 200);
	}

	/** {@code POST /v1/presence} "offline" (via client): nimmt nur die Meldung des Mods zurück, nicht die des Launchers. */
	public void presenceOffline(String token) throws IOException, ApiException {
		Presence body = new Presence();
		body.state = "offline";
		body.via = "client";
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

	// --- Freunde (API.md §6) ---

	/** {@code GET /v1/friends}: Freunde mit Online-Status und offene Anfragen, bereinigt. */
	public FriendsView friends(String token) throws IOException, ApiException {
		FriendsResponse body = parse(call("GET", "/v1/friends", null, token, 200), FriendsResponse.class);
		List<FriendsView.Friend> friends = new ArrayList<>();
		List<FriendsView.User> incoming = new ArrayList<>();
		List<FriendsView.User> outgoing = new ArrayList<>();
		if (body != null && body.friends != null) {
			for (FriendDto f : body.friends) {
				if (f == null || friends.size() >= 500) continue;
				String uuid = Uuids.normalize(f.uuid);
				String name = FriendsView.name(f.name);
				if (uuid == null || name == null) continue;
				String state = null;
				String version = null;
				String loader = null;
				String server = null;
				if (f.presence != null) {
					state = FriendsView.state(f.presence.state);
					if (state != null && f.presence.game != null) {
						version = FriendsView.version(f.presence.game.version);
						loader = FriendsView.loader(f.presence.game.loader);
						server = FriendsView.server(f.presence.game.server);
					}
				}
				friends.add(new FriendsView.Friend(uuid, name, state, version, loader, server));
			}
		}
		if (body != null && body.requests != null) {
			users(body.requests.incoming, incoming);
			users(body.requests.outgoing, outgoing);
		}
		int offers = body == null || body.capeOffers == null ? 0 : Math.max(0, Math.min(1000, body.capeOffers));
		return new FriendsView(FriendsView.sorted(friends), incoming, outgoing, offers,
				Collections.<CapeShare.Offer>emptyList());
	}

	// --- Umhänge teilen (API.md §5.10) ---

	/** {@code GET /v1/cape-offers}: offene Angebote an mich, bereinigt (höchstens 100). */
	public List<CapeShare.Offer> capeOffers(String token) throws IOException, ApiException {
		OffersResponse body = parse(call("GET", "/v1/cape-offers", null, token, 200), OffersResponse.class);
		List<CapeShare.Offer> out = new ArrayList<>();
		if (body == null || body.incoming == null) return out;
		for (OfferDto o : body.incoming) {
			if (o == null || o.cape == null || o.from == null || o.creator == null || out.size() >= 100) continue;
			if (!CapeShare.validCapeId(o.cape.id)) continue;
			String from = Uuids.normalize(o.from.uuid);
			String fromName = FriendsView.name(o.from.name);
			String creator = Uuids.normalize(o.creator.uuid);
			String creatorName = FriendsView.name(o.creator.name);
			if (from == null || fromName == null || creator == null || creatorName == null) continue;
			String url = o.cape.url != null && config.isApiUrl(o.cape.url) ? o.cape.url : null;
			int w = o.cape.width == null ? 0 : o.cape.width;
			int h = o.cape.height == null ? 0 : o.cape.height;
			int frames = o.cape.frames == null ? 1 : Math.max(1, Math.min(64, o.cape.frames));
			out.add(new CapeShare.Offer(o.cape.id, CapeShare.capeName(o.cape.name, o.cape.id), from, fromName, creator,
					creatorName, url, w, h, frames, FriendsView.time(o.createdAt)));
		}
		return out;
	}

	/** {@code POST /v1/cape-offers}: eigenen (freigegebenen) oder angenommenen geteilten Umhang einem Freund anbieten. */
	public void offerCape(String token, String capeId, String friend) throws IOException, ApiException {
		OfferRequest body = new OfferRequest();
		body.capeId = capePath(capeId);
		body.friend = path(friend);
		call("POST", "/v1/cape-offers", GSON.toJson(body), token, 201, 200);
	}

	/** {@code POST /v1/cape-offers/{capeId}/accept}. */
	public void acceptCapeOffer(String token, String capeId) throws IOException, ApiException {
		call("POST", "/v1/cape-offers/" + capePath(capeId) + "/accept", null, token, 200);
	}

	/** {@code POST /v1/cape-offers/{capeId}/decline}. */
	public void declineCapeOffer(String token, String capeId) throws IOException, ApiException {
		call("POST", "/v1/cape-offers/" + capePath(capeId) + "/decline", null, token, 204, 200);
	}

	/** {@code GET /v1/capes/{id}/holders}: wer den Umhang von mir hat (Ersteller: alle, sonst der eigene Ast). */
	public CapeShare.Holders capeHolders(String token, String capeId) throws IOException, ApiException {
		String id = capePath(capeId);
		HoldersResponse body = parse(call("GET", "/v1/capes/" + id + "/holders", null, token, 200), HoldersResponse.class);
		List<CapeShare.Holder> out = new ArrayList<>();
		if (body != null && body.holders != null) {
			for (HolderDto h : body.holders) {
				if (h == null || h.grantedBy == null || out.size() >= 100) continue;
				String uuid = Uuids.normalize(h.uuid);
				String name = FriendsView.name(h.name);
				String by = Uuids.normalize(h.grantedBy.uuid);
				String byName = FriendsView.name(h.grantedBy.name);
				boolean offered = "offered".equals(h.status);
				if (uuid == null || name == null || by == null || byName == null) continue;
				if (!offered && !"accepted".equals(h.status)) continue;
				out.add(new CapeShare.Holder(uuid, name, offered, by, byName));
			}
		}
		int count = body == null || body.count == null ? out.size() : Math.max(0, Math.min(1000, body.count));
		int limit = body == null || body.limit == null ? 0 : Math.max(0, Math.min(1000, body.limit));
		return new CapeShare.Holders(id, out, count, limit);
	}

	/** {@code DELETE /v1/capes/{id}/holders/{uuid}}: entziehen/zurückziehen (samt Weitergegebenem); eigene UUID = zurückgeben. */
	public void revokeCapeShare(String token, String capeId, String holder) throws IOException, ApiException {
		call("DELETE", "/v1/capes/" + capePath(capeId) + "/holders/" + path(holder), null, token, 204, 200);
	}

	/** Umhang-ID für den Pfad (nur gültige IDs – nie beliebiger Text in der Adresse). */
	private static String capePath(String capeId) throws ApiException {
		if (!CapeShare.validCapeId(capeId)) throw new ApiException(0, "invalid_request", 0);
		return capeId;
	}

	private static void users(List<UserDto> in, List<FriendsView.User> out) {
		if (in == null) return;
		for (UserDto u : in) {
			if (u == null || out.size() >= 500) continue;
			String uuid = Uuids.normalize(u.uuid);
			String name = FriendsView.name(u.name);
			if (uuid == null || name == null) continue;
			out.add(new FriendsView.User(uuid, name, FriendsView.time(u.createdAt != null ? u.createdAt : u.since)));
		}
	}

	/** {@code GET /v1/blocks}. */
	public List<FriendsView.User> blocks(String token) throws IOException, ApiException {
		BlocksResponse body = parse(call("GET", "/v1/blocks", null, token, 200), BlocksResponse.class);
		List<FriendsView.User> out = new ArrayList<>();
		if (body != null) users(body.blocked, out);
		return out;
	}

	/**
	 * {@code POST /v1/friends/requests}: Anfrage an einen Namen oder eine UUID. Rückgabe {@code "sent"} (201) oder
	 * {@code "accepted"} (200 – sie hatte schon angefragt, jetzt befreundet).
	 */
	public String requestFriend(String token, String target) throws IOException, ApiException {
		TargetRequest body = new TargetRequest();
		body.target = target;
		Http.Response response = call("POST", "/v1/friends/requests", GSON.toJson(body), token, 201, 200);
		RequestResult result = parse(response, RequestResult.class);
		if (response.status == 200 || (result != null && "accepted".equals(result.status))) return "accepted";
		return "sent";
	}

	/** {@code POST /v1/friends/requests/{uuid}/accept}. */
	public void acceptFriend(String token, String uuid) throws IOException, ApiException {
		call("POST", "/v1/friends/requests/" + path(uuid) + "/accept", null, token, 200);
	}

	/** {@code POST /v1/friends/requests/{uuid}/decline}. */
	public void declineFriend(String token, String uuid) throws IOException, ApiException {
		call("POST", "/v1/friends/requests/" + path(uuid) + "/decline", null, token, 204, 200);
	}

	/** {@code DELETE /v1/friends/requests/{uuid}}: eigene Anfrage zurückziehen. */
	public void cancelRequest(String token, String uuid) throws IOException, ApiException {
		call("DELETE", "/v1/friends/requests/" + path(uuid), null, token, 204, 200);
	}

	/** {@code DELETE /v1/friends/{uuid}}. */
	public void removeFriend(String token, String uuid) throws IOException, ApiException {
		call("DELETE", "/v1/friends/" + path(uuid), null, token, 204, 200);
	}

	/** {@code POST /v1/blocks}. */
	public void block(String token, String target) throws IOException, ApiException {
		TargetRequest body = new TargetRequest();
		body.target = target;
		call("POST", "/v1/blocks", GSON.toJson(body), token, 201, 200);
	}

	/** {@code DELETE /v1/blocks/{uuid}}. */
	public void unblock(String token, String uuid) throws IOException, ApiException {
		call("DELETE", "/v1/blocks/" + path(uuid), null, token, 204, 200);
	}

	/** UUID für den Pfad (nur 32 Hex-Zeichen – nie beliebiger Text in der Adresse). */
	private static String path(String uuid) throws ApiException {
		String n = Uuids.normalize(uuid);
		if (n == null) throw new ApiException(0, "invalid_request", 0);
		return n;
	}

	/** Umhang-PNG laden (mit ETag). 304 → Response mit leerem Körper. */
	public Http.Response texture(String url, String etag, String token) throws IOException, ApiException {
		if (!config.isApiUrl(url)) throw new ApiException(0, "foreign_url", 0);
		Http.Request request = new Http.Request("GET", url).header("Accept", "image/png");
		if (etag != null) request.header("If-None-Match", etag);
		// Eigene, noch nicht freigegebene Uploads liefert die API nur mit Token aus (sonst egal).
		if (token != null) request.header("Authorization", "Bearer " + token);
		request.maxBytes = MAX_TEXTURE_BYTES;
		Http.Response response = http.send(request);
		if (response.status == 200 || response.status == 304) return response;
		throw new ApiException(response.status, errorCode(response), retryAfter(response));
	}

	// --- Hilfen ---

	private Http.Response call(String method, String path, String json, String token, int expected)
			throws IOException, ApiException {
		return call(method, path, json, token, expected, expected);
	}

	/** Wie {@link #call(String, String, String, String, int)}, aber zwei erlaubte Statuscodes. */
	private Http.Response call(String method, String path, String json, String token, int expected, int alsoOk)
			throws IOException, ApiException {
		Http.Request request = new Http.Request(method, config.apiBase() + path).header("Accept", JSON);
		if (json != null) {
			request.header("Content-Type", JSON);
			request.body = json.getBytes(StandardCharsets.UTF_8);
		}
		if (token != null) request.header("Authorization", "Bearer " + token);
		Http.Response response = http.send(request);
		if (response.status != expected && response.status != alsoOk) {
			throw new ApiException(response.status, errorCode(response), retryAfter(response),
					response.status == 403 ? response.text() : null);
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

	public static String errorCode(Http.Response response) {
		try {
			ErrorBody body = GSON.fromJson(response.text(), ErrorBody.class);
			if (body != null && body.error != null && body.error.code != null) return body.error.code;
		} catch (RuntimeException ignored) {
			// kein JSON (z. B. Cloudflare-Fehlerseite)
		}
		return "http_" + response.status;
	}

	/** {@code Retry-After} in Sekunden → ms, auf 1 s bis 15 min begrenzt; fehlt → 0. */
	public static long retryAfter(Http.Response response) {
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

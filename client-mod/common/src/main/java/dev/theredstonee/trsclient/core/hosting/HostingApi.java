package dev.theredstonee.trsclient.core.hosting;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsApi;
import dev.theredstonee.trsclient.core.social.ChatJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Welt-Hosting-Endpunkte der TRS API (API.md §21). Blockierend – nur aus Hintergrund-Threads. Pfade enthalten nur
 * geprüfte IDs; Antworten werden in {@link Rooms}-Ansichten bereinigt.
 */
public final class HostingApi {
	private static final String JSON = "application/json";
	static final Gson GSON = new Gson();

	private final Http http;
	private final String base;

	public HostingApi(Http http, String apiBase) {
		this.http = http;
		this.base = apiBase;
	}

	// --- DTOs ---

	static final class UserDto {
		String uuid;
		String name;
	}

	static final class MemberDto {
		String uuid;
		String name;
		String state;
		String since;
	}

	static final class RoomDto {
		String id;
		String code;
		String name;
		UserDto host;
		String mcVersion;
		String loader;
		Integer maxPlayers;
		String gameMode;
		Boolean pvp;
		Boolean cheats;
		Boolean open;
		String visibility;
		Integer players;
		String expiresAt;
		List<MemberDto> members;
		String myState;
	}

	static final class RelayDto {
		String host;
		Integer tcpPort;
		Integer udpPort;
		String token;
		String expiresAt;
	}

	static final class ConnectBody {
		RoomDto room;
		String role;
		RelayDto relay;
		List<String> stun;
		String status;
	}

	static final class RoomsBody {
		List<RoomDto> rooms;
	}

	static final class BansBody {
		List<MemberDto> bans;
	}

	static final class HeartbeatBody {
		String expiresAt;
	}

	static final class SignalBody {
		Boolean delivered;
	}

	// --- Ergebnisse ---

	/** Raum + Verbindungsdaten (Erstellen, Beitreten angenommen). */
	public static final class Opened {
		public final Rooms.Room room;
		public final Rooms.ConnectInfo connect;

		Opened(Rooms.Room room, Rooms.ConnectInfo connect) {
			this.room = room;
			this.connect = connect;
		}
	}

	/** Ergebnis von {@code POST /join}: angenommen (mit Verbindung) oder angefragt (warten). */
	public static final class Joined {
		public final boolean accepted;
		public final Rooms.Room room;
		public final Rooms.ConnectInfo connect;

		Joined(boolean accepted, Rooms.Room room, Rooms.ConnectInfo connect) {
			this.accepted = accepted;
			this.room = room;
			this.connect = connect;
		}
	}

	/** Einstellungen beim Erstellen/Ändern (null = nicht senden). */
	public static final class Settings {
		public String name;
		public String mcVersion;
		public String loader;
		public Integer maxPlayers;
		public String gameMode;
		public Boolean pvp;
		public Boolean cheats;
		public Boolean open;
		public String visibility;

		String json() {
			JsonObject o = new JsonObject();
			if (name != null) o.addProperty("name", name);
			if (mcVersion != null) o.addProperty("mcVersion", mcVersion);
			if (loader != null) o.addProperty("loader", loader);
			if (maxPlayers != null) o.addProperty("maxPlayers", maxPlayers);
			if (gameMode != null) o.addProperty("gameMode", gameMode);
			if (pvp != null) o.addProperty("pvp", pvp);
			if (cheats != null) o.addProperty("cheats", cheats);
			if (open != null) o.addProperty("open", open);
			if (visibility != null) o.addProperty("visibility", visibility);
			return GSON.toJson(o);
		}
	}

	// --- Host (§21.2) ---

	public Opened create(String token, Settings s) throws IOException, ApiException {
		return opened(parse(call("POST", "/v1/hosting/rooms", s.json(), token, 201), ConnectBody.class));
	}

	/** Eigener offener Raum (nach Neustart) oder null. */
	public Rooms.Room mine(String token) throws IOException, ApiException {
		RoomsBody b = parse(call("GET", "/v1/hosting/rooms/mine", null, token, 200), RoomsBody.class);
		if (b == null || b.rooms == null) return null;
		for (RoomDto d : b.rooms) {
			Rooms.Room r = room(d);
			if (r != null) return r;
		}
		return null;
	}

	public Rooms.Room update(String token, String roomId, Settings s) throws IOException, ApiException {
		ConnectBody b = parse(call("PATCH", "/v1/hosting/rooms/" + id(roomId), s.json(), token, 200), ConnectBody.class);
		return b == null ? null : room(b.room);
	}

	/** Herzschlag mit Spielerzahl (inkl. Host); liefert expiresAt (ms) oder 0. */
	public long heartbeat(String token, String roomId, int players) throws IOException, ApiException {
		JsonObject o = new JsonObject();
		o.addProperty("players", Math.max(1, Math.min(Rooms.MAX_PLAYERS, players)));
		HeartbeatBody b = parse(call("POST", "/v1/hosting/rooms/" + id(roomId) + "/heartbeat", GSON.toJson(o), token, 200),
				HeartbeatBody.class);
		return b == null ? 0 : ChatJson.time(b.expiresAt);
	}

	public void close(String token, String roomId) throws IOException, ApiException {
		call("DELETE", "/v1/hosting/rooms/" + id(roomId), null, token, 204, 404);
	}

	public void invite(String token, String roomId, String uuid, boolean chat) throws IOException, ApiException {
		JsonObject o = new JsonObject();
		o.addProperty("uuid", uuid(uuid));
		if (chat) o.addProperty("chat", true);
		call("POST", "/v1/hosting/rooms/" + id(roomId) + "/invites", GSON.toJson(o), token, 201, 200);
	}

	public void revokeInvite(String token, String roomId, String uuid) throws IOException, ApiException {
		call("DELETE", "/v1/hosting/rooms/" + id(roomId) + "/invites/" + uuid(uuid), null, token, 204, 404);
	}

	public void accept(String token, String roomId, String uuid) throws IOException, ApiException {
		call("POST", "/v1/hosting/rooms/" + id(roomId) + "/requests/" + uuid(uuid) + "/accept", null, token, 200);
	}

	public void decline(String token, String roomId, String uuid) throws IOException, ApiException {
		call("POST", "/v1/hosting/rooms/" + id(roomId) + "/requests/" + uuid(uuid) + "/decline", null, token, 204, 404);
	}

	public void kick(String token, String roomId, String uuid, boolean ban, boolean remember)
			throws IOException, ApiException {
		JsonObject o = new JsonObject();
		if (ban) o.addProperty("ban", true);
		if (remember) o.addProperty("remember", true);
		call("POST", "/v1/hosting/rooms/" + id(roomId) + "/members/" + uuid(uuid) + "/kick", GSON.toJson(o), token, 204,
				404);
	}

	public void unban(String token, String roomId, String uuid) throws IOException, ApiException {
		call("DELETE", "/v1/hosting/rooms/" + id(roomId) + "/bans/" + uuid(uuid), null, token, 204, 404);
	}

	/** Dauerhafte Sperrliste. */
	public List<Rooms.Member> bans(String token) throws IOException, ApiException {
		BansBody b = parse(call("GET", "/v1/hosting/bans", null, token, 200), BansBody.class);
		List<Rooms.Member> out = new ArrayList<Rooms.Member>();
		if (b != null && b.bans != null) {
			for (MemberDto d : b.bans) {
				String u = Rooms.uuid(d.uuid);
				if (u != null && out.size() < 500) out.add(new Rooms.Member(u, Rooms.player(d.name), "banned", ChatJson.time(d.since)));
			}
		}
		return out;
	}

	public void removeBan(String token, String uuid) throws IOException, ApiException {
		call("DELETE", "/v1/hosting/bans/" + uuid(uuid), null, token, 204, 404);
	}

	// --- Gäste (§21.3) ---

	public List<Rooms.Room> friendsRooms(String token) throws IOException, ApiException {
		return rooms(parse(call("GET", "/v1/hosting/friends-rooms", null, token, 200), RoomsBody.class));
	}

	public Rooms.Room room(String token, String roomId) throws IOException, ApiException {
		ConnectBody b = parse(call("GET", "/v1/hosting/rooms/" + id(roomId), null, token, 200), ConnectBody.class);
		return b == null ? null : room(b.room);
	}

	/** Beitreten per Raum-ID (roomId != null) oder Code. */
	public Joined join(String token, String roomId, String code) throws IOException, ApiException {
		JsonObject o = new JsonObject();
		if (roomId != null) o.addProperty("roomId", id(roomId));
		else {
			String c = Rooms.normalizeCode(code);
			if (c == null) throw new ApiException(400, "invalid_code", 0);
			o.addProperty("code", c);
		}
		Http.Response r = call("POST", "/v1/hosting/join", GSON.toJson(o), token, 200, 202);
		ConnectBody b = parse(r, ConnectBody.class);
		if (b == null) throw new ApiException(r.status, "invalid_json", 0);
		Rooms.Room room = room(b.room);
		if (r.status == 202 || "requested".equals(b.status)) return new Joined(false, room, null);
		return new Joined(true, room, connectInfo(b));
	}

	public void leave(String token, String roomId) throws IOException, ApiException {
		call("POST", "/v1/hosting/rooms/" + id(roomId) + "/leave", null, token, 204, 404);
	}

	/** Frisches Relay-Token (vor jedem (Wieder-)Verbinden). */
	public Rooms.ConnectInfo connect(String token, String roomId) throws IOException, ApiException {
		ConnectBody b = parse(call("POST", "/v1/hosting/rooms/" + id(roomId) + "/connect", null, token, 200),
				ConnectBody.class);
		Rooms.ConnectInfo c = b == null ? null : connectInfo(b);
		if (c == null) throw new ApiException(200, "invalid_json", 0);
		return c;
	}

	// --- Signalisierung (§21.4) ---

	public boolean signal(String token, String roomId, String to, String kind, String sid, String data)
			throws IOException, ApiException {
		if (!kind.matches("offer|answer|candidate|bye")) throw new IllegalArgumentException("kind");
		if (sid != null && !sid.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("sid");
		if (data != null && data.length() > 4096) throw new ApiException(400, "signal_too_large", 0);
		JsonObject o = new JsonObject();
		o.addProperty("to", uuid(to));
		o.addProperty("kind", kind);
		if (sid != null) o.addProperty("sid", sid);
		o.addProperty("data", data == null ? "" : data);
		SignalBody b = parse(call("POST", "/v1/hosting/rooms/" + id(roomId) + "/signal", GSON.toJson(o), token, 200),
				SignalBody.class);
		return b != null && Boolean.TRUE.equals(b.delivered);
	}

	// --- Umwandeln ---

	static Opened opened(ConnectBody b) throws ApiException {
		Rooms.Room room = b == null ? null : room(b.room);
		Rooms.ConnectInfo c = b == null ? null : connectInfo(b);
		if (room == null || c == null) throw new ApiException(200, "invalid_json", 0);
		return new Opened(room, c);
	}

	static List<Rooms.Room> rooms(RoomsBody b) {
		List<Rooms.Room> out = new ArrayList<Rooms.Room>();
		if (b != null && b.rooms != null) {
			for (RoomDto d : b.rooms) {
				Rooms.Room r = room(d);
				if (r != null && out.size() < 100) out.add(r);
			}
		}
		return out;
	}

	static Rooms.Room room(RoomDto d) {
		if (d == null || !Rooms.validRoomId(d.id) || d.host == null) return null;
		String host = Rooms.uuid(d.host.uuid);
		if (host == null) return null;
		String code = Rooms.normalizeCode(d.code);
		List<Rooms.Member> members = new ArrayList<Rooms.Member>();
		if (d.members != null) {
			for (MemberDto m : d.members) {
				String u = Rooms.uuid(m.uuid);
				if (u == null || m.state == null || !m.state.matches("invited|requested|accepted|banned")) continue;
				if (members.size() < 200) members.add(new Rooms.Member(u, Rooms.player(m.name), m.state, ChatJson.time(m.since)));
			}
		}
		String vis = d.visibility != null && d.visibility.matches("friends|invited") ? d.visibility : null;
		String my = d.myState != null && d.myState.matches("invited|requested|accepted") ? d.myState : null;
		return new Rooms.Room(d.id, code, Rooms.name(d.name), host, Rooms.player(d.host.name), Rooms.version(d.mcVersion),
				Rooms.loader(d.loader), Rooms.clamp(d.maxPlayers, Rooms.MIN_PLAYERS, Rooms.MAX_PLAYERS, 8),
				Rooms.gameMode(d.gameMode), !Boolean.FALSE.equals(d.pvp), Boolean.TRUE.equals(d.cheats),
				!Boolean.FALSE.equals(d.open), vis, Rooms.clamp(d.players, 1, Rooms.MAX_PLAYERS, 1),
				ChatJson.time(d.expiresAt), members, my);
	}

	static Rooms.ConnectInfo connectInfo(ConnectBody b) {
		RelayDto r = b.relay;
		if (r == null || r.host == null || r.token == null) return null;
		if (!r.host.matches("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")) return null;
		if (r.token.length() > 512 || !r.token.matches("trsr1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) return null;
		int tcp = r.tcpPort == null ? 25503 : r.tcpPort;
		int udp = r.udpPort == null ? 25504 : r.udpPort;
		if (tcp < 1 || tcp > 65535 || udp < 1 || udp > 65535) return null;
		List<String> stun = new ArrayList<String>();
		if (b.stun != null) {
			for (String s : b.stun) {
				if (s != null && s.matches("[A-Za-z0-9.-]{1,253}:[0-9]{1,5}") && stun.size() < 4) stun.add(s);
			}
		}
		String role = "host".equals(b.role) ? "host" : "guest";
		return new Rooms.ConnectInfo(role, r.host, tcp, udp, r.token, ChatJson.time(r.expiresAt), stun);
	}

	// --- HTTP ---

	private static String id(String roomId) throws ApiException {
		if (!Rooms.validRoomId(roomId)) throw new ApiException(404, "room_not_found", 0);
		return roomId;
	}

	private static String uuid(String u) throws ApiException {
		String n = Rooms.uuid(u);
		if (n == null) throw new ApiException(404, "player_not_found", 0);
		return n;
	}

	private Http.Response call(String method, String path, String json, String token, int expected)
			throws IOException, ApiException {
		return call(method, path, json, token, expected, expected);
	}

	private Http.Response call(String method, String path, String json, String token, int expected, int alsoOk)
			throws IOException, ApiException {
		Http.Request request = new Http.Request(method, base + path).header("Accept", JSON);
		if (json != null) {
			request.header("Content-Type", JSON);
			request.body = json.getBytes(StandardCharsets.UTF_8);
		} else if (method.equals("POST")) {
			request.header("Content-Type", JSON);
			request.body = "{}".getBytes(StandardCharsets.UTF_8);
		}
		if (token != null) request.header("Authorization", "Bearer " + token);
		request.maxBytes = 512 * 1024;
		Http.Response response = http.send(request);
		if (response.status != expected && response.status != alsoOk) {
			throw new ApiException(response.status, TrsApi.errorCode(response), TrsApi.retryAfter(response));
		}
		return response;
	}

	static <T> T parse(Http.Response response, Class<T> type) throws ApiException {
		if (response.body.length == 0) return null;
		try {
			return GSON.fromJson(response.text(), type);
		} catch (JsonSyntaxException | IllegalStateException | NumberFormatException e) {
			throw new ApiException(response.status, "invalid_json", 0);
		}
	}
}

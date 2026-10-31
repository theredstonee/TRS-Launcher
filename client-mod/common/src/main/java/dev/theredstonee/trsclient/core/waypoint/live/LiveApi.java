package dev.theredstonee.trsclient.core.waypoint.live;

import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsApi;
import dev.theredstonee.trsclient.core.online.Uuids;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Live-Wegpunkte der TRS API (API.md §41), alles unter {@code /v1/waypoint-share}:
 *
 * <pre>
 * GET    /grants                        → { grants: [uuid], grantedToMe: [uuid] }
 * PUT    /grants/{uuid}                 (nur Freunde, sonst 403 not_friends/blocked)
 * DELETE /grants/{uuid}
 * PUT    /session { world, dimension }  → { peers: { send, receive: [uuid] } }
 * DELETE /session
 * POST   /position { x, y, z, dimension, yaw }   (nur bei send &gt; 0, höchstens alle 3 s)
 * PUT    /waypoints { world, rev?, waypoints: [...] } → { rev }   (409 rev_mismatch)
 * GET    /waypoints?world=…            → { rev, waypoints }
 * GET    /friends?world=…              → { owners: [{ uuid, name, rev, waypoints, position? }] }
 * </pre>
 *
 * Blockierend – nur im Hintergrund-Thread. Antworttexte werden roh zurückgegeben (Auswertung in {@link LiveJson}).
 * Fehler als {@link ApiException} (404/405/501 = Server kennt die Route nicht → {@code unsupported}).
 */
public final class LiveApi {
	public static final String BASE = "/v1/waypoint-share";
	static final int MAX_RESPONSE = 512 * 1024;

	private final Http http;
	private final String apiBase;

	public LiveApi(Http http, String apiBase) {
		this.http = http;
		this.apiBase = apiBase;
	}

	public String grants(String token) throws IOException, ApiException {
		return send("GET", "/grants", null, token);
	}

	public void grant(String token, String friend, boolean on) throws IOException, ApiException {
		String u = Uuids.normalize(friend);
		if (u == null) throw new ApiException(0, "invalid_request", 0);
		send(on ? "PUT" : "DELETE", "/grants/" + u, on ? "{}" : null, token);
	}

	public String session(String token, LiveWorld world, String dimension) throws IOException, ApiException {
		return send("PUT", "/session", LiveJson.sessionBody(world, dimension), token);
	}

	public void endSession(String token) throws IOException, ApiException {
		send("DELETE", "/session", null, token);
	}

	public void position(String token, String body) throws IOException, ApiException {
		send("POST", "/position", body, token);
	}

	public String putWaypoints(String token, String body) throws IOException, ApiException {
		return send("PUT", "/waypoints", body, token);
	}

	public String ownWaypoints(String token, LiveWorld world) throws IOException, ApiException {
		return send("GET", "/waypoints?world=" + enc(world.json()), null, token);
	}

	public String friends(String token, LiveWorld world) throws IOException, ApiException {
		return send("GET", "/friends?world=" + enc(world.json()), null, token);
	}

	private String send(String method, String path, String json, String token) throws IOException, ApiException {
		Http.Request r = new Http.Request(method, apiBase + BASE + path).header("Accept", "application/json");
		if (json != null) {
			r.header("Content-Type", "application/json");
			r.body = json.getBytes(StandardCharsets.UTF_8);
		}
		if (token != null) r.header("Authorization", "Bearer " + token);
		r.maxBytes = MAX_RESPONSE;
		Http.Response res = http.send(r);
		if (res.status >= 200 && res.status < 300) return res.body == null ? "" : res.text();
		if (res.status == 404 || res.status == 405 || res.status == 501) {
			// Kennt der Server §41 (noch) nicht – oder Freund/Welt unbekannt: Code aus dem Körper, sonst „unsupported“.
			String code = TrsApi.errorCode(res);
			throw new ApiException(res.status, code.startsWith("http_") ? "unsupported" : code, TrsApi.retryAfter(res));
		}
		// 409 rev_mismatch trägt den aktuellen Stand im Fehler ({"error":{"code","message","rev"}}).
		throw new ApiException(res.status, TrsApi.errorCode(res), TrsApi.retryAfter(res), res.status == 409 ? res.text() : null);
	}

	static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}
}

package dev.theredstonee.trsclient.core.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Attrappe der Sync-Endpunkte (API.md §17.1/§17.3): ein Konto je Token, Dokumente {@code client} und
 * {@code settings}, „neuere Änderung gewinnt“ mit {@code 409 stale} + {@code current}, 64-KiB-Grenze.
 */
final class FakeSyncServer implements Http {
	static final class Stored {
		JsonObject data;
		String updatedAt;
	}

	final Map<String, Stored> client = new HashMap<String, Stored>();
	final Map<String, Stored> settings = new HashMap<String, Stored>();
	final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
	boolean offline;
	int putClientCount;
	/** Vor dem nächsten PUT (nach dem GET) schreibt „ein anderer PC“ dieses Dokument. */
	Runnable beforePut;

	@Override
	@SuppressWarnings("deprecation")
	public Response send(Request r) throws IOException {
		if (offline) throw new IOException("offline");
		String path = r.url.replaceFirst("^https?://[^/]+", "");
		calls.add(r.method + " " + path);
		String auth = r.headers.get("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) return json(401, "{\"error\":{\"code\":\"unauthorized\"}}");
		String account = auth.substring(7);
		if (r.method.equals("GET") && path.equals("/v1/me/sync")) {
			JsonObject o = new JsonObject();
			o.add("client", doc(client.get(account)));
			o.add("settings", doc(settings.get(account)));
			return json(200, o.toString());
		}
		if (r.method.equals("PUT") && (path.equals("/v1/me/sync/client") || path.equals("/v1/me/sync/settings"))) {
			if (beforePut != null) {
				Runnable b = beforePut;
				beforePut = null;
				b.run();
			}
			boolean isClient = path.endsWith("client");
			if (isClient) putClientCount++;
			String bodyText = new String(r.body, StandardCharsets.UTF_8);
			if (bodyText.getBytes(StandardCharsets.UTF_8).length > 96 * 1024) return json(413, "{\"error\":{\"code\":\"payload_too_large\"}}");
			JsonObject body = new JsonParser().parse(bodyText).getAsJsonObject();
			JsonObject data = body.getAsJsonObject("data");
			String at = body.get("updatedAt").getAsString();
			if (data.toString().getBytes(StandardCharsets.UTF_8).length > 64 * 1024) return json(413, "{\"error\":{\"code\":\"payload_too_large\"}}");
			Map<String, Stored> store = isClient ? client : settings;
			Stored cur = store.get(account);
			if (cur != null && SyncTime.parse(cur.updatedAt) > SyncTime.parse(at)) {
				JsonObject err = new JsonObject();
				JsonObject e = new JsonObject();
				e.addProperty("code", "stale");
				e.add("current", doc(cur));
				err.add("error", e);
				return json(409, err.toString());
			}
			Stored s = new Stored();
			s.data = data;
			s.updatedAt = SyncTime.iso(SyncTime.parse(at));
			store.put(account, s);
			JsonObject o = new JsonObject();
			o.add(isClient ? "client" : "settings", doc(s));
			return json(200, o.toString());
		}
		return json(404, "{\"error\":{\"code\":\"not_found\"}}");
	}

	void put(String account, JsonObject data, long at) {
		Stored s = new Stored();
		s.data = data;
		s.updatedAt = SyncTime.iso(at);
		client.put(account, s);
	}

	JsonObject clientDoc(String account) {
		Stored s = client.get(account);
		return s == null ? null : s.data;
	}

	private static JsonElement doc(Stored s) {
		if (s == null) return com.google.gson.JsonNull.INSTANCE;
		JsonObject o = new JsonObject();
		o.add("data", s.data);
		o.addProperty("updatedAt", s.updatedAt);
		return o;
	}

	private static Response json(int status, String body) {
		Map<String, String> h = new HashMap<String, String>();
		h.put("content-type", "application/json");
		return new Response(status, h, body.getBytes(StandardCharsets.UTF_8));
	}
}

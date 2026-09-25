package dev.theredstonee.trsclient.core.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Die Sync-Endpunkte der TRS API, die der Client braucht (API.md §17): {@code GET /v1/me/sync} (liest die Dokumente
 * {@code client} und {@code settings}), {@code PUT /v1/me/sync/client} und {@code PUT /v1/me/sync/settings}.
 * Blockierend – nur im Sync-Thread aufrufen. Das Token wird nie geloggt.
 */
public final class SyncApi {
	/** Antwort von {@code GET /v1/me/sync} kann Skins, Presets usw. enthalten – großzügig begrenzen. */
	static final int MAX_RESPONSE = 512 * 1024;

	/** Ein Dokument {@code {data, updatedAt}}. */
	public static final class Doc {
		public final JsonObject data;
		public final String updatedAt;

		public Doc(JsonObject data, String updatedAt) {
			this.data = data;
			this.updatedAt = updatedAt;
		}

		public long updatedAtMillis() {
			return SyncTime.parse(updatedAt);
		}
	}

	/** Die für den Client wichtigen Teile von {@code GET /v1/me/sync} (je null = noch nie geschrieben). */
	public static final class Snapshot {
		public final Doc client;
		public final Doc settings;

		public Snapshot(Doc client, Doc settings) {
			this.client = client;
			this.settings = settings;
		}
	}

	/** {@code 409 stale}: auf dem Server liegt ein neuerer Stand ({@link #current}). */
	public static final class StaleException extends Exception {
		public final Doc current;

		public StaleException(Doc current) {
			super("stale");
			this.current = current;
		}
	}

	private final Http http;
	private final String base;

	public SyncApi(Http http, String apiBase) {
		this.http = http;
		this.base = apiBase;
	}

	public Snapshot get(String token) throws IOException, ApiException {
		Http.Response r = send("GET", "/v1/me/sync", null, token);
		if (r.status != 200) throw error(r);
		JsonObject body = parse(r);
		return new Snapshot(doc(body.get("client")), doc(body.get("settings")));
	}

	/** {@code PUT /v1/me/sync/client}. */
	public Doc putClient(String token, JsonObject data, String updatedAt) throws IOException, ApiException, StaleException {
		return put(token, "/v1/me/sync/client", "client", data, updatedAt);
	}

	/** {@code PUT /v1/me/sync/settings} (nur theme/accent/language). */
	public Doc putSettings(String token, JsonObject data, String updatedAt) throws IOException, ApiException, StaleException {
		return put(token, "/v1/me/sync/settings", "settings", data, updatedAt);
	}

	private Doc put(String token, String path, String key, JsonObject data, String updatedAt)
			throws IOException, ApiException, StaleException {
		JsonObject body = new JsonObject();
		body.add("data", data);
		body.addProperty("updatedAt", updatedAt);
		Http.Response r = send("PUT", path, ClientDoc.json(body), token);
		if (r.status == 200) {
			Doc d = doc(parse(r).get(key));
			return d != null ? d : new Doc(data, updatedAt);
		}
		if (r.status == 409) {
			JsonObject err = parseQuiet(r);
			JsonObject e = ClientDoc.object(err, "error");
			if (e != null && e.get("code") != null && "stale".equals(e.get("code").getAsString())) {
				throw new StaleException(doc(e.get("current")));
			}
		}
		throw error(r);
	}

	private Http.Response send(String method, String path, String json, String token) throws IOException {
		Http.Request req = new Http.Request(method, base + path).header("Accept", "application/json");
		req.header("Authorization", "Bearer " + token);
		if (json != null) {
			req.header("Content-Type", "application/json");
			req.body = json.getBytes(StandardCharsets.UTF_8);
		}
		req.maxBytes = MAX_RESPONSE;
		return http.send(req);
	}

	static Doc doc(JsonElement e) {
		if (e == null || !e.isJsonObject()) return null;
		JsonObject o = e.getAsJsonObject();
		JsonElement data = o.get("data");
		JsonElement at = o.get("updatedAt");
		if (data == null || !data.isJsonObject()) return null;
		return new Doc(data.getAsJsonObject(), at != null && at.isJsonPrimitive() ? at.getAsString() : null);
	}

	private static JsonObject parse(Http.Response r) throws ApiException {
		JsonObject o = parseQuiet(r);
		if (o == null) throw new ApiException(r.status, "invalid_json", 0);
		return o;
	}

	@SuppressWarnings("deprecation")
	private static JsonObject parseQuiet(Http.Response r) {
		try {
			// new JsonParser().parse: auch mit Gson 2.2.4 (Minecraft 1.8.9–1.11).
			JsonElement e = new JsonParser().parse(r.text());
			return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static ApiException error(Http.Response r) {
		String code = "http_" + r.status;
		JsonObject e = ClientDoc.object(parseQuiet(r), "error");
		if (e != null && e.get("code") != null && e.get("code").isJsonPrimitive()) code = e.get("code").getAsString();
		long retry = 0;
		String raw = r.header("Retry-After");
		if (raw != null) {
			try {
				retry = Math.max(1, Math.min(900, Long.parseLong(raw.trim()))) * 1000L;
			} catch (NumberFormatException ex) {
				retry = 60_000L;
			}
		}
		return new ApiException(r.status, code, retry);
	}
}

package dev.theredstonee.trsclient.core.wardrobe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Die Aufrufe der TRS API, die die Garderobe braucht (API.md §3.4, §5.3, §13.4, §14, §17): eigene Skins
 * („Meine Skins“, gleiche Daten wie der Launcher), das {@code wardrobe}-Dokument (Favoriten, Outfits, Emote-Rad),
 * TRS-Umhänge wählen. Nur im Hintergrund-Thread; das Token kommt von {@code TrsOnline}.
 */
public final class WardrobeApi {
	private static final Gson GSON = new Gson();
	private static final String JSON = "application/json";

	private final Http http;
	private final String base;

	public WardrobeApi(Http http, String base) {
		this.http = http;
		this.base = base;
	}

	// --- DTOs (Felder, Gson 2.2.4) ---

	public static final class SyncSkin {
		public String id;
		public String name;
		public String variant;
		public String sha256;
		public String updatedAt;
	}

	public static final class Tombstone {
		public String id;
		public String deletedAt;
	}

	/** LWW-Dokument ({@code data} + Zeit des Schreibers). */
	public static final class Doc {
		public JsonObject data;
		public String updatedAt;
	}

	public static final class SyncState {
		public List<SyncSkin> skins;
		public List<Tombstone> deletedSkins;
		public Doc wardrobe;
	}

	static final class SkinPut {
		String name;
		String variant;
		String png;
	}

	static final class SkinResponse {
		SyncSkin skin;
	}

	static final class DocPut {
		JsonObject data;
		String updatedAt;
	}

	static final class DocResponse {
		Doc wardrobe;
	}

	static final class ErrorBody {
		ErrorInfo error;
	}

	static final class ErrorInfo {
		String code;
		Doc current;
	}

	public static final class Cape {
		public String id;
		public String name;
		public String kind;
		public String status;
		public String url;
		public int width;
		public int height;
		public int scale;
		public int frames;
		public Integer frameTimeMs;
		public Boolean owned;
		public Boolean active;
		/** An Freunde weitergebbar (API.md §5.10). */
		public Boolean shareable;
		/** Nur bei Umhängen, die ein Freund geteilt hat. */
		public Shared shared;
		/** Sichtbare Inhaber (als Ersteller alle, sonst der eigene Ast). */
		public Integer holders;
	}

	public static final class Shared {
		public UserRef from;
		public UserRef creator;
	}

	public static final class UserRef {
		public String uuid;
		public String name;
	}

	static final class CapeList {
		List<Cape> capes;
	}

	static final class CapePut {
		String capeId;
	}

	public static final class SkinView {
		public String uuid;
		public String name;
		public String model;
		public String textureUrl;
	}

	/** Ergebnis eines Dokument-Schreibens: angenommen oder {@code 409 stale} mit dem neueren Stand. */
	public static final class DocResult {
		public final Doc doc;
		public final boolean stale;

		DocResult(Doc doc, boolean stale) {
			this.doc = doc;
			this.stale = stale;
		}
	}

	// --- Aufrufe ---

	/** {@code GET /v1/me/sync}. */
	public SyncState sync(String token) throws IOException, ApiException {
		Http.Response r = call("GET", "/v1/me/sync", null, token, 200, 1024 * 1024);
		SyncState s = parse(r, SyncState.class);
		if (s == null) throw new ApiException(200, "invalid_json", 0);
		if (s.skins == null) s.skins = new ArrayList<SyncSkin>();
		if (s.deletedSkins == null) s.deletedSkins = new ArrayList<Tombstone>();
		return s;
	}

	/** {@code GET /v1/me/sync/skins/{id}.png}. */
	public byte[] skinPng(String token, String id) throws IOException, ApiException {
		if (!SkinFiles.validId(id)) throw new ApiException(0, "invalid_request", 0);
		Http.Request req = new Http.Request("GET", base + "/v1/me/sync/skins/" + id + ".png").header("Accept", "image/png");
		req.header("Authorization", "Bearer " + token);
		req.maxBytes = SkinFiles.MAX_BYTES + 1024;
		Http.Response r = http.send(req);
		if (r.status != 200) throw new ApiException(r.status, errorCode(r), 0);
		return r.body;
	}

	/** {@code PUT /v1/me/sync/skins/{id}} – anlegen oder ersetzen (auch zum Umbenennen, statt PATCH). */
	public SyncSkin putSkin(String token, String id, String name, boolean slim, byte[] png) throws IOException, ApiException {
		if (!SkinFiles.validId(id)) throw new ApiException(0, "invalid_request", 0);
		SkinPut body = new SkinPut();
		body.name = SkinFiles.cleanName(name, "Skin");
		body.variant = slim ? "slim" : "classic";
		body.png = Base64.getEncoder().encodeToString(png);
		Http.Response r = call("PUT", "/v1/me/sync/skins/" + id, GSON.toJson(body), token, 200, 64 * 1024);
		SkinResponse s = parse(r, SkinResponse.class);
		return s == null ? null : s.skin;
	}

	/** {@code DELETE /v1/me/sync/skins/{id}}. */
	public void deleteSkin(String token, String id) throws IOException, ApiException {
		if (!SkinFiles.validId(id)) return;
		call("DELETE", "/v1/me/sync/skins/" + id, null, token, 204, 16 * 1024);
	}

	/** {@code PUT /v1/me/sync/wardrobe}; bei {@code 409 stale} kommt der neuere Stand zurück. */
	public DocResult putWardrobe(String token, JsonObject data, String updatedAt) throws IOException, ApiException {
		DocPut body = new DocPut();
		body.data = data;
		body.updatedAt = updatedAt;
		String json = GSON.toJson(body);
		if (json.length() > 96 * 1024) throw new ApiException(413, "payload_too_large", 0);
		Http.Request req = new Http.Request("PUT", base + "/v1/me/sync/wardrobe").header("Accept", JSON);
		req.header("Content-Type", JSON);
		req.header("Authorization", "Bearer " + token);
		req.body = json.getBytes(StandardCharsets.UTF_8);
		req.maxBytes = 256 * 1024;
		Http.Response r = http.send(req);
		if (r.status == 200) {
			DocResponse d = parse(r, DocResponse.class);
			return new DocResult(d == null ? null : d.wardrobe, false);
		}
		if (r.status == 409) {
			try {
				ErrorBody e = GSON.fromJson(r.text(), ErrorBody.class);
				if (e != null && e.error != null && "stale".equals(e.error.code)) return new DocResult(e.error.current, true);
			} catch (RuntimeException ignored) {
				// unten als Fehler
			}
		}
		throw new ApiException(r.status, errorCode(r), 0);
	}

	/** {@code GET /v1/capes}: Katalog aus Sicht des Nutzers. */
	public List<Cape> capes(String token) throws IOException, ApiException {
		CapeList l = parse(call("GET", "/v1/capes", null, token, 200, 512 * 1024), CapeList.class);
		return l == null || l.capes == null ? new ArrayList<Cape>() : l.capes;
	}

	/** {@code PUT /v1/me/cape} ({@code null} = ablegen). */
	public void setCape(String token, String capeId) throws IOException, ApiException {
		CapePut body = new CapePut();
		body.capeId = capeId;
		// Gson lässt null-Felder weg – "capeId": null muss aber gesendet werden.
		String json = capeId == null ? "{\"capeId\":null}" : GSON.toJson(body);
		call("PUT", "/v1/me/cape", json, token, 200, 64 * 1024);
	}

	/** {@code POST /v1/me/skin-changed} (andere TRS-Spieler sehen den neuen Skin sofort). */
	public void skinChanged(String token) throws IOException, ApiException {
		call("POST", "/v1/me/skin-changed", "{}", token, 204, 16 * 1024);
	}

	/** {@code GET /v1/skins/by-name/{name}} oder null, wenn es den Spieler nicht gibt. */
	public SkinView skinByName(String token, String name) throws IOException, ApiException {
		if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) throw new ApiException(0, "invalid_name", 0);
		try {
			return parse(call("GET", "/v1/skins/by-name/" + name.toLowerCase(Locale.ROOT), null, token, 200, 16 * 1024),
					SkinView.class);
		} catch (ApiException e) {
			if (e.status() == 404) return null;
			throw e;
		}
	}

	// --- Hilfen ---

	private Http.Response call(String method, String path, String json, String token, int expected, int maxBytes)
			throws IOException, ApiException {
		Http.Request request = new Http.Request(method, base + path).header("Accept", JSON);
		if (json != null) {
			request.header("Content-Type", JSON);
			request.body = json.getBytes(StandardCharsets.UTF_8);
		}
		if (token != null) request.header("Authorization", "Bearer " + token);
		request.maxBytes = maxBytes;
		Http.Response response = http.send(request);
		if (response.status != expected) throw new ApiException(response.status, errorCode(response), retryAfter(response));
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
			// kein JSON
		}
		return "http_" + response.status;
	}

	static long retryAfter(Http.Response response) {
		String raw = response.header("Retry-After");
		if (raw == null) return 0;
		try {
			return Math.max(1, Math.min(900, Long.parseLong(raw.trim()))) * 1000L;
		} catch (NumberFormatException e) {
			return 60_000L;
		}
	}
}

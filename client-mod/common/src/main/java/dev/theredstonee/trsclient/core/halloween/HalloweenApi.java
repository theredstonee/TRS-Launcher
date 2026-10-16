package dev.theredstonee.trsclient.core.halloween;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Halloween-Endpunkte der TRS API (API.md §36): Zustand, Kürbis gefunden, Süßes oder Saures, eigene Spuk-Effekte
 * teilen, Sticker-Katalog. Blockierend – nur aus dem Hintergrund-Thread von {@link HalloweenService}.
 */
public final class HalloweenApi {
	static final Gson GSON = new Gson();
	private static final String JSON = "application/json";

	private final Http http;
	private final String base;

	public HalloweenApi(Http http, String base) {
		this.http = http;
		this.base = base;
	}

	// --- DTOs (Gson; alle Felder optional) ---

	/** {@code GET /v1/me/halloween} (gleich dem SSE-Ereignis {@code halloween_updated}). */
	public static final class State {
		public Boolean active;
		public String endsAt;
		public Pumpkins pumpkins;
		public Trick trick;
		public List<String> badges;
		public Effects effects;
		/** Flügel-Wahl nach allen 13 Kürbissen (Nachtrag „Wings“); fehlt bei älteren APIs. */
		public Wings wings;
	}

	public static final class Pumpkins {
		public Integer total;
		public List<String> found;
		/** Rätsel zum nächsten Kürbis ({@code de}/{@code en}/{@code es}) oder null (alle gefunden). */
		public Map<String, String> nextHint;
	}

	public static final class Trick {
		public Boolean usedToday;
		/** {@code "sweet"} | {@code "sour"} (nur, wenn heute schon geklopft). */
		public String result;
		public Reward reward;
		/** Nächster Versuch ab (ISO-Zeit). */
		public String next;
	}

	/** Gewinn aus „Süßes oder Saures“ ({@code kind}: cape | emote | sticker). */
	public static final class Reward {
		public String kind;
		public String id;
		public Map<String, String> name;
	}

	/** {@code wings} im Zustand: gewählte Flügel (oder null), ob gewählt werden darf, die vier Möglichkeiten. */
	public static final class Wings {
		public String choice;
		public Boolean canChoose;
		public List<String> options;
	}

	/** Fehler der Flügel-Wahl: {@code wings_chosen} (409), {@code hunt_incomplete} (403) oder anderer Code. */
	public static final class WingsRefused extends Exception {
		public final String code;

		WingsRefused(String code) {
			super(code);
			this.code = code;
		}
	}

	static final class WingsPost {
		String id;
	}

	public static final class Effects {
		public Boolean hits;
		public Boolean trail;
	}

	/** {@code POST /v1/me/halloween/pumpkins/{id}}. */
	public static final class FindResult {
		public List<String> found;
		public Integer count;
		public Boolean complete;
		public FindReward reward;
	}

	public static final class FindReward {
		public String badge;
		public String cape;
	}

	/** {@code POST /v1/me/halloween/trick}. */
	public static final class TrickResult {
		public String result;
		public Reward reward;
		public String next;
	}

	/** Eintrag aus {@code GET /v1/stickers}. */
	public static final class Sticker {
		public String id;
		public Map<String, String> name;
		public String url;
	}

	static final class StickerList {
		List<Sticker> stickers;
	}

	static final class EffectsPut {
		boolean hits;
		boolean trail;
	}

	/** Fehler mit dem Zeitpunkt des nächsten Versuchs ({@code 409 trick_used}). */
	public static final class TrickUsed extends Exception {
		public final String next;

		TrickUsed(String next) {
			super("trick_used");
			this.next = next;
		}
	}

	// --- Aufrufe ---

	public State state(String token) throws IOException, ApiException {
		return parseState(call("GET", "/v1/me/halloween", null, token, 200).text());
	}

	public FindResult find(String token, String pumpkinId) throws IOException, ApiException {
		if (!dev.theredstonee.trsclient.core.halloween.Pumpkins.valid(pumpkinId)) throw new ApiException(404, "pumpkin_unknown", 0);
		return parse(call("POST", "/v1/me/halloween/pumpkins/" + pumpkinId, "{}", token, 200), FindResult.class);
	}

	public TrickResult trick(String token) throws IOException, ApiException, TrickUsed {
		try {
			return parse(call("POST", "/v1/me/halloween/trick", "{}", token, 200), TrickResult.class);
		} catch (ApiException e) {
			if (e.status() == 409 && "trick_used".equals(e.code())) throw new TrickUsed(nextFrom(e));
			throw e;
		}
	}

	public void effects(String token, boolean hits, boolean trail) throws IOException, ApiException {
		EffectsPut body = new EffectsPut();
		body.hits = hits;
		body.trail = trail;
		Http.Request request = request("PUT", "/v1/me/halloween/effects", GSON.toJson(body), token);
		Http.Response r = http.send(request);
		if (r.status != 200 && r.status != 204) throw new ApiException(r.status, errorCode(r), 0);
	}

	/**
	 * {@code POST /v1/me/halloween/wings {id}}: eine der vier Halloween-Flügel endgültig wählen (nur nach allen 13
	 * Kürbissen, nur einmal). Antwort = neuer Zustand (oder leer). 409 {@code wings_chosen} / 403 {@code hunt_incomplete}
	 * → {@link WingsRefused}.
	 */
	public State chooseWings(String token, String id) throws IOException, ApiException, WingsRefused {
		if (id == null || !id.matches("[a-z][a-z0-9_]{0,39}")) throw new WingsRefused("invalid_request");
		WingsPost body = new WingsPost();
		body.id = id;
		Http.Response r = http.send(request("POST", "/v1/me/halloween/wings", GSON.toJson(body), token));
		if (r.status == 409 || r.status == 403 || r.status == 404) throw new WingsRefused(errorCode(r));
		if (r.status != 200 && r.status != 201 && r.status != 204) throw new ApiException(r.status, errorCode(r), retryAfter(r), r.text());
		return r.status == 204 ? null : parseState(r.text());
	}

	public List<Sticker> stickers(String token) throws IOException, ApiException {
		StickerList l = parse(call("GET", "/v1/stickers", null, token, 200), StickerList.class);
		return l == null ? null : l.stickers;
	}

	/** Rohes PNG unter {@code path} (z. B. {@code /v1/stickers/x.png}), höchstens 256 KiB. */
	public byte[] png(String token, String path) throws IOException, ApiException {
		Http.Request request = new Http.Request("GET", path.startsWith("http") ? path : base + path).header("Accept", "image/png");
		if (token != null) request.header("Authorization", "Bearer " + token);
		request.maxBytes = 256 * 1024;
		Http.Response r = http.send(request);
		if (r.status != 200) throw new ApiException(r.status, "http_" + r.status, 0);
		return r.body;
	}

	// --- Hilfen ---

	/** Zustand aus JSON (GET bzw. SSE); kaputt → null. */
	public static State parseState(String json) {
		if (json == null || json.isEmpty()) return null;
		try {
			return GSON.fromJson(json, State.class);
		} catch (JsonSyntaxException | IllegalStateException | NumberFormatException e) {
			return null;
		}
	}

	private Http.Request request(String method, String path, String json, String token) {
		Http.Request request = new Http.Request(method, base + path).header("Accept", JSON);
		if (json != null) {
			request.header("Content-Type", JSON);
			request.body = json.getBytes(StandardCharsets.UTF_8);
		}
		if (token != null) request.header("Authorization", "Bearer " + token);
		request.maxBytes = 64 * 1024;
		return request;
	}

	private Http.Response call(String method, String path, String json, String token, int expected) throws IOException, ApiException {
		Http.Response response = http.send(request(method, path, json, token));
		if (response.status != expected) {
			throw new ApiException(response.status, errorCode(response), retryAfter(response), response.text());
		}
		return response;
	}

	private static <T> T parse(Http.Response response, Class<T> type) throws ApiException {
		try {
			return GSON.fromJson(response.text(), type);
		} catch (JsonSyntaxException | IllegalStateException | NumberFormatException e) {
			throw new ApiException(response.status, "invalid_json", 0);
		}
	}

	/** {@code next} aus der Fehlerantwort (oben oder in {@code error}). */
	static String nextFrom(ApiException e) {
		String body = e.body();
		if (body == null) return null;
		try {
			JsonElement root = new JsonParser().parse(body);
			if (!root.isJsonObject()) return null;
			JsonObject o = root.getAsJsonObject();
			if (o.has("next") && o.get("next").isJsonPrimitive()) return o.get("next").getAsString();
			if (o.has("error") && o.get("error").isJsonObject()) {
				JsonObject err = o.getAsJsonObject("error");
				if (err.has("next") && err.get("next").isJsonPrimitive()) return err.get("next").getAsString();
			}
		} catch (RuntimeException ignored) {
			// kein JSON
		}
		return null;
	}

	static String errorCode(Http.Response response) {
		try {
			JsonElement root = new JsonParser().parse(response.text());
			if (root.isJsonObject() && root.getAsJsonObject().has("error")) {
				JsonElement err = root.getAsJsonObject().get("error");
				if (err.isJsonObject() && err.getAsJsonObject().has("code")) return err.getAsJsonObject().get("code").getAsString();
				if (err.isJsonPrimitive()) return err.getAsString();
			}
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
			return 2000L;
		}
	}

	/** Name in der Sprache {@code lang} (Rückfall en, dann irgendeiner), sonst {@code fallback}. */
	public static String name(Map<String, String> names, String lang, String fallback) {
		if (names == null || names.isEmpty()) return fallback;
		String l = lang == null ? "en" : lang.toLowerCase(Locale.ROOT);
		int dash = l.indexOf('-');
		if (dash > 0) l = l.substring(0, dash);
		String v = names.get(l);
		if (v == null) v = names.get("en");
		if (v == null) v = names.values().iterator().next();
		if (v == null) return fallback;
		v = v.trim();
		return v.isEmpty() || v.length() > 200 ? fallback : v;
	}
}

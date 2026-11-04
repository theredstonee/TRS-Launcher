package dev.theredstonee.trsclient.core.minigames;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Rangliste der Minispiele (API §42):
 *
 * <pre>
 * POST /v1/minigames/:game/start        → { runId, seed }
 * POST /v1/minigames/:game/runs         { runId, inputs, score } → { accepted, rank, best, improved }
 * GET  /v1/minigames/:game/leaderboard  → { entries: [ {rank, uuid, name, score, at} ], me: {rank, score} | null, total }
 * </pre>
 *
 * Gerechnet wird nur auf dem Server: Er spielt die Eingaben nach und zählt das Ergebnis. Blockierend, nur im Hintergrund
 * aufrufen; das Token wird nie geloggt.
 */
public final class MinigamesApi {
	static final int MAX_RESPONSE = 256 * 1024;
	private static final Gson GSON = new Gson();

	/** Startwert einer gewerteten Partie. */
	public static final class Start {
		public final String runId;
		public final long seed;

		public Start(String runId, long seed) {
			this.runId = runId;
			this.seed = seed;
		}
	}

	/** Antwort auf eine eingereichte Partie. */
	public static final class Submitted {
		public final boolean accepted;
		public final int rank;
		public final long best;
		public final boolean improved;

		public Submitted(boolean accepted, int rank, long best, boolean improved) {
			this.accepted = accepted;
			this.rank = rank;
			this.best = best;
			this.improved = improved;
		}
	}

	/** Eintrag der Rangliste. */
	public static final class Entry {
		public final int rank;
		public final String name;
		public final long score;

		public Entry(int rank, String name, long score) {
			this.rank = rank;
			this.name = name;
			this.score = score;
		}
	}

	/** Rangliste samt eigenem Platz ({@code meRank} 0 = keiner). */
	public static final class Board {
		public final List<Entry> entries;
		public final int meRank;
		public final long meScore;
		public final int total;

		public Board(List<Entry> entries, int meRank, long meScore, int total) {
			this.entries = entries;
			this.meRank = meRank;
			this.meScore = meScore;
			this.total = total;
		}
	}

	private final Http http;
	private final String base;

	public MinigamesApi(Http http, String apiBase) {
		this.http = http;
		this.base = apiBase;
	}

	/** Nur die Kennungen, die der Launcher auch prüft: Buchstaben, Ziffern, Bindestrich, höchstens 64 Zeichen. */
	static boolean validRunId(String run) {
		if (run == null || run.isEmpty() || run.length() > 64) return false;
		for (int i = 0; i < run.length(); i++) {
			char c = run.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
			if (!ok) return false;
		}
		return true;
	}

	public Start start(String token, String game) throws IOException, ApiException {
		Http.Response r = send("POST", path(game, "start"), "", token);
		if (r.status != 200 && r.status != 201) throw error(r);
		JsonObject body = parse(r);
		String run = str(body, "runId");
		JsonElement seed = body.get("seed");
		if (!validRunId(run) || seed == null || !seed.isJsonPrimitive()) throw new ApiException(r.status, "invalid_json", 0);
		long value;
		try {
			value = seed.getAsLong();
		} catch (NumberFormatException e) {
			throw new ApiException(r.status, "invalid_json", 0);
		}
		if (value < 0 || value > 0xFFFFFFFFL) throw new ApiException(r.status, "invalid_json", 0);
		return new Start(run, value);
	}

	public Submitted submit(String token, String game, String runId, int[] inputs, long score) throws IOException, ApiException {
		Http.Response r = send("POST", path(game, "runs"), body(runId, inputs, score), token);
		if (r.status != 200 && r.status != 201) throw error(r);
		JsonObject body = parse(r);
		return new Submitted(bool(body, "accepted"), (int) num(body, "rank"), num(body, "best"), bool(body, "improved"));
	}

	public Board leaderboard(String token, String game) throws IOException, ApiException {
		Http.Response r = send("GET", path(game, "leaderboard"), null, token);
		if (r.status != 200) throw error(r);
		JsonObject body = parse(r);
		List<Entry> entries = new ArrayList<Entry>();
		JsonElement list = body.get("entries");
		if (list != null && list.isJsonArray()) {
			JsonArray array = list.getAsJsonArray();
			for (int i = 0; i < array.size() && i < 100; i++) {
				if (!array.get(i).isJsonObject()) continue;
				JsonObject o = array.get(i).getAsJsonObject();
				String name = str(o, "name");
				entries.add(new Entry((int) num(o, "rank"), name == null ? "?" : name, num(o, "score")));
			}
		}
		int meRank = 0;
		long meScore = 0;
		JsonElement me = body.get("me");
		if (me != null && me.isJsonObject()) {
			meRank = (int) num(me.getAsJsonObject(), "rank");
			meScore = num(me.getAsJsonObject(), "score");
		}
		return new Board(entries, meRank, meScore, (int) num(body, "total"));
	}

	/** Körper des Einreichens. Von Hand zusammengesetzt, weil Gson 2.2.4 (Minecraft 1.8.9) keine Zahlen in Listen kennt. */
	static String body(String runId, int[] inputs, long score) {
		StringBuilder out = new StringBuilder(32 + inputs.length * 4);
		out.append("{\"runId\":").append(GSON.toJson(new JsonPrimitive(runId))).append(",\"inputs\":[");
		for (int i = 0; i < inputs.length; i++) {
			if (i > 0) out.append(',');
			out.append(inputs[i]);
		}
		return out.append("],\"score\":").append(score).append('}').toString();
	}

	private static String path(String game, String action) {
		if (!Replay.STAPLER.equals(game) && !Replay.LOREN.equals(game)) throw new IllegalArgumentException(game);
		return "/v1/minigames/" + game + "/" + action;
	}

	private Http.Response send(String method, String path, String json, String token) throws IOException {
		Http.Request req = new Http.Request(method, base + path).header("Accept", "application/json");
		if (token != null) req.header("Authorization", "Bearer " + token);
		if (json != null) {
			if (!json.isEmpty()) req.header("Content-Type", "application/json");
			req.body = json.getBytes(StandardCharsets.UTF_8);
		}
		req.maxBytes = MAX_RESPONSE;
		return http.send(req);
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static boolean bool(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsBoolean();
	}

	private static long num(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) return 0;
		try {
			return e.getAsLong();
		} catch (NumberFormatException ex) {
			return 0;
		}
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
		JsonObject o = parseQuiet(r);
		JsonElement err = o == null ? null : o.get("error");
		if (err != null && err.isJsonObject()) {
			String c = str(err.getAsJsonObject(), "code");
			if (c != null) code = c;
		}
		return new ApiException(r.status, code, 0);
	}
}

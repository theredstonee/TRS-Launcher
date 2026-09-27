package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.online.TrsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Eigene Schaltungen einreichen (angemeldet mit TRS): {@code POST /v1/circuits/submissions} und
 * {@code GET /v1/me/circuit-submissions}. Aufrufe laufen in einem eigenen Hintergrund-Thread; die Oberfläche liest
 * nur die zuletzt gemeldeten Ergebnisse. Fehlercodes vom Server: {@code circuit_duplicate} (409),
 * {@code rate_limited} (429), {@code sanctioned} (403), {@code invalid_circuit} (400).
 */
public final class CircuitSubmissions {
	/** Eine eigene Einreichung. */
	public static final class Submission {
		public String id;
		public String name;
		public String category;
		/** {@code pending}, {@code approved} oder {@code rejected}. */
		public String status;
		/** Grund bei {@code rejected}. */
		public String reason;
		public String createdAt;
	}

	/** Ergebnis des Einreichens. */
	public static final class Result {
		public boolean ok;
		public String id;
		public String status;
		/** Fehlercode (Server-Code, {@code unauthorized}, {@code offline} …). */
		public String error;
		public long retryAfterMs;
	}

	/** Zugang zum Token der TRS-Anmeldung (null = nicht angemeldet). */
	public interface Tokens {
		String token();

		/** Server hat das Token abgelehnt (401) – neu anmelden lassen. */
		void rejected(String token);
	}

	private final Http http;
	private final String apiBase;
	private volatile Tokens tokens;
	private volatile boolean busy;
	private volatile Result lastResult;
	private volatile List<Submission> mine;
	private volatile String mineError;
	private volatile boolean mineLoading;

	public CircuitSubmissions(Http http, String apiBase) {
		this.http = http;
		this.apiBase = apiBase;
		this.tokens = new Tokens() {
			@Override
			public String token() {
				dev.theredstonee.trsclient.core.online.TrsOnline o = dev.theredstonee.trsclient.core.online.TrsOnline.current();
				return o == null ? null : o.token();
			}

			@Override
			public void rejected(String token) {
				dev.theredstonee.trsclient.core.online.TrsOnline o = dev.theredstonee.trsclient.core.online.TrsOnline.current();
				if (o != null) o.tokenRejected(token);
			}
		};
	}

	/** Anderer Token-Zugang (Tests). */
	public void tokens(Tokens t) {
		tokens = t;
	}

	public boolean loggedIn() {
		try {
			return tokens.token() != null;
		} catch (RuntimeException e) {
			return false;
		}
	}

	public boolean busy() {
		return busy;
	}

	public Result lastResult() {
		return lastResult;
	}

	public void clearResult() {
		lastResult = null;
	}

	public List<Submission> mine() {
		return mine;
	}

	public String mineError() {
		return mineError;
	}

	public boolean mineLoading() {
		return mineLoading;
	}

	/** Anfrage-Körper nach Vertrag: {@code {circuit, name, category, description, lang}}. */
	public static JsonObject body(JsonObject circuit, String name, String category, String description, String lang) {
		JsonObject body = new JsonObject();
		body.add("circuit", circuit);
		body.addProperty("name", name);
		body.addProperty("category", category);
		body.addProperty("description", description);
		body.addProperty("lang", lang);
		return body;
	}

	/** Einreichen im Hintergrund; Ergebnis danach in {@link #lastResult()}. */
	public void submitAsync(final JsonObject circuit, final String name, final String category, final String description,
			final String lang) {
		if (busy) return;
		busy = true;
		lastResult = null;
		thread(new Runnable() {
			@Override
			public void run() {
				try {
					lastResult = submit(circuit, name, category, description, lang);
				} finally {
					busy = false;
				}
			}
		});
	}

	/** Einreichen (synchron). */
	public Result submit(JsonObject circuit, String name, String category, String description, String lang) {
		Result r = new Result();
		String token = tokens.token();
		if (token == null) {
			r.error = "unauthorized";
			return r;
		}
		try {
			Http.Request req = new Http.Request("POST", apiBase + "/v1/circuits/submissions")
					.header("Accept", "application/json").header("Content-Type", "application/json")
					.header("Authorization", "Bearer " + token);
			req.body = body(circuit, name, category, description, lang).toString().getBytes(StandardCharsets.UTF_8);
			Http.Response res = http.send(req);
			if (res.status == 201 || res.status == 200) {
				JsonObject o = new JsonParser().parse(res.text()).getAsJsonObject();
				r.ok = true;
				r.id = str(o, "id");
				r.status = str(o, "status");
				return r;
			}
			if (res.status == 401) tokens.rejected(token);
			r.error = res.status == 401 ? "unauthorized" : TrsApi.errorCode(res);
			r.retryAfterMs = TrsApi.retryAfter(res);
			return r;
		} catch (IOException e) {
			r.error = "offline";
			return r;
		} catch (RuntimeException e) {
			r.error = "invalid_response";
			return r;
		}
	}

	/** „Meine Einreichungen“ im Hintergrund neu laden. */
	public void refreshMineAsync() {
		if (mineLoading) return;
		mineLoading = true;
		thread(new Runnable() {
			@Override
			public void run() {
				try {
					loadMine();
				} finally {
					mineLoading = false;
				}
			}
		});
	}

	/** „Meine Einreichungen“ (synchron); Fehler in {@link #mineError()}. */
	public List<Submission> loadMine() {
		String token = tokens.token();
		if (token == null) {
			mineError = "unauthorized";
			return null;
		}
		try {
			Http.Request req = new Http.Request("GET", apiBase + "/v1/me/circuit-submissions").header("Accept", "application/json")
					.header("Authorization", "Bearer " + token);
			Http.Response res = http.send(req);
			if (res.status != 200) {
				if (res.status == 401) tokens.rejected(token);
				mineError = res.status == 401 ? "unauthorized" : TrsApi.errorCode(res);
				return null;
			}
			JsonElement root = new JsonParser().parse(res.text());
			JsonArray arr = root.isJsonArray() ? root.getAsJsonArray()
					: root.getAsJsonObject().has("submissions") ? root.getAsJsonObject().getAsJsonArray("submissions") : new JsonArray();
			List<Submission> list = new ArrayList<Submission>();
			for (JsonElement e : arr) {
				if (!e.isJsonObject() || list.size() >= 200) continue;
				JsonObject o = e.getAsJsonObject();
				Submission s = new Submission();
				s.id = clip(str(o, "id"), 64);
				s.name = clip(str(o, "name"), Circuit.MAX_NAME);
				s.category = clip(str(o, "category"), 16);
				s.status = clip(str(o, "status"), 16);
				s.reason = clip(str(o, "reason"), 300);
				s.createdAt = clip(str(o, "createdAt"), 40);
				list.add(s);
			}
			mine = Collections.unmodifiableList(list);
			mineError = null;
			return mine;
		} catch (IOException e) {
			mineError = "offline";
			return null;
		} catch (RuntimeException e) {
			mineError = "invalid_response";
			return null;
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static String clip(String s, int max) {
		if (s == null) return null;
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < s.length() && b.length() < max; i++) {
			char c = s.charAt(i);
			if (c >= 32 && c != '§') b.append(c);
		}
		return b.toString();
	}

	private static void thread(Runnable r) {
		Thread t = new Thread(r, "TRS-Circuits-Submit");
		t.setDaemon(true);
		t.start();
	}
}

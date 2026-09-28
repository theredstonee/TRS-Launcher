package dev.theredstonee.trsclient.core.notes;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.online.ApiException;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.sync.SyncTime;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Notiz-Sync mit der TRS API (API.md §17.5):
 *
 * <pre>
 * GET  /v1/me/sync/notes?since=&lt;cursor&gt;&amp;limit=500
 *      → 200 { notes: [NoteView], cursor: "…", more: false, reset: false }
 * POST /v1/me/sync/notes   { changes: [ {id, world, title, text, createdAt, updatedAt}
 *                                     | {id, world, deleted: true, updatedAt} ] }   (1–50 Einträge, ≤ 512 KiB)
 *      → 200 { results: [ {id, status: "ok"|"stale"|"note_limit"|"invalid", current?: NoteView} ] }
 * NoteView = { id, world: {type:"server", address} | {type:"world", id, name?}, title, text, createdAt, updatedAt }
 *          | { id, world, deleted: true, updatedAt }
 * </pre>
 *
 * Kennt der Server die Route nicht ({@code 404}/{@code 405}/{@code 501}), wirft {@link #pull} {@link Unsupported} –
 * dann bleiben die Notizen nur auf diesem PC. Blockierend, nur im Sync-Thread aufrufen; das Token wird nie geloggt.
 */
public final class NotesSyncApi {
	public static final String PATH = "/v1/me/sync/notes";
	/** Einträge je POST. */
	public static final int MAX_BATCH = 50;
	/** Körper eines POST (Server-Grenze 512 KiB, etwas Luft). */
	public static final int MAX_BATCH_BYTES = 480 * 1024;
	/** Notizen je Seite beim Holen (Server: 1–500). */
	public static final int PAGE = 500;
	static final int MAX_RESPONSE = 8 * 1024 * 1024;
	private static final Gson GSON = new Gson();

	/** Server kennt den Notiz-Sync (noch) nicht. */
	public static final class Unsupported extends Exception {
		public Unsupported(int status) {
			super("notes sync unsupported (HTTP " + status + ")");
		}
	}

	/** Eine Notiz vom Konto samt Welt. */
	public static final class Remote {
		public final NoteWorld world;
		public final Note note;

		public Remote(NoteWorld world, Note note) {
			this.world = world;
			this.note = note;
		}
	}

	/** Eine Seite von {@code GET}. */
	public static final class Page {
		public final List<Remote> notes;
		public final String cursor;
		public final boolean more;
		/**
		 * Server kannte den Cursor nicht mehr (älter als die gelöschten Grabsteine): diese Seite ist der Anfang der
		 * VOLLSTÄNDIGEN Liste – früher synchronisierte Notizen, die darin fehlen, wurden anderswo gelöscht.
		 */
		public final boolean reset;

		public Page(List<Remote> notes, String cursor, boolean more) {
			this(notes, cursor, more, false);
		}

		public Page(List<Remote> notes, String cursor, boolean more, boolean reset) {
			this.notes = notes;
			this.cursor = cursor;
			this.more = more;
			this.reset = reset;
		}
	}

	/** Ergebnis je Eintrag eines POST. */
	public static final class Result {
		public static final String OK = "ok";
		public static final String STALE = "stale";
		public static final String LIMIT = "note_limit";
		public static final String INVALID = "invalid";

		public final String id;
		public final String status;
		/** Bei {@link #STALE}: der neuere Stand des Kontos. */
		public final Remote current;

		public Result(String id, String status, Remote current) {
			this.id = id;
			this.status = status;
			this.current = current;
		}
	}

	private final Http http;
	private final String base;

	public NotesSyncApi(Http http, String apiBase) {
		this.http = http;
		this.base = apiBase;
	}

	/** Änderungen seit {@code cursor} (null = alles). */
	public Page pull(String token, String cursor) throws IOException, ApiException, Unsupported {
		String url = PATH + "?limit=" + PAGE + (cursor == null || cursor.isEmpty() ? "" : "&since=" + enc(cursor));
		Http.Response r = send("GET", url, null, token);
		if (r.status == 404 || r.status == 405 || r.status == 501) throw new Unsupported(r.status);
		if (r.status != 200) throw error(r);
		JsonObject body = parse(r);
		List<Remote> notes = new ArrayList<Remote>();
		JsonElement list = body.get("notes");
		if (list != null && list.isJsonArray()) {
			for (JsonElement e : list.getAsJsonArray()) {
				Remote n = remote(e);
				if (n != null) notes.add(n);
			}
		}
		JsonElement c = body.get("cursor");
		JsonElement more = body.get("more");
		JsonElement reset = body.get("reset");
		String next = c != null && c.isJsonPrimitive() ? c.getAsString() : cursor;
		if (next != null && next.length() > 256) next = null;
		return new Page(notes, next, more != null && more.isJsonPrimitive() && more.getAsBoolean(),
				reset != null && reset.isJsonPrimitive() && reset.getAsBoolean());
	}

	/** Schreibt Änderungen (höchstens {@link #MAX_BATCH}); Ergebnisse in derselben Reihenfolge, fehlende = Fehler. */
	public List<Result> push(String token, List<NotesStore.Entry> changes) throws IOException, ApiException, Unsupported {
		JsonObject body = new JsonObject();
		JsonArray arr = new JsonArray();
		for (NotesStore.Entry e : changes) arr.add(change(e));
		body.add("changes", arr);
		Http.Response r = send("POST", PATH, GSON.toJson(body), token);
		if (r.status == 404 || r.status == 405 || r.status == 501) throw new Unsupported(r.status);
		if (r.status != 200) throw error(r);
		List<Result> out = new ArrayList<Result>();
		JsonElement results = parse(r).get("results");
		if (results != null && results.isJsonArray()) {
			for (JsonElement e : results.getAsJsonArray()) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				String id = str(o, "id");
				String status = str(o, "status");
				if (id == null || status == null) continue;
				out.add(new Result(id, status, remote(o.get("current"))));
			}
		}
		return out;
	}

	/** JSON eines Eintrags für {@code POST}. */
	public static JsonObject change(NotesStore.Entry e) {
		JsonObject o = new JsonObject();
		o.addProperty("id", e.note.id);
		o.add("world", e.world.toJson());
		if (e.note.deleted) {
			o.addProperty("deleted", true);
		} else {
			o.addProperty("title", e.note.title == null ? "" : e.note.title);
			o.addProperty("text", e.note.text == null ? "" : e.note.text);
			o.addProperty("createdAt", SyncTime.iso(e.note.created));
		}
		o.addProperty("updatedAt", SyncTime.iso(e.note.updated));
		return o;
	}

	/** Größe eines Eintrags im Körper (UTF-8). */
	public static int size(NotesStore.Entry e) {
		return GSON.toJson(change(e)).getBytes(StandardCharsets.UTF_8).length + 1;
	}

	/** NoteView → Notiz; null, wenn unbrauchbar. */
	public static Remote remote(JsonElement e) {
		if (e == null || !e.isJsonObject()) return null;
		JsonObject o = e.getAsJsonObject();
		NoteWorld world = NoteWorld.fromJson(o.get("world"));
		String id = str(o, "id");
		if (world == null || id == null) return null;
		Note n = new Note();
		n.id = id;
		JsonElement del = o.get("deleted");
		n.deleted = del != null && del.isJsonPrimitive() && del.getAsBoolean();
		n.title = str(o, "title");
		n.text = str(o, "text");
		n.updated = SyncTime.parse(str(o, "updatedAt"));
		n.created = SyncTime.parse(str(o, "createdAt"));
		if (n.updated <= 0) return null;
		Note ok = n.normalized();
		return ok == null ? null : new Remote(world, ok);
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

	private static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			return s;
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
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

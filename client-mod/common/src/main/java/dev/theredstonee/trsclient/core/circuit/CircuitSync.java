package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lädt die Schaltungs-Bibliothek von der TRS API – einmal je Spielstart, im Hintergrund (Thread „TRS-Circuits“):
 * <ol>
 *   <li>{@code GET /v1/circuits/index} mit {@code If-None-Match} (ETag des letzten Index) – 304 = nichts zu tun.</li>
 *   <li>Nur neue bzw. geänderte Einträge: {@code GET /v1/circuits/{id}?rev={rev}} (unveränderlich je rev).</li>
 *   <li>Entfernte Einträge löschen, Index atomar schreiben.</li>
 * </ol>
 * Keine Anmeldung. Jede Schaltung wird streng geprüft ({@link Circuit#parse}); höchstens
 * {@link CircuitCache#MAX_ENTRIES} Einträge, {@link CircuitCache#MAX_CIRCUIT_BYTES} je Schaltung.
 */
public final class CircuitSync {
	/** Stand der Abfrage in diesem Spielstart. */
	public enum Status {
		IDLE, RUNNING, UP_TO_DATE, UPDATED, OFFLINE, DISABLED, FAILED
	}

	/** Ergebnis eines Laufs (Tests, Log). */
	public static final class Result {
		public Status status = Status.IDLE;
		public int fetched;
		public int removed;
		public int invalid;
		public int indexRequests;
		public int circuitRequests;
	}

	private static volatile Status status = Status.IDLE;
	private static volatile boolean started;

	/** Server-Antwort des Index. */
	static final class IndexBody {
		String version;
		List<CircuitCache.Entry> circuits;
	}

	private CircuitSync() {
	}

	public static Status status() {
		return status;
	}

	/**
	 * Einmal je Spielstart: Cache laden und veröffentlichen, dann (wenn erlaubt) beim Server prüfen und die
	 * aktualisierte Bibliothek veröffentlichen. Nie im Render-Thread.
	 *
	 * @param apiBase null = keine Online-Abfrage (TRS API im Launcher/Menü abgeschaltet)
	 */
	public static synchronized void startOnce(final Http http, final String apiBase, final CircuitCache cache) {
		if (started) return;
		started = true;
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				BlockCatalog catalog = CircuitLibrary.blockCatalog();
				try {
					CircuitLibrary.publish(cache.load(catalog));
				} catch (RuntimeException ignored) {
					// Cache kaputt → gleich neu laden
				}
				if (apiBase == null || http == null) {
					status = Status.DISABLED;
					return;
				}
				Result r = CircuitSync.run(http, apiBase, cache, catalog);
				if (r.status == Status.UPDATED) CircuitLibrary.publish(cache.load(catalog));
			}
		}, "TRS-Circuits");
		t.setDaemon(true);
		t.start();
	}

	/** Ein vollständiger Abgleich (synchron; Tests rufen das direkt). */
	public static Result run(Http http, String apiBase, CircuitCache cache, BlockCatalog catalog) {
		Result result = new Result();
		status = Status.RUNNING;
		try {
			CircuitCache.Index old = cache.readIndex();
			boolean complete = old != null;
			if (old != null) {
				for (CircuitCache.Entry e : old.circuits) {
					if (!cache.hasCircuit(e.id)) complete = false;
				}
			}
			Http.Request req = new Http.Request("GET", apiBase + "/v1/circuits/index").header("Accept", "application/json");
			if (complete && old.etag != null) req.header("If-None-Match", old.etag);
			req.maxBytes = CircuitCache.MAX_INDEX_BYTES;
			result.indexRequests++;
			Http.Response res = http.send(req);
			if (res.status == 304) {
				result.status = Status.UP_TO_DATE;
				return finish(result);
			}
			if (res.status != 200) {
				result.status = Status.FAILED;
				return finish(result);
			}
			IndexBody body = new Gson().fromJson(res.text(), IndexBody.class);
			if (body == null || body.circuits == null) {
				result.status = Status.FAILED;
				return finish(result);
			}
			Map<String, String> cachedRev = new HashMap<String, String>();
			if (old != null) for (CircuitCache.Entry e : old.circuits) cachedRev.put(e.id, e.rev);
			CircuitCache.Index next = new CircuitCache.Index();
			next.version = body.version != null && body.version.length() <= 128 ? body.version : null;
			String etag = res.header("ETag");
			next.etag = etag != null && etag.length() <= 200 ? etag : (next.version == null ? null : "\"" + next.version + "\"");
			Set<String> keep = new HashSet<String>();
			for (CircuitCache.Entry e : body.circuits) {
				if (next.circuits.size() >= CircuitCache.MAX_ENTRIES) break;
				if (e == null || !e.valid() || keep.contains(e.id)) {
					result.invalid++;
					continue;
				}
				String have = cachedRev.get(e.id);
				if (e.rev.equals(have) && cache.hasCircuit(e.id)) {
					keep.add(e.id);
					next.circuits.add(e);
					continue;
				}
				String json = fetch(http, apiBase, e, catalog, result);
				if (json != null) {
					cache.writeCircuit(e.id, json);
					result.fetched++;
					keep.add(e.id);
					next.circuits.add(e);
				} else if (have != null && cache.hasCircuit(e.id)) {
					// neue rev kaputt/nicht erreichbar: alte behalten, beim nächsten Start erneut versuchen
					CircuitCache.Entry kept = copy(e);
					kept.rev = have;
					keep.add(e.id);
					next.circuits.add(kept);
					next.etag = null;
				} else {
					next.etag = null;
				}
			}
			result.removed = cache.removeOthers(keep);
			cache.writeIndex(next);
			result.status = Status.UPDATED;
			return finish(result);
		} catch (IOException e) {
			result.status = Status.OFFLINE;
			return finish(result);
		} catch (RuntimeException e) {
			result.status = Status.FAILED;
			return finish(result);
		}
	}

	private static Result finish(Result r) {
		status = r.status;
		return r;
	}

	private static CircuitCache.Entry copy(CircuitCache.Entry e) {
		CircuitCache.Entry c = new CircuitCache.Entry();
		c.id = e.id;
		c.rev = e.rev;
		c.updatedAt = e.updatedAt;
		c.minVersion = e.minVersion;
		c.maxVersion = e.maxVersion;
		return c;
	}

	/** Eine Schaltung laden und streng prüfen; null = nicht verwendbar. */
	private static String fetch(Http http, String apiBase, CircuitCache.Entry e, BlockCatalog catalog, Result result) {
		try {
			Http.Request req = new Http.Request("GET", apiBase + "/v1/circuits/" + e.id + "?rev=" + enc(e.rev))
					.header("Accept", "application/json");
			req.maxBytes = CircuitCache.MAX_CIRCUIT_BYTES;
			result.circuitRequests++;
			Http.Response res = http.send(req);
			if (res.status != 200) {
				result.invalid++;
				return null;
			}
			String text = res.text();
			JsonObject o = new JsonParser().parse(text).getAsJsonObject();
			Circuit c = Circuit.parse(o, catalog);
			if (!c.id.equals(e.id)) throw new IllegalArgumentException("id passt nicht");
			return text;
		} catch (IOException | RuntimeException ex) {
			result.invalid++;
			return null;
		}
	}

	private static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			return s;
		}
	}

	/** Nur für Tests: nächster {@link #startOnce} läuft wieder. */
	static synchronized void resetForTests() {
		started = false;
		status = Status.IDLE;
	}

	/** Liste der IDs im Cache-Index (Tests). */
	static List<String> ids(CircuitCache.Index index) {
		List<String> out = new ArrayList<String>();
		if (index != null) for (CircuitCache.Entry e : index.circuits) out.add(e.id);
		return out;
	}
}

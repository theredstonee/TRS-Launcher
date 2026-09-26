package dev.theredstonee.trsclient.core.hosting.share;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.link.TrsLink;
import dev.theredstonee.trsclient.core.online.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Erkennt Mods in den Stores. Mit dem TRS Launcher ({@code hosting.mods} über den TRS Link, siehe
 * docs/hosting-link.md §6) prüft der Launcher die Dateien der Instanz selbst gegen Modrinth (SHA-1/SHA-512) und
 * CurseForge (Murmur2-Fingerprint, der API-Schlüssel liegt nur im Launcher). Ohne Launcher fragt die Mod direkt bei
 * Modrinth (SHA-512) – CurseForge fehlt dann.
 *
 * <p>Ergebnis: SHA-1 → Treffer. Blockierend – nur aus Hintergrund-Threads.
 */
public final class StoreLookup {
	static final String MODRINTH = "https://api.modrinth.com/v2/version_files";

	/** Ein Store-Treffer. */
	public static final class Match {
		public final String sha1;
		public final SharedContent.Source source;
		public final String projectId;
		public final String fileId;
		public final long fingerprint;

		public Match(String sha1, SharedContent.Source source, String projectId, String fileId, long fingerprint) {
			this.sha1 = sha1;
			this.source = source;
			this.projectId = projectId;
			this.fileId = fileId;
			this.fingerprint = fingerprint;
		}

		boolean valid() {
			if (sha1 == null || !SharedContent.SHA1.matcher(sha1).matches()) return false;
			if (source == SharedContent.Source.MODRINTH) {
				return projectId != null && SharedContent.MODRINTH_ID.matcher(projectId).matches() && fileId != null
						&& SharedContent.MODRINTH_ID.matcher(fileId).matches();
			}
			if (source == SharedContent.Source.CURSEFORGE) {
				return projectId != null && SharedContent.CF_ID.matcher(projectId).matches() && fileId != null
						&& SharedContent.CF_ID.matcher(fileId).matches();
			}
			return false;
		}
	}

	private StoreLookup() {
	}

	/** Über den Launcher (falls er es kann), sonst Modrinth direkt. {@code viaLauncher[0]} sagt, welcher Weg lief. */
	public static Map<String, Match> identify(Http http, List<ModScan.LocalMod> mods, boolean[] viaLauncher) throws IOException {
		TrsLink link = TrsLink.shared();
		if (link != null && link.status().has(TrsLink.FEATURE_HOSTING_MODS)) {
			Map<String, Match> m = launcher(link, 90_000L);
			if (m != null) {
				if (viaLauncher != null) viaLauncher[0] = true;
				return m;
			}
		}
		if (viaLauncher != null) viaLauncher[0] = false;
		return modrinth(http, mods);
	}

	/** Launcher fragen; null = ging nicht (dann Modrinth direkt). */
	static Map<String, Match> launcher(TrsLink link, long timeoutMs) {
		final Map<String, Match> out = new HashMap<String, Match>();
		final boolean[] ok = { false };
		final CountDownLatch done = new CountDownLatch(1);
		link.request("hosting.mods", null, timeoutMs, new TrsLink.Callback() {
			@Override
			public void done(TrsLink.Line response) {
				if (response.mods != null) {
					for (TrsLink.ModSourceDto d : response.mods) {
						if (out.size() >= SharedContent.MAX_MODS * 2) break;
						Match m = new Match(lower(d.sha1), SharedContent.Source.of(d.source), d.projectId, d.fileId,
								d.fingerprint == null ? -1 : d.fingerprint);
						if (m.valid() && m.source != null) out.put(m.sha1, m);
					}
				}
				ok[0] = true;
				done.countDown();
			}

			@Override
			public void failed(String code) {
				done.countDown();
			}
		});
		try {
			done.await(timeoutMs + 1000L, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return ok[0] ? out : null;
	}

	/** Modrinth: {@code POST /v2/version_files} mit SHA-512 (je 100); der Treffer muss auch per SHA-1 passen. */
	@SuppressWarnings("deprecation")
	public static Map<String, Match> modrinth(Http http, List<ModScan.LocalMod> mods) throws IOException {
		Map<String, Match> out = new HashMap<String, Match>();
		Map<String, ModScan.LocalMod> bySha512 = new HashMap<String, ModScan.LocalMod>();
		for (ModScan.LocalMod m : mods) bySha512.put(m.sha512, m);
		java.util.List<String> keys = new java.util.ArrayList<String>(bySha512.keySet());
		for (int i = 0; i < keys.size(); i += 100) {
			JsonObject body = new JsonObject();
			JsonArray hashes = new JsonArray();
			for (String h : keys.subList(i, Math.min(keys.size(), i + 100))) hashes.add(new com.google.gson.JsonPrimitive(h));
			body.add("hashes", hashes);
			body.addProperty("algorithm", "sha512");
			Http.Request r = new Http.Request("POST", MODRINTH).header("Content-Type", "application/json")
					.header("Accept", "application/json");
			r.body = body.toString().getBytes(StandardCharsets.UTF_8);
			r.maxBytes = 8 * 1024 * 1024;
			Http.Response res = http.send(r);
			if (res.status != 200) throw new IOException("modrinth " + res.status);
			JsonElement root;
			try {
				root = new JsonParser().parse(res.text());
			} catch (RuntimeException e) {
				throw new IOException("modrinth json");
			}
			if (!root.isJsonObject()) continue;
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
				ModScan.LocalMod local = bySha512.get(e.getKey().toLowerCase(Locale.ROOT));
				Match m = local == null ? null : modrinthMatch(local, e.getValue());
				if (m != null) out.put(m.sha1, m);
			}
		}
		return out;
	}

	/** Version aus der Antwort: Projekt, Version und die Datei mit genau unseren Hashes. */
	static Match modrinthMatch(ModScan.LocalMod local, JsonElement v) {
		if (v == null || !v.isJsonObject()) return null;
		JsonObject o = v.getAsJsonObject();
		String id = str(o, "id");
		String project = str(o, "project_id");
		JsonElement files = o.get("files");
		if (files == null || !files.isJsonArray()) return null;
		for (JsonElement f : files.getAsJsonArray()) {
			if (!f.isJsonObject()) continue;
			JsonElement h = f.getAsJsonObject().get("hashes");
			if (h == null || !h.isJsonObject()) continue;
			String sha1 = lower(str(h.getAsJsonObject(), "sha1"));
			String sha512 = lower(str(h.getAsJsonObject(), "sha512"));
			if (local.sha1.equals(sha1) && local.sha512.equals(sha512)) {
				Match m = new Match(local.sha1, SharedContent.Source.MODRINTH, project, id, -1);
				return m.valid() ? m : null;
			}
		}
		return null;
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static String lower(String s) {
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}
}

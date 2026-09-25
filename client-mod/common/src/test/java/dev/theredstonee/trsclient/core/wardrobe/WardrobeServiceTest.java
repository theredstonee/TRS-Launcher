package dev.theredstonee.trsclient.core.wardrobe;

import dev.theredstonee.trsclient.core.account.SessionData;
import dev.theredstonee.trsclient.core.online.Http;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Garderoben-Dienst gegen eine Attrappe von TRS API und Mojang: Sync in beide Richtungen, LWW, Anwenden. */
class WardrobeServiceTest {
	@TempDir
	Path dir;

	/** Führt Aufgaben sofort im aufrufenden Thread aus. */
	static final class Direct extends AbstractExecutorService {
		@Override
		public void execute(Runnable command) {
			command.run();
		}

		@Override
		public void shutdown() {
		}

		@Override
		public List<Runnable> shutdownNow() {
			return Collections.emptyList();
		}

		@Override
		public boolean isShutdown() {
			return false;
		}

		@Override
		public boolean isTerminated() {
			return false;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return true;
		}
	}

	/** Server-Attrappe mit Zustand. */
	static final class Fake implements Http {
		final List<String> calls = new ArrayList<String>();
		final Map<String, byte[]> skins = new HashMap<String, byte[]>();
		final Map<String, String> names = new HashMap<String, String>();
		String wardrobe = "{\"data\":{\"favorites\":[\"aaaaaaaaaaaa\"]},\"updatedAt\":\"2026-09-25T10:00:00.000Z\"}";
		/** Nächstes PUT wardrobe mit 409 beantworten. */
		String staleCurrent;
		String lastMultipart;
		int wardrobePuts;

		static Response json(int status, String body) {
			Map<String, String> h = new HashMap<String, String>();
			h.put("content-type", "application/json");
			return new Response(status, h, body.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public Response send(Request r) throws IOException {
			String path = r.url.replaceFirst("^https?://[^/]+", "");
			String body = r.body == null ? "" : new String(r.body, StandardCharsets.UTF_8);
			calls.add(r.method + " " + path);
			if (path.equals("/minecraft/profile") && r.method.equals("GET")) {
				return json(200, "{\"id\":\"5ce0000000000000000000000000abcd\",\"name\":\"Tester\",\"skins\":[],\"capes\":["
						+ "{\"id\":\"2340c0e0-3dd2-4d4c-9b6f-7c9c4f3a2b1c\",\"state\":\"INACTIVE\",\"alias\":\"Migrator\"}]}");
			}
			if (path.equals("/minecraft/profile/skins") && r.method.equals("POST")) {
				lastMultipart = new String(r.body, StandardCharsets.ISO_8859_1);
				return json(200, "{\"id\":\"5ce0000000000000000000000000abcd\",\"name\":\"Tester\",\"skins\":[],\"capes\":[]}");
			}
			if (path.equals("/v1/me/skin-changed")) return json(204, "");
			if (path.equals("/v1/capes")) return json(200, "{\"capes\":[{\"id\":\"redstone\",\"name\":\"Redstone\",\"owned\":true,\"active\":true,\"frames\":1}]}");
			if (path.equals("/v1/me/sync") && r.method.equals("GET")) {
				StringBuilder sb = new StringBuilder("{\"skins\":[");
				boolean first = true;
				for (String id : skins.keySet()) {
					if (!first) sb.append(',');
					first = false;
					sb.append("{\"id\":\"").append(id).append("\",\"name\":\"").append(names.get(id))
							.append("\",\"variant\":\"slim\",\"sha256\":\"").append(SkinFiles.sha256(skins.get(id)))
							.append("\",\"updatedAt\":\"2026-09-25T09:00:00.000Z\"}");
				}
				sb.append("],\"deletedSkins\":[],\"wardrobe\":").append(wardrobe).append('}');
				return json(200, sb.toString());
			}
			if (path.startsWith("/v1/me/sync/skins/") && path.endsWith(".png")) {
				String id = path.substring("/v1/me/sync/skins/".length(), path.length() - 4);
				byte[] png = skins.get(id);
				if (png == null) return json(404, "{\"error\":{\"code\":\"skin_not_found\"}}");
				return new Response(200, new HashMap<String, String>(), png);
			}
			if (path.startsWith("/v1/me/sync/skins/") && r.method.equals("PUT")) {
				String id = path.substring("/v1/me/sync/skins/".length());
				com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(body).getAsJsonObject();
				String png = o.get("png").getAsString();
				String name = o.get("name").getAsString();
				skins.put(id, Base64.getDecoder().decode(png));
				names.put(id, name);
				return json(200, "{\"skin\":{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"variant\":\"classic\",\"sha256\":\""
						+ SkinFiles.sha256(skins.get(id)) + "\",\"updatedAt\":\"2026-09-25T11:00:00.000Z\"}}");
			}
			if (path.startsWith("/v1/me/sync/skins/") && r.method.equals("DELETE")) {
				String id = path.substring("/v1/me/sync/skins/".length());
				skins.remove(id);
				return json(204, "");
			}
			if (path.equals("/v1/me/sync/wardrobe") && r.method.equals("PUT")) {
				wardrobePuts++;
				if (staleCurrent != null) {
					String cur = staleCurrent;
					staleCurrent = null;
					wardrobe = cur;
					return json(409, "{\"error\":{\"code\":\"stale\",\"message\":\"x\",\"current\":" + cur + "}}");
				}
				wardrobe = body;
				return json(200, "{\"wardrobe\":" + body + "}");
			}
			throw new IOException("keine Route: " + r.method + " " + path);
		}
	}

	final class Plat implements WardrobeService.Platform {
		String token = "tok";
		int lookChanges;
		boolean trsCapeChanged;

		@Override
		public SessionData session() {
			return new SessionData("5ce0000000000000000000000000abcd", "Tester", "mc-access-token-123", null);
		}

		@Override
		public String trsToken() {
			return token;
		}

		@Override
		public String apiBase() {
			return "https://api.test";
		}

		@Override
		public void trsTokenRejected(String t) {
			token = null;
		}

		@Override
		public void lookChanged(byte[] skinPng, boolean slim, byte[] capePng, boolean capeChanged) {
			lookChanges++;
		}

		@Override
		public void trsCapeChanged() {
			trsCapeChanged = true;
		}

		@Override
		public Path configDir() {
			return dir;
		}

		@Override
		public String userAgent() {
			return "test";
		}

		@Override
		public void log(String message) {
		}
	}

	private WardrobeService service(Fake http, Plat plat) {
		return WardrobeService.create(plat, http, new MojangServices(http, "https://services.test", "https://mojang.test"),
				new SafeFetch("test"), new Direct());
	}

	@Test
	void syncsBothWaysAndAppliesSkins() throws Exception {
		Fake http = new Fake();
		byte[] remote = PngWriter.write(64, 64, SkinEditor.blankTemplate(true));
		http.skins.put("aaaaaaaaaaaa", remote);
		http.names.put("aaaaaaaaaaaa", "Remote");
		Plat plat = new Plat();
		WardrobeService s = service(http, plat);
		s.open();
		WardrobeService.State st = s.state();
		assertEquals(WardrobeService.Task.NONE, st.task);
		assertEquals(1, st.skins.size());
		assertEquals("Remote", st.skins.get(0).name);
		assertTrue(st.skins.get(0).slim);
		assertTrue(st.doc.favorite("aaaaaaaaaaaa"), "Favoriten aus dem Server-Dokument");
		assertEquals(1, st.mojangCapes.size());
		assertEquals("trs:redstone", st.activeTrsCape);
		assertTrue(st.trs);
		assertTrue(Files.isRegularFile(dir.resolve("trsclient/wardrobe/5ce0000000000000000000000000abcd/skins/aaaaaaaaaaaa.png")));

		// Neuer Skin aus dem Editor → sofort hochgeladen
		s.saveEdited(SkinEditor.blankTemplate(false), false, "Eigener", null);
		st = s.state();
		assertNotNull(st.added);
		assertEquals(2, st.skins.size());
		assertEquals("Eigener", http.names.get(st.added));
		assertFalse(st.skin(st.added).pending);

		// Anwenden → Mojang-Upload (multipart mit Variante) + TRS-Meldung + Menü-Figur
		s.apply("aaaaaaaaaaaa");
		assertTrue(http.lastMultipart.contains("name=\"variant\"\r\n\r\nslim"));
		assertTrue(http.lastMultipart.contains("PNG"));
		assertTrue(http.calls.contains("POST /v1/me/skin-changed"));
		assertEquals(1, plat.lookChanges);
		assertEquals(st.skin("aaaaaaaaaaaa").look, s.state().activeLook);
		assertEquals("wardrobe.msg.applied", s.state().message);

		// Favorit lokal ändern; Server hat inzwischen einen neueren Stand → 409 → der Server-Stand gewinnt
		s.toggleFavorite(st.added);
		assertTrue(s.state().doc.favorite(st.added));
		http.staleCurrent = "{\"data\":{\"favorites\":[],\"outfits\":[],\"emoteSlots\":[\"tanzen\",\"winken\"]},\"updatedAt\":\"2099-01-01T00:00:00.000Z\"}";
		s.open();
		assertTrue(http.wardrobePuts >= 1);
		assertTrue(s.state().doc.favorites.isEmpty(), "neuerer Server-Stand übernommen");
		assertEquals("tanzen", EmoteSlots.current().get(0));

		// Löschen → DELETE auf dem Server, Grabstein-frei
		s.delete(st.added);
		assertNull(s.state().skin(st.added));
		assertFalse(http.skins.containsKey(st.added));
	}

	@Test
	void offlineStaysLocalAndUploadsLater() throws Exception {
		Fake http = new Fake();
		http.wardrobe = "null";
		Plat plat = new Plat();
		plat.token = null;
		WardrobeService s = service(http, plat);
		s.open();
		s.saveEdited(SkinEditor.blankTemplate(false), false, "Offline", null);
		String id = s.state().added;
		assertNotNull(id);
		assertFalse(s.state().trs);
		assertTrue(http.skins.isEmpty());
		s.toggleFavorite(id);
		// Später mit TRS: hochladen + Dokument schreiben
		plat.token = "tok";
		s.open();
		assertEquals("Offline", http.names.get(id));
		assertTrue(http.wardrobe.contains(id), "Favorit ins Dokument geschrieben: " + http.wardrobe);
	}
}

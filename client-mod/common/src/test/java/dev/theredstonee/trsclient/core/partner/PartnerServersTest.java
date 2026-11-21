package dev.theredstonee.trsclient.core.partner;

import com.google.gson.JsonParser;
import dev.theredstonee.trsclient.core.online.Http;
import dev.theredstonee.trsclient.core.ui.menus.PartnerSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartnerServersTest {
	private static final String BODY = "{\"servers\":["
			+ "{\"id\":\"ps_ch1\",\"partner\":{\"slug\":\"craftinghosting\",\"name\":\"CraftingHosting\"},\"name\":\"CraftingHosting Lobby\","
			+ "\"address\":\"Lobby.CraftingHosting.free:25565\",\"motd\":\"§aKostenlos\\u0007 in  2 Minuten\","
			+ "\"icon\":\"/v1/partners/craftinghosting/logo.png?v=abc123\",\"minVersion\":\"1.8\",\"maxVersion\":\"1.21.11\"},"
			+ "{\"id\":\"ps_old\",\"partner\":{\"slug\":\"retro\",\"name\":\"Retro\"},\"name\":\"Retro\",\"address\":\"retro.example.net\","
			+ "\"minVersion\":\"1.8\",\"maxVersion\":\"1.12.2\"}],\"ttl\":120}";

	private static final class FakeHttp implements Http {
		final List<Request> requests = new ArrayList<Request>();
		int status = 200;
		String body = BODY;
		boolean down;

		@Override
		public Response send(Request request) throws IOException {
			requests.add(request);
			if (down) throw new IOException("offline");
			Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("etag", "\"v1\"");
			if ("\"v1\"".equals(request.headers.get("If-None-Match")) && request.method.equals("GET") && status == 200) {
				return new Response(304, headers, new byte[0]);
			}
			byte[] b = request.method.equals("POST") ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
			return new Response(request.method.equals("POST") ? 204 : status, headers, b);
		}
	}

	private static final class FakeEnv implements PartnerServers.Env {
		boolean on = true;
		String token;

		@Override
		public boolean enabled() {
			return on;
		}

		@Override
		public String apiBase() {
			return "https://api.test";
		}

		@Override
		public String token() {
			return token;
		}
	}

	private static PartnerServers service(Path dir, Http http, PartnerServers.Env env, String mc) {
		PartnerServers s = new PartnerServers();
		s.init(dir, http, mc, env, Runnable::run);
		return s;
	}

	@Test
	void parsesAndCleansEntries() {
		List<PartnerServer> list = PartnerServer.parseList(new JsonParser().parse(BODY).getAsJsonObject().get("servers"));
		assertEquals(2, list.size());
		PartnerServer ch = list.get(0);
		assertEquals("lobby.craftinghosting.free", ch.address);
		assertEquals("Kostenlos in 2 Minuten", ch.motd);
		assertEquals("CraftingHosting", ch.partnerName);
		assertEquals("/v1/partners/craftinghosting/logo.png?v=abc123", ch.icon);
		assertNull(list.get(1).icon);
	}

	@Test
	void dropsBadEntriesAndForeignIcons() {
		String json = "[{\"id\":\"x1\",\"address\":\"a.net\"},"
				+ "{\"id\":\"ps_a\",\"address\":\"bad host\"},"
				+ "{\"id\":\"ps_b\",\"address\":\"b.net\",\"icon\":\"https://evil.example/logo.png\",\"name\":\"" + repeat('n', 80) + "\"},"
				+ "{\"id\":\"ps_c\",\"address\":\"B.net\"},"
				+ "{\"id\":\"ps_d\",\"address\":\"d.net\"},{\"id\":\"ps_e\",\"address\":\"e.net\"},{\"id\":\"ps_f\",\"address\":\"f.net\"}]";
		List<PartnerServer> list = PartnerServer.parseList(new JsonParser().parse(json));
		assertEquals(PartnerServer.MAX, list.size());
		assertEquals("ps_b", list.get(0).id);
		assertNull(list.get(0).icon);
		assertEquals(PartnerServer.MAX_NAME, list.get(0).name.length());
		assertEquals("ps_d", list.get(1).id, "doppelte Adresse fällt weg");
	}

	@Test
	void versionRange() {
		PartnerServer s = PartnerServer.parse(new JsonParser().parse(
				"{\"id\":\"ps_1\",\"address\":\"a.net\",\"minVersion\":\"1.8\",\"maxVersion\":\"1.21.11\"}"));
		assertNotNull(s);
		assertTrue(s.supports("1.8.9"));
		assertTrue(s.supports("1.21.11"));
		assertFalse(s.supports("1.7.10"));
		assertFalse(s.supports("26.1"));
		assertTrue(s.supports(null));
		assertTrue(PartnerServer.compare("1.21.11", "1.21.9") > 0);
		assertEquals(0, PartnerServer.compare("1.8", "1.8.0"));
	}

	@Test
	void fetchesOncePerTtlWithEtag(@TempDir Path dir) {
		FakeHttp http = new FakeHttp();
		FakeEnv env = new FakeEnv();
		PartnerServers s = service(dir, http, env, "1.21.11");
		long t = 1_000_000L;
		List<PartnerServer> first = s.servers(t);
		assertEquals(1, http.requests.size());
		assertEquals("https://api.test/v1/partners/servers?mc=1.21.11", http.requests.get(0).url);
		assertNull(http.requests.get(0).headers.get("If-None-Match"));
		// Abruf lief direkt (Executor im Test), Version 1.12-Server fällt weg
		assertEquals(1, s.servers(t + 1).size());
		assertTrue(first.isEmpty() || first.size() == 1);
		s.servers(t + 119_000);
		assertEquals(1, http.requests.size(), "vor Ablauf der ttl kein neuer Abruf");
		s.servers(t + 120_000);
		assertEquals(2, http.requests.size());
		assertEquals("\"v1\"", http.requests.get(1).headers.get("If-None-Match"));
		assertEquals(1, s.servers(t + 120_001).size(), "304 behält die Liste");
	}

	@Test
	void diskCacheServesOfflineStart(@TempDir Path dir) {
		FakeHttp http = new FakeHttp();
		FakeEnv env = new FakeEnv();
		long t = System.currentTimeMillis();
		service(dir, http, env, "1.21.11").servers(t);

		FakeHttp offline = new FakeHttp();
		offline.down = true;
		PartnerServers again = service(dir, offline, env, "1.21.11");
		assertEquals(1, again.servers(t + 1000).size(), "aus der Datei, ohne Abruf (ttl läuft noch)");
		assertEquals(0, offline.requests.size());
		assertEquals(1, again.servers(t + 200_000).size(), "offline: Liste bleibt");
		assertEquals(1, offline.requests.size());
		again.servers(t + 200_001);
		assertEquals(1, offline.requests.size(), "nach Fehler erst nach der Wartezeit wieder");

		PartnerServers otherVersion = service(dir, offline, env, "1.8.9");
		assertTrue(otherVersion.servers(t + 1000).isEmpty(), "Cache einer anderen Version gilt nicht");
	}

	@Test
	void disabledMeansNoRequest(@TempDir Path dir) {
		FakeHttp http = new FakeHttp();
		FakeEnv env = new FakeEnv();
		env.on = false;
		PartnerServers s = service(dir, http, env, "1.21.11");
		assertTrue(s.servers(5000).isEmpty());
		assertEquals(0, http.requests.size());
		env.on = true;
		assertFalse(s.servers(6000).isEmpty());
	}

	@Test
	void clampsTtl() {
		assertEquals(PartnerServers.MIN_TTL, PartnerServers.ttl(new JsonParser().parse("5")));
		assertEquals(PartnerServers.MAX_TTL, PartnerServers.ttl(new JsonParser().parse("99999999")));
		assertEquals(PartnerServers.DEFAULT_TTL, PartnerServers.ttl(null));
		assertEquals(PartnerServers.DEFAULT_TTL, PartnerServers.ttl(new JsonParser().parse("\"x\"")));
	}

	@Test
	void reportsJoinWithTokenIfThere(@TempDir Path dir) {
		FakeHttp http = new FakeHttp();
		FakeEnv env = new FakeEnv();
		PartnerServers s = service(dir, http, env, "1.21.11");
		PartnerServer server = s.servers(1).get(0);
		s.reportJoin(server);
		Http.Request anon = http.requests.get(http.requests.size() - 1);
		assertEquals("POST", anon.method);
		assertEquals("https://api.test/v1/partners/servers/ps_ch1/join", anon.url);
		assertNull(anon.headers.get("Authorization"));
		env.token = "trs_secret";
		s.reportJoin(server);
		assertEquals("Bearer trs_secret", http.requests.get(http.requests.size() - 1).headers.get("Authorization"));
	}

	@Test
	void pingsAgainOnlyWhenDue() {
		final List<String> asked = new ArrayList<String>();
		PartnerPings pings = new PartnerPings((address, timeoutMs) -> {
			asked.add(address);
			return null;
		}, Runnable::run);
		assertNull(pings.result("a.net", 0));
		assertEquals(1, asked.size());
		pings.result("a.net", 0);
		assertEquals(1, asked.size(), "nach einem Fehler erst nach der Wartezeit");
		pings.result("a.net", System.currentTimeMillis() + PartnerPings.RETRY_MS + 1);
		assertEquals(2, asked.size());
	}

	@Test
	void sectionLayoutAndHits() {
		assertEquals(0, PartnerSection.height(0));
		assertEquals(PartnerSection.TITLE_H + 2 * PartnerSection.ROW + PartnerSection.OWN_H, PartnerSection.height(2));
		int x = 100, y = 40, w = 300;
		assertEquals(PartnerSection.Target.ROW, PartnerSection.hit(2, x, y, w, 32, 200, 150, PartnerSection.rowTop(y, 1) + 5).target);
		assertEquals(1, PartnerSection.hit(2, x, y, w, 32, 200, 150, PartnerSection.rowTop(y, 1) + 5).row);
		assertEquals(PartnerSection.Target.HIDE, PartnerSection.hit(2, x, y, w, 32, 200, x + w - 3, y + 4).target);
		assertEquals(PartnerSection.Target.NONE, PartnerSection.hit(2, x, y, w, 32, 200, 150, y + PartnerSection.height(2) - 3).target);
		assertEquals(PartnerSection.Target.NONE, PartnerSection.hit(2, x, y, w, 60, 200, 150, PartnerSection.rowTop(y, 0) + 1).target,
				"außerhalb der sichtbaren Liste");
		assertEquals(PartnerSection.Target.NONE, PartnerSection.hit(0, x, y, w, 0, 999, 150, y + 20).target);
	}

	private static String repeat(char c, int n) {
		return String.join("", Collections.nCopies(n, String.valueOf(c)));
	}
}

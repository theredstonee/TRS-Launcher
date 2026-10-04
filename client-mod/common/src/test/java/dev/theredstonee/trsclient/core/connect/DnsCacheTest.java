package dev.theredstonee.trsclient.core.connect;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SRV-Zeilen, IP-Text, Zwischenspeicher (TTL), Launcher-Hinweise. */
class DnsCacheTest {
	static final class FakeClock implements DnsCache.Clock {
		long now = 1_000_000L;

		@Override
		public long millis() {
			return now;
		}
	}

	static byte[] ip(String s) {
		return IpLiteral.parse(s);
	}

	// --- SRV ---

	@Test
	void srvParse() {
		SrvRecord r = SrvRecord.parse("0 5 25577 mc.example.net.");
		assertNotNull(r);
		assertEquals(25577, r.port);
		assertEquals("mc.example.net.", r.target, "trailing dot kept exactly like vanilla sends it");
		assertEquals(5, r.weight);
		assertNotNull(SrvRecord.parse("  10   0 25565   Lobby-1.Example.NET  "));
		assertNull(SrvRecord.parse("0 5 25577"));
		assertNull(SrvRecord.parse("0 5 0 mc.example.net."));
		assertNull(SrvRecord.parse("0 5 70000 mc.example.net."));
		assertNull(SrvRecord.parse("0 5 25565 ."), "'.' means: no service");
		assertNull(SrvRecord.parse("a b c d"));
		assertNull(SrvRecord.parse("0 5 25565 bad/host"));
		assertNull(SrvRecord.parse(null));
	}

	@Test
	void srvPickLowestPriorityThenHighestWeight() {
		SrvRecord r = SrvRecord.pick(Arrays.asList("20 100 25570 backup.example.net.", "10 5 25566 a.example.net.",
				"10 50 25567 b.example.net.", "garbage"));
		assertEquals("b.example.net.", r.target);
		assertEquals(25567, r.port);
		assertNull(SrvRecord.pick(Collections.singletonList("nonsense")));
		assertNull(SrvRecord.pick(null));
	}

	// --- IP-Text ---

	@Test
	void ipLiteralsWithoutDns() {
		assertArrayEquals(new byte[]{(byte) 203, 0, 113, 7}, IpLiteral.parse("203.0.113.7"));
		assertEquals(16, IpLiteral.parse("2001:db8::1").length);
		assertEquals(16, IpLiteral.parse("[::1]").length);
		assertNull(IpLiteral.parse("256.1.1.1"));
		assertNull(IpLiteral.parse("1.2.3"));
		assertNull(IpLiteral.parse("example.com"));
		assertNull(IpLiteral.parse("fe80::1%eth0"), "no zone ids");
		assertNull(IpLiteral.parse("zz::1"), "would be a DNS lookup in Java");
		assertNull(IpLiteral.parse(""));
		assertNull(IpLiteral.parse(null));
	}

	// --- Speicher ---

	@Test
	void keyNormalizesCaseAndTrailingDot() {
		assertEquals("mc.example.net", DnsCache.key("Mc.Example.NET."));
		assertEquals("mc.example.net", DnsCache.key(" mc.example.net "));
	}

	@Test
	void addressesExpireAfterTtlWithCaps() {
		FakeClock clock = new FakeClock();
		DnsCache c = new DnsCache(clock);
		c.putAddresses("play.example.net", Arrays.asList(ip("203.0.113.1"), ip("2001:db8::1")), 30, DnsCache.Source.GAME);
		assertNotNull(c.addresses("PLAY.example.net."));
		clock.now += 29_999;
		assertNotNull(c.addresses("play.example.net"));
		clock.now += 1;
		assertNull(c.addresses("play.example.net"), "expired exactly at the TTL");

		// Riesige TTL → höchstens 10 min.
		c.putAddresses("a.example.net", Collections.singletonList(ip("203.0.113.2")), 86_400, DnsCache.Source.GAME);
		clock.now += DnsCache.MAX_TTL_MS - 1;
		assertNotNull(c.addresses("a.example.net"));
		clock.now += 1;
		assertNull(c.addresses("a.example.net"));

		// TTL 0 → Mindestzeit; unbekannt → Standard.
		c.putAddresses("b.example.net", Collections.singletonList(ip("203.0.113.3")), 0, DnsCache.Source.GAME);
		c.putAddresses("c.example.net", Collections.singletonList(ip("203.0.113.4")), -1, DnsCache.Source.GAME);
		clock.now += DnsCache.MIN_TTL_MS;
		assertNull(c.addresses("b.example.net"));
		assertNotNull(c.addresses("c.example.net"));
		clock.now += DnsCache.DEFAULT_TTL_MS;
		assertNull(c.addresses("c.example.net"));
	}

	@Test
	void inetAddressesKeepTheExactNameAndPutWinnerFirst() throws Exception {
		DnsCache c = new DnsCache(new FakeClock());
		c.putAddresses("mc.example.net.", Arrays.asList(ip("2001:db8::1"), ip("203.0.113.1")), -1, DnsCache.Source.GAME);
		c.winner("mc.example.net", ip("203.0.113.1"));
		List<InetAddress> l = c.inetAddresses("mc.example.net.");
		assertEquals(2, l.size());
		assertEquals("203.0.113.1", l.get(0).getHostAddress());
		// Handshake-Name: genau so, wie Vanilla ihn übergab (mit Punkt, ohne Rückwärts-Auflösung).
		assertEquals("mc.example.net.", l.get(0).getHostName());
		assertEquals("mc.example.net.", l.get(1).getHostName());
	}

	@Test
	void duplicatesAndBadLengthsAreDropped() {
		DnsCache c = new DnsCache(new FakeClock());
		c.putAddresses("x.example.net", Arrays.asList(ip("203.0.113.1"), ip("203.0.113.1"), new byte[3], null), -1,
				DnsCache.Source.GAME);
		assertEquals(1, c.addresses("x.example.net").ips.size());
		c.putAddresses("y.example.net", new ArrayList<byte[]>(), -1, DnsCache.Source.GAME);
		assertNull(c.addresses("y.example.net"));
	}

	@Test
	void srvCacheKnowsNoneVersusUnknown() {
		FakeClock clock = new FakeClock();
		DnsCache c = new DnsCache(clock);
		assertNull(c.srv("a.example.net"), "unknown");
		c.putSrv("a.example.net", null, -1);
		DnsCache.Srv s = c.srv("a.example.net");
		assertNotNull(s);
		assertNull(s.record, "known: no SRV record");
		c.putSrv("b.example.net", SrvRecord.parse("0 0 25577 mc.example.net."), 60);
		assertEquals(25577, c.srv("B.example.net").record.port);
		clock.now += 60_000;
		assertNull(c.srv("b.example.net"));
	}

	@Test
	void invalidateForgetsEverything() {
		DnsCache c = new DnsCache(new FakeClock());
		c.putAddresses("a.example.net", Collections.singletonList(ip("203.0.113.1")), -1, DnsCache.Source.GAME);
		c.putSrv("a.example.net", null, -1);
		c.winner("a.example.net", ip("203.0.113.1"));
		c.invalidate("A.example.net.");
		assertNull(c.addresses("a.example.net"));
		assertNull(c.srv("a.example.net"));
		assertNull(c.winner("a.example.net"));
	}

	@Test
	void boundedNumberOfHosts() {
		DnsCache c = new DnsCache(new FakeClock());
		for (int i = 0; i < DnsCache.MAX_HOSTS + 20; i++) {
			c.putAddresses("h" + i + ".example.net", Collections.singletonList(new byte[]{10, 0, (byte) (i >> 8), (byte) i}), -1,
					DnsCache.Source.GAME);
		}
		assertEquals(DnsCache.MAX_HOSTS, c.size());
		assertNull(c.addresses("h0.example.net"), "oldest dropped");
		assertNotNull(c.addresses("h" + (DnsCache.MAX_HOSTS + 19) + ".example.net"));
	}

	// --- Launcher-Hinweise ---

	static ConnectHints.HostDto host(String name, String srv, long expires, String fastest, String... ips) {
		ConnectHints.HostDto h = new ConnectHints.HostDto();
		h.name = name;
		h.srv = srv;
		h.expires = expires;
		h.fastest = fastest;
		h.ips = Arrays.asList(ips);
		return h;
	}

	@Test
	void hintsFillTheCacheAndExpire() {
		FakeClock clock = new FakeClock();
		DnsCache c = new DnsCache(clock);
		ConnectHints.FileDto f = new ConnectHints.FileDto();
		f.version = 1;
		f.hosts = Arrays.asList(
				host("play.example.net", "0 5 25577 mc.example.net.", clock.now + 60_000, null),
				host("mc.example.net.", null, clock.now + 60_000, "203.0.113.9", "2001:db8::9", "203.0.113.9"),
				host("old.example.net", "", clock.now - 1, null, "203.0.113.1"),
				host("evil.example.net", null, clock.now + 60_000, null, "example.com", "300.1.1.1"),
				host("bad name/x", null, clock.now + 60_000, null, "203.0.113.5"));
		assertEquals(3, ConnectHints.apply(f, c));
		assertEquals("mc.example.net.", c.srv("play.example.net").record.target);
		List<InetAddress> l = c.inetAddresses("mc.example.net.");
		assertEquals("203.0.113.9", l.get(0).getHostAddress(), "launcher's fastest first");
		assertEquals(DnsCache.Source.LAUNCHER, c.addresses("mc.example.net").source);
		assertNull(c.addresses("old.example.net"), "expired hint ignored");
		assertNull(c.addresses("evil.example.net"), "only IP literals are taken, never names");
		assertNull(c.addresses("bad name/x"));
		clock.now += 60_000;
		assertNull(c.addresses("mc.example.net"));
		assertNull(c.srv("play.example.net"));
	}

	/** Genau die Form, die der Launcher schreibt (Rust-Test connect_hints::json_matches_the_contract). */
	@Test
	void launcherJsonIsUnderstood() throws Exception {
		long exp = System.currentTimeMillis() + 60_000;
		String json = "{\"version\":1,\"join\":\"play.example.net\",\"hosts\":["
				+ "{\"name\":\"play.example.net\",\"srv\":\"0 5 25577 mc.example.net.\",\"ips\":[\"203.0.113.7\"],\"expires\":" + exp + "},"
				+ "{\"name\":\"mc.example.net.\",\"ips\":[\"203.0.113.7\",\"2001:db8::7\"],\"fastest\":\"203.0.113.7\",\"expires\":" + exp + "},"
				+ "{\"name\":\"none.example.net\",\"srv\":\"\",\"ips\":[\"203.0.113.7\"],\"expires\":" + exp + "}]}";
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("trs-hints2");
		try {
			java.nio.file.Path f = dir.resolve("trsclient").resolve(ConnectHints.FILE);
			java.nio.file.Files.createDirectories(f.getParent());
			java.nio.file.Files.write(f, json.getBytes("UTF-8"));
			DnsCache c = new DnsCache();
			ConnectHints h = new ConnectHints(dir);
			assertEquals(3, h.refresh(c));
			assertEquals("play.example.net", h.join());
			assertEquals("mc.example.net.", c.srv("play.example.net").record.target);
			assertEquals(25577, c.srv("play.example.net").record.port);
			assertNull(c.srv("none.example.net").record);
			assertEquals("203.0.113.7", c.inetAddresses("mc.example.net.").get(0).getHostAddress());
			assertEquals(2, c.addresses("mc.example.net").ips.size());
		} finally {
			java.nio.file.Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	@Test
	void hintsWithWrongVersionAreIgnored() {
		DnsCache c = new DnsCache(new FakeClock());
		ConnectHints.FileDto f = new ConnectHints.FileDto();
		f.version = 2;
		f.hosts = Collections.singletonList(host("a.example.net", null, Long.MAX_VALUE, null, "203.0.113.1"));
		assertEquals(0, ConnectHints.apply(f, c));
		assertEquals(0, ConnectHints.apply(null, c));
		assertFalse(c.size() > 0);
	}

	@Test
	void hintsFileIsReadAndReloadedOnChange() throws Exception {
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("trs-hints");
		try {
			java.nio.file.Path f = dir.resolve("trsclient").resolve(ConnectHints.FILE);
			java.nio.file.Files.createDirectories(f.getParent());
			long exp = System.currentTimeMillis() + 60_000;
			java.nio.file.Files.write(f, ("{\"version\":1,\"hosts\":[{\"name\":\"play.example.net\",\"srv\":\"\",\"ips\":[\"203.0.113.7\"],"
					+ "\"expires\":" + exp + "}]}").getBytes("UTF-8"));
			DnsCache c = new DnsCache();
			ConnectHints h = new ConnectHints(dir);
			assertEquals(1, h.refresh(c));
			assertEquals(-1, h.refresh(c), "unchanged file is not read again");
			assertTrue(c.addresses("play.example.net") != null);
			java.nio.file.Files.write(f, "{not json".getBytes("UTF-8"));
			assertEquals(0, h.refresh(c), "broken file → nothing, no exception");
		} finally {
			java.nio.file.Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}
}

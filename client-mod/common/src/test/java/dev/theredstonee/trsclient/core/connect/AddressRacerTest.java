package dev.theredstonee.trsclient.core.connect;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Happy-Eyeballs-Rennen mit einer Netz-Attrappe (eigene Uhr) und einmal mit echten Sockets auf localhost. */
class AddressRacerTest {
	static final AddressRacer.Settings S = AddressRacer.DEFAULT;

	/** Netz-Attrappe: je IP eine Verzögerung und ein Ausgang; die Uhr läuft nur in {@link #poll}. */
	static final class FakeNet implements AddressRacer.Dialer<String>, AddressRacer.Clock {
		static final int OK = 1;
		static final int FAIL = 0;
		/** Antwortet nie (verlorene SYNs, kaputtes IPv6). */
		static final int NEVER = -1;
		long now = 1000;
		final Map<String, long[]> script = new LinkedHashMap<String, long[]>();
		final Map<String, Long> started = new LinkedHashMap<String, Long>();
		final Set<String> closed = new HashSet<String>();
		final Set<String> done = new HashSet<String>();
		final Set<String> refuseStart = new HashSet<String>();

		FakeNet on(String ip, long delay, int outcome) {
			try {
				script.put(InetAddress.getByAddress(IpLiteral.parse(ip)).getHostAddress(), new long[]{delay, outcome});
			} catch (IOException e) {
				throw new IllegalArgumentException(ip);
			}
			return this;
		}

		@Override
		public long millis() {
			return now;
		}

		@Override
		public String start(InetSocketAddress target) throws IOException {
			String ip = target.getAddress().getHostAddress();
			if (refuseStart.contains(ip)) throw new IOException("Network is unreachable");
			started.put(ip, now);
			return ip;
		}

		@Override
		public void poll(long timeoutMs, List<AddressRacer.Event<String>> out) {
			long best = Long.MAX_VALUE;
			for (Map.Entry<String, Long> e : started.entrySet()) {
				String ip = e.getKey();
				if (closed.contains(ip) || done.contains(ip)) continue;
				long[] s = script.get(ip);
				if (s == null || s[1] == NEVER) continue;
				best = Math.min(best, e.getValue() + s[0]);
			}
			if (best > now + timeoutMs) {
				now += timeoutMs;
				return;
			}
			now = Math.max(now, best);
			for (Map.Entry<String, Long> e : started.entrySet()) {
				String ip = e.getKey();
				if (closed.contains(ip) || done.contains(ip)) continue;
				long[] s = script.get(ip);
				if (s == null || s[1] == NEVER || e.getValue() + s[0] != best) continue;
				done.add(ip);
				out.add(new AddressRacer.Event<String>(ip, s[1] == OK, s[1] == OK ? null : new ConnectException("Connection refused")));
			}
		}

		@Override
		public void close(String handle) {
			closed.add(handle);
		}
	}

	static List<InetSocketAddress> targets(String... ips) throws IOException {
		List<InetSocketAddress> out = new ArrayList<InetSocketAddress>();
		for (String ip : ips) out.add(new InetSocketAddress(InetAddress.getByAddress("play.example.net", IpLiteral.parse(ip)), 25565));
		return out;
	}

	static AddressRacer.Result<String> run(FakeNet net, String... ips) throws IOException {
		return AddressRacer.race(targets(ips), net, net, S, null, null);
	}

	@Test
	void firstAddressAnswersFast_onlyOneAttempt() throws IOException {
		FakeNet net = new FakeNet().on("2001:db8::1", 30, FakeNet.OK).on("203.0.113.1", 30, FakeNet.OK);
		AddressRacer.Result<String> r = run(net, "2001:db8::1", "203.0.113.1");
		assertEquals("2001:db8:0:0:0:0:0:1", r.winner);
		assertEquals(1, r.attempts.size());
		assertEquals(30, r.totalMs);
	}

	@Test
	void brokenIpv6_ipv4WinsAfterDelay() throws IOException {
		FakeNet net = new FakeNet().on("2001:db8::1", 0, FakeNet.NEVER).on("203.0.113.1", 40, FakeNet.OK);
		AddressRacer.Result<String> r = run(net, "2001:db8::1", "203.0.113.1");
		assertEquals("203.0.113.1", r.winner);
		assertEquals(250 + 40, r.totalMs);
		assertEquals(AddressRacer.Outcome.CANCELLED, r.attempts.get(0).outcome);
		assertTrue(net.closed.contains("2001:db8:0:0:0:0:0:1"), "the losing attempt is closed");
		assertFalse(net.closed.contains("203.0.113.1"), "the winner stays open");
	}

	@Test
	void slowButAliveFirstStillWinsIfFaster() throws IOException {
		// Erste antwortet nach 300 ms, zweite (ab 250 ms) bräuchte 200 ms → erste gewinnt, zweite wird geschlossen.
		FakeNet net = new FakeNet().on("203.0.113.1", 300, FakeNet.OK).on("203.0.113.2", 200, FakeNet.OK);
		AddressRacer.Result<String> r = run(net, "203.0.113.1", "203.0.113.2");
		assertEquals("203.0.113.1", r.winner);
		assertEquals(300, r.totalMs);
		assertTrue(net.closed.contains("203.0.113.2"));
	}

	@Test
	void refusedFirst_nextStartsImmediately() throws IOException {
		FakeNet net = new FakeNet().on("203.0.113.1", 20, FakeNet.FAIL).on("203.0.113.2", 20, FakeNet.OK);
		AddressRacer.Result<String> r = run(net, "203.0.113.1", "203.0.113.2");
		assertEquals("203.0.113.2", r.winner);
		assertEquals(40, r.totalMs, "no 250 ms wait after a failure");
		assertEquals(AddressRacer.Outcome.FAILED, r.attempts.get(0).outcome);
	}

	@Test
	void startErrorCountsAsFailure() throws IOException {
		FakeNet net = new FakeNet().on("203.0.113.2", 10, FakeNet.OK);
		net.refuseStart.add("2001:db8:0:0:0:0:0:1");
		AddressRacer.Result<String> r = run(net, "2001:db8::1", "203.0.113.2");
		assertEquals("203.0.113.2", r.winner);
		assertEquals(10, r.totalMs);
	}

	@Test
	void allRefused_noWinnerQuickly() throws IOException {
		FakeNet net = new FakeNet().on("203.0.113.1", 15, FakeNet.FAIL).on("203.0.113.2", 15, FakeNet.FAIL)
				.on("203.0.113.3", 15, FakeNet.FAIL);
		AddressRacer.Result<String> r = run(net, "203.0.113.1", "203.0.113.2", "203.0.113.3");
		assertNull(r.winner);
		assertFalse(r.ok());
		assertEquals(3, r.attempts.size());
		assertEquals(45, r.totalMs);
	}

	@Test
	void allBlackholed_attemptTimeoutsThenOverallCap() throws IOException {
		FakeNet net = new FakeNet().on("203.0.113.1", 0, FakeNet.NEVER).on("203.0.113.2", 0, FakeNet.NEVER);
		AddressRacer.Result<String> r = run(net, "203.0.113.1", "203.0.113.2");
		assertNull(r.winner);
		// Erster Versuch nach 3 s aufgegeben, der letzte läuft bis zum Gesamtlimit.
		assertEquals(AddressRacer.Outcome.TIMEOUT, r.attempts.get(0).outcome);
		assertEquals(S.attemptMs, r.attempts.get(0).endMs - r.attempts.get(0).startMs);
		assertEquals(AddressRacer.Outcome.TIMEOUT, r.attempts.get(1).outcome);
		assertEquals(S.overallMs, r.totalMs);
		assertTrue(net.closed.containsAll(Arrays.asList("203.0.113.1", "203.0.113.2")));
	}

	@Test
	void lastSurvivorMayTakeLongerThanAttemptTimeout() throws IOException {
		// Erste tot, zweite braucht 7 s (verlorenes SYN) → gewinnt trotzdem (letzter Versuch, Gesamtlimit 12 s).
		FakeNet net = new FakeNet().on("203.0.113.1", 0, FakeNet.NEVER).on("203.0.113.2", 7000, FakeNet.OK);
		AddressRacer.Result<String> r = run(net, "203.0.113.1", "203.0.113.2");
		assertEquals("203.0.113.2", r.winner);
		assertEquals(250 + 7000, r.totalMs);
	}

	@Test
	void manyAddresses_staggeredEvery250ms() throws IOException {
		FakeNet net = new FakeNet().on("203.0.113.1", 0, FakeNet.NEVER).on("203.0.113.2", 0, FakeNet.NEVER)
				.on("203.0.113.3", 10, FakeNet.OK);
		AddressRacer.Result<String> r = run(net, "203.0.113.1", "203.0.113.2", "203.0.113.3");
		assertEquals("203.0.113.3", r.winner);
		assertEquals(Long.valueOf(1000), net.started.get("203.0.113.1"));
		assertEquals(Long.valueOf(1250), net.started.get("203.0.113.2"));
		assertEquals(Long.valueOf(1500), net.started.get("203.0.113.3"));
		assertEquals(510, r.totalMs);
	}

	@Test
	void cancelStopsTheRace() throws IOException {
		final FakeNet net = new FakeNet().on("203.0.113.1", 0, FakeNet.NEVER).on("203.0.113.2", 0, FakeNet.NEVER);
		AddressRacer.Result<String> r = AddressRacer.race(targets("203.0.113.1", "203.0.113.2"), net, net, S, null,
				new AddressRacer.Cancel() {
					@Override
					public boolean cancelled() {
						return net.now >= 1600;
					}
				});
		assertTrue(r.cancelled);
		assertNull(r.winner);
		assertTrue(r.totalMs < 1000);
		assertEquals(2, net.closed.size());
	}

	@Test
	void listenerSeesEveryAttempt() throws IOException {
		FakeNet net = new FakeNet().on("2001:db8::1", 0, FakeNet.NEVER).on("203.0.113.1", 5, FakeNet.OK);
		final List<String> seen = new ArrayList<String>();
		AddressRacer.race(targets("2001:db8::1", "203.0.113.1"), net, net, S, new AddressRacer.Listener() {
			@Override
			public void attempt(int index, int total, InetSocketAddress target) {
				seen.add(index + "/" + total + " " + target.getAddress().getHostAddress());
			}
		}, null);
		assertEquals(Arrays.asList("1/2 2001:db8:0:0:0:0:0:1", "2/2 203.0.113.1"), seen);
	}

	// --- Reihenfolge (RFC 8305 §4) ---

	static List<InetAddress> addrs(String... ips) throws IOException {
		List<InetAddress> out = new ArrayList<InetAddress>();
		for (String ip : ips) out.add(InetAddress.getByAddress("h", IpLiteral.parse(ip)));
		return out;
	}

	static List<String> names(List<InetAddress> l) {
		List<String> out = new ArrayList<String>();
		for (InetAddress a : l) out.add(a.getHostAddress());
		return out;
	}

	@Test
	void orderInterleavesFamiliesStartingWithFirst() throws IOException {
		List<InetAddress> o = AddressRacer.order(addrs("203.0.113.1", "203.0.113.2", "2001:db8::1", "2001:db8::2"), null);
		assertEquals(Arrays.asList("203.0.113.1", "2001:db8:0:0:0:0:0:1", "203.0.113.2", "2001:db8:0:0:0:0:0:2"), names(o));
		o = AddressRacer.order(addrs("2001:db8::1", "203.0.113.1", "203.0.113.2"), null);
		assertEquals(Arrays.asList("2001:db8:0:0:0:0:0:1", "203.0.113.1", "203.0.113.2"), names(o));
	}

	@Test
	void orderPutsLastWinnerFirstAndDropsDuplicates() throws IOException {
		List<InetAddress> o = AddressRacer.order(addrs("2001:db8::1", "203.0.113.1", "203.0.113.1", "203.0.113.2"),
				IpLiteral.parse("203.0.113.2"));
		assertEquals(Arrays.asList("203.0.113.2", "2001:db8:0:0:0:0:0:1", "203.0.113.1"), names(o));
		assertEquals("h", o.get(0).getHostName(), "names stay attached (no reverse lookup)");
	}

	// --- echte Sockets ---

	@Test
	void realSockets_refusedThenListening() throws Exception {
		int closedPort;
		try (ServerSocket tmp = new ServerSocket(0)) {
			closedPort = tmp.getLocalPort();
		}
		try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
			List<InetSocketAddress> t = new ArrayList<InetSocketAddress>();
			t.add(new InetSocketAddress(InetAddress.getByAddress("local.test", new byte[]{127, 0, 0, 1}), closedPort));
			t.add(new InetSocketAddress(InetAddress.getByAddress("local.test", new byte[]{127, 0, 0, 1}), ss.getLocalPort()));
			AddressRacer.Result<SocketChannel> r;
			try (NioDialer d = new NioDialer()) {
				r = AddressRacer.race(t, d, AddressRacer.MONOTONIC, S, null, null);
			}
			assertNotNull(r.winner);
			try (SocketChannel ch = r.winner; Socket accepted = ss.accept()) {
				assertTrue(ch.isConnected());
				assertEquals(ss.getLocalPort(), r.address.getPort());
				assertEquals("local.test", r.address.getHostString());
				assertNotNull(accepted);
			}
			// Windows meldet „abgelehnt“ auf localhost erst nach ~2 s – dann läuft der erste Versuch beim Sieg noch.
			assertTrue(r.attempts.get(0).outcome == AddressRacer.Outcome.FAILED
					|| r.attempts.get(0).outcome == AddressRacer.Outcome.CANCELLED, String.valueOf(r.attempts));
		}
	}
}

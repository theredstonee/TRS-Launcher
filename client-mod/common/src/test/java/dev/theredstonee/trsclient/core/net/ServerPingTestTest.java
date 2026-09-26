package dev.theredstonee.trsclient.core.net;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Status-Ping gegen eine lokale Server-Attrappe und die Reihenfolge des Ping-Tests. */
class ServerPingTestTest {
	/** Minimaler Status-Server: Handshake + Status → JSON, Ping → (nach {@code pongDelay} ms) Pong. */
	static ServerSocket fakeServer(final int pongDelay, final boolean answerPing) throws IOException {
		final ServerSocket ss = new ServerSocket(0);
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				while (!ss.isClosed()) {
					try (Socket s = ss.accept()) {
						DataInputStream in = new DataInputStream(s.getInputStream());
						OutputStream out = s.getOutputStream();
						byte[] hs = new byte[StatusPing.readVarInt(in)];
						in.readFully(hs);
						byte[] req = new byte[StatusPing.readVarInt(in)];
						in.readFully(req);
						byte[] json = "{\"version\":{\"name\":\"1.21.11\",\"protocol\":774},\"players\":{\"max\":20,\"online\":3},\"description\":\"x\"}"
								.getBytes(StandardCharsets.UTF_8);
						java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
						DataOutputStream d = new DataOutputStream(body);
						StatusPing.writeVarInt(d, 0);
						StatusPing.writeVarInt(d, json.length);
						d.write(json);
						StatusPing.frame(out, body.toByteArray());
						out.flush();
						if (!answerPing) continue;
						byte[] ping = new byte[StatusPing.readVarInt(in)];
						in.readFully(ping);
						Thread.sleep(pongDelay);
						StatusPing.frame(out, ping);
						out.flush();
					} catch (Exception ignored) {
						// nächster Versuch / Ende
					}
				}
			}
		}, "fake-slp");
		t.setDaemon(true);
		t.start();
		return ss;
	}

	@Test
	void pingMeasuresThePongRoundTrip() throws Exception {
		try (ServerSocket ss = fakeServer(40, true)) {
			StatusPing.Result r = StatusPing.ping("127.0.0.1:" + ss.getLocalPort(), 2000);
			assertTrue(r.ok, r.toString());
			assertTrue(r.latencyMs >= 40 && r.latencyMs < 1000, r.toString());
			assertEquals(3, r.online);
			assertEquals(20, r.max);
			assertEquals("1.21.11", r.version);
		}
	}

	@Test
	void serversWithoutPongStillCountWithTheConnectTime() throws Exception {
		try (ServerSocket ss = fakeServer(0, false)) {
			StatusPing.Result r = StatusPing.ping("127.0.0.1:" + ss.getLocalPort(), 500);
			assertTrue(r.ok, r.toString());
			assertEquals(r.connectMs, r.latencyMs);
		}
	}

	@Test
	void unreachableServersFailQuickly() throws Exception {
		int port;
		try (ServerSocket ss = new ServerSocket(0)) {
			port = ss.getLocalPort();
		}
		long t0 = System.nanoTime();
		StatusPing.Result r = StatusPing.ping("127.0.0.1:" + port, 1000);
		assertFalse(r.ok);
		assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000);
	}

	@Test
	void addressesAreParsedLikeVanilla() {
		assertArrayEquals(new String[]{"mc.example.org", null}, StatusPing.parse("mc.example.org"));
		assertArrayEquals(new String[]{"mc.example.org", "25570"}, StatusPing.parse(" mc.example.org:25570 "));
		assertArrayEquals(new String[]{"::1", "25565"}, StatusPing.parse("[::1]:25565"));
		assertArrayEquals(new String[]{"fe80::1", null}, StatusPing.parse("fe80::1"));
		assertNull(StatusPing.parse("host:99999"));
		assertNull(StatusPing.parse(""));
		assertNull(StatusPing.parse("host:abc"));
	}

	// --- Ping-Test der Serverliste ---

	private static StatusPing.Result ok(long ms) {
		return new StatusPing.Result(true, ms, ms, 1, 10, "v", null);
	}

	@Test
	void runsWithLimitedParallelismAndSortsByPing() throws Exception {
		final AtomicInteger running = new AtomicInteger();
		final AtomicInteger maxRunning = new AtomicInteger();
		ServerPingTest test = new ServerPingTest(new ServerPingTest.Pinger() {
			@Override
			public StatusPing.Result ping(String address, int timeoutMs) {
				int now = running.incrementAndGet();
				maxRunning.set(Math.max(maxRunning.get(), now));
				try {
					Thread.sleep(30);
				} catch (InterruptedException ignored) {
					// Test
				}
				running.decrementAndGet();
				if (address.startsWith("down")) return StatusPing.Result.failed("timeout");
				return ok(Long.parseLong(address.substring(address.indexOf('-') + 1)));
			}
		});
		List<String> servers = Arrays.asList("a-90", "b-20", "down1", "c-55", "d-20", "e-300", "down2", "f-5", "g-60", "h-61");
		assertTrue(test.start(servers, 0));
		assertFalse(test.start(servers, 100), "läuft schon");
		long until = System.currentTimeMillis() + 5000;
		while (test.running() && System.currentTimeMillis() < until) Thread.sleep(10);
		assertFalse(test.running());
		assertEquals(10, test.finished());
		assertTrue(maxRunning.get() <= ServerPingTest.PARALLEL, "höchstens " + ServerPingTest.PARALLEL + " gleichzeitig");
		assertEquals("20 ms", ServerPingTest.label(test.entry("b-20")));
		assertEquals("—", ServerPingTest.label(test.entry("down1")));

		boolean[] pinned = new boolean[servers.size()];
		pinned[4] = true; // d-20 angeheftet
		int[] order = test.order(servers, pinned);
		List<String> sorted = new ArrayList<String>();
		for (int i : order) sorted.add(servers.get(i));
		assertEquals(Arrays.asList("d-20", "f-5", "b-20", "c-55", "g-60", "h-61", "a-90", "e-300", "down1", "down2"), sorted);

		// Tausche wie Vanilla ergeben genau diese Reihenfolge.
		List<String> list = new ArrayList<String>(servers);
		for (int[] s : ServerPingTest.swaps(order)) {
			String a = list.get(s[0]);
			list.set(s[0], list.get(s[1]));
			list.set(s[1], a);
		}
		assertEquals(sorted, list);
		assertTrue(ServerPingTest.swaps(identity(10)).isEmpty());

		// Neuer Test erst nach der Wartezeit.
		assertFalse(test.start(servers, ServerPingTest.COOLDOWN_MS - 1));
		assertTrue(test.cooldownLeft(ServerPingTest.COOLDOWN_MS - 1) > 0);
		assertTrue(test.start(servers, ServerPingTest.COOLDOWN_MS));
	}

	private static int[] identity(int n) {
		int[] a = new int[n];
		for (int i = 0; i < n; i++) a[i] = i;
		return a;
	}
}

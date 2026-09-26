package dev.theredstonee.trsclient.core.hosting.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STUN, Relay-Protokoll (gegen ein nachgebautes Relay) und die UDP-Verbindung über Loopback. */
class NetTest {
	// --- STUN ---

	@Test
	void stunRequestAndResponseRoundTrip() throws Exception {
		byte[] txid = new byte[12];
		new Random(1).nextBytes(txid);
		byte[] req = Stun.bindingRequest(txid);
		assertEquals(20, req.length);
		assertTrue(Stun.isStun(req, 0, req.length));
		InetSocketAddress mapped = new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 54321);
		byte[] res = Stun.bindingResponse(txid, mapped);
		assertEquals(52, res.length);
		assertEquals(mapped, Stun.parseBindingResponse(res, 0, res.length, txid));
	}

	@Test
	void stunRejectsWrongTransactionFingerprintAndLength() throws Exception {
		byte[] txid = new byte[12];
		byte[] other = new byte[12];
		other[0] = 1;
		InetSocketAddress mapped = new InetSocketAddress(InetAddress.getByName("198.51.100.2"), 1234);
		byte[] res = Stun.bindingResponse(txid, mapped);
		assertNull(Stun.parseBindingResponse(res, 0, res.length, other));
		byte[] broken = res.clone();
		broken[broken.length - 1] ^= 1;
		assertNull(Stun.parseBindingResponse(broken, 0, broken.length, txid), "falscher FINGERPRINT");
		assertNull(Stun.parseBindingResponse(res, 0, res.length - 4, txid), "Länge passt nicht");
		assertNull(Stun.parseBindingResponse(new byte[] { 1, 2, 3 }, 0, 3, txid));
		// Nur MAPPED-ADDRESS (alter Server) – ohne FINGERPRINT.
		byte[] plain = Arrays.copyOf(res, 20 + 12 + 12);
		System.arraycopy(res, 32, plain, 20, 12);
		Stun.putShort(plain, 2, 12);
		assertEquals(mapped, Stun.parseBindingResponse(plain, 0, 32, txid));
	}

	@Test
	void stunAgainstLocalResponder() throws Exception {
		// Kleiner STUN-Antworter auf Loopback (wie das Relay): UdpLink.stun liefert die eigene Adresse zurück.
		final java.net.DatagramSocket server = new java.net.DatagramSocket(0, InetAddress.getLoopbackAddress());
		Thread t = new Thread(() -> {
			try {
				byte[] buf = new byte[600];
				java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
				server.receive(p);
				byte[] txid = Arrays.copyOfRange(buf, 8, 20);
				byte[] res = Stun.bindingResponse(txid, (InetSocketAddress) p.getSocketAddress());
				server.send(new java.net.DatagramPacket(res, res.length, p.getSocketAddress()));
			} catch (IOException ignored) {
				// Ende
			}
		});
		t.start();
		UdpLink link = UdpLink.open();
		try {
			InetSocketAddress me = link.stun(Collections.singletonList((InetSocketAddress) server.getLocalSocketAddress()), 3000);
			assertNotNull(me);
			assertEquals(link.localPort(), me.getPort());
		} finally {
			link.close("test");
			server.close();
		}
	}

	@Test
	void candidateParsingAcceptsOnlyIpv4Literals() {
		assertNotNull(UdpLink.parseCandidate("192.168.1.2:25565"));
		assertNull(UdpLink.parseCandidate("example.com:25565"), "kein DNS");
		assertNull(UdpLink.parseCandidate("1.2.3.4:0"));
		assertNull(UdpLink.parseCandidate("1.2.3.4:70000"));
		assertNull(UdpLink.parseCandidate("0.0.0.0:1234"));
		assertNull(UdpLink.parseCandidate("256.1.1.1:1"));
		assertNull(UdpLink.parseCandidate(null));
		assertEquals("10.0.0.1:77", UdpLink.formatCandidate(UdpLink.parseCandidate("10.0.0.1:77")));
	}

	// --- Relay-Protokoll gegen ein nachgebautes Relay ---

	/** Minimales Relay nach PROTOCOL.md §2: prüft Präambel/Frames, paart Gast und Host-Datenverbindung, pipet roh. */
	static final class FakeRelay implements AutoCloseable {
		final ServerSocket server;
		final String hostToken;
		final String guestToken;
		final String guestUuid;
		volatile OutputStream control;
		final ConcurrentHashMap<String, Socket> waiting = new ConcurrentHashMap<String, Socket>();
		final BlockingQueue<String> events = new ArrayBlockingQueue<String>(100);
		volatile String errorForGuest;

		FakeRelay(String hostToken, String guestToken, String guestUuid) throws IOException {
			this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			this.hostToken = hostToken;
			this.guestToken = guestToken;
			this.guestUuid = guestUuid;
			Thread t = new Thread(() -> {
				while (!server.isClosed()) {
					try {
						final Socket s = server.accept();
						new Thread(() -> handle(s)).start();
					} catch (IOException e) {
						return;
					}
				}
			});
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return server.getLocalPort();
		}

		void handle(Socket s) {
			try {
				InputStream in = s.getInputStream();
				OutputStream out = s.getOutputStream();
				byte[] pre = new byte[5];
				new DataInputStream(in).readFully(pre);
				if (!Arrays.equals(pre, Relay.PREAMBLE)) {
					out.write(Relay.frame(Relay.ERROR, "bad_preamble"));
					s.close();
					return;
				}
				Relay.Frame f = Relay.read(in, 1024);
				if (f.type == Relay.HOST_HELLO) {
					if (!hostToken.equals(f.text())) {
						out.write(Relay.frame(Relay.ERROR, "bad_token"));
						s.close();
						return;
					}
					control = out;
					out.write(Relay.frame(Relay.WELCOME, "{\"room\":\"h1\",\"maxGuests\":9}"));
					events.add("host");
					while (true) {
						Relay.Frame c = Relay.read(in, 1024);
						if (c.type == Relay.PING) {
							synchronized (out) {
								out.write(Relay.frame(Relay.PONG, c.payload));
							}
							events.add("ping");
						} else if (c.type == Relay.KICK) {
							events.add("kick:" + Relay.uuidHex(c.payload, 0));
						}
					}
				} else if (f.type == Relay.GUEST_HELLO) {
					if (errorForGuest != null) {
						out.write(Relay.frame(Relay.ERROR, errorForGuest));
						s.close();
						return;
					}
					if (!guestToken.equals(f.text())) {
						out.write(Relay.frame(Relay.ERROR, "bad_token"));
						s.close();
						return;
					}
					byte[] pair = new byte[16];
					new Random().nextBytes(pair);
					waiting.put(Relay.uuidHex(pair, 0), s);
					byte[] open = new byte[32];
					System.arraycopy(pair, 0, open, 0, 16);
					System.arraycopy(Relay.uuidBytes(guestUuid), 0, open, 16, 16);
					synchronized (control) {
						control.write(Relay.frame(Relay.GUEST_OPEN, open));
					}
				} else if (f.type == Relay.PAIR) {
					Socket guest = waiting.remove(Relay.uuidHex(f.payload, 0));
					if (guest == null) {
						out.write(Relay.frame(Relay.ERROR, "unknown_pair"));
						s.close();
						return;
					}
					out.write(Relay.frame(Relay.WELCOME, "{\"room\":\"h1\"}"));
					guest.getOutputStream().write(Relay.frame(Relay.WELCOME, "{\"room\":\"h1\"}"));
					pipe(guest, s);
					pipe(s, guest);
				}
			} catch (IOException e) {
				// Ende
			}
		}

		static void pipe(final Socket from, final Socket to) {
			Thread t = new Thread(() -> {
				try {
					InputStream in = from.getInputStream();
					OutputStream out = to.getOutputStream();
					byte[] buf = new byte[8192];
					int n;
					while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
				} catch (IOException ignored) {
					// Ende
				} finally {
					try {
						to.close();
					} catch (IOException ignored) {
						// egal
					}
				}
			});
			t.setDaemon(true);
			t.start();
		}

		@Override
		public void close() throws IOException {
			server.close();
		}
	}

	static final class Collect implements PeerStream.Sink {
		final ByteArrayOutputStream got = new ByteArrayOutputStream();
		final CountDownLatch closed = new CountDownLatch(1);

		@Override
		public synchronized void data(byte[] b, int off, int len) {
			got.write(b, off, len);
		}

		@Override
		public void closed(String reason) {
			closed.countDown();
		}

		synchronized int size() {
			return got.size();
		}

		synchronized byte[] bytes() {
			return got.toByteArray();
		}
	}

	static void waitFor(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
	}

	@Test
	void frameEncodingMatchesProtocol() throws Exception {
		byte[] f = Relay.frame(Relay.GUEST_HELLO, "abc");
		assertArrayEquals(new byte[] { 0x02, 0x00, 0x03, 'a', 'b', 'c' }, f);
		Relay.Frame r = Relay.read(new java.io.ByteArrayInputStream(f), 1024);
		assertEquals(Relay.GUEST_HELLO, r.type);
		assertEquals("abc", r.text());
		byte[] big = new byte[3 + 2000];
		big[1] = (byte) (2000 >>> 8);
		big[2] = (byte) 2000;
		Relay.RelayException e = assertThrows(Relay.RelayException.class, () -> Relay.read(new java.io.ByteArrayInputStream(big), 1024));
		assertEquals("bad_frame", e.code);
		assertEquals("00112233445566778899aabbccddeeff", Relay.uuidHex(Relay.uuidBytes("00112233445566778899aabbccddeeff"), 0));
	}

	@Test
	void hostAndGuestPairThroughRelayAndPipeBytes() throws Exception {
		final String guest = "0123456789abcdef0123456789abcdef";
		try (FakeRelay relay = new FakeRelay("trsr1.host", "trsr1.guest", guest)) {
			final AtomicReference<RelayStream> hostSide = new AtomicReference<RelayStream>();
			final AtomicReference<String> openedFor = new AtomicReference<String>();
			final CountDownLatch paired = new CountDownLatch(1);
			RelayControl control = RelayControl.connect("127.0.0.1", relay.port(), "trsr1.host", new RelayControl.Listener() {
				@Override
				public void guestOpen(byte[] pairId, String uuid) {
					openedFor.set(uuid);
					try {
						hostSide.set(Relay.pair("127.0.0.1", relay.port(), pairId));
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					paired.countDown();
				}

				@Override
				public void guestClosed(byte[] pairId) {
				}

				@Override
				public void closed(String code) {
				}
			});
			assertTrue(control.welcome().contains("maxGuests"));
			assertEquals("host", relay.events.poll(5, TimeUnit.SECONDS));
			RelayStream g = Relay.guest("127.0.0.1", relay.port(), "trsr1.guest");
			assertTrue(paired.await(5, TimeUnit.SECONDS));
			assertEquals(guest, openedFor.get());
			Collect atHost = new Collect();
			Collect atGuest = new Collect();
			hostSide.get().start(atHost);
			g.start(atGuest);
			final byte[] up = new byte[200_000];
			new Random(5).nextBytes(up);
			final byte[] down = "hello guest".getBytes(StandardCharsets.UTF_8);
			assertTrue(g.write(up, 0, up.length));
			assertTrue(hostSide.get().write(down, 0, down.length));
			waitFor(() -> atHost.size() == up.length && atGuest.size() == down.length, 5000);
			assertArrayEquals(up, atHost.bytes());
			assertArrayEquals(down, atGuest.bytes());
			assertEquals(PeerStream.Path.RELAY, g.path());
			// KICK + PING kommen auf der Kontrollverbindung an.
			control.kick(guest);
			assertEquals("kick:" + guest, relay.events.poll(5, TimeUnit.SECONDS));
			// Gast schließt → Host-Seite endet auch.
			g.close("bye");
			assertTrue(atHost.closed.await(5, TimeUnit.SECONDS));
			control.close();
		}
	}

	@Test
	void relayErrorsBecomeCodes() throws Exception {
		try (FakeRelay relay = new FakeRelay("trsr1.host", "trsr1.guest", "0123456789abcdef0123456789abcdef")) {
			Relay.RelayException e = assertThrows(Relay.RelayException.class,
					() -> RelayControl.connect("127.0.0.1", relay.port(), "trsr1.wrong", null));
			assertEquals("bad_token", e.code);
			assertTrue(e.tokenProblem());
			relay.errorForGuest = "host_offline";
			Relay.RelayException g = assertThrows(Relay.RelayException.class, () -> Relay.guest("127.0.0.1", relay.port(), "trsr1.guest"));
			assertEquals("host_offline", g.code);
			assertFalse(g.backOff());
			relay.errorForGuest = "rate_limited";
			assertTrue(assertThrows(Relay.RelayException.class, () -> Relay.guest("127.0.0.1", relay.port(), "x")).backOff());
			relay.errorForGuest = "<script>";
			assertEquals("unknown", assertThrows(Relay.RelayException.class, () -> Relay.guest("127.0.0.1", relay.port(), "x")).code);
		}
	}

	// --- UDP-Verbindung (Lochstanzen + zuverlässiger Strom) über Loopback ---

	static UdpLink[] punchedPair(double loss) throws Exception {
		final UdpLink host = UdpLink.open();
		final UdpLink guest = UdpLink.open();
		byte[] gn = P2pKeys.nonce();
		byte[] hn = P2pKeys.nonce();
		final P2pKeys keys = new P2pKeys(gn, hn, "sid1");
		final List<InetSocketAddress> hc = Collections.singletonList(new InetSocketAddress(InetAddress.getLoopbackAddress(), host.localPort()));
		final List<InetSocketAddress> gc = Collections.singletonList(new InetSocketAddress(InetAddress.getLoopbackAddress(), guest.localPort()));
		final boolean[] hostOk = new boolean[1];
		Thread t = new Thread(() -> {
			try {
				hostOk[0] = host.punch(keys, UdpLink.Role.CONTROLLED, gc, 5000);
			} catch (IOException e) {
				hostOk[0] = false;
			}
		});
		t.start();
		assertTrue(guest.punch(keys, UdpLink.Role.CONTROLLING, hc, 5000));
		t.join(6000);
		assertTrue(hostOk[0]);
		host.lossForTest = loss;
		guest.lossForTest = loss;
		host.begin("test-host");
		guest.begin("test-guest");
		return new UdpLink[] { host, guest };
	}

	@Test
	void udpLinkPunchesAndStreamsReliablyWithLoss() throws Exception {
		UdpLink[] l = punchedPair(0.10);
		Collect atHost = new Collect();
		Collect atGuest = new Collect();
		l[0].start(atHost);
		l[1].start(atGuest);
		final byte[] up = new byte[600_000];
		new Random(9).nextBytes(up);
		final byte[] down = new byte[120_000];
		new Random(10).nextBytes(down);
		for (int off = 0; off < up.length; off += 5000) l[1].write(up, off, Math.min(5000, up.length - off));
		for (int off = 0; off < down.length; off += 777) l[0].write(down, off, Math.min(777, down.length - off));
		waitFor(() -> atHost.size() == up.length && atGuest.size() == down.length, 30_000);
		assertArrayEquals(up, atHost.bytes());
		assertArrayEquals(down, atGuest.bytes());
		assertEquals(PeerStream.Path.DIRECT, l[0].path());
		assertTrue(l[1].rudp().retransmits() > 0, "bei 10 % Verlust muss neu gesendet worden sein");
		// Geordnetes Schließen erreicht die Gegenseite.
		l[1].close("bye");
		assertTrue(atHost.closed.await(10, TimeUnit.SECONDS));
		assertTrue(atGuest.closed.await(10, TimeUnit.SECONDS));
	}

	@Test
	void punchingFailsWithWrongKeyOrNoPeer() throws Exception {
		final UdpLink host = UdpLink.open();
		UdpLink guest = UdpLink.open();
		try {
			final P2pKeys hostKeys = new P2pKeys(P2pKeys.nonce(), P2pKeys.nonce(), "a");
			P2pKeys guestKeys = new P2pKeys(P2pKeys.nonce(), P2pKeys.nonce(), "a");
			final List<InetSocketAddress> gc = Collections.singletonList(new InetSocketAddress(InetAddress.getLoopbackAddress(), guest.localPort()));
			Thread t = new Thread(() -> {
				try {
					host.punch(hostKeys, UdpLink.Role.CONTROLLED, gc, 1500);
				} catch (IOException ignored) {
					// egal
				}
			});
			t.start();
			long start = System.currentTimeMillis();
			assertFalse(guest.punch(guestKeys, UdpLink.Role.CONTROLLING,
					Collections.singletonList(new InetSocketAddress(InetAddress.getLoopbackAddress(), host.localPort())), 1500));
			assertTrue(System.currentTimeMillis() - start >= 1400);
			t.join(3000);
		} finally {
			host.close("t");
			guest.close("t");
		}
	}

	@Test
	void keysAreSymmetricAndSidBound() {
		byte[] g = P2pKeys.nonce();
		byte[] h = P2pKeys.nonce();
		assertEquals(new P2pKeys(g, h, "x").connId(), new P2pKeys(g, h, "x").connId());
		assertFalse(new P2pKeys(g, h, "x").connId() == new P2pKeys(g, h, "y").connId());
		byte[] msg = new byte[40];
		javax.crypto.Mac mac = new P2pKeys(g, h, "x").mac();
		P2pKeys.seal(mac, msg, 28);
		assertTrue(P2pKeys.verify(mac, msg, 0, 40));
		msg[3] ^= 1;
		assertFalse(P2pKeys.verify(mac, msg, 0, 40));
	}
}

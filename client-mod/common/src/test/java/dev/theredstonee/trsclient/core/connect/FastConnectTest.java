package dev.theredstonee.trsclient.core.connect;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * „Schnell verbinden“ als Ganzes: Handshake-Name bleibt, Netty übernimmt die im Rennen aufgebaute Verbindung (genau
 * eine Verbindung beim Server), Vorab-Auflösung, Proxy-Erkennung.
 */
class FastConnectTest {
	/** Resolver-Attrappe: feste Antworten, zählt Abfragen. */
	static final class FakeResolver implements DnsLookup {
		final Map<String, DnsLookup.SrvAnswer> srv = new HashMap<String, DnsLookup.SrvAnswer>();
		final Map<String, List<byte[]>> addrs = new HashMap<String, List<byte[]>>();
		final AtomicInteger queries = new AtomicInteger();

		@Override
		public DnsLookup.SrvAnswer srv(String host) {
			queries.incrementAndGet();
			DnsLookup.SrvAnswer a = srv.get(host);
			return a == null ? DnsLookup.SrvAnswer.NONE : a;
		}

		@Override
		public List<byte[]> addresses(String host) {
			queries.incrementAndGet();
			List<byte[]> l = addrs.get(host);
			return l == null ? new ArrayList<byte[]>() : l;
		}
	}

	FakeResolver fake;

	@BeforeEach
	void setUp() {
		FastConnect.reset();
		fake = new FakeResolver();
		FastConnect.resolver = fake;
		FastConnect.allowLoopback = true;
		FastConnect.init(null, () -> true, () -> true, null);
	}

	@AfterEach
	void tearDown() {
		FastConnect.resolver = SystemResolver.INSTANCE;
		FastConnect.allowLoopback = false;
		FastConnect.reset();
	}

	/** Im Thread „Server Connector #…“ ausführen (wie Vanilla). */
	static <T> T onConnector(final java.util.concurrent.Callable<T> body) throws Exception {
		final AtomicReference<T> out = new AtomicReference<T>();
		final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					out.set(body.call());
				} catch (Throwable e) {
					err.set(e);
				}
			}
		}, "Server Connector #42");
		t.start();
		t.join(30_000);
		if (err.get() instanceof Exception) throw (Exception) err.get();
		if (err.get() != null) throw new AssertionError(err.get());
		return out.get();
	}

	@Test
	void ipLiteralKeepsTypedTextAsHandshakeName() {
		InetAddress a = FastConnect.cachedAddress("203.0.113.7");
		assertNotNull(a);
		assertEquals("203.0.113.7", a.getHostName(), "no reverse DNS lookup – the IP as typed");
		InetAddress v6 = FastConnect.cachedAddress("2001:db8::7");
		assertEquals("2001:db8::7", v6.getHostName());
	}

	@Test
	void prefetchResolvesSrvAndAddressesOnce() {
		fake.srv.put("play.example.net", DnsLookup.SrvAnswer.of(SrvRecord.parse("0 0 25577 mc.example.net.")));
		fake.addrs.put("play.example.net", Collections.singletonList(IpLiteral.parse("203.0.113.1")));
		fake.addrs.put("mc.example.net.", Arrays.asList(IpLiteral.parse("2001:db8::2"), IpLiteral.parse("203.0.113.2")));
		assertEquals("mc.example.net.", FastConnect.resolveNow("play.example.net", 25565));
		int q = fake.queries.get();
		assertEquals(3, q);
		// Zweiter Durchlauf: alles aus dem Speicher.
		FastConnect.resolveNow("play.example.net", 25565);
		assertEquals(q, fake.queries.get());
		DnsCache.Srv s = FastConnect.cachedSrv("play.example.net");
		assertEquals("mc.example.net.", s.record.target);
		InetAddress a = FastConnect.cachedAddress("mc.example.net.");
		assertEquals("mc.example.net.", a.getHostName(), "handshake name exactly as vanilla resolved it");
		// Mit Port: kein SRV.
		FastConnect.resolveNow("other.example.net", 25570);
		assertNull(FastConnect.CACHE.srv("other.example.net"));
	}

	@Test
	void winnerIsHandedToNettyAsTheOnlyConnection() throws Exception {
		final AtomicInteger accepted = new AtomicInteger();
		final CountDownLatch got = new CountDownLatch(1);
		final AtomicReference<String> received = new AtomicReference<String>();
		final ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
		Thread server = new Thread(new Runnable() {
			@Override
			public void run() {
				while (!ss.isClosed()) {
					try {
						Socket s = ss.accept();
						accepted.incrementAndGet();
						InputStream in = s.getInputStream();
						byte[] buf = new byte[5];
						int n = 0;
						while (n < 5) {
							int r = in.read(buf, n, 5 - n);
							if (r < 0) break;
							n += r;
						}
						received.set(new String(buf, 0, n, StandardCharsets.US_ASCII));
						got.countDown();
					} catch (Exception e) {
						return;
					}
				}
			}
		});
		server.setDaemon(true);
		server.start();
		final int port = ss.getLocalPort();
		// Zuerst eine Adresse ohne Server (abgelehnt), dann die echte.
		fake.addrs.put("play.test", Arrays.asList(new byte[]{127, 0, 0, 2}, new byte[]{127, 0, 0, 1}));
		NioEventLoopGroup group = new NioEventLoopGroup(1);
		try {
			final InetSocketAddress vanilla = new InetSocketAddress(InetAddress.getByAddress("play.test", new byte[]{127, 0, 0, 2}), port);
			final CountDownLatch active = new CountDownLatch(1);
			Channel ch = onConnector(() -> {
				FastConnect.beforeConnect(vanilla);
				@SuppressWarnings("rawtypes")
				Class cls = FastConnect.channelClass(NioSocketChannel.class);
				assertSame(FastNioChannel.class, cls);
				InetAddress target = FastConnect.connectAddress(vanilla.getAddress());
				assertArrayEquals(new byte[]{127, 0, 0, 1}, target.getAddress());
				assertEquals("play.test", target.getHostName(), "same name, other IP");
				@SuppressWarnings("unchecked")
				Bootstrap b = new Bootstrap().group(group).channel(cls).handler(new ChannelInitializer<Channel>() {
					@Override
					protected void initChannel(Channel c) {
						c.pipeline().addLast(new ChannelInboundHandlerAdapter() {
							@Override
							public void channelActive(ChannelHandlerContext ctx) throws Exception {
								active.countDown();
								super.channelActive(ctx);
							}
						});
					}
				});
				Channel c = b.connect(target, vanilla.getPort()).syncUninterruptibly().channel();
				FastConnect.afterConnect();
				return c;
			});
			assertTrue(active.await(5, TimeUnit.SECONDS), "channelActive fired");
			assertTrue(ch.isActive());
			ch.writeAndFlush(Unpooled.copiedBuffer("hello", StandardCharsets.US_ASCII)).syncUninterruptibly();
			assertTrue(got.await(5, TimeUnit.SECONDS));
			assertEquals("hello", received.get());
			Thread.sleep(200);
			assertEquals(1, accepted.get(), "the server sees exactly one connection");
			assertEquals("127.0.0.1", FastConnect.LAST.ip);
			assertArrayEquals(new byte[]{127, 0, 0, 1}, FastConnect.CACHE.winner("play.test"));
			ch.close().syncUninterruptibly();
		} finally {
			group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
			ss.close();
		}
	}

	@Test
	void onlyTheConnectScreenThreadRaces() throws Exception {
		fake.addrs.put("play.test", Arrays.asList(new byte[]{127, 0, 0, 2}, new byte[]{127, 0, 0, 1}));
		InetSocketAddress vanilla = new InetSocketAddress(InetAddress.getByAddress("play.test", new byte[]{127, 0, 0, 2}), 1);
		FastConnect.beforeConnect(vanilla);
		assertSame(NioSocketChannel.class, FastConnect.channelClass(NioSocketChannel.class));
		assertSame(vanilla.getAddress(), FastConnect.connectAddress(vanilla.getAddress()));
		assertEquals(0, fake.queries.get());
	}

	@Test
	void singleAddressStaysVanilla() throws Exception {
		fake.addrs.put("play.test", Collections.singletonList(new byte[]{127, 0, 0, 1}));
		final InetSocketAddress vanilla = new InetSocketAddress(InetAddress.getByAddress("play.test", new byte[]{127, 0, 0, 1}), 1);
		Object cls = onConnector(() -> {
			FastConnect.beforeConnect(vanilla);
			return FastConnect.channelClass(NioSocketChannel.class);
		});
		assertSame(NioSocketChannel.class, cls);
	}

	@Test
	void noRaceWithSocksProxy() throws Exception {
		fake.addrs.put("play.test", Arrays.asList(new byte[]{127, 0, 0, 2}, new byte[]{127, 0, 0, 1}));
		final InetSocketAddress vanilla = new InetSocketAddress(InetAddress.getByAddress("play.test", new byte[]{127, 0, 0, 2}), 1);
		System.setProperty("socksProxyHost", "127.0.0.1");
		try {
			Object cls = onConnector(() -> {
				FastConnect.beforeConnect(vanilla);
				return FastConnect.channelClass(NioSocketChannel.class);
			});
			assertSame(NioSocketChannel.class, cls);
		} finally {
			System.clearProperty("socksProxyHost");
		}
	}

	@Test
	void epollStyleChannelGetsTheWinnerAddressOnly() throws Exception {
		final ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
		try {
			fake.addrs.put("play.test", Arrays.asList(new byte[]{127, 0, 0, 2}, new byte[]{127, 0, 0, 1}));
			final InetSocketAddress vanilla = new InetSocketAddress(InetAddress.getByAddress("play.test", new byte[]{127, 0, 0, 2}),
					ss.getLocalPort());
			Object[] out = onConnector(() -> {
				FastConnect.beforeConnect(vanilla);
				Object cls = FastConnect.channelClass(io.netty.channel.local.LocalChannel.class);
				InetAddress a = FastConnect.connectAddress(vanilla.getAddress());
				FastConnect.afterConnect();
				return new Object[]{cls, a};
			});
			assertSame(io.netty.channel.local.LocalChannel.class, out[0]);
			assertArrayEquals(new byte[]{127, 0, 0, 1}, ((InetAddress) out[1]).getAddress());
		} finally {
			ss.close();
		}
	}

	@Test
	void allAddressesDeadFailsWithAClearMessage() throws Exception {
		int closed;
		try (ServerSocket tmp = new ServerSocket(0)) {
			closed = tmp.getLocalPort();
		}
		fake.addrs.put("play.test", Arrays.asList(new byte[]{127, 0, 0, 2}, new byte[]{127, 0, 0, 3}));
		final InetSocketAddress vanilla = new InetSocketAddress(InetAddress.getByAddress("play.test", new byte[]{127, 0, 0, 2}), closed);
		FastConnect.ConnectFailed e = null;
		try {
			onConnector(() -> {
				FastConnect.beforeConnect(vanilla);
				return null;
			});
		} catch (FastConnect.ConnectFailed ex) {
			e = ex;
		}
		assertNotNull(e);
		assertTrue(e.toString().contains("play.test"), e.toString());
		assertNull(FastConnect.CACHE.addresses("play.test"), "stale entries are dropped for the next try");
	}
}

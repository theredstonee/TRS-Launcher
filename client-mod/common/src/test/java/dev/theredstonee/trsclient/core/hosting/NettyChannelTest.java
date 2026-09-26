package dev.theredstonee.trsclient.core.hosting;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import dev.theredstonee.trsclient.core.hosting.netty.ServerAttach;
import dev.theredstonee.trsclient.core.hosting.netty.TrsChannel;
import dev.theredstonee.trsclient.core.hosting.netty.TrsConnect;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Netty-Kanal über einen Strom (mit Netty 4.0.23 = Minecraft 1.8.9, der ältesten API): Host-Seite über den anonymen
 * TCP-Initializer eines „Listeners“ (wie {@code ServerConnectionListener$1}), Client-Seite über die Marker-Adresse.
 */
class NettyChannelTest {
	/** Wie Minecrafts ServerConnectionListener: erster anonymer Initializer = TCP (nimmt den Listener). */
	static final class FakeListener {
		final List<Channel> connections = Collections.synchronizedList(new ArrayList<Channel>());
		final ByteArrayOutputStream got = new ByteArrayOutputStream();
		final CountDownLatch active = new CountDownLatch(1);
		final CountDownLatch inactive = new CountDownLatch(1);

		Object tcpInitializer() {
			return new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(Channel ch) {
					connections.add(ch);
					ch.pipeline().addLast("timeout", new ChannelInboundHandlerAdapter());
					ch.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter() {
						@Override
						public void channelActive(ChannelHandlerContext ctx) {
							active.countDown();
						}

						@Override
						public void channelRead(ChannelHandlerContext ctx, Object msg) {
							ByteBuf b = (ByteBuf) msg;
							byte[] data = new byte[b.readableBytes()];
							b.readBytes(data);
							b.release();
							synchronized (got) {
								got.write(data, 0, data.length);
							}
							// Echo in Großbuchstaben zurück.
							ctx.writeAndFlush(Unpooled.wrappedBuffer(new String(data, StandardCharsets.UTF_8).toUpperCase().getBytes(StandardCharsets.UTF_8)));
						}

						@Override
						public void channelInactive(ChannelHandlerContext ctx) {
							inactive.countDown();
						}
					});
				}
			};
		}

		Object memoryInitializer() {
			return new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(Channel ch) {
					throw new IllegalStateException("falscher Initializer");
				}
			};
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

		synchronized String text() {
			return new String(got.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void hostSideAttachRunsVanillaPipelineOverStream() throws Exception {
		FakeListener listener = new FakeListener();
		assertTrue(ServerAttach.supported(listener));
		PipeStream[] pipe = PipeStream.pair(PeerStream.Path.RELAY);
		TrsChannel ch = ServerAttach.attach(listener, pipe[0], null);
		assertTrue(listener.active.await(5, TimeUnit.SECONDS), "channelActive wie bei TCP");
		assertEquals(1, listener.connections.size());
		assertSame(ch, listener.connections.get(0));
		assertEquals(PeerStream.Path.RELAY, TrsConnect.path(ch));
		Collect guest = new Collect();
		pipe[1].start(guest);
		pipe[1].write("hallo".getBytes(StandardCharsets.UTF_8), 0, 5);
		long end = System.currentTimeMillis() + 5000;
		while (!guest.text().equals("HALLO") && System.currentTimeMillis() < end) Thread.sleep(10);
		assertEquals("HALLO", guest.text());
		// Gast trennt → Kanal wird inaktiv (Minecraft räumt die Verbindung ab).
		pipe[1].close("bye");
		assertTrue(listener.inactive.await(5, TimeUnit.SECONDS));
		assertFalse(ch.isOpen());
	}

	@Test
	void loginGuardClosesImpostorBeforeServerSeesBytes() throws Exception {
		FakeListener listener = new FakeListener();
		PipeStream[] pipe = PipeStream.pair(PeerStream.Path.DIRECT);
		TrsChannel ch = ServerAttach.attach(listener, pipe[0], name -> !name.equalsIgnoreCase("Host"));
		assertTrue(listener.active.await(5, TimeUnit.SECONDS));
		Collect guest = new Collect();
		pipe[1].start(guest);
		byte[] login = HostingLogicTest.login(2, "Host");
		pipe[1].write(login, 0, login.length);
		assertTrue(listener.inactive.await(5, TimeUnit.SECONDS), "Anmeldung als Host wird abgewiesen");
		synchronized (listener.got) {
			assertEquals(0, listener.got.size(), "der Server hat nichts davon gesehen");
		}
		assertEquals("login rejected", ch.closeReason());
	}

	@Test
	void clientSideConnectsThroughMarkerAddress() throws Exception {
		NioEventLoopGroup group = new NioEventLoopGroup(1);
		try {
			PipeStream[] pipe = PipeStream.pair(PeerStream.Path.DIRECT);
			String address = TrsConnect.offer(pipe[0]);
			assertTrue(address.startsWith(TrsConnect.MARKER_IP + ":"));
			int port = Integer.parseInt(address.substring(address.indexOf(':') + 1));
			InetSocketAddress target = new InetSocketAddress(TrsConnect.MARKER_IP, port);
			// Wie der Mixin: Ziel merken, Kanalklasse tauschen.
			TrsConnect.target(target);
			@SuppressWarnings("unchecked")
			Class<? extends Channel> cls = TrsConnect.channelForTarget(NioSocketChannel.class);
			assertSame(TrsChannel.class, cls);
			TrsConnect.target(new InetSocketAddress("127.0.0.1", 25565));
			assertSame(NioSocketChannel.class, TrsConnect.channelForTarget(NioSocketChannel.class), "andere Server unverändert");
			final ByteArrayOutputStream got = new ByteArrayOutputStream();
			final CountDownLatch read = new CountDownLatch(1);
			ChannelFuture f = new Bootstrap().group(group).channel(cls).handler(new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(Channel ch) {
					ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
						@Override
						public void channelRead(ChannelHandlerContext ctx, Object msg) {
							ByteBuf b = (ByteBuf) msg;
							byte[] d = new byte[b.readableBytes()];
							b.readBytes(d);
							b.release();
							got.write(d, 0, d.length);
							read.countDown();
						}
					});
				}
			}).connect(target).syncUninterruptibly();
			assertTrue(f.isSuccess());
			Channel ch = f.channel();
			assertTrue(ch.isActive());
			Collect host = new Collect();
			pipe[1].start(host);
			ch.writeAndFlush(Unpooled.wrappedBuffer("ping".getBytes(StandardCharsets.UTF_8))).syncUninterruptibly();
			pipe[1].write("pong".getBytes(StandardCharsets.UTF_8), 0, 4);
			assertTrue(read.await(5, TimeUnit.SECONDS));
			assertEquals("pong", new String(got.toByteArray(), StandardCharsets.UTF_8));
			long end = System.currentTimeMillis() + 5000;
			while (!host.text().equals("ping") && System.currentTimeMillis() < end) Thread.sleep(10);
			assertEquals("ping", host.text());
			// Unbekannte Marker-Adresse (schon abgeholt) → Verbindungsfehler statt TCP.
			ChannelFuture again = new Bootstrap().group(group).channel(TrsChannel.class).handler(new ChannelInboundHandlerAdapter())
					.connect(target).awaitUninterruptibly();
			assertFalse(again.isSuccess());
			assertNotNull(again.cause());
			ch.close().syncUninterruptibly();
		} finally {
			group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
		}
	}

	/**
	 * Wie Minecraft beim Protokollwechsel (1.20.5+): mitten im channelRead autoRead aus und sofort wieder an – danach
	 * dürfen spätere Daten nicht liegen bleiben (Regression: leere Leserunde hat das vorgemerkte Lesen verbraucht).
	 */
	@Test
	void autoReadToggleInsideReadKeepsReading() throws Exception {
		final ByteArrayOutputStream got = new ByteArrayOutputStream();
		final CountDownLatch active = new CountDownLatch(1);
		PipeStream[] pipe = PipeStream.pair(PeerStream.Path.RELAY);
		final TrsChannel ch = ServerAttach.attach(new ToggleListener(got, active), pipe[0], null);
		assertTrue(active.await(5, TimeUnit.SECONDS));
		pipe[1].start(new Collect());
		// Mehrere Stücke auf einmal (liegen gleichzeitig in der Warteschlange), danach einzeln.
		for (int round = 0; round < 3; round++) {
			for (int i = 0; i < 4; i++) pipe[1].write(new byte[] { (byte) ('a' + round * 5 + i) }, 0, 1);
			Thread.sleep(150);
			pipe[1].write(new byte[] { (byte) ('a' + round * 5 + 4) }, 0, 1);
			Thread.sleep(150);
		}
		long end = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < end) {
			synchronized (got) {
				if (got.size() == 15) break;
			}
			Thread.sleep(10);
		}
		synchronized (got) {
			assertEquals("abcdefghijklmno", new String(got.toByteArray(), StandardCharsets.UTF_8));
		}
		ch.close();
	}

	/** Listener, dessen TCP-Initializer bei jedem Stück autoRead aus- und wieder einschaltet. */
	static final class ToggleListener {
		final ByteArrayOutputStream got;
		final CountDownLatch active;

		ToggleListener(ByteArrayOutputStream got, CountDownLatch active) {
			this.got = got;
			this.active = active;
		}

		Object tcpInitializer() {
			return new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(Channel ch) {
					ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
						@Override
						public void channelActive(ChannelHandlerContext ctx) {
							active.countDown();
						}

						@Override
						public void channelRead(ChannelHandlerContext ctx, Object msg) {
							ByteBuf b = (ByteBuf) msg;
							byte[] d = new byte[b.readableBytes()];
							b.readBytes(d);
							b.release();
							synchronized (got) {
								got.write(d, 0, d.length);
							}
							ctx.channel().config().setAutoRead(false);
							ctx.channel().config().setAutoRead(true);
						}
					});
				}
			};
		}
	}

	@Test
	void autoReadOffHoldsBytesUntilReadingAgain() throws Exception {
		FakeListener listener = new FakeListener();
		PipeStream[] pipe = PipeStream.pair(PeerStream.Path.RELAY);
		final TrsChannel ch = ServerAttach.attach(listener, pipe[0], null);
		assertTrue(listener.active.await(5, TimeUnit.SECONDS));
		pipe[1].start(new Collect());
		ch.eventLoop().submit(() -> ch.config().setAutoRead(false)).get();
		pipe[1].write("abc".getBytes(StandardCharsets.UTF_8), 0, 3);
		Thread.sleep(300);
		synchronized (listener.got) {
			assertEquals(0, listener.got.size(), "autoRead=false: nichts ausliefern (Minecraft-Protokollwechsel)");
		}
		ch.eventLoop().submit(() -> ch.config().setAutoRead(true)).get();
		long end = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < end) {
			synchronized (listener.got) {
				if (listener.got.size() == 3) break;
			}
			Thread.sleep(10);
		}
		synchronized (listener.got) {
			assertEquals("abc", new String(listener.got.toByteArray(), StandardCharsets.UTF_8));
		}
		ch.close();
	}
}

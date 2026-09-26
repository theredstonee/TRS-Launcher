/*
 * Öffentlicher Link über das e4mc-Relay ("QUIClime"-Protokoll).
 *
 * Angepasst aus e4mc_minecraft (QuiclimeSession.java, https://github.com/vgskye/e4mc-minecraft-architectury):
 * Copyright (c) 2024 Skye – MIT License (Volltext in META-INF/LICENSE-e4mc dieser JAR). Änderungen für den
 * TRS Client: Java 8, eigener ClassLoader ohne Minecraft-Abhängigkeiten, Übergabe jedes eingehenden Streams als
 * PeerStream an das TRS-Welt-Hosting, kein Dialtone/Iroh, keine Chat-Ausgaben.
 */
package dev.theredstonee.trsclient.e4mcbridge;

import dev.theredstonee.trsclient.core.hosting.e4mc.PublicTunnel;
import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Umsetzung von {@link PublicTunnel} – wird nur im isolierten ClassLoader geladen. */
public final class QuiclimeTunnel implements PublicTunnel {
	private static final Pattern KIND = Pattern.compile("\"kind\"\\s*:\\s*\"([a-z_]{1,40})\"");
	private static final Pattern DOMAIN = Pattern.compile("\"domain\"\\s*:\\s*\"([A-Za-z0-9.-]{1,253})\"");
	private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,400})\"");

	private volatile EventLoopGroup group;
	private volatile Channel datagram;
	private volatile QuicChannel quic;
	private volatile boolean stopped;

	public QuiclimeTunnel() {
	}

	/** Relay vom Broker: {"id":"de","host":"de.e4mc.link","port":25575}. */
	static String[] relay(String brokerUrl) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(brokerUrl).openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(10_000);
		c.setInstanceFollowRedirects(false);
		c.setRequestProperty("Accept", "application/json");
		c.setRequestProperty("User-Agent", "TRS-Client");
		try {
			if (c.getResponseCode() != 200) throw new IllegalStateException("broker HTTP " + c.getResponseCode());
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (InputStream in = c.getInputStream()) {
				byte[] buf = new byte[1024];
				int n;
				while ((n = in.read(buf)) > 0 && out.size() < 8192) out.write(buf, 0, n);
			}
			String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
			Matcher h = Pattern.compile("\"host\"\\s*:\\s*\"([A-Za-z0-9.-]{1,253})\"").matcher(json);
			Matcher p = Pattern.compile("\"port\"\\s*:\\s*([0-9]{1,5})").matcher(json);
			if (!h.find() || !p.find()) throw new IllegalStateException("broker answer");
			return new String[] { h.group(1), p.group(1) };
		} finally {
			c.disconnect();
		}
	}

	@Override
	public void start(String brokerUrl, final Events events) throws Exception {
		String[] r = relay(brokerUrl);
		final String host = r[0];
		final int port = Integer.parseInt(r[1]);
		final QuicSslContext context = QuicSslContextBuilder.forClient().applicationProtocols("quiclime").build();
		io.netty.channel.ChannelHandler codec = new QuicClientCodecBuilder()
				.sslContext(context)
				.sslEngineProvider(ch -> context.newEngine(ch.alloc(), host, port))
				.initialMaxStreamsBidirectional(512)
				.maxIdleTimeout(10, TimeUnit.SECONDS)
				.initialMaxData(4611686018427387903L)
				.initialMaxStreamDataBidirectionalRemote(1250000)
				.initialMaxStreamDataBidirectionalLocal(1250000)
				.initialMaxStreamDataUnidirectional(1250000)
				.build();
		group = new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("TRS-e4mc", true), NioIoHandler.newFactory());
		final InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName(host), port);
		new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec).bind(0)
				.addListener((ChannelFutureListener) bound -> {
					if (!bound.isSuccess()) {
						events.failed("bind: " + bound.cause());
						return;
					}
					datagram = bound.channel();
					QuicChannel.newBootstrap(datagram)
							.streamHandler(new ChannelInitializer<QuicStreamChannel>() {
								@Override
								protected void initChannel(QuicStreamChannel ch) {
									// Jeder vom Relay geöffnete Stream = ein Spieler (voller Minecraft-Bytestrom).
									QuicPeerStream s = new QuicPeerStream(ch);
									ch.pipeline().addLast(s.handler());
									events.stream(s);
								}
							})
							.handler(new ChannelInboundHandlerAdapter() {
								@Override
								public void channelInactive(ChannelHandlerContext ctx) throws Exception {
									super.channelInactive(ctx);
									if (!stopped) events.failed("connection closed");
								}

								@Override
								public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
									if (!stopped) events.failed(String.valueOf(cause));
								}
							})
							.remoteAddress(remote)
							.connect()
							.addListener((GenericFutureListener<Future<QuicChannel>>) connected -> {
								if (!connected.isSuccess()) {
									events.failed("connect: " + connected.cause());
									return;
								}
								quic = connected.getNow();
								openControl(quic, events);
							});
				});
	}

	private void openControl(QuicChannel q, final Events events) {
		q.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
			@Override
			protected void initChannel(QuicStreamChannel ch) {
				ch.pipeline().addLast(new ControlDecoder(), new ChannelInboundHandlerAdapter() {
					@Override
					public void channelRead(ChannelHandlerContext ctx, Object msg) {
						String json = (String) msg;
						Matcher k = KIND.matcher(json);
						if (!k.find()) return;
						String kind = k.group(1);
						if (kind.equals("domain_assignment_complete")) {
							Matcher d = DOMAIN.matcher(json);
							if (d.find()) events.domain(d.group(1));
						} else if (kind.equals("request_message_broadcast")) {
							Matcher m = MESSAGE.matcher(json);
							if (m.find()) events.broadcast(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
						}
					}
				});
			}
		}).addListener((GenericFutureListener<Future<QuicStreamChannel>>) f -> {
			if (!f.isSuccess()) {
				events.failed("control: " + f.cause());
				return;
			}
			QuicStreamChannel control = f.getNow();
			control.writeAndFlush(controlMessage("{\"kind\":\"probe_capabilities\"}"));
			control.writeAndFlush(controlMessage("{\"kind\":\"request_domain_assignment\"}"));
		});
	}

	/** VarInt-Länge + JSON (serverbound). */
	static ByteBuf controlMessage(String json) {
		byte[] b = json.getBytes(StandardCharsets.UTF_8);
		ByteBuf out = Unpooled.buffer(b.length + 5);
		int v = b.length;
		while ((v & 0xFFFFFF80) != 0) {
			out.writeByte(v & 0x7F | 0x80);
			v >>>= 7;
		}
		out.writeByte(v);
		out.writeBytes(b);
		return out;
	}

	/** Clientbound: ein Längen-Byte + JSON (so schreibt es das Relay). */
	static final class ControlDecoder extends ByteToMessageDecoder {
		@Override
		protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
			while (in.readableBytes() >= 1) {
				int size = in.getUnsignedByte(in.readerIndex());
				if (in.readableBytes() < size + 1) return;
				in.skipBytes(1);
				byte[] b = new byte[size];
				in.readBytes(b);
				out.add(new String(b, StandardCharsets.UTF_8));
			}
		}
	}

	@Override
	public void stop() {
		stopped = true;
		QuicChannel q = quic;
		Channel d = datagram;
		EventLoopGroup g = group;
		try {
			if (q != null) q.close().awaitUninterruptibly(2000);
			if (d != null) d.close().awaitUninterruptibly(2000);
		} finally {
			if (g != null) g.shutdownGracefully(0, 1, TimeUnit.SECONDS);
		}
	}

	/** Ein QUIC-Stream als {@link PeerStream}. */
	static final class QuicPeerStream implements PeerStream {
		private static final InetSocketAddress PLACEHOLDER = placeholder();
		private final QuicStreamChannel ch;
		private final List<byte[]> early = new ArrayList<byte[]>();
		private volatile Sink sink;
		private volatile boolean closedSeen;
		private String closedReason;

		QuicPeerStream(QuicStreamChannel ch) {
			this.ch = ch;
		}

		private static InetSocketAddress placeholder() {
			try {
				return new InetSocketAddress(InetAddress.getByAddress("e4mc-link", new byte[] { 127, 0, 0, 1 }), 0);
			} catch (java.net.UnknownHostException e) {
				return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
			}
		}

		ChannelInboundHandlerAdapter handler() {
			return new ChannelInboundHandlerAdapter() {
				@Override
				public void channelRead(ChannelHandlerContext ctx, Object msg) {
					ByteBuf b = (ByteBuf) msg;
					try {
						byte[] data = new byte[b.readableBytes()];
						b.readBytes(data);
						deliver(data);
					} finally {
						b.release();
					}
				}

				@Override
				public void channelInactive(ChannelHandlerContext ctx) {
					ended("closed");
				}

				@Override
				public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
					ctx.close();
				}
			};
		}

		private void deliver(byte[] data) {
			Sink s;
			synchronized (early) {
				s = sink;
				if (s == null) {
					early.add(data);
					return;
				}
			}
			s.data(data, 0, data.length);
		}

		private void ended(String reason) {
			Sink s;
			synchronized (early) {
				if (closedSeen) return;
				closedSeen = true;
				closedReason = reason;
				s = sink;
			}
			if (s != null) s.closed(reason);
		}

		@Override
		public void start(Sink s) {
			boolean closed;
			synchronized (early) {
				for (byte[] b : early) s.data(b, 0, b.length);
				early.clear();
				sink = s;
				closed = closedSeen;
			}
			if (closed) s.closed(closedReason == null ? "closed" : closedReason);
		}

		@Override
		public boolean write(byte[] b, int off, int len) {
			if (!ch.isActive()) return false;
			ch.writeAndFlush(Unpooled.copiedBuffer(b, off, len));
			return true;
		}

		@Override
		public void close(String reason) {
			ch.close();
		}

		@Override
		public boolean isOpen() {
			return ch.isActive();
		}

		@Override
		public Path path() {
			return Path.PUBLIC;
		}

		@Override
		public InetSocketAddress remoteAddress() {
			return PLACEHOLDER;
		}
	}
}

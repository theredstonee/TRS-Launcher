package dev.theredstonee.trsclient.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Einsetzen der TRS-Handler an einer nachgebauten Minecraft-Pipeline (Netty EmbeddedChannel). */
class NetPipelineTest {
	/** Wie Vanillas Entschlüsselung: Handler → Hilfsobjekt → Cipher. */
	static final class FakeCipherBase {
		final Cipher cipher;

		FakeCipherBase(Cipher cipher) {
			this.cipher = cipher;
		}
	}

	static final class FakeCipherDecoder extends MessageToMessageDecoder<ByteBuf> {
		private final FakeCipherBase base;

		FakeCipherDecoder(Cipher c) {
			base = new FakeCipherBase(c);
		}

		@Override
		protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
			byte[] b = new byte[in.readableBytes()];
			in.readBytes(b);
			out.add(Unpooled.wrappedBuffer(base.cipher.update(b)));
		}
	}

	/** Paket-Attrappen. */
	static final class Pong {
		final long time;

		Pong(long time) {
			this.time = time;
		}
	}

	static final class Time {
		final long ticks;

		Time(long ticks) {
			this.ticks = ticks;
		}
	}

	static final class Platform implements NetPlatform {
		long now = 1000;
		final List<Long> sent = new ArrayList<Long>();
		boolean active = true;
		int latency = -1;

		@Override
		public ChannelHandler upgradeDecompress(ChannelHandler vanilla) {
			return null;
		}

		@Override
		public ChannelHandler upgradeCompress(ChannelHandler vanilla) {
			return null;
		}

		@Override
		public boolean vanillaDecrypt(ChannelHandler handler) {
			return handler instanceof FakeCipherDecoder;
		}

		@Override
		public long pongTime(Object packet) {
			return packet instanceof Pong ? ((Pong) packet).time : NONE;
		}

		@Override
		public long gameTime(Object packet) {
			return packet instanceof Time ? ((Time) packet).ticks : NONE;
		}

		@Override
		public long keepAliveId(Object packet) {
			return NONE;
		}

		@Override
		public boolean activePing() {
			return active;
		}

		@Override
		public boolean sendPing(long millis) {
			sent.add(millis);
			return true;
		}

		@Override
		public long millis() {
			return now;
		}

		@Override
		public int serverLatency() {
			return latency;
		}

		@Override
		public boolean multiplayer() {
			return true;
		}
	}

	private final Platform platform = new Platform();

	@BeforeEach
	void setUp() {
		NetBoost.init(platform, new NetBoost.Switch() {
			@Override
			public boolean on() {
				return true;
			}
		}, null);
	}

	@AfterEach
	void tearDown() {
		NetBoost.ping().reset();
	}

	/** Pipeline wie Minecraft: timeout, splitter, decoder, packet_handler (Attrappen, die durchreichen). */
	private static EmbeddedChannel minecraftLike(final List<Object> received) {
		// EmbeddedChannel hängt selbst einen Sammel-Handler ans Ende – unsere Attrappen deshalb davor (addFirst rückwärts).
		EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
		ch.pipeline().addFirst("packet_handler", new ChannelInboundHandlerAdapter() {
			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) {
				received.add(msg);
			}
		});
		ch.pipeline().addFirst("decoder", new ChannelInboundHandlerAdapter());
		ch.pipeline().addFirst("splitter", new ChannelInboundHandlerAdapter());
		ch.pipeline().addFirst("timeout", new ChannelInboundHandlerAdapter());
		return ch;
	}

	@Test
	void decryptionIsSwappedBeforeTheFirstEncryptedByteAndDecryptsCorrectly() throws Exception {
		List<Object> received = new ArrayList<Object>();
		EmbeddedChannel ch = minecraftLike(received);
		NetBoost.attach(ch);
		ch.runPendingTasks();
		assertNotNull(ch.pipeline().get(NetBoost.GATE));
		assertNotNull(ch.pipeline().get(NetBoost.WATCH));

		// Klartext (Verschlüsselungs-Anfrage), dann schaltet „Minecraft“ die Verschlüsselung ein.
		ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
		byte[] key = new byte[16];
		new Random(5).nextBytes(key);
		ch.pipeline().addBefore("splitter", "decrypt", new FakeCipherDecoder(NetCodecsTest.jdk(Cipher.DECRYPT_MODE, key)));

		Cipher enc = NetCodecsTest.jdk(Cipher.ENCRYPT_MODE, key);
		byte[] p1 = NetCodecsTest.packetLike(5000, 1);
		byte[] p2 = NetCodecsTest.packetLike(123, 2);
		ch.writeInbound(Unpooled.wrappedBuffer(enc.update(p1)));
		ch.writeInbound(Unpooled.wrappedBuffer(enc.update(p2)));

		assertTrue(ch.pipeline().get("decrypt") instanceof FastDecrypt, "getauscht");
		assertTrue(NetBoost.STATS.fastDecrypt);
		assertEquals(3, received.size());
		assertArrayEquals(p1, NetCodecsTest.bytes((ByteBuf) received.get(1)));
		assertArrayEquals(p2, NetCodecsTest.bytes((ByteBuf) received.get(2)));
		ch.finish();
	}

	@Test
	void decryptionAlreadyRunningIsNeverSwapped() throws Exception {
		List<Object> received = new ArrayList<Object>();
		EmbeddedChannel ch = minecraftLike(received);
		byte[] key = new byte[16];
		new Random(6).nextBytes(key);
		ch.pipeline().addBefore("splitter", "decrypt", new FakeCipherDecoder(NetCodecsTest.jdk(Cipher.DECRYPT_MODE, key)));
		NetBoost.attach(ch);
		ch.runPendingTasks();
		Cipher enc = NetCodecsTest.jdk(Cipher.ENCRYPT_MODE, key);
		byte[] p = NetCodecsTest.packetLike(700, 3);
		ch.writeInbound(Unpooled.wrappedBuffer(enc.update(p)));
		assertTrue(ch.pipeline().get("decrypt") instanceof FakeCipherDecoder, "Vanilla bleibt");
		assertFalse(NetBoost.STATS.fastDecrypt);
		assertArrayEquals(p, NetCodecsTest.bytes((ByteBuf) received.get(0)));
		ch.finish();
	}

	@Test
	void watchMeasuresPongsAndTicksWithoutTouchingPackets() {
		List<Object> received = new ArrayList<Object>();
		EmbeddedChannel ch = minecraftLike(received);
		NetBoost.attach(ch);
		ch.runPendingTasks();
		// Ping senden (Hauptthread-Takt), Antwort 37 ms später.
		NetBoost.tick(ch, true, 2000, 100);
		assertEquals(1, platform.sent.size());
		long t = platform.sent.get(0);
		platform.now += 37;
		Pong pong = new Pong(t);
		ch.writeInbound(pong);
		Time time = new Time(1234);
		ch.writeInbound(time);
		assertEquals(2, received.size());
		assertTrue(received.get(0) == pong && received.get(1) == time, "unverändert weitergereicht");
		PingMeter.Snapshot s = NetBoost.ping().snapshot(null, platform.now, System.nanoTime());
		assertEquals(PingMeter.Source.ACTIVE, s.source);
		assertEquals(37, s.current);
		// Eine fremde Antwort (z. B. Vanillas F3-Grafik) zählt nicht.
		ch.writeInbound(new Pong(5));
		assertEquals(37, NetBoost.ping().snapshot(null, platform.now, System.nanoTime()).current);
		ch.finish();
	}

	@Test
	void localChannelsAreLeftAlone() {
		assertFalse(NetBoost.isLocal(new EmbeddedChannel(new ChannelInboundHandlerAdapter())));
	}
}

package dev.theredstonee.trsclient.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

import java.util.Locale;

/**
 * Mess-Sonde nur für Selbsttests: zwei Handler um den Entpacker ("decompress") messen, wie lange er je Paket braucht –
 * für Vanillas und den TRS-Entpacker auf genau dieselbe Weise (vorher/nachher im Spiel). Reicht alles unverändert weiter.
 */
public final class NetTiming {
	public static final String IN = "trsclient_probe_in";
	public static final String OUT = "trsclient_probe_out";

	private long start;
	private long inBytes;
	private long packets;
	private long nanos;
	private long bytes;
	private long bigPackets;
	private long bigNanos;

	/** Sonde einsetzen, sobald es einen Entpacker gibt (mehrfach aufrufen schadet nicht). true = sitzt. */
	public boolean attach(Channel ch) {
		if (ch == null || !ch.isOpen()) return false;
		ChannelPipeline p = ch.pipeline();
		if (p.get(IN) != null) return true;
		if (p.get("decompress") == null) return false;
		try {
			p.addBefore("decompress", IN, new ChannelInboundHandlerAdapter() {
				@Override
				public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
					start = System.nanoTime();
					inBytes = msg instanceof ByteBuf ? ((ByteBuf) msg).readableBytes() : 0;
					ctx.fireChannelRead(msg);
				}
			});
			p.addAfter("decompress", OUT, new ChannelInboundHandlerAdapter() {
				@Override
				public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
					long d = System.nanoTime() - start;
					int out = msg instanceof ByteBuf ? ((ByteBuf) msg).readableBytes() : 0;
					synchronized (NetTiming.this) {
						packets++;
						nanos += d;
						bytes += out;
						// „Große“ Pakete (Chunks): mehr als 4 KB entpackt.
						if (out > 4096) {
							bigPackets++;
							bigNanos += d;
						}
					}
					ctx.fireChannelRead(msg);
				}
			});
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	public synchronized void reset() {
		packets = 0;
		nanos = 0;
		bytes = 0;
		bigPackets = 0;
		bigNanos = 0;
	}

	public synchronized String summary() {
		return String.format(Locale.ROOT, "%d Pakete, Ø %.1f µs (Ø %.0f B), davon %d Chunk-Pakete (>4 KB) Ø %.1f µs",
				packets, packets == 0 ? 0 : nanos / 1000.0 / packets, packets == 0 ? 0 : (double) bytes / packets, bigPackets,
				bigPackets == 0 ? 0 : bigNanos / 1000.0 / bigPackets);
	}

	/** Ø Mikrosekunden je Chunk-Paket (> 4 KB), -1 ohne Daten. */
	public synchronized double bigMicros() {
		return bigPackets == 0 ? -1 : bigNanos / 1000.0 / bigPackets;
	}
}

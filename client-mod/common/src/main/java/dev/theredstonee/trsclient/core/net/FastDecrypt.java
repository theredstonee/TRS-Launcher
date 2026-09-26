package dev.theredstonee.trsclient.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * Ersatz für Minecrafts Entschlüsselungs-Handler ("decrypt"): gleiche Stelle in der Pipeline, gleiches Ergebnis, aber
 * mit {@link FastCfb8}. Wird nur eingesetzt, bevor das erste verschlüsselte Byte ankommt (siehe {@link NetBoost}).
 */
public final class FastDecrypt extends MessageToMessageDecoder<ByteBuf> {
	private final FastCfb8 cfb;

	public FastDecrypt(FastCfb8 cfb) {
		this.cfb = cfb;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		int total = in.readableBytes();
		long t0 = System.nanoTime();
		ByteBuf dst = ctx.alloc().heapBuffer(Math.max(total, 1));
		boolean ok = false;
		try {
			byte[] target = dst.array();
			int at = dst.arrayOffset() + dst.writerIndex();
			byte[] history = cfb.history();
			int left = total;
			while (left > 0) {
				int n = Math.min(FastCfb8.CHUNK, left);
				in.readBytes(history, 16, n);
				cfb.decryptChunk(n, target, at);
				at += n;
				left -= n;
			}
			dst.writerIndex(dst.writerIndex() + total);
			out.add(dst);
			ok = true;
		} finally {
			if (!ok) dst.release();
		}
		NetBoost.STATS.decrypted(total, System.nanoTime() - t0);
	}
}

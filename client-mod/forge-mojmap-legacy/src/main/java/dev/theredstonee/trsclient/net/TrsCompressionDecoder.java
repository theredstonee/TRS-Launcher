package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.core.net.NetCodecs;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.CompressionDecoder;

import java.util.List;

/**
 * Vanillas Entpacker (bis 1.20.1) ohne die zwei {@code new byte[]} je Paket: gleiche Prüfungen, gleiche Meldungen, gleiches
 * Ergebnis (siehe {@link NetCodecs.Inflate}). Unterklasse, damit Vanillas {@code instanceof CompressionDecoder} beim
 * erneuten Setzen der Schwelle weiter greift. Ab 1.20.2 ist Vanilla selbst schlank – dort wird sie nicht eingesetzt.
 */
public final class TrsCompressionDecoder extends CompressionDecoder {
	private final NetCodecs.Inflate inflate = new NetCodecs.Inflate();
	private int threshold;
	private boolean validate;

	//? if >=1.17.1 {
	/*public TrsCompressionDecoder(int threshold, boolean validate) {
		super(threshold, validate);
		this.threshold = threshold;
		this.validate = validate;
	}

	@Override
	public void setThreshold(int threshold, boolean validate) {
		super.setThreshold(threshold, validate);
		this.threshold = threshold;
		this.validate = validate;
	}
	*///?} else {
	public TrsCompressionDecoder(int threshold, boolean validate) {
		super(threshold);
		this.threshold = threshold;
		this.validate = true;
	}

	@Override
	public void setThreshold(int threshold) {
		super.setThreshold(threshold);
		this.threshold = threshold;
	}
	//?}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		if (in.readableBytes() == 0) return;
		int size = NetCodecs.readVarInt(in);
		if (size == 0) {
			out.add(in.readBytes(in.readableBytes()));
			return;
		}
		//? if >=1.17.1 {
		/*int max = 8388608; boolean check = validate;
		*///?} else
		int max = 2097152; boolean check = true;
		if (check) {
			if (size < threshold) {
				throw new DecoderException("Badly compressed packet - size of " + size + " is below server threshold of " + threshold);
			}
			if (size > max) {
				throw new DecoderException("Badly compressed packet - size of " + size + " is larger than protocol maximum of " + max);
			}
		}
		// Bis 1.20.1 prüft Vanilla die entpackte Länge nicht (fehlende Bytes bleiben 0) – genauso hier.
		out.add(inflate.inflate(ctx.alloc(), in, size, false));
	}

	@Override
	protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
		inflate.end();
		super.handlerRemoved0(ctx);
	}
}

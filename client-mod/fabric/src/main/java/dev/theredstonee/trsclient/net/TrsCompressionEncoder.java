package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.core.net.NetCodecs;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;

/**
 * Vanillas Packer ohne {@code new byte[]} je Paket: gleiche Schwelle, gleiche zlib-Stufe, gleiche Bytes auf der Leitung
 * (siehe {@link NetCodecs.Deflate}). Unterklasse, damit Vanillas {@code instanceof CompressionEncoder} weiter greift.
 */
public final class TrsCompressionEncoder extends CompressionEncoder {
	private final NetCodecs.Deflate deflate = new NetCodecs.Deflate();
	private int threshold;

	public TrsCompressionEncoder(int threshold) {
		super(threshold);
		this.threshold = threshold;
	}

	@Override
	public void setThreshold(int threshold) {
		super.setThreshold(threshold);
		this.threshold = threshold;
	}

	@Override
	protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) {
		int size = in.readableBytes();
		//? if >=1.20.5 {
		if (size > 8388608) throw new IllegalArgumentException("Packet too big (is " + size + ", should be less than 8388608)");
		//?}
		if (size < threshold) {
			NetCodecs.writeVarInt(out, 0);
			out.writeBytes(in);
		} else {
			NetCodecs.writeVarInt(out, size);
			deflate.deflate(in, out);
		}
	}
}

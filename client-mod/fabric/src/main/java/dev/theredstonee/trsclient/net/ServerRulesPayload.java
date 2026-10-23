package dev.theredstonee.trsclient.net;

//? if >=1.20.5 {
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}

/**
 * TRS Server Rules ab Minecraft 1.20.5: Custom-Payloads brauchen dort einen Typ samt Codec. Inhalt = rohe UTF-8-Bytes
 * ohne Längenpräfix (Protokoll siehe {@code core.serverrules.ServerRules}). Davor laufen die Kanäle über die
 * Puffer-API von Fabric (siehe {@link ServerRulesNet}).
 */
public final class ServerRulesPayload {
	private ServerRulesPayload() {
	}

	//? if >=1.20.5 {
	// Server -> Client: trsclient:rules
	public static final class Rules implements CustomPacketPayload {
		public static final Type<Rules> TYPE = new Type<>(OnlineHooks.id(ServerRules.PATH_RULES));
		public static final StreamCodec<FriendlyByteBuf, Rules> CODEC = StreamCodec.of(
				(buf, payload) -> buf.writeBytes(payload.data), buf -> new Rules(readAll(buf)));

		private final byte[] data;

		public Rules(byte[] data) {
			this.data = data;
		}

		// null = zu groß (verworfen)
		public byte[] data() {
			return data;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// Client -> Server: trsclient:hello
	public static final class Hello implements CustomPacketPayload {
		public static final Type<Hello> TYPE = new Type<>(OnlineHooks.id(ServerRules.PATH_HELLO));
		public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
				(buf, payload) -> buf.writeBytes(payload.data), buf -> new Hello(readAll(buf)));

		private final byte[] data;

		public Hello(byte[] data) {
			this.data = data == null ? new byte[0] : data;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// Alle restlichen Bytes; mehr als ServerRules.MAX_BYTES wird übersprungen (null).
	static byte[] readAll(FriendlyByteBuf buf) {
		int n = buf.readableBytes();
		if (n > ServerRules.MAX_BYTES) {
			buf.skipBytes(n);
			return null;
		}
		byte[] out = new byte[n];
		buf.readBytes(out);
		return out;
	}
	//?}
}

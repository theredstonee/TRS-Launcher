package dev.theredstonee.trsclient.net;

import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if >=1.20.5 {
import net.minecraft.network.codec.StreamCodec;
//?}

/**
 * Pakete der TRS Server Rules (NeoForge). Inhalt = rohe UTF-8-Bytes ohne Längenpräfix (Protokoll siehe
 * {@code core.serverrules.ServerRules}).
 * <ul>
 *   <li>ab 1.20.5: Payload-Typen mit StreamCodec (wie Fabric),</li>
 *   <li>1.20.2–1.20.4: Payloads mit {@code write}/{@code id} (1.20.4 liest über den Reader {@link Rules#read}),</li>
 *   <li>1.20.2/1.20.3 empfangen über einen Ereignis-Kanal (siehe {@link ServerRulesNet}); hier nur das Senden.</li>
 * </ul>
 */
public final class ServerRulesPayload {
	private ServerRulesPayload() {
	}

	// Server -> Client: trsclient:rules
	public static final class Rules implements CustomPacketPayload {
		//? if >=1.20.5 {
		public static final Type<Rules> TYPE = new Type<>(OnlineHooks.id(ServerRules.PATH_RULES));
		public static final StreamCodec<FriendlyByteBuf, Rules> CODEC = StreamCodec.of(
				(buf, payload) -> write(buf, payload.data), Rules::read);
		//?} else {
		/*public static final net.minecraft.resources.ResourceLocation ID = OnlineHooks.id(ServerRules.PATH_RULES);
		*///?}

		private final byte[] data;

		public Rules(byte[] data) {
			this.data = data;
		}

		public static Rules read(FriendlyByteBuf buf) {
			return new Rules(readAll(buf));
		}

		// null = zu groß (verworfen)
		public byte[] data() {
			return data;
		}

		//? if >=1.20.5 {
		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
		//?} else {
		/*@Override
		public void write(FriendlyByteBuf buf) {
			ServerRulesPayload.write(buf, data);
		}

		@Override
		public net.minecraft.resources.ResourceLocation id() {
			return ID;
		}
		*///?}
	}

	// Client -> Server: trsclient:hello
	public static final class Hello implements CustomPacketPayload {
		//? if >=1.20.5 {
		public static final Type<Hello> TYPE = new Type<>(OnlineHooks.id(ServerRules.PATH_HELLO));
		public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
				(buf, payload) -> write(buf, payload.data), Hello::read);
		//?} else {
		/*public static final net.minecraft.resources.ResourceLocation ID = OnlineHooks.id(ServerRules.PATH_HELLO);
		*///?}

		private final byte[] data;

		public Hello(byte[] data) {
			this.data = data == null ? new byte[0] : data;
		}

		public static Hello read(FriendlyByteBuf buf) {
			return new Hello(readAll(buf));
		}

		//? if >=1.20.5 {
		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
		//?} else {
		/*@Override
		public void write(FriendlyByteBuf buf) {
			ServerRulesPayload.write(buf, data);
		}

		@Override
		public net.minecraft.resources.ResourceLocation id() {
			return ID;
		}
		*///?}
	}

	static void write(FriendlyByteBuf buf, byte[] data) {
		if (data != null) buf.writeBytes(data);
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
}

package dev.theredstonee.trsrules.fabric;

import dev.theredstonee.trsrules.common.Protocol;
import dev.theredstonee.trsrules.mod.Mc;
import dev.theredstonee.trsrules.mod.ModServerRules;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Networking for Minecraft 1.20.1 – 1.20.4 (Fabric API with channel ids and raw byte buffers).
 * The global receiver makes Fabric API announce {@code trsclient:hello} to clients (minecraft:register).
 */
final class FabricNet {
	private static final ResourceLocation HELLO = Mc.id("trsclient", "hello");
	private static final ResourceLocation RULES = Mc.id("trsclient", "rules");

	private FabricNet() {
	}

	static boolean register(ModServerRules mod) {
		ServerPlayNetworking.registerGlobalReceiver(HELLO, (server, player, handler, buf, responseSender) -> {
			int n = buf.readableBytes();
			if (n <= 0 || n > Protocol.MAX_PAYLOAD) return;
			byte[] data = new byte[n];
			buf.readBytes(data);
			mod.onHello(player, data); // switches to the server thread itself
		});
		return true;
	}

	static boolean canReceive(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, RULES);
	}

	static void send(ServerPlayer player, byte[] payload) {
		ServerPlayNetworking.send(player, RULES, new FriendlyByteBuf(Unpooled.wrappedBuffer(payload)));
	}
}

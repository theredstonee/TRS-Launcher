package dev.theredstonee.trsrules.neoforge;

import dev.theredstonee.trsrules.common.Protocol;
import dev.theredstonee.trsrules.mod.Mc;
import dev.theredstonee.trsrules.mod.ModPlatform;
import dev.theredstonee.trsrules.mod.ModServerRules;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * TRS Server Rules for NeoForge dedicated servers ({@code dist = DEDICATED_SERVER}: never constructed on a client or in
 * single player, so it cannot clash with payloads a TRS Client registers there).
 *
 * <p>Both payloads are registered as <b>optional</b> (version {@value #CHANNEL_VERSION}), so vanilla and Fabric clients
 * can join. Clients without NeoForge announce their channels with minecraft:register; NeoForge accepts optional payloads
 * from such clients ad hoc and {@code hasChannel} reports their registrations.</p>
 */
@Mod(value = ModServerRules.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class TrsRulesNeoForge {
	/** Payload channel version – a NeoForge TRS Client registering the same optional payloads must use the same. */
	public static final String CHANNEL_VERSION = "1";

	/** Client → server. */
	record Hello(byte[] data) implements CustomPacketPayload {
		static final Type<Hello> TYPE = new Type<>(Mc.id("trsclient", "hello"));
		static final StreamCodec<FriendlyByteBuf, Hello> CODEC = CustomPacketPayload.codec(
				(value, buf) -> buf.writeBytes(value.data), buf -> new Hello(readAll(buf)));

		@Override
		public Type<Hello> type() {
			return TYPE;
		}
	}

	/** Server → client. */
	record Rules(byte[] data) implements CustomPacketPayload {
		static final Type<Rules> TYPE = new Type<>(Mc.id("trsclient", "rules"));
		static final StreamCodec<FriendlyByteBuf, Rules> CODEC = CustomPacketPayload.codec(
				(value, buf) -> buf.writeBytes(value.data), buf -> new Rules(readAll(buf)));

		@Override
		public Type<Rules> type() {
			return TYPE;
		}
	}

	private final ModServerRules mod;

	public TrsRulesNeoForge(IEventBus modBus) {
		mod = new ModServerRules(new NeoForgePlatform(), FMLPaths.CONFIGDIR.get());
		modBus.addListener(RegisterPayloadHandlersEvent.class, this::onRegisterPayloads);

		IEventBus bus = NeoForge.EVENT_BUS;
		bus.addListener(ServerStartingEvent.class, e -> mod.serverStarting(e.getServer()));
		bus.addListener(ServerStoppedEvent.class, e -> mod.serverStopped());
		bus.addListener(ServerTickEvent.Post.class, e -> mod.tick());
		bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
			if (e.getEntity() instanceof ServerPlayer p) mod.onJoin(p);
		});
		bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
			if (e.getEntity() instanceof ServerPlayer p) mod.onQuit(p);
		});
		bus.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, e -> {
			if (e.getEntity() instanceof ServerPlayer p) mod.onWorldChange(p);
		});
		bus.addListener(PlayerEvent.PlayerRespawnEvent.class, e -> {
			if (e.getEntity() instanceof ServerPlayer p) mod.onWorldChange(p);
		});
		bus.addListener(RegisterCommandsEvent.class, e -> mod.registerCommand(e.getDispatcher()));
	}

	private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(CHANNEL_VERSION).optional();
		registrar.playToServer(Hello.TYPE, Hello.CODEC, (payload, context) -> {
			if (payload.data().length > 0 && context.player() instanceof ServerPlayer p) mod.onHello(p, payload.data());
		});
		// Handled by the TRS Client; the server only sends it.
		registrar.playToClient(Rules.TYPE, Rules.CODEC, (payload, context) -> {
		});
	}

	private static byte[] readAll(FriendlyByteBuf buf) {
		int n = buf.readableBytes();
		if (n > Protocol.MAX_PAYLOAD) {
			buf.skipBytes(n);
			return new byte[0];
		}
		byte[] data = new byte[n];
		buf.readBytes(data);
		return data;
	}

	/** NeoForge: payloads only to clients that have the channel (negotiated or minecraft:register); no permission API. */
	private static final class NeoForgePlatform implements ModPlatform {
		@Override
		public String name() {
			return "NeoForge";
		}

		@Override
		public boolean canReceive(ServerPlayer player) {
			return player.connection != null && player.connection.hasChannel(Rules.TYPE);
		}

		@Override
		public void send(ServerPlayer player, byte[] payload) {
			PacketDistributor.sendToPlayer(player, new Rules(payload));
		}

		@Override
		public Boolean permission(ServerPlayer player, String permission) {
			return null; // NeoForge's PermissionAPI needs nodes registered at startup – use bypass-players / bypass-op-level
		}
	}
}

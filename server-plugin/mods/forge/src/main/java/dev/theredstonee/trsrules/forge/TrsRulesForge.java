package dev.theredstonee.trsrules.forge;

import dev.theredstonee.trsrules.common.Protocol;
import dev.theredstonee.trsrules.mod.Mc;
import dev.theredstonee.trsrules.mod.ModPlatform;
import dev.theredstonee.trsrules.mod.ModServerRules;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

/**
 * TRS Server Rules for Forge 1.20.1 dedicated servers. Does nothing on clients / in single player.
 *
 * <p>The two channels are event channels that accept every remote version, including "absent" and vanilla, so
 * vanilla, Fabric and Forge clients without the TRS Client can join. Rules go out as plain vanilla custom payload
 * packets – only to players that sent a TRS Client hello (which means they listen on {@code trsclient:rules}).</p>
 */
@Mod(ModServerRules.MOD_ID)
public final class TrsRulesForge {
	private static final ResourceLocation HELLO = Mc.id("trsclient", "hello");
	private static final ResourceLocation RULES = Mc.id("trsclient", "rules");
	private static final String CHANNEL_VERSION = "1";

	private ModServerRules mod;

	public TrsRulesForge() {
		// Clients do not need this mod (and the server's version is never compared with theirs).
		ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
				() -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, fromServer) -> true));
		if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;

		mod = new ModServerRules(new ForgePlatform(), FMLPaths.CONFIGDIR.get());

		EventNetworkChannel hello = channel(HELLO);
		hello.addListener(this::onHelloPayload);
		channel(RULES); // registered so Forge announces it and accepts it on every connection type

		MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
		MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
		MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
		MinecraftForge.EVENT_BUS.addListener(this::onLogin);
		MinecraftForge.EVENT_BUS.addListener(this::onLogout);
		MinecraftForge.EVENT_BUS.addListener(this::onDimensionChange);
		MinecraftForge.EVENT_BUS.addListener(this::onRespawn);
		MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
	}

	/** A channel that accepts every remote version, also "absent" (vanilla, Fabric and Forge clients without TRS). */
	private static EventNetworkChannel channel(ResourceLocation id) {
		return NetworkRegistry.ChannelBuilder.named(id)
				.networkProtocolVersion(() -> CHANNEL_VERSION)
				.clientAcceptedVersions(v -> true)
				.serverAcceptedVersions(v -> true)
				.eventNetworkChannel();
	}

	private void onHelloPayload(NetworkEvent.ClientCustomPayloadEvent event) {
		NetworkEvent.Context ctx = event.getSource().get();
		ServerPlayer player = ctx.getSender();
		FriendlyByteBuf buf = event.getPayload();
		ctx.setPacketHandled(true);
		if (player == null || buf == null) return;
		int n = buf.readableBytes();
		if (n <= 0 || n > Protocol.MAX_PAYLOAD) return;
		byte[] data = new byte[n];
		buf.readBytes(data);
		mod.onHello(player, data); // switches to the server thread itself
	}

	private void onServerStarting(ServerStartingEvent e) {
		mod.serverStarting(e.getServer());
	}

	private void onServerStopped(ServerStoppedEvent e) {
		mod.serverStopped();
	}

	private void onServerTick(TickEvent.ServerTickEvent e) {
		if (e.phase == TickEvent.Phase.END) mod.tick();
	}

	private void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
		if (e.getEntity() instanceof ServerPlayer p) mod.onJoin(p);
	}

	private void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
		if (e.getEntity() instanceof ServerPlayer p) mod.onQuit(p);
	}

	private void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent e) {
		if (e.getEntity() instanceof ServerPlayer p) mod.onWorldChange(p);
	}

	private void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
		if (e.getEntity() instanceof ServerPlayer p) mod.onWorldChange(p);
	}

	private void onRegisterCommands(RegisterCommandsEvent e) {
		mod.registerCommand(e.getDispatcher());
	}

	/** Forge 1.20.1: vanilla custom payload packets; no permission API. */
	private static final class ForgePlatform implements ModPlatform {
		@Override
		public String name() {
			return "Forge";
		}

		@Override
		public boolean canReceive(ServerPlayer player) {
			// Rules are only sent after a hello, and a TRS Client that says hello listens on trsclient:rules.
			return player.connection != null;
		}

		@Override
		public void send(ServerPlayer player, byte[] payload) {
			player.connection.send(new ClientboundCustomPayloadPacket(RULES, new FriendlyByteBuf(Unpooled.wrappedBuffer(payload))));
		}

		@Override
		public Boolean permission(ServerPlayer player, String permission) {
			return null; // no permission API on Forge – use bypass-players / bypass-op-level
		}
	}
}

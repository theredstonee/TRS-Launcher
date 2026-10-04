package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.connect.ServerPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Schnell verbinden – Server-Ressourcenpakete: Vor Vanillas Handler sieht {@link ServerPacks} jedes Paket, das ein
 * Server schickt (merken, vorgeladene Datei an Vanillas Platz legen). Ist genau dieses Paket schon aktiv (Wechsel im
 * Proxy-Netzwerk), antwortet der Client sofort mit denselben Status-Meldungen, die Vanilla nach dem (unnötigen)
 * Neuladen schicken würde. 1.20.2 räumt Pakete beim Wechsel selbst ab – dort wird nie übersprungen.
 */
//? if >=1.20.2 {
@Mixin(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class)
//?} else
/*@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)*/
public abstract class PackPushMixin {
	//? if >=1.20.2 {
	@Shadow
	@Final
	protected Connection connection;
	@Shadow
	@Final
	protected ServerData serverData;
	//?} else {
	/*@Shadow
	@Final
	private Connection connection;
	*///?}

	//? if >=1.20.3 {
	@Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$packPush(net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
		// Vanilla ruft den Handler zuerst im Netzwerk-Thread auf und leitet dann in den Hauptthread um.
		if (!Minecraft.getInstance().isSameThread()) return;
		java.util.UUID id = packet.id();
		String server = serverData != null ? serverData.ip : null;
		if (!ServerPacks.onPush(server, id.toString(), packet.url(), packet.hash())) return;
		ci.cancel();
		connection.send(new net.minecraft.network.protocol.common.ServerboundResourcePackPacket(id,
				net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action.ACCEPTED));
		connection.send(new net.minecraft.network.protocol.common.ServerboundResourcePackPacket(id,
				net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action.DOWNLOADED));
		connection.send(new net.minecraft.network.protocol.common.ServerboundResourcePackPacket(id,
				net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
	}

	@Inject(method = "handleResourcePackPop", at = @At("HEAD"), require = 0)
	private void trsclient$packPop(net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket packet, CallbackInfo ci) {
		if (!Minecraft.getInstance().isSameThread()) return;
		ServerPacks.onPop(packet.id().isPresent() ? packet.id().get().toString() : null);
	}
	//?} elif >=1.20.2 {
	/*@Inject(method = "handleResourcePack", at = @At("HEAD"), require = 0)
	private void trsclient$packPush(net.minecraft.network.protocol.common.ClientboundResourcePackPacket packet, CallbackInfo ci) {
		// 1.20.2 entfernt Server-Pakete bei jedem Wechsel selbst → nie überspringen, nur merken/vorgeladen ablegen.
		ServerPacks.onPop(null);
		ServerPacks.onPush(serverData != null ? serverData.ip : null, null, packet.getUrl(), packet.getHash());
	}
	*///?} else {
	/*@Inject(method = "handleResourcePack", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$packPush(net.minecraft.network.protocol.game.ClientboundResourcePackPacket packet, CallbackInfo ci) {
		ServerData data = Minecraft.getInstance().getCurrentServer();
		if (!ServerPacks.onPush(data != null ? data.ip : null, null, packet.getUrl(), packet.getHash())) return;
		ci.cancel();
		connection.send(new net.minecraft.network.protocol.game.ServerboundResourcePackPacket(
				net.minecraft.network.protocol.game.ServerboundResourcePackPacket.Action.ACCEPTED));
		connection.send(new net.minecraft.network.protocol.game.ServerboundResourcePackPacket(
				net.minecraft.network.protocol.game.ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
	}
	*///?}
}

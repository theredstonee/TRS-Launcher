package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.connect.ServerPacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Schnell verbinden – Server-Ressourcenpakete: Jede Status-Meldung, die der Client für ein Paket baut (angenommen,
 * abgelehnt, geladen …), geht an {@link ServerPacks} – daraus folgt, welche Pakete gemerkt/vorgeladen werden und
 * welches gerade aktiv ist. Nur der Konstruktor, den Minecraft zum Senden nutzt (ab 1.20.5 baut auch der eingebaute
 * Server beim Lesen damit – daher nur Meldungen aus dem Hauptthread des Clients).
 */
//? if >=1.20.2 {
/*@Mixin(net.minecraft.network.protocol.common.ServerboundResourcePackPacket.class)
*///?} else
@Mixin(net.minecraft.network.protocol.game.ServerboundResourcePackPacket.class)
public abstract class PackStatusMixin {
	//? if >=1.20.3 {
	/*@Inject(method = "<init>(Ljava/util/UUID;Lnet/minecraft/network/protocol/common/ServerboundResourcePackPacket$Action;)V", at = @At("RETURN"), require = 0)
	private void trsclient$status(java.util.UUID id, net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action action, CallbackInfo ci) {
		if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) return;
		ServerPacks.onStatus(id == null ? null : id.toString(), action == null ? null : action.name());
	}
	*///?} elif >=1.20.2 {
	/*@Inject(method = "<init>(Lnet/minecraft/network/protocol/common/ServerboundResourcePackPacket$Action;)V", at = @At("RETURN"), require = 0)
	private void trsclient$status(net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action action, CallbackInfo ci) {
		ServerPacks.onStatus(null, action == null ? null : action.name());
	}
	*///?} else {
	@Inject(method = "<init>(Lnet/minecraft/network/protocol/game/ServerboundResourcePackPacket$Action;)V", at = @At("RETURN"), require = 0)
	private void trsclient$status(net.minecraft.network.protocol.game.ServerboundResourcePackPacket.Action action, CallbackInfo ci) {
		ServerPacks.onStatus(null, action == null ? null : action.name());
	}
	//?}
}

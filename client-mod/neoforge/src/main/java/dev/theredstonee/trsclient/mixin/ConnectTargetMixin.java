package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.menus.DisconnectUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.17 {
import net.minecraft.client.multiplayer.resolver.ServerAddress;
//?}

/**
 * Fehlerbildschirm: merkt sich, wohin zuletzt verbunden wurde – auch wenn es schon bei der Anmeldung scheitert
 * (dann ist {@code Minecraft#getCurrentServer} nicht verlässlich gesetzt). Für „Erneut verbinden“ / „Neu anmelden“.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectTargetMixin {
	//? if >=1.20.5 {
	@Inject(method = "startConnecting", at = @At("HEAD"), require = 0)
	private static void trsclient$target(Screen parent, Minecraft mc, ServerAddress address, ServerData data, boolean quickPlay,
			net.minecraft.client.multiplayer.TransferState transfer, CallbackInfo ci) {
		DisconnectUi.target(data);
		dev.theredstonee.trsclient.core.connect.FastConnect.connectScreenOpened();
	}
	//?} elif >=1.20 {
	/*@Inject(method = "startConnecting", at = @At("HEAD"), require = 0)
	private static void trsclient$target(Screen parent, Minecraft mc, ServerAddress address, ServerData data, boolean quickPlay, CallbackInfo ci) {
		DisconnectUi.target(data);
		dev.theredstonee.trsclient.core.connect.FastConnect.connectScreenOpened();
	}
	*///?} elif >=1.17 {
	/*@Inject(method = "startConnecting", at = @At("HEAD"), require = 0)
	private static void trsclient$target(Screen parent, Minecraft mc, ServerAddress address, ServerData data, CallbackInfo ci) {
		DisconnectUi.target(data);
		dev.theredstonee.trsclient.core.connect.FastConnect.connectScreenOpened();
	}
	*///?} else {
	/*@Inject(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/ServerData;)V",
			at = @At("RETURN"), require = 0)
	private void trsclient$target(Screen parent, Minecraft mc, ServerData data, CallbackInfo ci) {
		DisconnectUi.target(data);
		dev.theredstonee.trsclient.core.connect.FastConnect.connectScreenOpened();
	}
	*///?}
}

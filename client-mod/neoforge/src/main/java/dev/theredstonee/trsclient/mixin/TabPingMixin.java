package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.qol.QolHooks;
import dev.theredstonee.trsclient.qol.QolHuds;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tabliste: Ping in ms statt Balken (optional farbig) und Namen im Streamer-Modus ersetzen. */
@Mixin(PlayerTabOverlay.class)
public abstract class TabPingMixin {
	//? if >=26.1 {
	/*@Inject(method = "extractPingIcon(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIILnet/minecraft/client/multiplayer/PlayerInfo;)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$ping(net.minecraft.client.gui.GuiGraphicsExtractor g, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
		if (QolHuds.tabPing(Gfx.of(g), width, x, y, info.getLatency())) ci.cancel();
	}
	*///?} elif >=1.20 {
	@Inject(method = "renderPingIcon(Lnet/minecraft/client/gui/GuiGraphics;IIILnet/minecraft/client/multiplayer/PlayerInfo;)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$ping(net.minecraft.client.gui.GuiGraphics g, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
		if (QolHuds.tabPing(Gfx.of(g), width, x, y, info.getLatency())) ci.cancel();
	}
	//?} elif >=1.16 {
	/*@Inject(method = "renderPingIcon(Lcom/mojang/blaze3d/vertex/PoseStack;IIILnet/minecraft/client/multiplayer/PlayerInfo;)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$ping(com.mojang.blaze3d.vertex.PoseStack pose, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
		if (QolHuds.tabPing(Gfx.of(pose), width, x, y, info.getLatency())) ci.cancel();
	}
	*///?} else {
	/*@Inject(method = "renderPingIcon(IIILnet/minecraft/client/multiplayer/PlayerInfo;)V", at = @At("HEAD"), cancellable = true,
			require = 1)
	private void trsclient$ping(int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
		if (QolHuds.tabPing(Gfx.of(), width, x, y, info.getLatency())) ci.cancel();
	}
	*///?}

	@Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$mask(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		Component name = cir.getReturnValue();
		Component masked = QolHooks.maskName(name);
		if (masked != name) cir.setReturnValue(masked);
	}
}

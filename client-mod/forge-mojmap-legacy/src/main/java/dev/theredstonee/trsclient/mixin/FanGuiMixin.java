package dev.theredstonee.trsclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.16 {
import dev.theredstonee.trsclient.online.FanHooks;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//?}
//? if >=1.21.6 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?} elif >=1.16
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fan-Symbol in der Tabliste (und überall, wo die Oberfläche einen von {@code FanHooks} erweiterten Namen schreibt):
 * vor dem Text das Bild an der Stelle des Platzhalters. GuiGraphicsExtractor#text ab 26.1, GuiGraphics#drawString
 * 1.20–1.21.11, Font#drawShadow(PoseStack, Component, …) 1.16–1.19.4. Bis 1.15 nicht in der Mixin-Liste.
 */
//? if >=26.1 {
/*@Mixin(net.minecraft.client.gui.GuiGraphicsExtractor.class)
*///?} elif >=1.20 {
/*@Mixin(net.minecraft.client.gui.GuiGraphics.class)
*///?} else
@Mixin(net.minecraft.client.gui.Font.class)
public abstract class FanGuiMixin {
	//? if >=26.1 {
	/*@Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(Font font, Component text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
		FanHooks.gui(this, text, x, y);
	}
	*///?} elif >=1.21.6 {
	/*@Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(Font font, Component text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
		FanHooks.gui(this, text, x, y);
	}
	*///?} elif >=1.20 {
	/*@Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I", at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(Font font, Component text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
		FanHooks.gui(this, text, x, y);
	}
	*///?} elif >=1.16 {
	@Inject(method = "drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I", at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(com.mojang.blaze3d.vertex.PoseStack pose, Component text, float x, float y, int color,
			CallbackInfoReturnable<Integer> cir) {
		FanHooks.gui(pose, text, (int) x, (int) y);
	}
	//?}
}

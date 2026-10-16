package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.16 && <26.2 {
import dev.theredstonee.trsclient.online.FanHooks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//?}
//? if >=1.21.6 && <26.2 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?} elif >=1.16 && <1.21.6
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fan-Symbol auf Namensschildern über dem Kopf: Vanilla schreibt den Namen mit {@code Font#drawInBatch(Component, …)}
 * (bis 1.21.8 im EntityRenderer, 1.21.9–26.1 im NameTagFeatureRenderer). Ist der Text ein von {@code FanHooks}
 * erweiterter Name, kommt das Symbol mit derselben Matrix und demselben Modus in denselben Puffer. Ab 26.2 übernimmt
 * {@code FanNameTagMixin}; bis 1.15 gibt es keine Fan-Symbole (Namen sind dort reiner Text).
 */
@Mixin(Font.class)
public abstract class FanTextMixin {
	//? if >=26.1 && <26.2 {
	/*@Inject(method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
			at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(Component text, float x, float y, int color, boolean shadow, org.joml.Matrix4fc pose,
			MultiBufferSource buffers, Font.DisplayMode mode, int background, int light, CallbackInfo ci) {
		FanHooks.world(text, x, y, color, pose, buffers, mode == Font.DisplayMode.SEE_THROUGH, light);
	}
	*///?} elif >=1.21.6 && <26.1 {
	/*@Inject(method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
			at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(Component text, float x, float y, int color, boolean shadow, org.joml.Matrix4f pose,
			MultiBufferSource buffers, Font.DisplayMode mode, int background, int light, CallbackInfo ci) {
		FanHooks.world(text, x, y, color, pose, buffers, mode == Font.DisplayMode.SEE_THROUGH, light);
	}
	*///?} elif >=1.19.4 && <1.21.6 {
	/*@Inject(method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
			at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(Component text, float x, float y, int color, boolean shadow, org.joml.Matrix4f pose,
			MultiBufferSource buffers, Font.DisplayMode mode, int background, int light, CallbackInfoReturnable<Integer> cir) {
		FanHooks.world(text, x, y, color, pose, buffers, mode == Font.DisplayMode.SEE_THROUGH, light);
	}
	*///?} elif >=1.19.3 && <1.19.4 {
	/*@Inject(method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;ZII)I",
			at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(Component text, float x, float y, int color, boolean shadow, org.joml.Matrix4f pose,
			MultiBufferSource buffers, boolean seeThrough, int background, int light, CallbackInfoReturnable<Integer> cir) {
		FanHooks.world(text, x, y, color, pose, buffers, seeThrough, light);
	}
	*///?} elif >=1.16 && <1.19.3 {
	@Inject(method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLcom/mojang/math/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;ZII)I",
			at = @At("HEAD"), require = 0)
	private void trsclient$fanSymbol(Component text, float x, float y, int color, boolean shadow, com.mojang.math.Matrix4f pose,
			MultiBufferSource buffers, boolean seeThrough, int background, int light, CallbackInfoReturnable<Integer> cir) {
		FanHooks.world(text, x, y, color, pose, buffers, seeThrough, light);
	}
	//?}
}

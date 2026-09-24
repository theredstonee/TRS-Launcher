package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.render.ColorPass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Modul „Farben“: direkt nach {@code GameRenderer#renderLevel} (Welt samt Hand ist gezeichnet, HUD und
 * Menüs noch nicht) läuft der Farb-Durchgang ({@link ColorPass}). Ziel je Version mit vollem Deskriptor:
 * {@code render(FJZ)V} bis 1.20.6 (renderLevel mit PoseStack 1.15–1.20.4), {@code render(DeltaTracker, Z)V}
 * ab 1.21, {@code render()V} ab 26.3.
 */
@Mixin(GameRenderer.class)
public abstract class ColorGradeMixin {
	//? if >=26.3 {
	/*@Inject(method = "render()V", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER))
	private void trsclient$gradeColors(CallbackInfo ci) {
		ColorPass.afterLevel();
	}
	*///?} elif >=1.21 {
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			shift = At.Shift.AFTER))
	private void trsclient$gradeColors(CallbackInfo ci) {
		ColorPass.afterLevel();
	}
	//?} elif >=1.20.5 {
	/*@Inject(method = "render(FJZ)V", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJ)V", shift = At.Shift.AFTER))
	private void trsclient$gradeColors(CallbackInfo ci) {
		ColorPass.afterLevel();
	}
	*///?} elif >=1.15 {
	/*@Inject(method = "render(FJZ)V", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
			shift = At.Shift.AFTER))
	private void trsclient$gradeColors(CallbackInfo ci) {
		ColorPass.afterLevel();
	}
	*///?} else {
	/*@Inject(method = "render(FJZ)V", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJ)V", shift = At.Shift.AFTER))
	private void trsclient$gradeColors(CallbackInfo ci) {
		ColorPass.afterLevel();
	}
	*///?}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.qol.QolHuds;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bossleiste verschieben und skalieren (Position/Größe aus dem HUD-Editor); gezeichnet wird weiter von Vanilla. */
@Mixin(BossHealthOverlay.class)
public abstract class BossBarMixin {
	@Unique
	private boolean trsclient$moved;

	//? if >=26.1 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), require = 1)
	private void trsclient$start(net.minecraft.client.gui.GuiGraphicsExtractor g, CallbackInfo ci) {
		trsclient$moved = QolHuds.bossBarBegin(Gfx.of(g));
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("RETURN"), require = 1)
	private void trsclient$end(net.minecraft.client.gui.GuiGraphicsExtractor g, CallbackInfo ci) {
		if (trsclient$moved) QolHuds.end(Gfx.of(g));
		trsclient$moved = false;
	}
	*///?} elif >=1.20 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), require = 1)
	private void trsclient$start(net.minecraft.client.gui.GuiGraphics g, CallbackInfo ci) {
		trsclient$moved = QolHuds.bossBarBegin(Gfx.of(g));
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("RETURN"), require = 1)
	private void trsclient$end(net.minecraft.client.gui.GuiGraphics g, CallbackInfo ci) {
		if (trsclient$moved) QolHuds.end(Gfx.of(g));
		trsclient$moved = false;
	}
	*///?} elif >=1.16 {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"), require = 1)
	private void trsclient$start(com.mojang.blaze3d.vertex.PoseStack pose, CallbackInfo ci) {
		trsclient$moved = QolHuds.bossBarBegin(Gfx.of(pose));
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("RETURN"), require = 1)
	private void trsclient$end(com.mojang.blaze3d.vertex.PoseStack pose, CallbackInfo ci) {
		if (trsclient$moved) QolHuds.end(Gfx.of(pose));
		trsclient$moved = false;
	}
	//?} else {
	/*@Inject(method = "render()V", at = @At("HEAD"), require = 1)
	private void trsclient$start(CallbackInfo ci) {
		trsclient$moved = QolHuds.bossBarBegin(Gfx.of());
	}

	@Inject(method = "render()V", at = @At("RETURN"), require = 1)
	private void trsclient$end(CallbackInfo ci) {
		if (trsclient$moved) QolHuds.end(Gfx.of());
		trsclient$moved = false;
	}
	*///?}
}

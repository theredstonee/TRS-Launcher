package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} elif >=1.16
/*import com.mojang.blaze3d.vertex.PoseStack;*/

/**
 * Ressourcen laden (sonst Mojang-Logo): nach dem Vanilla-Zeichnen eine eigene Überblendung im Redstone-Stil
 * mit derselben Deckkraft und demselben Fortschritt. Das Überblenden und Beenden selbst bleibt bei Minecraft.
 * Nur Rechtecke – beim ersten Start ist die Schrift noch nicht geladen.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
	@Shadow
	private float currentProgress;
	@Shadow
	private long fadeOutStart;
	@Shadow
	private long fadeInStart;
	@Shadow
	@Final
	private boolean fadeIn;

	/** Deckkraft wie Vanilla (Einblenden 500 ms, Ausblenden 1000 ms nach einer Sekunde Halten). */
	private float trsclient$alpha() {
		long now = System.nanoTime() / 1_000_000L;
		float out = fadeOutStart > -1L ? (now - fadeOutStart) / 1000.0F : -1.0F;
		float in = fadeInStart > -1L ? (now - fadeInStart) / 500.0F : -1.0F;
		if (out >= 1.0F) return 1.0F - Math.max(0f, Math.min(1f, out - 1.0F));
		if (fadeIn) return Math.max(0f, Math.min(1f, in));
		return 1.0F;
	}

	//? if >=26.1 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$overlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Gfx gfx = Gfx.of(g);
		VanillaMenus.resourceOverlay(gfx, gfx.width(), gfx.height(), currentProgress, trsclient$alpha());
	}
	*///?} elif >=1.20 {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$overlay(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Gfx gfx = Gfx.of(g);
		VanillaMenus.resourceOverlay(gfx, gfx.width(), gfx.height(), currentProgress, trsclient$alpha());
	}
	//?} elif >=1.16 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$overlay(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Gfx gfx = Gfx.of(pose);
		VanillaMenus.resourceOverlay(gfx, gfx.width(), gfx.height(), currentProgress, trsclient$alpha());
	}
	*///?} else {
	/*@Inject(method = "render(IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$overlay(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Gfx gfx = Gfx.of();
		VanillaMenus.resourceOverlay(gfx, gfx.width(), gfx.height(), currentProgress, trsclient$alpha());
	}
	*///?}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.qol.QolGuiAccess;
import dev.theredstonee.trsclient.qol.QolHuds;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD von Minecraft (Gui, ab 26.2 Hud): Scoreboard selbst zeichnen (verschiebbar, skalierbar, ohne rote Zahlen),
 * Titel verschieben/skalieren (ab 1.20.5 eigene Methode; davor nur Größe über Vanillas 4,0/2,0 im render) und Titel/
 * Aktionsleiste lesen (Warteschlange, Auto-GG).
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.gui.Hud.class)
*///?} else
@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class QolGuiMixin implements QolGuiAccess {
	//? if >=1.16 {
	@Shadow
	private net.minecraft.network.chat.Component title;
	@Shadow
	private net.minecraft.network.chat.Component overlayMessageString;
	//?} else {
	/*@Shadow
	private String title;
	@Shadow
	private String overlayMessageString;
	*///?}

	@Unique
	private boolean trsclient$titleMoved;

	@Override
	public Object trsclient$title() {
		return title;
	}

	@Override
	public Object trsclient$overlay() {
		return overlayMessageString;
	}

	//? if >=26.1 {
	/*@Inject(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$sidebar(net.minecraft.client.gui.GuiGraphicsExtractor g, Objective objective, CallbackInfo ci) {
		if (QolHuds.sidebar(Gfx.of(g), objective)) ci.cancel();
	}
	*///?} elif >=1.20 {
	/*@Inject(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$sidebar(net.minecraft.client.gui.GuiGraphics g, Objective objective, CallbackInfo ci) {
		if (QolHuds.sidebar(Gfx.of(g), objective)) ci.cancel();
	}
	*///?} elif >=1.16 {
	@Inject(method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$sidebar(com.mojang.blaze3d.vertex.PoseStack pose, Objective objective, CallbackInfo ci) {
		if (QolHuds.sidebar(Gfx.of(pose), objective)) ci.cancel();
	}
	//?} else {
	/*@Inject(method = "displayScoreboardSidebar(Lnet/minecraft/world/scores/Objective;)V", at = @At("HEAD"), cancellable = true,
			require = 1)
	private void trsclient$sidebar(Objective objective, CallbackInfo ci) {
		if (QolHuds.sidebar(Gfx.of(), objective)) ci.cancel();
	}
	*///?}

	//? if >=26.1 {
	/*@Inject(method = "extractTitle(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At("HEAD"), require = 1)
	private void trsclient$titleStart(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta,
			CallbackInfo ci) {
		trsclient$titleMoved = QolHuds.titleBegin(Gfx.of(g));
	}

	@Inject(method = "extractTitle(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At("RETURN"), require = 1)
	private void trsclient$titleEnd(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta,
			CallbackInfo ci) {
		if (trsclient$titleMoved) QolHuds.end(Gfx.of(g));
		trsclient$titleMoved = false;
	}
	*///?} elif >=1.21 {
	/*@Inject(method = "renderTitle(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"),
			require = 1)
	private void trsclient$titleStart(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.DeltaTracker delta, CallbackInfo ci) {
		trsclient$titleMoved = QolHuds.titleBegin(Gfx.of(g));
	}

	@Inject(method = "renderTitle(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"),
			require = 1)
	private void trsclient$titleEnd(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.DeltaTracker delta, CallbackInfo ci) {
		if (trsclient$titleMoved) QolHuds.end(Gfx.of(g));
		trsclient$titleMoved = false;
	}
	*///?} elif >=1.20.5 {
	/*@Inject(method = "renderTitle(Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At("HEAD"), require = 1)
	private void trsclient$titleStart(net.minecraft.client.gui.GuiGraphics g, float delta, CallbackInfo ci) {
		trsclient$titleMoved = QolHuds.titleBegin(Gfx.of(g));
	}

	@Inject(method = "renderTitle(Lnet/minecraft/client/gui/GuiGraphics;F)V", at = @At("RETURN"), require = 1)
	private void trsclient$titleEnd(net.minecraft.client.gui.GuiGraphics g, float delta, CallbackInfo ci) {
		if (trsclient$titleMoved) QolHuds.end(Gfx.of(g));
		trsclient$titleMoved = false;
	}
	*///?} elif >=1.20 {
	/*@ModifyConstant(method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
			constant = {@Constant(floatValue = 4.0F), @Constant(floatValue = 2.0F)}, require = 0)
	private float trsclient$titleScale(float vanilla) {
		return vanilla * QolHuds.titleFactor();
	}
	*///?} elif >=1.16 {
	@ModifyConstant(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
			constant = {@Constant(floatValue = 4.0F), @Constant(floatValue = 2.0F)}, require = 0)
	private float trsclient$titleScale(float vanilla) {
		return vanilla * QolHuds.titleFactor();
	}
	//?} else {
	/*@ModifyConstant(method = "render(F)V", constant = {@Constant(floatValue = 4.0F), @Constant(floatValue = 2.0F)}, require = 0)
	private float trsclient$titleScale(float vanilla) {
		return vanilla * QolHuds.titleFactor();
	}
	*///?}
}

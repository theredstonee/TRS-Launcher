package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} elif >=1.16
/*import com.mojang.blaze3d.vertex.PoseStack;*/

/** Welten-Liste im Redstone-Stil: Karte hinter jedem Vanilla-Eintrag (Inhalt bleibt von Minecraft). */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldEntryMixin {
	//? if >=26.1 {
	/*@Inject(method = "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
		WorldSelectionList.WorldListEntry e = (WorldSelectionList.WorldListEntry) (Object) this;
		VanillaMenus.worldEntryBefore(Gfx.of(g), this, e.getContentX(), e.getContentY(), e.getContentWidth(), e.getContentHeight(), hovered);
	}
	*///?} elif >=1.21.9 {
	/*@Inject(method = "renderContent(Lnet/minecraft/client/gui/GuiGraphics;IIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick, CallbackInfo ci) {
		WorldSelectionList.WorldListEntry e = (WorldSelectionList.WorldListEntry) (Object) this;
		VanillaMenus.worldEntryBefore(Gfx.of(g), this, e.getContentX(), e.getContentY(), e.getContentWidth(), e.getContentHeight(), hovered);
	}
	*///?} elif >=1.20 {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIIIIIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		VanillaMenus.worldEntryBefore(Gfx.of(g), this, left, top, width, height, hovered);
	}
	//?} elif >=1.16 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIIIIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(PoseStack pose, int index, int top, int left, int width, int height, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		VanillaMenus.worldEntryBefore(Gfx.of(pose), this, left, top, width, height, hovered);
	}
	*///?} else {
	/*@Inject(method = "render(IIIIIIIZF)V", at = @At("HEAD"), require = 1)
	private void trsclient$before(int index, int top, int left, int width, int height, int mouseX, int mouseY,
			boolean hovered, float partialTick, CallbackInfo ci) {
		VanillaMenus.worldEntryBefore(Gfx.of(), this, left, top, width, height, hovered);
	}
	*///?}
}

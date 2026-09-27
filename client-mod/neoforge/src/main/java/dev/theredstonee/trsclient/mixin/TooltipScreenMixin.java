package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.comfort.ComfortHooks;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
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
 * Bessere Tooltips: am Ende des Inventar-Zeichnens die Zusatzkarte (Shulker-Raster, Karte, Hunger) neben den Tooltip
 * des überfahrenen Feldes legen. Logik in {@link ComfortHooks#afterContainerRender} bzw. {@code core.tooltip}.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class TooltipScreenMixin {
	@Shadow
	protected Slot hoveredSlot;

	// Ab 1.21.6 zeichnen Rezeptbuch-Bildschirme (Inventar, Werkbank, Ofen) nicht über render, sondern direkt über
	// renderContents (26.x: extractContents) – dort sitzt der Haken.
	//? if >=26.1 {
	/*@Inject(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$tooltipCard(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		ComfortHooks.afterContainerRender((AbstractContainerScreen<?>) (Object) this, hoveredSlot, Gfx.of(g), mouseX, mouseY);
	}
	*///?} elif >=1.21.6 {
	/*@Inject(method = "renderContents(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$tooltipCard(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		ComfortHooks.afterContainerRender((AbstractContainerScreen<?>) (Object) this, hoveredSlot, Gfx.of(g), mouseX, mouseY);
	}
	*///?} elif >=1.20 {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$tooltipCard(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		ComfortHooks.afterContainerRender((AbstractContainerScreen<?>) (Object) this, hoveredSlot, Gfx.of(g), mouseX, mouseY);
	}
	//?} elif >=1.16 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$tooltipCard(PoseStack pose, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		ComfortHooks.afterContainerRender((AbstractContainerScreen<?>) (Object) this, hoveredSlot, Gfx.of(pose), mouseX, mouseY);
	}
	*///?} else {
	/*@Inject(method = "render(IIF)V", at = @At("TAIL"), require = 1)
	private void trsclient$tooltipCard(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		ComfortHooks.afterContainerRender((AbstractContainerScreen<?>) (Object) this, hoveredSlot, Gfx.of(), mouseX, mouseY);
	}
	*///?}
}

package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.20.5 {
import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.ui.Gfx;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20.5 {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * Ab 1.20.5 zeichnen Listen eigenen Hintergrund und Trennlinien (Texturen) – in gestylten Menüs stattdessen
 * eine Redstone-Mulde und Staubleitungen. Bis 1.20.4 schaltet {@link VanillaMenus} den Listen-Hintergrund ab
 * (dann steht dieser Mixin nicht in der Liste).
 */
@Mixin(AbstractSelectionList.class)
public abstract class MenuListMixin {
	//? if >=26.1 {
	/*@Inject(method = "extractListBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$background(GuiGraphicsExtractor g, CallbackInfo ci) {
		if (VanillaMenus.listBackground((AbstractSelectionList<?>) (Object) this, Gfx.of(g))) ci.cancel();
	}

	@Inject(method = "extractListSeparators(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$separators(GuiGraphicsExtractor g, CallbackInfo ci) {
		if (VanillaMenus.listSeparators((AbstractSelectionList<?>) (Object) this, Gfx.of(g))) ci.cancel();
	}
	*///?} elif >=1.20.5 {
	@Inject(method = "renderListBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$background(GuiGraphics g, CallbackInfo ci) {
		if (VanillaMenus.listBackground((AbstractSelectionList<?>) (Object) this, Gfx.of(g))) ci.cancel();
	}

	@Inject(method = "renderListSeparators(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$separators(GuiGraphics g, CallbackInfo ci) {
		if (VanillaMenus.listSeparators((AbstractSelectionList<?>) (Object) this, Gfx.of(g))) ci.cancel();
	}
	//?}
}

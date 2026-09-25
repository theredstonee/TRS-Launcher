package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
/*import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Ab 26.1 zeichnet das Pausenmenü seinen Hintergrund selbst (ohne Screen#extractBackground) – hier ebenso
 * durch den Redstone-Hintergrund ersetzen. Davor reicht {@link MenuScreenMixin}; dann nicht in der Liste.
 */
@Mixin(PauseScreen.class)
public abstract class PauseBackgroundMixin {
	//? if >=26.1 {
	/*@Inject(method = "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$background(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (VanillaMenus.drawBackground((Screen) (Object) this, Gfx.of(g))) ci.cancel();
	}
	*///?}
}

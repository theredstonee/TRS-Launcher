package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.2 {
/*import net.minecraft.client.gui.Hud;
*///?} else
import net.minecraft.client.gui.Gui;

/**
 * Eigenes Fadenkreuz: blendet das Vanilla-Fadenkreuz aus (das eigene zeichnet der HUD-Manager).
 * Forge ruft auch in seinen eigenen HUD-Ebenen (VanillaGuiOverlay/ForgeLayeredDraw) diese Methode auf.
 * Ziel: Gui#renderCrosshair bis 1.21.11, Gui#extractCrosshair in 26.1.x, Hud#extractCrosshair ab 26.2.
 */
//? if >=26.2 {
/*@Mixin(Hud.class)
*///?} else
@Mixin(Gui.class)
public abstract class CrosshairMixin {
	//? if >=26.1 {
	/*@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true, require = 1)
	*///?} else
	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$hideVanillaCrosshair(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null && client.hud().crosshair().replacesVanilla()) ci.cancel();
	}
}

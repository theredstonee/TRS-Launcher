package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.6 {
import dev.theredstonee.trsclient.TrsClient;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/**
 * Eigenes Fadenkreuz: blendet bis 1.21.5 das Vanilla-Fadenkreuz aus (das eigene zeichnet der HUD-Manager).
 * Ab 1.21.6 ersetzt TRS die Fabric-HUD-Ebene "crosshair" direkt – dann ist dieser Mixin leer.
 */
@Mixin(Gui.class)
public abstract class CrosshairMixin {
	//? if <1.21.6 {
	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$hideVanillaCrosshair(CallbackInfo ci) {
		if (TrsClient.get().hud().crosshair().replacesVanilla()) ci.cancel();
	}
	//?}
}

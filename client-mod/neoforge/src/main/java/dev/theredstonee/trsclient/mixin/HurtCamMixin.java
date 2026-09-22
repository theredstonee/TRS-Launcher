package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Kein Schadens-Wackeln": überspringt das Kippen der Kamera beim Schaden (GameRenderer#bobHurt).
 * Rein optisch – Schaden, Blickrichtung und Treffer bleiben unverändert.
 * (NeoForges ViewportEvent.ComputeCameraAngles hilft hier nicht: das Kippen steckt nicht in den
 * Kamerawinkeln, sondern in der Matrix, die bobHurt aufsetzt.)
 */
@Mixin(GameRenderer.class)
public abstract class HurtCamMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$noHurtCam(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null && client.modules().noHurtCam.isEnabled()) ci.cancel();
	}
}

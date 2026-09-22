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
 * (Die Parameter von bobHurt ändern sich ab 26.1; der Methodenname reicht, sie ist nicht überladen.)
 */
@Mixin(GameRenderer.class)
public abstract class HurtCamMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$noHurtCam(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null && client.modules().noHurtCam.isEnabled()) ci.cancel();
	}
}

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
 * Die Methode heißt in 1.14.4–1.19.4 gleich (ab 1.15 mit PoseStack, für den Namen egal).
 */
@Mixin(GameRenderer.class)
public abstract class HurtCamMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$noHurtCam(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null && client.modules().noHurtCam.isEnabled()) ci.cancel();
	}
}

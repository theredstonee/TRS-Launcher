package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom: teilt das Sichtfeld der Welt durch den aktuellen Zoom-Faktor – am Ende von
 * GameRenderer#getFov (nach Forges FOV-Event), nur mit useFovSetting, damit die Hand nicht mitzoomt.
 */
@Mixin(GameRenderer.class)
public abstract class FovMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 1)
	private void trsclient$applyZoom(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
		HookStats.fov++;
		if (!useFovSetting) return;
		double factor = TrsClient.get().updateZoom();
		if (factor != 1.0) cir.setReturnValue(cir.getReturnValue() / factor);
	}
}

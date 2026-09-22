package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.Camera;
//? if <26.1
import net.minecraft.client.renderer.GameRenderer;

/**
 * Zoom: teilt das Sichtfeld der Welt durch den aktuellen Zoom-Faktor.
 * Bis 1.21.11: GameRenderer#getFov (double bis 1.21.1, float ab 1.21.2; nur mit useFovSetting,
 * damit die Hand nicht mitzoomt). Ab 26.1: Camera#calculateFov (reines Welt-Sichtfeld).
 * Ohne MixinExtras: @Inject am RETURN + setReturnValue (läuft nach Forges ComputeFov-Event).
 */
//? if >=26.1 {
/*@Mixin(Camera.class)
*///?} else
@Mixin(GameRenderer.class)
public abstract class FovMixin {
	//? if >=26.1 {
	/*@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true, require = 1)
	private void trsclient$applyZoom(CallbackInfoReturnable<Float> cir) {
		HookStats.fov++;
		double factor = TrsClient.get().updateZoom();
		float result = factor == 1.0 ? cir.getReturnValueF() : (float) (cir.getReturnValueF() / factor);
		if (factor != 1.0) cir.setReturnValue(result);
		TrsClient.get().setWorldFov(result);
	}
	*///?} elif >=1.21.2 {
	/*@Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 1)
	private void trsclient$applyZoom(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
		HookStats.fov++;
		if (!useFovSetting) return;
		double factor = TrsClient.get().updateZoom();
		float result = factor == 1.0 ? cir.getReturnValueF() : (float) (cir.getReturnValueF() / factor);
		if (factor != 1.0) cir.setReturnValue(result);
		TrsClient.get().setWorldFov(result);
	}
	*///?} else {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 1)
	private void trsclient$applyZoom(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
		HookStats.fov++;
		// Nur das Welt-Sichtfeld zoomen, nicht die Hand.
		if (!useFovSetting) return;
		double factor = TrsClient.get().updateZoom();
		double result = factor == 1.0 ? cir.getReturnValueD() : cir.getReturnValueD() / factor;
		if (factor != 1.0) cir.setReturnValue(result);
		TrsClient.get().setWorldFov(result);
	}
	//?}
}

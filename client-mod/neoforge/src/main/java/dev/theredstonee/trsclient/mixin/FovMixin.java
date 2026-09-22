package dev.theredstonee.trsclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import net.minecraft.client.Camera;
//? if <26.1
import net.minecraft.client.renderer.GameRenderer;

/**
 * Zoom: teilt das Sichtfeld der Welt durch den aktuellen Zoom-Faktor.
 * Bis 1.21.11: GameRenderer#getFov (double bis 1.21.1, float ab 1.21.2; nur mit useFovSetting,
 * damit die Hand nicht mitzoomt). Ab 26.1: Camera#calculateFov (reines Welt-Sichtfeld).
 */
//? if >=26.1 {
/*@Mixin(Camera.class)
*///?} else
@Mixin(GameRenderer.class)
public abstract class FovMixin {
	//? if >=26.1 {
	/*@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"), require = 1)
	private float trsclient$applyZoom(float fov) {
		HookStats.fov++;
		double factor = TrsClient.get().updateZoom();
		float result = factor == 1.0 ? fov : (float) (fov / factor);
		TrsClient.get().setWorldFov(result);
		return result;
	}
	*///?} elif >=1.21.2 {
	/*@ModifyReturnValue(method = "getFov", at = @At("RETURN"), require = 1)
	private float trsclient$applyZoom(float fov, Camera camera, float partialTick, boolean useFovSetting) {
		HookStats.fov++;
		if (!useFovSetting) return fov;
		double factor = TrsClient.get().updateZoom();
		float result = factor == 1.0 ? fov : (float) (fov / factor);
		TrsClient.get().setWorldFov(result);
		return result;
	}
	*///?} else {
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"), require = 1)
	private double trsclient$applyZoom(double fov, Camera camera, float partialTick, boolean useFovSetting) {
		HookStats.fov++;
		// Nur das Welt-Sichtfeld zoomen, nicht die Hand.
		if (!useFovSetting) return fov;
		double factor = TrsClient.get().updateZoom();
		double result = factor == 1.0 ? fov : fov / factor;
		TrsClient.get().setWorldFov(result);
		return result;
	}
	//?}
}

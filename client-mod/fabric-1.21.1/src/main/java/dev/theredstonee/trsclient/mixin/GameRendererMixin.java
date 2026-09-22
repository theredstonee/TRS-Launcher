package dev.theredstonee.trsclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zoom: teilt das Sichtfeld der Welt durch den aktuellen Zoom-Faktor. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"), require = 1)
	private double trsclient$applyZoom(double fov, Camera camera, float partialTick, boolean useFovSetting) {
		HookStats.fov++;
		// Nur das Welt-Sichtfeld zoomen, nicht die Hand.
		if (!useFovSetting) return fov;
		double factor = TrsClient.get().updateZoom();
		return factor == 1.0 ? fov : fov / factor;
	}
}

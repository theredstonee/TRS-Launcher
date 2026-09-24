package dev.theredstonee.trsclient.mixin;

// Nebel aus gibt es 1.17–1.21.5: davor feste GL-Aufrufe, ab 1.21.6 liegen die Nebelwerte in einem
// GPU-Puffer. Fabric trägt den Mixin nur dort ein; in Forge 1.15–1.16 (feste Mixin-Liste) ist er leer.
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.17 && <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//?}
//? if >=1.21.2 && <1.21.6 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}
//? if >=1.17 && <1.21.2 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/**
 * Kein Entfernungs-Nebel: nur der normale Gelände-Nebel in der Luft wird weit nach hinten geschoben.
 * Unter Wasser, in Lava, bei Blindheit/Dunkelheit (kurzer Nebel) und im Nebel-Himmel bleibt alles
 * wie in Vanilla. 1.17–1.21.1 über die Shader-Nebelwerte nach setupFog, 1.21.2–1.21.5 über dessen
 * Rückgabewert.
 */
//? if >=1.21.6 {
/*@Mixin(net.minecraft.client.renderer.fog.FogRenderer.class)
*///?} else
@Mixin(net.minecraft.client.renderer.FogRenderer.class)
public abstract class FogMixin {
	//? if >=1.21.2 && <1.21.6 {
	/*@Inject(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;Lorg/joml/Vector4f;FZF)Lnet/minecraft/client/renderer/FogParameters;",
			at = @At("RETURN"), cancellable = true, require = 0)
	private static void trsclient$noFog(Camera camera, FogRenderer.FogMode mode, org.joml.Vector4f color, float viewDistance,
			boolean thick, float partial, CallbackInfoReturnable<net.minecraft.client.renderer.FogParameters> cir) {
		net.minecraft.client.renderer.FogParameters fog = cir.getReturnValue();
		if (fog != null && trsclient$clear(camera, mode, thick, fog.end(), viewDistance)) {
			cir.setReturnValue(net.minecraft.client.renderer.FogParameters.NO_FOG);
		}
	}
	*///?}
	//? if >=1.19.3 && <1.21.2 {
	@Inject(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
			at = @At("TAIL"), require = 0)
	private static void trsclient$noFog(Camera camera, FogRenderer.FogMode mode, float viewDistance, boolean thick, float partial,
			CallbackInfo ci) {
		if (trsclient$clear(camera, mode, thick, RenderSystem.getShaderFogEnd(), viewDistance)) trsclient$push();
	}
	//?}
	//? if >=1.17 && <1.19.3 {
	/*@Inject(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZ)V",
			at = @At("TAIL"), require = 0)
	private static void trsclient$noFog(Camera camera, FogRenderer.FogMode mode, float viewDistance, boolean thick, CallbackInfo ci) {
		if (trsclient$clear(camera, mode, thick, RenderSystem.getShaderFogEnd(), viewDistance)) trsclient$push();
	}
	*///?}
	//? if >=1.17 && <1.21.6 {

	// Normaler Gelände-Nebel in der Luft (Ende ≈ Sichtweite)?
	private static boolean trsclient$clear(Camera camera, FogRenderer.FogMode mode, boolean thick, float end, float viewDistance) {
		if (mode != FogRenderer.FogMode.FOG_TERRAIN || thick || !PerfHooks.hide(PerfFeature.FOG)) return false;
		if (camera.getFluidInCamera() != FogType.NONE || end < viewDistance * 0.8f) return false;
		PerfHooks.fogCleared++;
		return true;
	}
	//?}
	//? if >=1.17 && <1.21.2 {

	private static void trsclient$push() {
		RenderSystem.setShaderFogStart(1_000_000f);
		RenderSystem.setShaderFogEnd(2_000_000f);
	}
	//?}
}

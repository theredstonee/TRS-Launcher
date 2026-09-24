package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Himmel aus: der Himmel (Farbverlauf, Sonne, Mond, Sterne, Wolkenhintergrund) wird nicht gezeichnet –
 * sichtbar bleibt die Nebelfarbe, mit der das Bild ohnehin gelöscht wird. Bis 1.21.1 {@code renderSky}
 * (sechs Signaturen), ab 1.21.2 der Sky-Pass des Frame-Graphen.
 */
@Mixin(LevelRenderer.class)
public abstract class SkyMixin {
	//? if >=26.3 {
	/*@Inject(method = "addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	*///?} elif >=26.1 {
	/*@Inject(method = "addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	*///?} elif >=1.21.9 {
	/*@Inject(method = "addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/Camera;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	*///?} elif >=1.21.6 {
	/*@Inject(method = "addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/Camera;FLcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	*///?} elif >=1.21.2 {
	/*@Inject(method = "addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/FogParameters;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	*///?} elif >=1.20.5 {
	@Inject(method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	//?} elif >=1.19.3 {
	/*@Inject(method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	*///?} elif >=1.18.2 {
	/*@Inject(method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/math/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	*///?} elif >=1.17 {
	/*@Inject(method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/math/Matrix4f;FLjava/lang/Runnable;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	*///?} elif >=1.15 {
	/*@Inject(method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At("HEAD"), cancellable = true, require = 0)
	*///?} else {
	/*@Inject(method = "renderSky(F)V", at = @At("HEAD"), cancellable = true, require = 0)
	*///?}
	private void trsclient$noSky(CallbackInfo ci) {
		if (PerfHooks.hide(PerfFeature.SKY)) {
			PerfHooks.skySkipped++;
			ci.cancel();
		}
	}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.21.9 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?} else
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block-Entity-Distanz: Truhen, Schilder, Banner, Köpfe … weiter weg als eingestellt werden nicht
 * gezeichnet. Bis 1.21.8 über {@code render}, ab 1.21.9 über {@code tryExtractRenderState} (null =
 * nichts zeichnen, das prüft der Aufrufer ohnehin), ab 26.2 mit einem zusätzlichen Schalter.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullMixin {
	//? if >=26.2 {
	/*@Inject(method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$cull(BlockEntity blockEntity, float partial,
			net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay overlay, boolean flag,
			CallbackInfoReturnable<net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> cir) {
		if (PerfHooks.cullBlockEntity(blockEntity)) cir.setReturnValue(null);
	}
	*///?} elif >=1.21.9 {
	/*@Inject(method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$cull(BlockEntity blockEntity, float partial,
			net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay overlay,
			CallbackInfoReturnable<net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> cir) {
		if (PerfHooks.cullBlockEntity(blockEntity)) cir.setReturnValue(null);
	}
	*///?} elif >=1.15 {
	@Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$cull(BlockEntity blockEntity, float partial, com.mojang.blaze3d.vertex.PoseStack pose,
			net.minecraft.client.renderer.MultiBufferSource buffers, CallbackInfo ci) {
		if (PerfHooks.cullBlockEntity(blockEntity)) ci.cancel();
	}
	//?} else {
	/*@Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FI)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$cull(BlockEntity blockEntity, float partial, int destroyStage, CallbackInfo ci) {
		if (PerfHooks.cullBlockEntity(blockEntity)) ci.cancel();
	}
	*///?}
}

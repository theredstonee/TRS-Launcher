package dev.theredstonee.trsclient.mixin;

// Begleiter am Boden: eigener Welt-Haken direkt nach den Entities (ab 1.15; 1.14 hat keinen PoseStack).
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.online.PetHooks;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Welt-Haken der Begleiter am Boden ({@link PetHooks#render}): nach dem Zeichnen der Entities, mit derselben Pose und
 * denselben Puffern wie sie – so liegen Pinguine im selben Licht, Nebel und in derselben Tiefe wie Wesen.
 * Bis 1.21.1: das erste {@code checkPoseStack} in {@code renderLevel} (die Pose dort ist ein Parameter bzw. ab 1.20.5
 * eine lokale Variable – {@code @ModifyArg} reicht sie in beiden Fällen durch); 1.21.2–1.21.8: Ende von
 * {@code renderEntities}; ab 1.21.9: Ende von {@code submitEntities} (Einreichen in den Sammler). Ziele mit vollem
 * Deskriptor, {@code require = 0}: fehlt eins, gibt es nur keine Begleiter.
 */
@Mixin(LevelRenderer.class)
public abstract class PetWorldMixin {
	//? if >=26.1 {
	/*@org.spongepowered.asm.mixin.injection.Inject(method = "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
			at = @At("TAIL"), require = 0)
	private void trsclient$pets(PoseStack pose, net.minecraft.client.renderer.state.level.LevelRenderState state,
			net.minecraft.client.renderer.SubmitNodeCollector collector, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		PetHooks.render(pose, collector);
	}
	*///?} elif >=1.21.9 {
	/*@org.spongepowered.asm.mixin.injection.Inject(method = "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
			at = @At("TAIL"), require = 0)
	private void trsclient$pets(PoseStack pose, net.minecraft.client.renderer.state.LevelRenderState state,
			net.minecraft.client.renderer.SubmitNodeCollector collector, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		PetHooks.render(pose, collector);
	}
	*///?} elif >=1.21.2 {
	/*@org.spongepowered.asm.mixin.injection.Inject(method = "renderEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/Camera;Lnet/minecraft/client/DeltaTracker;Ljava/util/List;)V",
			at = @At("TAIL"), require = 0)
	private void trsclient$pets(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers,
			net.minecraft.client.Camera camera, net.minecraft.client.DeltaTracker delta, java.util.List<?> entities,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		PetHooks.render(pose, buffers);
	}
	*///?} elif >=1.21 {
	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
					ordinal = 0), require = 0)
	private PoseStack trsclient$pets(PoseStack pose) {
		PetHooks.render(pose, net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource());
		return pose;
	}
	//?} elif >=1.20.5 {
	/*@org.spongepowered.asm.mixin.injection.ModifyArg(method = "renderLevel(FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
					ordinal = 0), require = 0)
	private PoseStack trsclient$pets(PoseStack pose) {
		PetHooks.render(pose, net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource());
		return pose;
	}
	*///?} elif >=1.19.3 {
	/*@org.spongepowered.asm.mixin.injection.ModifyArg(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
					ordinal = 0), require = 0)
	private PoseStack trsclient$pets(PoseStack pose) {
		PetHooks.render(pose, net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource());
		return pose;
	}
	*///?} else {
	/*@org.spongepowered.asm.mixin.injection.ModifyArg(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lcom/mojang/math/Matrix4f;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
					ordinal = 0), require = 0)
	private PoseStack trsclient$pets(PoseStack pose) {
		PetHooks.render(pose, net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource());
		return pose;
	}
	*///?}
}
//?}

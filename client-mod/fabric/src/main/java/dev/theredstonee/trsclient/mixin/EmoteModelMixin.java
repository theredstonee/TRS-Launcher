package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.online.EmoteHooks;
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Emote-Pose auf Spielermodelle (und ihre Rüstung) legen: am Ende von {@code HumanoidModel#setupAnim}. Bis 1.21.1
 * mit der Entity (und am Anfang die Ruhehaltung wiederherstellen), ab 1.21.2 mit dem Render-State. Die Logik steht
 * in {@link EmoteHooks}; ohne laufendes Emote passiert nichts.
 */
@Mixin(HumanoidModel.class)
public abstract class EmoteModelMixin {
	//? if >=1.21.2 {
	/*@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"),
			require = 0)
	private void trsclient$emote(net.minecraft.client.renderer.entity.state.HumanoidRenderState state, CallbackInfo ci) {
		EmoteHooks.afterSetup((HumanoidModel<?>) (Object) this, state);
	}
	*///?} elif >=1.15 {
	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"), require = 0)
	private void trsclient$emoteRest(net.minecraft.world.entity.LivingEntity entity, float limbSwing, float limbAmount,
			float age, float yaw, float pitch, CallbackInfo ci) {
		EmoteHooks.beforeSetup((HumanoidModel<?>) (Object) this);
	}

	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"), require = 0)
	private void trsclient$emote(net.minecraft.world.entity.LivingEntity entity, float limbSwing, float limbAmount,
			float age, float yaw, float pitch, CallbackInfo ci) {
		EmoteHooks.afterSetup((HumanoidModel<?>) (Object) this, entity, age);
	}
	//?} else {
	/*@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFFF)V", at = @At("HEAD"), require = 0)
	private void trsclient$emoteRest(net.minecraft.world.entity.LivingEntity entity, float limbSwing, float limbAmount,
			float age, float yaw, float pitch, float scale, CallbackInfo ci) {
		EmoteHooks.beforeSetup((HumanoidModel<?>) (Object) this);
	}

	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFFF)V", at = @At("TAIL"), require = 0)
	private void trsclient$emote(net.minecraft.world.entity.LivingEntity entity, float limbSwing, float limbAmount,
			float age, float yaw, float pitch, float scale, CallbackInfo ci) {
		EmoteHooks.afterSetup((HumanoidModel<?>) (Object) this, entity, age);
	}
	*///?}
}

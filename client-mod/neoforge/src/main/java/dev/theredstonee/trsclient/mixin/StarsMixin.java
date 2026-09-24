package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.21.2 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?} else
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sterne aus. Bis 1.21.1 zeichnet der Himmel Sterne nur bei Sternenhelligkeit &gt; 0 – die wird hier
 * 0 (ClientLevel, in 1.14 noch Level); ab 1.21.2 wird {@code SkyRenderer#renderStars} übersprungen.
 */
//? if >=1.21.2 {
/*@Mixin(net.minecraft.client.renderer.SkyRenderer.class)
*///?} elif >=1.15 {
@Mixin(net.minecraft.client.multiplayer.ClientLevel.class)
//?} else
/*@Mixin(net.minecraft.world.level.Level.class)*/
public abstract class StarsMixin {
	//? if >=26.3 {
	/*@Inject(method = "renderStars(Lcom/mojang/renderpearl/api/commands/RenderPass;FLcom/mojang/blaze3d/vertex/PoseStack;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$noStars(CallbackInfo ci) {
		if (PerfHooks.hide(PerfFeature.STARS)) {
			PerfHooks.starsHidden++;
			ci.cancel();
		}
	}
	*///?} elif >=1.21.6 {
	/*@Inject(method = "renderStars(FLcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$noStars(CallbackInfo ci) {
		if (PerfHooks.hide(PerfFeature.STARS)) {
			PerfHooks.starsHidden++;
			ci.cancel();
		}
	}
	*///?} elif >=1.21.2 {
	/*@Inject(method = "renderStars(Lnet/minecraft/client/renderer/FogParameters;FLcom/mojang/blaze3d/vertex/PoseStack;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$noStars(CallbackInfo ci) {
		if (PerfHooks.hide(PerfFeature.STARS)) {
			PerfHooks.starsHidden++;
			ci.cancel();
		}
	}
	*///?} else {
	@Inject(method = "getStarBrightness(F)F", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$noStars(CallbackInfoReturnable<Float> cir) {
		if (PerfHooks.hide(PerfFeature.STARS)) {
			PerfHooks.starsHidden++;
			cir.setReturnValue(0f);
		}
	}
	//?}
}

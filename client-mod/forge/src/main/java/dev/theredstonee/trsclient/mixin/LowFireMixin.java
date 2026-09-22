package dev.theredstonee.trsclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Niedriges Feuer: verschiebt die Flammen am Bildschirmrand nach unten, damit man beim Brennen
 * noch etwas sieht. Die Methode heißt bis 26.1 renderFire, ab 26.2 submitFire; ihr erster bzw.
 * zweiter Parameter ist immer der PoseStack, auf dem die Flammen liegen.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class LowFireMixin {
	//? if >=26.2 {
	/*@Inject(method = "submitFire", at = @At("HEAD"), require = 1)
	private static void trsclient$lowerFire(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		pose.pushPose();
		pose.translate(0, -trsclient$offset(), 0);
	}

	@Inject(method = "submitFire", at = @At("RETURN"), require = 1)
	private static void trsclient$restoreFire(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		pose.popPose();
	}
	*///?} elif >=1.21.9 {
	/*@Inject(method = "renderFire", at = @At("HEAD"), require = 1)
	private static void trsclient$lowerFire(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		pose.pushPose();
		pose.translate(0, -trsclient$offset(), 0);
	}

	@Inject(method = "renderFire", at = @At("RETURN"), require = 1)
	private static void trsclient$restoreFire(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, CallbackInfo ci) {
		pose.popPose();
	}
	*///?} elif >=1.21.4 {
	/*@Inject(method = "renderFire", at = @At("HEAD"), require = 1)
	private static void trsclient$lowerFire(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
			CallbackInfo ci) {
		pose.pushPose();
		pose.translate(0, -trsclient$offset(), 0);
	}

	@Inject(method = "renderFire", at = @At("RETURN"), require = 1)
	private static void trsclient$restoreFire(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
			CallbackInfo ci) {
		pose.popPose();
	}
	*///?} else {
	@Inject(method = "renderFire", at = @At("HEAD"), require = 1)
	private static void trsclient$lowerFire(net.minecraft.client.Minecraft minecraft, PoseStack pose, CallbackInfo ci) {
		pose.pushPose();
		pose.translate(0, -trsclient$offset(), 0);
	}

	@Inject(method = "renderFire", at = @At("RETURN"), require = 1)
	private static void trsclient$restoreFire(net.minecraft.client.Minecraft minecraft, PoseStack pose, CallbackInfo ci) {
		pose.popPose();
	}
	//?}

	/** Absenkung in Blöcken (0 = Modul aus). */
	private static float trsclient$offset() {
		TrsClient client = TrsClient.get();
		if (client == null || !client.modules().lowFire.isEnabled()) return 0;
		return (float) client.modules().lowFireHeight.get();
	}
}

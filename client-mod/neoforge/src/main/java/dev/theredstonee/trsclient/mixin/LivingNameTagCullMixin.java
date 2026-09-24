package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Namensschild-Distanz für Lebewesen und Spieler: LivingEntityRenderer überschreibt shouldShowName
 * (Teams, Unsichtbarkeit) ohne die Basisklasse zu fragen – deshalb ein eigener Einstieg.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingNameTagCullMixin {
	//? if >=1.21.2 {
	/*@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$nameDistance(LivingEntity entity, double distSq, CallbackInfoReturnable<Boolean> cir) {
		if (PerfHooks.hideNameTag(distSq)) cir.setReturnValue(false);
	}
	*///?} else {
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$nameDistance(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
		if (PerfHooks.hideNameTag(entity)) cir.setReturnValue(false);
	}
	//?}
}

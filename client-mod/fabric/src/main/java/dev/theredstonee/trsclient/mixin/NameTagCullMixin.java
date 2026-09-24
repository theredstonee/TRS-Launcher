package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Namensschild-Distanz (Wesen ohne eigenes shouldShowName, z. B. Rüstungsständer-lose Entities);
 * Lebewesen und Spieler laufen über {@link LivingNameTagCullMixin}. Ab 1.21.2 bekommt die Methode
 * den Abstand² zur Kamera gleich mit.
 */
@Mixin(EntityRenderer.class)
public abstract class NameTagCullMixin {
	//? if >=1.21.2 {
	/*@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$nameDistance(Entity entity, double distSq, CallbackInfoReturnable<Boolean> cir) {
		if (PerfHooks.hideNameTag(distSq)) cir.setReturnValue(false);
	}
	*///?} else {
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$nameDistance(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (PerfHooks.hideNameTag(entity)) cir.setReturnValue(false);
	}
	//?}
}

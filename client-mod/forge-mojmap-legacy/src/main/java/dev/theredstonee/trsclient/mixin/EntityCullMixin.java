package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity-Culling: {@code shouldRender} sagt „nein“ für Wesen hinter Wänden oder außerhalb der
 * eingestellten Entfernung – erst NACH Vanillas eigener Prüfung (Sichtkegel/Frustum, Entfernung), also nur
 * für Wesen, die sonst gezeichnet würden. Die drei doubles sind die Kameraposition. Signatur: 1.14 mit
 * Culler, 1.15–26.2 mit Frustum, ab 26.3 zusätzlich die Teil-Tick-Zeit.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityCullMixin {
	//? if >=26.3 {
	/*@Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$cull(Entity entity, net.minecraft.client.renderer.culling.Frustum frustum, double camX, double camY,
			double camZ, float partial, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && PerfHooks.cullEntity(entity, camX, camY, camZ)) cir.setReturnValue(false);
	}
	*///?} elif >=1.15 {
	@Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$cull(Entity entity, net.minecraft.client.renderer.culling.Frustum frustum, double camX, double camY,
			double camZ, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && PerfHooks.cullEntity(entity, camX, camY, camZ)) cir.setReturnValue(false);
	}
	//?} else {
	/*@Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Culler;DDD)Z",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$cull(Entity entity, net.minecraft.client.renderer.culling.Culler culler, double camX, double camY,
			double camZ, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && PerfHooks.cullEntity(entity, camX, camY, camZ)) cir.setReturnValue(false);
	}
	*///?}
}

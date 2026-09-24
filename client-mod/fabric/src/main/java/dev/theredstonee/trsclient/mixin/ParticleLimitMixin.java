package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Partikel: ausgeschaltete Arten (Explosion, Regen-Spritzer, Rauch) entstehen gar nicht erst
 * (createParticle liefert null – wie bei einem Typ ohne Anbieter), Obergrenze und Menge greifen in
 * add. Beide Methoden haben in 1.14.4–26.3 dieselbe Signatur.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleLimitMixin {
	@Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$filterKind(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz,
			CallbackInfoReturnable<Particle> cir) {
		if (!PerfHooks.allowKind(options)) cir.setReturnValue(null);
	}

	@Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$limit(Particle particle, CallbackInfo ci) {
		if (!PerfHooks.allowAdd()) ci.cancel();
	}
}

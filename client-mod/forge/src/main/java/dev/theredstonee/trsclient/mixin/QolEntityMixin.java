package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.qol.QolHooks;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entity-Ereignis 3 (Tod) eines Spielers – Totem-Pop-Liste aufräumen. Nur lesen. */
@Mixin(LivingEntity.class)
public abstract class QolEntityMixin {
	@Inject(method = "handleEntityEvent(B)V", at = @At("HEAD"), require = 0)
	private void trsclient$event(byte id, CallbackInfo ci) {
		if (id == 3) QolHooks.onEntityEvent((LivingEntity) (Object) this, id);
	}
}

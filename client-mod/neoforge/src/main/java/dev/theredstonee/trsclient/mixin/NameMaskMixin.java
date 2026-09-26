package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.qol.QolHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Streamer-Modus: angezeigter Name eines Spielers (Namensschild u. a.) – nur auf dem eigenen Bildschirm. */
@Mixin(Player.class)
public abstract class NameMaskMixin {
	@Inject(method = "getDisplayName()Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$mask(CallbackInfoReturnable<Component> cir) {
		Component name = cir.getReturnValue();
		Component masked = QolHooks.maskName(name);
		if (masked != name) cir.setReturnValue(masked);
	}
}

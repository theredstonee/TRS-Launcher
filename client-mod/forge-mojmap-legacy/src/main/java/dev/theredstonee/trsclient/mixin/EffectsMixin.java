package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla-Effektsymbole oben rechts aus, solange das Effekt-Modul sie selbst zeichnet (Forge ruft Gui#renderEffects). */
@Mixin(Gui.class)
public abstract class EffectsMixin {
	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$hideVanillaEffects(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null && client.modules().effects.isEnabled()) ci.cancel();
	}
}

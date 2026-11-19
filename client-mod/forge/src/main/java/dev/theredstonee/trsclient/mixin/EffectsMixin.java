package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla-Effektsymbole oben rechts aus, solange das Effekt-Modul sie selbst zeichnet. */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.gui.Hud.class)
*///?} else
@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class EffectsMixin {
	//? if >=26.1 {
	/*@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true, require = 1)
	*///?} else
	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$hideVanillaEffects(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null && client.modules().effects.isEnabled()) ci.cancel();
	}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.connect.FastSwitch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Schneller Serverwechsel: Während der Konfigurationsphase (ab 1.20.2, keine Welt, ein Bildschirm offen) bremst
 * Minecraft auf 60 Bilder pro Sekunde – ab 1.21.2 nach einer Minute ohne Eingabe sogar auf 30 bzw. 10. Pakete werden
 * aber nur einmal je Bild abgearbeitet, jede Rückfrage des Servers wartet also auf das nächste Bild. Solange ein Wechsel
 * läuft ({@link FastSwitch#active()}), gilt deshalb die normale Bildrate. Ein minimiertes Fenster bleibt gedrosselt.
 * Nur in der Mixin-Liste ab 1.20.2.
 */
//? if >=1.21.2 {
/*@Mixin(com.mojang.blaze3d.platform.FramerateLimitTracker.class)
*///?} else
@Mixin(net.minecraft.client.Minecraft.class)
public abstract class FrameCapMixin {
	//? if >=1.21.2 {
	/*@org.spongepowered.asm.mixin.Shadow
	@org.spongepowered.asm.mixin.Final
	private net.minecraft.client.Minecraft minecraft;
	@org.spongepowered.asm.mixin.Shadow
	private int framerateLimit;

	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$noThrottleWhileSwitching(CallbackInfoReturnable<Integer> cir) {
		if (!FastSwitch.active() || minecraft.getWindow().isIconified()) return;
		if (framerateLimit > cir.getReturnValue()) cir.setReturnValue(framerateLimit);
	}
	*///?} elif >=1.20.2 {
	/*@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$noThrottleWhileSwitching(CallbackInfoReturnable<Integer> cir) {
		if (!FastSwitch.active()) return;
		int window = ((net.minecraft.client.Minecraft) (Object) this).getWindow().getFramerateLimit();
		if (window > cir.getReturnValue()) cir.setReturnValue(window);
	}
	*///?}
}

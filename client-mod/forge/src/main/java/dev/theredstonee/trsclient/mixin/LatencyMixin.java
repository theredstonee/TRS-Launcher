package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.perf.LatencyHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Niedrige Eingabeverzögerung: direkt bevor Minecraft die gesammelte Mausbewegung auf die Kamera anwendet, liest der
 * Client noch einmal die Fenster-Ereignisse – so zählt auch Bewegung, die während der Ticks oder des Wartens auf die
 * Grafikkarte kam.
 */
@Mixin(Minecraft.class)
public abstract class LatencyMixin {
	//? if >=1.20.5 {
	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V"), require = 0)
	//?} else
	/*@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;turnPlayer()V"), require = 0)*/
	private void trsclient$beforeMouse(CallbackInfo ci) {
		LatencyHooks.beforeMouse();
	}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Langsamere Maus beim Zoomen. Forge 1.20.1 hat (anders als NeoForge) kein Event für die
 * Kameradrehung – daher wird die angesammelte Mausbewegung vor {@code turnPlayer} geteilt.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;

	@Inject(method = "turnPlayer", at = @At("HEAD"), require = 1)
	private void trsclient$slowMouseWhileZooming(CallbackInfo ci) {
		HookStats.turn++;
		double divisor = TrsClient.get().mouseDivisor();
		if (divisor > 1.0) {
			accumulatedDX /= divisor;
			accumulatedDY /= divisor;
		}
	}
}

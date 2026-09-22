package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CPS-Zählung, Zoom per Mausrad und langsamere Maus beim Zoomen. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;

	@Inject(method = "onPress", at = @At("HEAD"), require = 1)
	private void trsclient$countClick(long window, int button, int action, int mods, CallbackInfo ci) {
		HookStats.press++;
		if (action == GLFW.GLFW_PRESS && window == minecraft.getWindow().getWindow()) {
			TrsClient.get().onMouseClick(button);
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
		HookStats.scroll++;
		if (window == minecraft.getWindow().getWindow() && yOffset != 0 && TrsClient.get().onScroll(yOffset)) {
			ci.cancel();
		}
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), require = 1)
	private void trsclient$slowMouseWhileZooming(double movementTime, CallbackInfo ci) {
		HookStats.turn++;
		double divisor = TrsClient.get().mouseDivisor();
		if (divisor > 1.0) {
			accumulatedDX /= divisor;
			accumulatedDY /= divisor;
		}
	}
}

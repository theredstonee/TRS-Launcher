package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.9
//import net.minecraft.client.input.MouseButtonInfo;

/** CPS-Zählung, Zoom per Mausrad und langsamere Maus beim Zoomen. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;

	// Maustaste: onPress(window, button, action, mods) bis 1.21.8, onButton(window, info, action) ab 1.21.9.
	//? if >=1.21.9 {
	/*@Inject(method = "onButton", at = @At("HEAD"), require = 1)
	private void trsclient$countClick(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		HookStats.press++;
		if (action == InputConstants.PRESS && window == trsclient$window()) {
			TrsClient.get().onMouseClick(info.button());
		}
	}
	*///?} else {
	@Inject(method = "onPress", at = @At("HEAD"), require = 1)
	private void trsclient$countClick(long window, int button, int action, int mods, CallbackInfo ci) {
		HookStats.press++;
		if (action == InputConstants.PRESS && window == trsclient$window()) {
			TrsClient.get().onMouseClick(button);
		}
	}
	//?}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
		HookStats.scroll++;
		if (window == trsclient$window() && yOffset != 0 && TrsClient.get().onScroll(yOffset)) {
			ci.cancel();
		}
	}

	// turnPlayer() bis 1.20.4, turnPlayer(double movementTime) ab 1.20.5.
	@Inject(method = "turnPlayer", at = @At("HEAD"), require = 1)
	//? if >=1.20.5 {
	private void trsclient$slowMouseWhileZooming(double movementTime, CallbackInfo ci) {
	//?} else
	/*private void trsclient$slowMouseWhileZooming(CallbackInfo ci) {*/
		HookStats.turn++;
		double divisor = TrsClient.get().mouseDivisor();
		if (divisor > 1.0) {
			accumulatedDX /= divisor;
			accumulatedDY /= divisor;
		}
	}

	private long trsclient$window() {
		//? if >=1.21.9 {
		/*return minecraft.getWindow().handle();
		*///?} else
		return minecraft.getWindow().getWindow();
	}
}

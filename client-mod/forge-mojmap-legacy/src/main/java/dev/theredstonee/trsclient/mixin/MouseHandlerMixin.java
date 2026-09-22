package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.camera.FreelookState;
import dev.theredstonee.trsclient.dev.HookStats;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CPS-Zählung, Zoom per Mausrad, langsamere Maus beim Zoomen und Freelook-Drehung.
 * Signaturen sind von 1.15.2 bis 1.19.4 gleich (onPress/onScroll/turnPlayer()).
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;

	@Inject(method = "onPress", at = @At("HEAD"), require = 1)
	private void trsclient$countClick(long window, int button, int action, int mods, CallbackInfo ci) {
		HookStats.press++;
		if (action == GLFW.GLFW_PRESS && window == Mc.window().getWindow()) {
			TrsClient.get().onMouseClick(button);
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
		HookStats.scroll++;
		if (window == Mc.window().getWindow() && yOffset != 0 && TrsClient.get().onScroll(yOffset)) {
			ci.cancel();
		}
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), require = 1)
	private void trsclient$slowMouseWhileZooming(CallbackInfo ci) {
		HookStats.turn++;
		double divisor = TrsClient.get().mouseDivisor();
		if (divisor > 1.0) {
			accumulatedDX /= divisor;
			accumulatedDY /= divisor;
		}
	}

	/** Freelook: Mausbewegung dreht die Kamera statt der Spielfigur. */
	@Redirect(method = "turnPlayer",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), require = 1)
	private void trsclient$freelookTurn(LocalPlayer player, double yaw, double pitch) {
		FreelookState freelook = TrsClient.get().pvp().freelook();
		if (freelook.active()) freelook.turn(yaw, pitch);
		else player.turn(yaw, pitch);
	}
}

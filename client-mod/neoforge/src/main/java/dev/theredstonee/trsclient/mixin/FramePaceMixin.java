package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dynamische FPS und FPS-Messung: vor jedem Bild (Minecraft#runTick, in allen Versionen gleich)
 * meldet der Client den Fensterzustand und wartet ggf. bis zum nächsten erlaubten Bild.
 */
@Mixin(Minecraft.class)
public abstract class FramePaceMixin {
	@Inject(method = "runTick(Z)V", at = @At("HEAD"), require = 0)
	private void trsclient$paceFrame(CallbackInfo ci) {
		PerfHooks.beforeFrame();
	}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Textur-Animationen aus: TextureManager#tick schaltet die animierten Texturen (Wasser, Lava,
 * Feuer, Portal …) weiter – übersprungen bleiben sie auf ihrem Bild stehen. Gleich in allen Versionen.
 */
@Mixin(TextureManager.class)
public abstract class TextureAnimationMixin {
	@Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, require = 0)
	private void trsclient$noAnimations(CallbackInfo ci) {
		if (PerfHooks.hide(PerfFeature.TEXTURE_ANIMATIONS)) {
			PerfHooks.animationsSkipped++;
			ci.cancel();
		}
	}
}

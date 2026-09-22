package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Fullbright: ersetzt den gelesenen Gamma-Wert nur beim Berechnen der Lightmap
 * ({@code options.gamma().get().floatValue()}). Die Vanilla-Option bleibt unverändert.
 * Forge 1.20.1 hat dafür kein Event und liefert kein MixinExtras mit – daher ein schlichter @Redirect
 * auf genau diesen einen {@code floatValue()}-Aufruf.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
	private static final float FULLBRIGHT_GAMMA = 16.0F;

	@Redirect(
			method = "updateLightTexture",
			at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0),
			slice = @Slice(from = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")),
			require = 1)
	private float trsclient$fullbright(Double gamma) {
		HookStats.lightmap++;
		return TrsClient.get().fullbright() ? FULLBRIGHT_GAMMA : gamma.floatValue();
	}
}

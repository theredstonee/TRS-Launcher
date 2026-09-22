package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
//? if <1.19 {
import net.minecraft.client.Options;
//?} else
/*import org.spongepowered.asm.mixin.injection.Slice;*/

/**
 * Fullbright: ersetzt den gelesenen Gamma-Wert nur beim Berechnen der Lightmap.
 * Die Vanilla-Option bleibt unverändert und gilt sofort wieder, wenn Fullbright aus ist.
 * Bis 1.18.2 ist gamma ein double-Feld ({@code options.gamma}), ab 1.19 eine OptionInstance
 * ({@code options.gamma().get().floatValue()}). Forge liefert kein MixinExtras mit → @Redirect.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
	private static final float FULLBRIGHT_GAMMA = 16.0F;

	//? if <1.19 {
	@Redirect(method = "updateLightTexture",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;gamma:D", opcode = 180),
			require = 1)
	private double trsclient$fullbright(Options options) {
		HookStats.lightmap++;
		return TrsClient.get().fullbright() ? FULLBRIGHT_GAMMA : options.gamma;
	}
	//?} else {
	/*@Redirect(method = "updateLightTexture",
			at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0),
			slice = @Slice(from = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")),
			require = 1)
	private float trsclient$fullbright(Double gamma) {
		HookStats.lightmap++;
		return TrsClient.get().fullbright() ? FULLBRIGHT_GAMMA : gamma.floatValue();
	}
	*///?}
}

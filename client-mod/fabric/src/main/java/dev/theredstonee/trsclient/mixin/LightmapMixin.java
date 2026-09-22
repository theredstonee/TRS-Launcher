package dev.theredstonee.trsclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.dev.HookStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;
//? if <1.19
/*import org.objectweb.asm.Opcodes;*/
//? if >=26.1 {
/*import net.minecraft.client.renderer.LightmapRenderStateExtractor;
*///?} else
import net.minecraft.client.renderer.LightTexture;

/**
 * Fullbright: ersetzt den gelesenen Gamma-Wert nur beim Berechnen der Lightmap.
 * Die Vanilla-Option bleibt unverändert und gilt sofort wieder, wenn Fullbright aus ist.
 * Ziel: LightTexture#updateLightTexture bis 1.21.11, LightmapRenderStateExtractor#extract ab 26.1
 * (in beiden: Options.gamma().get() → Double.floatValue()).
 */
//? if >=26.1 {
/*@Mixin(LightmapRenderStateExtractor.class)
*///?} else
@Mixin(LightTexture.class)
public abstract class LightmapMixin {
	private static final float FULLBRIGHT_GAMMA = 16.0F;

	//? if >=1.19 {
	@ModifyExpressionValue(
			//? if >=26.1 {
			/*method = "extract",
			*///?} else
			method = "updateLightTexture",
			at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0),
			slice = @Slice(from = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")),
			require = 1)
	private float trsclient$fullbright(float gamma) {
		HookStats.lightmap++;
		return TrsClient.get().fullbright() ? FULLBRIGHT_GAMMA : gamma;
	}
	//?} else {
	/*// Bis 1.18.2 ist Gamma ein einfaches double-Feld der Optionen.
	@ModifyExpressionValue(method = "updateLightTexture",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;gamma:D", opcode = Opcodes.GETFIELD),
			require = 1)
	private double trsclient$fullbright(double gamma) {
		HookStats.lightmap++;
		return TrsClient.get().fullbright() ? FULLBRIGHT_GAMMA : gamma;
	}
	*///?}
}

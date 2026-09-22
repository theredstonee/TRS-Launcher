package dev.theredstonee.trsclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.theredstonee.trsclient.TrsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=26.3 {
/*import net.minecraft.client.player.FirstPersonHandsAndItems;
*///?} else
import net.minecraft.client.renderer.ItemInHandRenderer;

/**
 * 1.7-Animationen (Teil 1): Die Hand senkt sich nicht mehr, während die Angriffs-Abklingzeit läuft –
 * dafür tut der Hand-Renderer so, als wäre der Schlag immer voll aufgeladen. Rein optisch;
 * Schaden und Abklingzeit selbst bleiben unverändert.
 *
 * <p>Die Klasse heißt bis 26.2 ItemInHandRenderer, ab 26.3 FirstPersonHandsAndItems; die gelesene
 * Stärke heißt bis 1.21.10 getAttackStrengthScale und danach getItemSwapScale.
 */
//? if >=26.3 {
/*@Mixin(FirstPersonHandsAndItems.class)
*///?} else
@Mixin(ItemInHandRenderer.class)
public abstract class OldAnimationsMixin {
	@ModifyExpressionValue(method = "tick",
			at = @At(value = "INVOKE",
					//? if >=1.21.11 {
					/*target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"),
					*///?} else
					target = "Lnet/minecraft/client/player/LocalPlayer;getAttackStrengthScale(F)F"),
			require = 1)
	private float trsclient$noCooldownDip(float scale) {
		TrsClient client = TrsClient.get();
		if (client == null) return scale;
		return client.modules().oldAnimations.isEnabled() && client.modules().oldAnimationsNoDip.get() ? 1.0F : scale;
	}
}

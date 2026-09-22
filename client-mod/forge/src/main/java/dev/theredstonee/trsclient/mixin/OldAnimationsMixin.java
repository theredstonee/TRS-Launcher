package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
//? if >=26.3 {
/*import net.minecraft.client.player.FirstPersonHandsAndItems;
*///?} else
import net.minecraft.client.renderer.ItemInHandRenderer;

/**
 * 1.7-Animationen (Teil 1): Die Hand senkt sich nicht mehr, während die Angriffs-Abklingzeit läuft –
 * dafür liest der Hand-Renderer die Stärke als "voll aufgeladen". Rein optisch; Schaden und
 * Abklingzeit selbst bleiben unverändert.
 *
 * <p>Die Klasse heißt bis 26.2 ItemInHandRenderer, ab 26.3 FirstPersonHandsAndItems; die gelesene
 * Stärke heißt bis 1.21.10 getAttackStrengthScale und danach getItemSwapScale. Ohne MixinExtras
 * (gibt es in Forge erst ab 1.21.10) wird der Aufruf per @Redirect ersetzt.
 */
//? if >=26.3 {
/*@Mixin(FirstPersonHandsAndItems.class)
*///?} else
@Mixin(ItemInHandRenderer.class)
public abstract class OldAnimationsMixin {
	@Redirect(method = "tick",
			//? if >=1.21.11 {
			/*at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"),
			*///?} else
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getAttackStrengthScale(F)F"),
			require = 1)
	private float trsclient$noCooldownDip(LocalPlayer player, float partialTick) {
		//? if >=1.21.11 {
		/*float scale = player.getItemSwapScale(partialTick);
		*///?} else
		float scale = player.getAttackStrengthScale(partialTick);
		TrsClient client = TrsClient.get();
		if (client == null) return scale;
		return client.modules().oldAnimations.isEnabled() && client.modules().oldAnimationsNoDip.get() ? 1.0F : scale;
	}
}

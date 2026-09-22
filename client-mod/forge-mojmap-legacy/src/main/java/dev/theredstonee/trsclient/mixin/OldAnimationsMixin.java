package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.7-Animationen (Teil 1): Die Hand senkt sich nicht mehr, während die Angriffs-Abklingzeit läuft –
 * dafür liest der Hand-Renderer die Stärke immer als "voll aufgeladen". Rein optisch;
 * Schaden und Abklingzeit selbst bleiben unverändert.
 *
 * <p>{@code ItemInHandRenderer#tick} liest {@code LocalPlayer#getAttackStrengthScale} in allen
 * Versionen von 1.14.4 bis 1.19.4 an genau einer Stelle. Forge liefert kein MixinExtras mit,
 * deshalb {@code @Redirect} statt {@code @ModifyExpressionValue}.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class OldAnimationsMixin {
	@Redirect(method = "tick",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/player/LocalPlayer;getAttackStrengthScale(F)F"), require = 1)
	private float trsclient$noCooldownDip(LocalPlayer player, float partialTick) {
		TrsClient client = TrsClient.get();
		float scale = player.getAttackStrengthScale(partialTick);
		if (client == null) return scale;
		return client.modules().oldAnimations.isEnabled() && client.modules().oldAnimationsNoDip.get() ? 1.0F : scale;
	}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.core.connect.ServerPacks;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Schneller Serverwechsel: Lädt ein Server-Ressourcenpaket neu, hält Vanilla die Ladeanzeige mindestens 1 s offen –
 * auch wenn das Laden schon fertig ist – und meldet dem Server erst danach „geladen“ (der Server wartet so lange).
 * Im Mehrspieler darf die Anzeige ausblenden, sobald das Laden wirklich fertig ist. Das Laden selbst bleibt gleich.
 * Nur in der Mixin-Liste ab 1.21.9 (davor steckt die Bedingung mitten in {@code render}).
 */
@Mixin(LoadingOverlay.class)
public abstract class PackOverlayMixin {
	//? if >=1.21.9 {
	/*@Shadow
	@Final
	private boolean fadeIn;

	@Inject(method = "isReadyToFadeOut", at = @At("RETURN"), cancellable = true, require = 0)
	private void trsclient$noMinimum(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() || !fadeIn || !ServerPacks.fastSwitchOn()) return;
		if (net.minecraft.client.Minecraft.getInstance().getCurrentServer() != null) cir.setReturnValue(true);
	}
	*///?}
}

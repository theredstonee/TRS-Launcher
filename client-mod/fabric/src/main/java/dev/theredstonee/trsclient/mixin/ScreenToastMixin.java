package dev.theredstonee.trsclient.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.19.4 {
/*import dev.theredstonee.trsclient.social.SocialHooks;
import dev.theredstonee.trsclient.ui.Gfx;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
//? if >=1.16 && <1.19.4
/*import com.mojang.blaze3d.vertex.PoseStack;*/

/**
 * Bis 1.19.3 (ohne allgemeinen Haken nach {@code Screen#renderWithTooltip}): direkt nachdem der GameRenderer den
 * offenen Bildschirm gezeichnet hat, die Sozial-Toasts darüber zeichnen – auch über Vanilla-Menüs. Ab 1.19.4 erledigt
 * das {@link MenuScreenMixin}; dann ist diese Klasse leer und steht nicht in der Mixin-Liste. {@code require = 0}: fehlt
 * der Aufruf (andere Mods), zeichnet SocialHooks wie zuvor im HUD bzw. nach TRS-Bildschirmen (Haken gilt dann als tot).
 */
@Mixin(GameRenderer.class)
public abstract class ScreenToastMixin {
	//? if >=1.19.3 && <1.19.4 {
	/*@Inject(method = "render(FJZ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", shift = At.Shift.AFTER), require = 0)
	private void trsclient$afterScreen(float partialTick, long nanos, boolean renderLevel, CallbackInfo ci) {
		if (SocialHooks.afterScreenPending()) SocialHooks.afterScreen(Gfx.of(new PoseStack()));
	}
	*///?} elif >=1.16 && <1.19.3 {
	/*@Inject(method = "render(FJZ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V", shift = At.Shift.AFTER), require = 0)
	private void trsclient$afterScreen(float partialTick, long nanos, boolean renderLevel, CallbackInfo ci) {
		if (SocialHooks.afterScreenPending()) SocialHooks.afterScreen(Gfx.of(new PoseStack()));
	}
	*///?} elif <1.16 {
	/*@Inject(method = "render(FJZ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;render(IIF)V", shift = At.Shift.AFTER), require = 0)
	private void trsclient$afterScreen(float partialTick, long nanos, boolean renderLevel, CallbackInfo ci) {
		if (SocialHooks.afterScreenPending()) SocialHooks.afterScreen(Gfx.of());
	}
	*///?}
}

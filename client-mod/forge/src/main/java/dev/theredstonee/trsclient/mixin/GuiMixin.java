package dev.theredstonee.trsclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.20.5 {
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.ui.Gfx;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
//? if >=1.21
import net.minecraft.client.DeltaTracker;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.2 {
/*import net.minecraft.client.gui.Hud;
*///?} else
import net.minecraft.client.gui.Gui;

/**
 * HUD: zeichnet die TRS-HUD-Elemente am Ende des Vanilla-HUDs (ab 1.20.6; Forge hat dort je nach Version
 * gar kein, ein eigenes oder ein EventBus-7-HUD-Event – der Mixin ist überall gleich).
 * Ziel: Gui#render bis 1.21.11, Gui#extractRenderState in 26.1.x, Hud#extractRenderState ab 26.2.
 * Forge kehrt ab 1.21.4 (ForgeLayeredDraw) vorzeitig zurück → @At("RETURN") statt TAIL.
 * Bis 1.20.4 ersetzt Forges ForgeGui Gui#render komplett, dort hängt das HUD am RenderGuiEvent (TrsClientMod).
 */
//? if >=26.2 {
/*@Mixin(Hud.class)
*///?} else
@Mixin(Gui.class)
public abstract class GuiMixin {
	//? if >=26.1 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At("RETURN"), require = 1)
	private void trsclient$renderHud(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null) client.hud().render(Gfx.of(g));
	}
	*///?} elif >=1.21 {
	@Inject(method = "render", at = @At("RETURN"), require = 1)
	private void trsclient$renderHud(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null) client.hud().render(Gfx.of(g));
	}
	//?} elif >=1.20.5 {
	/*@Inject(method = "render", at = @At("RETURN"), require = 1)
	private void trsclient$renderHud(GuiGraphics g, float partialTick, CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null) client.hud().render(Gfx.of(g));
	}
	*///?}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nur Minecraft 1.14: Die Fabric API hat dort noch keinen HUD-Callback – das TRS-HUD
 * wird deshalb am Ende von Gui#render gezeichnet. (Nur in 1.14 in trsclient.mixins.json eingetragen.)
 */
@Mixin(Gui.class)
public abstract class HudMixin {
	@Inject(method = "render", at = @At("TAIL"), require = 1)
	private void trsclient$renderHud(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		//? if <1.15
		/*if (client != null) client.renderHud(Gfx.of());*/
	}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Strg+Klick auf eine Chat-Zeile kopiert sie in die Zwischenablage.
 * {@code mouseClicked(double, double, int)} ist von 1.14.4 bis 1.19.4 unverändert.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$copyLine(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (button != 0) return;
		TrsClient client = TrsClient.get();
		if (client == null) return;
		// Screen.hasControlDown() berücksichtigt auf macOS die Cmd-Taste.
		if (client.chat().onChatClick(mouseX, mouseY, Screen.hasControlDown())) cir.setReturnValue(true);
	}
}

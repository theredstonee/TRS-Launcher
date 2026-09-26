package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
*///?}

/** Strg+Klick auf eine Chat-Zeile kopiert sie in die Zwischenablage. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	//? if >=1.21.9 {
	/*@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$copyLine(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		int b = event.button() == dev.theredstonee.trsclient.compat.Keys.MOUSE_LEFT ? 0
				: event.button() == dev.theredstonee.trsclient.compat.Keys.MOUSE_RIGHT ? 1 : -1;
		if (!dev.theredstonee.trsclient.qol.QolHooks.copyAllowed(b, trsclient$control())) return;
		if (TrsClient.get().chat().onChatClick(event.x(), event.y(), true)) cir.setReturnValue(true);
	}
	*///?} else {
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$copyLine(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (!dev.theredstonee.trsclient.qol.QolHooks.copyAllowed(button, trsclient$control())) return;
		if (TrsClient.get().chat().onChatClick(mouseX, mouseY, true)) cir.setReturnValue(true);
	}
	//?}

	/** Strg (bzw. Cmd auf macOS) gedrückt? */
	private static boolean trsclient$control() {
		//? if >=1.21.9 {
		/*return net.minecraft.client.Minecraft.getInstance().hasControlDown();
		*///?} else
		return net.minecraft.client.gui.screens.Screen.hasControlDown();
	}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.TrsClient;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat-Verbesserungen: Zeitstempel und Zusammenfassen gleicher Nachrichten. Angesetzt wird an
 * der Stelle, an der alle eingehenden Nachrichten zusammenlaufen – bis 1.19
 * {@code addMessage(Component, int)}, ab 1.19.1 {@code addMessage(Component, MessageSignature,
 * GuiMessageTag)}. Weil es mehrere Überladungen gibt, steht hier die volle Signatur.
 */
@Mixin(ChatComponent.class)
public abstract class ChatMixin {
	//? if >=1.19.1 {
	/*@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;"
			+ "Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	*///?} else {
	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;I)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	//?}
	private Component trsclient$improveMessage(Component message) {
		TrsClient client = TrsClient.get();
		return client == null ? message : client.chat().onMessage(message);
	}

	/** Chat geleert (F3+D / Weltwechsel): Zusammenfassen neu beginnen. */
	@Inject(method = "clearMessages", at = @At("HEAD"), require = 1)
	private void trsclient$chatCleared(CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null) client.chat().onChatCleared();
	}
}

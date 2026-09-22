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
 * der Stelle, an der alle eingehenden Nachrichten zusammenlaufen – die heißt je nach Version anders:
 * addMessage(Component, int) bis 1.19, addMessage(Component, MessageSignature, GuiMessageTag) bis
 * 1.21.11 und ab 26.1 die private Fassung mit GuiMessageSource.
 */
@Mixin(ChatComponent.class)
public abstract class ChatMixin {
	//? if >=26.1 {
	/*@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;"
			+ "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	*///?} elif >=1.19.1 {
	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;"
			+ "Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), argsOnly = true, require = 1)
	//?} else {
	/*@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;I)V",
			at = @At("HEAD"), argsOnly = true, require = 1)
	*///?}
	private Component trsclient$improveMessage(Component message) {
		TrsClient client = TrsClient.get();
		return client == null ? message : client.chat().onMessage(message);
	}

	/** Chat geleert (F3+D / Weltwechsel): Zusammenfassen neu beginnen. */
	@Inject(method = "clearMessages", at = @At("HEAD"), require = 1)
	private void trsclient$chatCleared(boolean clearHistory, CallbackInfo ci) {
		TrsClient client = TrsClient.get();
		if (client != null) client.chat().onChatCleared();
	}
}

package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.qol.QolHooks;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat-Filter (Nachricht verwerfen) und längerer Verlauf (Vanillas feste 100 Zeilen ersetzen). Angesetzt an derselben
 * Stelle wie {@link ChatMixin}: addMessage(Component, int) bis 1.19, (Component, MessageSignature, GuiMessageTag) bis
 * 1.21.11 und ab 26.1 die private Fassung mit GuiMessageSource. Die 100 steht bis 1.20.4 in addMessage, danach in
 * addMessageToDisplayQueue/addMessageToQueue.
 */
@Mixin(ChatComponent.class)
public abstract class QolChatMixin {
	//? if >=26.1 {
	/*@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;"
			+ "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$filter(Component message, net.minecraft.network.chat.MessageSignature signature,
			net.minecraft.client.multiplayer.chat.GuiMessageSource source, net.minecraft.client.multiplayer.chat.GuiMessageTag tag,
			CallbackInfo ci) {
		if (QolHooks.hideChat(message)) ci.cancel();
	}
	*///?} elif >=1.19.1 {
	/*@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;"
			+ "Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$filter(Component message, net.minecraft.network.chat.MessageSignature signature,
			net.minecraft.client.GuiMessageTag tag, CallbackInfo ci) {
		if (QolHooks.hideChat(message)) ci.cancel();
	}
	*///?} else {
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;I)V", at = @At("HEAD"), cancellable = true, require = 1)
	private void trsclient$filter(Component message, int id, CallbackInfo ci) {
		if (QolHooks.hideChat(message)) ci.cancel();
	}
	//?}

	//? if >=1.20.5 {
	/*@ModifyConstant(method = {"addMessageToDisplayQueue", "addMessageToQueue"}, constant = @Constant(intValue = 100), require = 1)
	*///?} elif >=1.19.1 {
	/*@ModifyConstant(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;"
			+ "ILnet/minecraft/client/GuiMessageTag;Z)V", constant = @Constant(intValue = 100), require = 1)
	*///?} else
	@ModifyConstant(method = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V", constant = @Constant(intValue = 100), require = 1)
	private int trsclient$history(int vanilla) {
		return QolHooks.chatHistory(vanilla);
	}
}

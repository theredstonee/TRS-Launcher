package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.chatheads.ChatHeadHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 *
 * Based on Chat Heads by dzwdz (https://github.com/dzwdz/chat_heads), modified for the TRS Client.
 */

/**
 * Signierter Spieler-Chat (ab 1.19.1): die UUID steht am {@code PlayerChatMessage}, nicht im Text.
 * Unter 1.19.1 gibt es die Klasse nicht – dann bleibt nur die Text-Erkennung.
 */
//? if >=1.19.1 {
@Mixin(net.minecraft.client.multiplayer.chat.ChatListener.class)
public abstract class ChatSenderMixin {
	//? if >=26.1 {
	/*@Inject(method = "showMessageToPlayer", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addPlayerMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V"))
	*///?} else {
	@Inject(method = "showMessageToPlayer", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V"))
	//?}
	private void trsclient$sender(net.minecraft.network.chat.ChatType.Bound bound,
			net.minecraft.network.chat.PlayerChatMessage message, net.minecraft.network.chat.Component text,
			com.mojang.authlib.GameProfile profile, boolean filtered, java.time.Instant time,
			CallbackInfoReturnable<Boolean> cir) {
		ChatHeadHooks.arm(message, profile);
	}
}
//?} else {
/*public abstract class ChatSenderMixin {
}
*///?}

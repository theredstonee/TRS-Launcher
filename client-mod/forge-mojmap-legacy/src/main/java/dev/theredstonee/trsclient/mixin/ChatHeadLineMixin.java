package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.chatheads.ChatHeadHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 *
 * Based on Chat Heads by dzwdz (https://github.com/dzwdz/chat_heads), modified for the TRS Client.
 */

/**
 * Merkt sich zu jeder umbrochenen Zeile, ob sie die erste der Nachricht ist. Der Konstruktor läuft einmal,
 * das Zeichnen danach nicht mehr.
 */
//? if >=26.1 {
/*@Mixin(net.minecraft.client.multiplayer.chat.GuiMessage.Line.class)
*///?} elif >=1.19.1 {
/*@Mixin(net.minecraft.client.GuiMessage.Line.class)
*///?} else
@Mixin(net.minecraft.client.GuiMessage.class)
public abstract class ChatHeadLineMixin {
	//? if >=26.1 {
	/*@Inject(method = "<init>(Lnet/minecraft/client/multiplayer/chat/GuiMessage;Lnet/minecraft/util/FormattedCharSequence;Z)V",
			at = @At("RETURN"), require = 1)
	private void trsclient$line(net.minecraft.client.multiplayer.chat.GuiMessage parent,
			net.minecraft.util.FormattedCharSequence content, boolean endOfEntry, CallbackInfo ci) {
		ChatHeadHooks.onLine(content);
	}
	*///?} elif >=1.19.1 {
	/*@Inject(method = "<init>(ILnet/minecraft/util/FormattedCharSequence;Lnet/minecraft/client/GuiMessageTag;Z)V",
			at = @At("RETURN"), require = 1)
	private void trsclient$line(int addedTime, net.minecraft.util.FormattedCharSequence content,
			net.minecraft.client.GuiMessageTag tag, boolean endOfEntry, CallbackInfo ci) {
		ChatHeadHooks.onLine(content);
	}
	*///?} elif >=1.16 {
	@Inject(method = "<init>(ILjava/lang/Object;I)V", at = @At("RETURN"), require = 1)
	private void trsclient$line(int addedTime, Object message, int id, CallbackInfo ci) {
		ChatHeadHooks.onLine(message);
	}
	//?} else {
	/*@Inject(method = "<init>(ILnet/minecraft/network/chat/Component;I)V", at = @At("RETURN"), require = 0)
	private void trsclient$line(int addedTime, net.minecraft.network.chat.Component message, int id, CallbackInfo ci) {
		ChatHeadHooks.onLine(message);
	}
	*///?}
}

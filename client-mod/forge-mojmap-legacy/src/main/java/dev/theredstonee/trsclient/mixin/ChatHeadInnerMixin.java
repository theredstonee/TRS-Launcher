package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.chatheads.ChatHeadHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 *
 * Based on Chat Heads by dzwdz (https://github.com/dzwdz/chat_heads), modified for the TRS Client.
 */

/**
 * Ab 1.21.11 zeichnet der Chat nicht mehr über {@code drawString}, sondern über die innere Klasse von
 * {@code ChatComponent}. Der Text rückt dort über die Zeichenfläche, der Kopf kommt davor.
 * Unter 1.21.11 ist das hier nur eine leere Klasse und steht nicht in der Mixin-Liste.
 */
//? if >=26.1 {
/*@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$1")
public abstract class ChatHeadInnerMixin {
	@ModifyArg(method = "accept", index = 2, require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleMessage(IFLnet/minecraft/util/FormattedCharSequence;)Z"))
	private net.minecraft.util.FormattedCharSequence trsclient$offset(int y, float opacity, net.minecraft.util.FormattedCharSequence line) {
		ChatHeadHooks.offsetAccess(y, opacity, line);
		return line;
	}

	@Inject(method = "accept", require = 1,
			at = @At(value = "INVOKE", shift = At.Shift.AFTER,
					target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleMessage(IFLnet/minecraft/util/FormattedCharSequence;)Z"))
	private void trsclient$undo(CallbackInfo ci) {
		ChatHeadHooks.undoAccess();
	}

	@Inject(method = "accept", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleTagIcon(IIZLnet/minecraft/client/multiplayer/chat/GuiMessageTag;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag$Icon;)V"))
	private void trsclient$tag(CallbackInfo ci) {
		ChatHeadHooks.nudgeTag();
	}

	@Inject(method = "accept", require = 1,
			at = @At(value = "INVOKE", shift = At.Shift.AFTER,
					target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleTagIcon(IIZLnet/minecraft/client/multiplayer/chat/GuiMessageTag;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag$Icon;)V"))
	private void trsclient$tagUndo(CallbackInfo ci) {
		ChatHeadHooks.unnudgeTag();
	}
}
*///?} elif >=1.21.11 {
/*@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$1")
public abstract class ChatHeadInnerMixin {
	@ModifyArg(method = "accept", index = 2, require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleMessage(IFLnet/minecraft/util/FormattedCharSequence;)Z"))
	private net.minecraft.util.FormattedCharSequence trsclient$offset(int y, float opacity, net.minecraft.util.FormattedCharSequence line) {
		ChatHeadHooks.offsetAccess(y, opacity, line);
		return line;
	}

	@Inject(method = "accept", require = 1,
			at = @At(value = "INVOKE", shift = At.Shift.AFTER,
					target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleMessage(IFLnet/minecraft/util/FormattedCharSequence;)Z"))
	private void trsclient$undo(CallbackInfo ci) {
		ChatHeadHooks.undoAccess();
	}

	@Inject(method = "accept", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleTagIcon(IIZLnet/minecraft/client/GuiMessageTag;Lnet/minecraft/client/GuiMessageTag$Icon;)V"))
	private void trsclient$tag(CallbackInfo ci) {
		ChatHeadHooks.nudgeTag();
	}

	@Inject(method = "accept", require = 1,
			at = @At(value = "INVOKE", shift = At.Shift.AFTER,
					target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;handleTagIcon(IIZLnet/minecraft/client/GuiMessageTag;Lnet/minecraft/client/GuiMessageTag$Icon;)V"))
	private void trsclient$tagUndo(CallbackInfo ci) {
		ChatHeadHooks.unnudgeTag();
	}
}
*///?} else {
public abstract class ChatHeadInnerMixin {
}
//?}

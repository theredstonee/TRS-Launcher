package dev.theredstonee.trsclient.mixin;

import dev.theredstonee.trsclient.chatheads.ChatHeadHooks;
import dev.theredstonee.trsclient.core.chatheads.ChatHeadLayout;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 *
 * Based on Chat Heads by dzwdz (https://github.com/dzwdz/chat_heads), modified for the TRS Client.
 */

/**
 * Kopf vor der Chat-Zeile: Zeile schmaler umbrechen, Text um die Kopfbreite nach rechts, Kopf an die erste
 * Zeile. Dieselben Methoden wie die übrigen Chat-Mixins, damit sich die Haken nicht in die Quere kommen.
 */
@Mixin(ChatComponent.class)
public abstract class ChatHeadMixin {
	//? if >=26.1 {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
			at = @At("HEAD"), require = 1)
	private void trsclient$frame(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
			int ticks, int mouseX, int mouseY, net.minecraft.client.gui.components.ChatComponent.DisplayMode mode,
			boolean focused, CallbackInfo ci) {
		ChatHeadHooks.frame(graphics);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
			at = @At("HEAD"), require = 1)
	private void trsclient$access(net.minecraft.client.gui.components.ChatComponent.ChatGraphicsAccess graphics,
			int mouseX, int height, net.minecraft.client.gui.components.ChatComponent.DisplayMode mode, CallbackInfo ci) {
		ChatHeadHooks.access(graphics);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
			at = @At("RETURN"), require = 1)
	private void trsclient$frameEnd(CallbackInfo ci) {
		ChatHeadHooks.clearFrame();
	}
	*///?} elif >=1.21.11 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("HEAD"), require = 1)
	private void trsclient$frame(net.minecraft.client.gui.GuiGraphics graphics, net.minecraft.client.gui.Font font,
			int ticks, int mouseX, int mouseY, boolean focused, boolean something, CallbackInfo ci) {
		ChatHeadHooks.frame(graphics);
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IIZ)V", at = @At("HEAD"), require = 1)
	private void trsclient$access(net.minecraft.client.gui.components.ChatComponent.ChatGraphicsAccess graphics,
			int mouseX, int height, boolean focused, CallbackInfo ci) {
		ChatHeadHooks.access(graphics);
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("RETURN"), require = 1)
	private void trsclient$frameEnd(CallbackInfo ci) {
		ChatHeadHooks.clearFrame();
	}
	*///?} elif >=1.20.5 && <1.21.11 {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V", at = @At("HEAD"), require = 1)
	private void trsclient$frame(net.minecraft.client.gui.GuiGraphics graphics, int ticks, int mouseX, int mouseY,
			boolean focused, CallbackInfo ci) {
		ChatHeadHooks.frame(graphics);
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V", at = @At("RETURN"), require = 1)
	private void trsclient$frameEnd(net.minecraft.client.gui.GuiGraphics graphics, int ticks, int mouseX, int mouseY,
			boolean focused, CallbackInfo ci) {
		ChatHeadHooks.clearFrame();
	}
	//?} elif >=1.20 && <1.20.5 {
	/*@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;III)V", at = @At("HEAD"), require = 1)
	private void trsclient$frame(net.minecraft.client.gui.GuiGraphics graphics, int ticks, int mouseX, int mouseY,
			CallbackInfo ci) {
		ChatHeadHooks.frame(graphics);
	}

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;III)V", at = @At("RETURN"), require = 1)
	private void trsclient$frameEnd(net.minecraft.client.gui.GuiGraphics graphics, int ticks, int mouseX, int mouseY,
			CallbackInfo ci) {
		ChatHeadHooks.clearFrame();
	}
	*///?} elif >=1.16 {
	/*@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At("HEAD"), require = 1)
	private void trsclient$frame(com.mojang.blaze3d.vertex.PoseStack pose, int ticks, CallbackInfo ci) {
		ChatHeadHooks.frame(pose);
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", at = @At("RETURN"), require = 1)
	private void trsclient$frameEnd(com.mojang.blaze3d.vertex.PoseStack pose, int ticks, CallbackInfo ci) {
		ChatHeadHooks.clearFrame();
	}
	*///?} else {
	/*@Inject(method = "render(I)V", at = @At("HEAD"), require = 0)
	private void trsclient$frame(int ticks, CallbackInfo ci) {
		ChatHeadHooks.frame(null);
	}
	*///?}

	//? if >=1.21.6 && <1.21.11 {
	/*@ModifyArg(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V", index = 2, require = 1,
			at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"))
	private int trsclient$move(net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence line, int x, int y, int color) {
		return ChatHeadHooks.offset(x, line, y, color);
	}
	*///?} elif >=1.20.5 && <1.21.6 {
	@ModifyArg(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V", index = 2, require = 1,
			at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
	private int trsclient$move(net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence line, int x, int y, int color) {
		return ChatHeadHooks.offset(x, line, y, color);
	}
	//?} elif >=1.20 && <1.20.5 {
	/*@ModifyArg(method = "render(Lnet/minecraft/client/gui/GuiGraphics;III)V", index = 2, require = 1,
			at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
	private int trsclient$move(net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence line, int x, int y, int color) {
		return ChatHeadHooks.offset(x, line, y, color);
	}
	*///?} elif >=1.16 {
	/*@ModifyArg(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V", index = 2, require = 1,
			at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/util/FormattedCharSequence;FFI)I"))
	private float trsclient$move(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.util.FormattedCharSequence line, float x, float y, int color) {
		return ChatHeadHooks.offset(x, line, y, color);
	}
	*///?} else {
	/*@ModifyArg(method = "render(I)V", index = 1, require = 0,
			at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/Font;drawShadow(Ljava/lang/String;FFI)I"))
	private float trsclient$move(String line, float x, float y, int color) {
		return ChatHeadHooks.offset(x, line, y, color);
	}
	*///?}

	//? if >=26.1 {
	/*@Inject(method = "addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V", at = @At("HEAD"), require = 1)
	private void trsclient$begin(net.minecraft.client.multiplayer.chat.GuiMessage message, CallbackInfo ci) {
		ChatHeadHooks.begin(message.content());
	}

	@ModifyArg(method = "addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V", index = 1, require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"))
	*///?} elif >=1.21.11 {
	/*@Inject(method = "addMessageToDisplayQueue(Lnet/minecraft/client/GuiMessage;)V", at = @At("HEAD"), require = 1)
	private void trsclient$begin(net.minecraft.client.GuiMessage message, CallbackInfo ci) {
		ChatHeadHooks.begin(message.content());
	}

	@ModifyArg(method = "addMessageToDisplayQueue(Lnet/minecraft/client/GuiMessage;)V", index = 1, require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"))
	*///?} elif >=1.20.5 {
	@Inject(method = "addMessageToDisplayQueue(Lnet/minecraft/client/GuiMessage;)V", at = @At("HEAD"), require = 1)
	private void trsclient$begin(net.minecraft.client.GuiMessage message, CallbackInfo ci) {
		ChatHeadHooks.begin(message.content());
	}

	@ModifyArg(method = "addMessageToDisplayQueue(Lnet/minecraft/client/GuiMessage;)V", index = 1, require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"))
	//?} elif >=1.19.1 {
	/*@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
			at = @At("HEAD"), require = 1)
	private void trsclient$begin(Component message, net.minecraft.network.chat.MessageSignature signature, int ticks,
			net.minecraft.client.GuiMessageTag tag, boolean refresh, CallbackInfo ci) {
		ChatHeadHooks.begin(message);
	}

	@ModifyArg(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
			index = 1, require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"))
	*///?} elif >=1.16 {
	/*@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V", at = @At("HEAD"), require = 1)
	private void trsclient$begin(Component message, int id, int ticks, boolean refresh, CallbackInfo ci) {
		ChatHeadHooks.begin(message);
	}

	@ModifyArg(method = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V", index = 1, require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"))
	*///?} else {
	/*@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V", at = @At("HEAD"), require = 0)
	private void trsclient$begin(Component message, int id, int ticks, boolean refresh, CallbackInfo ci) {
		ChatHeadHooks.begin(message);
	}

	@ModifyArg(method = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V", index = 1, require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/Component;ILnet/minecraft/client/gui/Font;ZZ)Ljava/util/List;"))
	*///?}
	private int trsclient$wrap(int width) {
		return ChatHeadHooks.wrap(width);
	}

	//? if >=1.16 && <1.21.11 {
	@ModifyArg(method = "getClickedComponentStyleAt", index = 1, require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/StringSplitter;componentStyleAtWidth(Lnet/minecraft/util/FormattedCharSequence;I)Lnet/minecraft/network/chat/Style;"))
	private int trsclient$click(int x) {
		if (!ChatHeadHooks.shift() || x <= 0) return x;
		return x - ChatHeadLayout.WIDTH;
	}
	//?}

	//? if >=1.19.2 && <1.21.11 {
	@Inject(method = "getTagIconLeft", at = @At("RETURN"), require = 1)
	private void trsclient$tag(CallbackInfoReturnable<Integer> cir) {
		if (ChatHeadHooks.shift()) cir.setReturnValue(Integer.valueOf(cir.getReturnValue().intValue() + ChatHeadLayout.WIDTH));
	}
	//?}

	@Inject(method = "clearMessages", at = @At("HEAD"), require = 1)
	private void trsclient$cleared(CallbackInfo ci) {
		ChatHeadHooks.clear();
	}
}

package dev.theredstonee.trsclient.chatheads;

import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.ui.GfxImage;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 *
 * Based on Chat Heads by dzwdz (https://github.com/dzwdz/chat_heads), modified for the TRS Client.
 */

/**
 * Zeichnet Gesicht (UV 8,8) und Hut (UV 40,8) als zwei Blits, 8×8 auf die Zeilenhöhe. {@code raw} ist dieselbe
 * Zeichenfläche wie {@code GfxImage}: GuiGraphicsExtractor (26.x), GuiGraphics (1.20–1.21), PoseStack (1.16–1.19)
 * oder null (1.14/1.15, die OpenGL-Matrix steht schon).
 */
public final class ChatHeadDraw {
	private ChatHeadDraw() {
	}

	/** Kopf an (x, y) der aktuellen Transformation. {@code hat} lässt die zweite Schicht weg. */
	public static void draw(Object raw, int x, int y, TextureRef texture, int argb, boolean hat) {
		if (texture == null || argb == 0) return;
		translate(raw, x, y);
		// pending: in 1.20–1.21.1 liegen Flächen noch im Puffer und müssen vor der Textur raus.
		GfxImage.blit(raw, texture, 8f, 8f, 8, 8, argb, true);
		if (hat) GfxImage.blit(raw, texture, 40f, 8f, 8, 8, argb, false);
		translate(raw, -x, -y);
	}

	/** Verschieben, gleiche Stufen wie {@code Gfx#translate}. */
	public static void translate(Object raw, float x, float y) {
		//? if >=26.1 {
		/*((net.minecraft.client.gui.GuiGraphicsExtractor) raw).pose().translate(x, y);
		*///?} elif >=1.21.6 {
		/*((net.minecraft.client.gui.GuiGraphics) raw).pose().translate(x, y);
		*///?} elif >=1.20 {
		/*((net.minecraft.client.gui.GuiGraphics) raw).pose().translate(x, y, 0);
		*///?} elif >=1.16 {
		((com.mojang.blaze3d.vertex.PoseStack) raw).translate(x, y, 0);
		//?} elif >=1.15 {
		/*com.mojang.blaze3d.systems.RenderSystem.translatef(x, y, 0);
		*///?} else {
		/*com.mojang.blaze3d.platform.GlStateManager.translatef(x, y, 0);
		*///?}
	}
}

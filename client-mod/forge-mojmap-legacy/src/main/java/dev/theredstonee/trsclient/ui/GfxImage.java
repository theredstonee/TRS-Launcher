package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.ui.TextureRef;

/**
 * Texturen zeichnen und frei transformieren für {@code core.ui} ({@code Canvas#image/rotate/scale}) – alle
 * Versionsweichen an einer Stelle. {@code raw} = {@code Gfx#raw()}: GuiGraphicsExtractor (26.x), GuiGraphics
 * (1.20–1.21.11), PoseStack (1.16–1.19.4) oder null (1.14/1.15, OpenGL-Matrix).
 *
 * <p>Gezeichnet wird immer ein Textur-Ausschnitt ins Rechteck (0, 0)–(w, h) der aktuellen Transformation; die
 * Lage (auch schräg, für die Spielerfigur) kommt über Verschieben/Drehen/Skalieren. Reihenfolge: 1.20–1.21.1
 * sammelt Flächen bis zum Flush, zeichnet Texturen aber sofort – deshalb vorher flush(). Ab 1.21.2 laufen beide
 * über denselben Puffer bzw. ab 1.21.6 über den GUI-Render-State (überlappende Elemente bleiben in Reihenfolge).
 */
public final class GfxImage {
	private GfxImage() {
	}

	/**
	 * Textur-Ausschnitt (u, v, w×h Texel) zeichnen, eingefärbt mit ARGB. {@code pending}: seit dem letzten Bild
	 * wurden Flächen/Text gesammelt, die vorher gezeichnet werden müssen (nur 1.20–1.21.1 von Bedeutung).
	 */
	public static void blit(Object raw, TextureRef t, float u, float v, int w, int h, int argb, boolean pending) {
		//? if >=26.1 {
		/*((net.minecraft.client.gui.GuiGraphicsExtractor) raw).blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
				(net.minecraft.resources.Identifier) t.id, 0, 0, u, v, w, h, t.width, t.height, argb);
		*///?} elif >=1.21.11 {
		/*((net.minecraft.client.gui.GuiGraphics) raw).blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
				(net.minecraft.resources.Identifier) t.id, 0, 0, u, v, w, h, t.width, t.height, argb);
		*///?} elif >=1.21.6 {
		/*((net.minecraft.client.gui.GuiGraphics) raw).blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
				(net.minecraft.resources.ResourceLocation) t.id, 0, 0, u, v, w, h, t.width, t.height, argb);
		*///?} elif >=1.21.2 {
		/*((net.minecraft.client.gui.GuiGraphics) raw).blit(net.minecraft.client.renderer.RenderType::guiTextured,
				(net.minecraft.resources.ResourceLocation) t.id, 0, 0, u, v, w, h, t.width, t.height, argb);
		*///?} elif >=1.20 {
		/*net.minecraft.client.gui.GuiGraphics g = (net.minecraft.client.gui.GuiGraphics) raw;
		if (pending) g.flush();
		begin(argb);
		g.blit((net.minecraft.resources.ResourceLocation) t.id, 0, 0, u, v, w, h, t.width, t.height);
		end();
		*///?} elif >=1.17 {
		/*com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, (net.minecraft.resources.ResourceLocation) t.id);
		begin(argb);
		net.minecraft.client.gui.GuiComponent.blit((com.mojang.blaze3d.vertex.PoseStack) raw, 0, 0, u, v, w, h, t.width, t.height);
		end();
		*///?} elif >=1.16 {
		net.minecraft.client.Minecraft.getInstance().getTextureManager().bind((net.minecraft.resources.ResourceLocation) t.id);
		begin(argb);
		net.minecraft.client.gui.GuiComponent.blit((com.mojang.blaze3d.vertex.PoseStack) raw, 0, 0, u, v, w, h, t.width, t.height);
		end();
		//?} else {
		/*net.minecraft.client.Minecraft.getInstance().getTextureManager().bind((net.minecraft.resources.ResourceLocation) t.id);
		begin(argb);
		net.minecraft.client.gui.GuiComponent.blit(0, 0, u, v, w, h, t.width, t.height);
		end();
		*///?}
	}

	// Bis 1.21.1: Farbe/Deckkraft über die Shader- bzw. GL-Farbe, Mischen an.
	private static void begin(int argb) {
		float a = ((argb >>> 24) & 0xFF) / 255f;
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		//? if >=1.21.2 {
		/*// ab 1.21.2 trägt der Blit die Farbe selbst
		*///?} elif >=1.17 {
		/*com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, g, b, a);
		*///?} elif >=1.15 {
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		com.mojang.blaze3d.systems.RenderSystem.color4f(r, g, b, a);
		//?} else {
		/*com.mojang.blaze3d.platform.GlStateManager.enableBlend();
		com.mojang.blaze3d.platform.GlStateManager.blendFuncSeparate(770, 771, 1, 0);
		com.mojang.blaze3d.platform.GlStateManager.color4f(r, g, b, a);
		*///?}
	}

	private static void end() {
		//? if >=1.21.2 {
		/*// nichts zurückzusetzen
		*///?} elif >=1.17 {
		/*com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		*///?} elif >=1.15 {
		com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		//?} else {
		/*com.mojang.blaze3d.platform.GlStateManager.color4f(1f, 1f, 1f, 1f);
		com.mojang.blaze3d.platform.GlStateManager.disableBlend();
		*///?}
	}

	/** Dreht die Zeichenebene (Bogenmaß, um die Bildschirmachse). */
	public static void rotate(Object raw, float radians) {
		//? if >=26.1 {
		/*((net.minecraft.client.gui.GuiGraphicsExtractor) raw).pose().rotate(radians);
		*///?} elif >=1.21.6 {
		/*((net.minecraft.client.gui.GuiGraphics) raw).pose().rotate(radians);
		*///?} elif >=1.20 {
		/*((net.minecraft.client.gui.GuiGraphics) raw).pose().mulPose(com.mojang.math.Axis.ZP.rotation(radians));
		*///?} elif >=1.19.3 {
		/*((com.mojang.blaze3d.vertex.PoseStack) raw).mulPose(com.mojang.math.Axis.ZP.rotation(radians));
		*///?} elif >=1.16 {
		((com.mojang.blaze3d.vertex.PoseStack) raw).mulPose(com.mojang.math.Vector3f.ZP.rotation(radians));
		//?} elif >=1.15 {
		/*com.mojang.blaze3d.systems.RenderSystem.rotatef((float) Math.toDegrees(radians), 0f, 0f, 1f);
		*///?} else {
		/*com.mojang.blaze3d.platform.GlStateManager.rotatef((float) Math.toDegrees(radians), 0f, 0f, 1f);
		*///?}
	}

	/** Ungleichmäßig skalieren. */
	public static void scale(Object raw, float sx, float sy) {
		//? if >=26.1 {
		/*((net.minecraft.client.gui.GuiGraphicsExtractor) raw).pose().scale(sx, sy);
		*///?} elif >=1.21.6 {
		/*((net.minecraft.client.gui.GuiGraphics) raw).pose().scale(sx, sy);
		*///?} elif >=1.20 {
		/*((net.minecraft.client.gui.GuiGraphics) raw).pose().scale(sx, sy, 1f);
		*///?} elif >=1.16 {
		((com.mojang.blaze3d.vertex.PoseStack) raw).scale(sx, sy, 1f);
		//?} elif >=1.15 {
		/*com.mojang.blaze3d.systems.RenderSystem.scalef(sx, sy, 1f);
		*///?} else {
		/*com.mojang.blaze3d.platform.GlStateManager.scalef(sx, sy, 1f);
		*///?}
	}
}

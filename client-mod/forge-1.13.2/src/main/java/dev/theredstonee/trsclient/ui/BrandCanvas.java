package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import org.lwjgl.opengl.GL11;

/**
 * Zeichenfläche des TRS-Menüs für Minecraft 1.13.2: setzt die versionsunabhängige
 * {@link Canvas}-Schnittstelle auf {@link Brand} (Gui.drawRect, FontRenderer, GL11) um.
 */
public final class BrandCanvas implements Canvas {
	private static final BrandCanvas INSTANCE = new BrandCanvas();

	static {
		// Texturen für core.ui (Skins, Umhänge, Vorschaubilder) – ab dem ersten Zeichnen verfügbar.
		Textures.install(GfxImage.Store.INSTANCE);
	}

	private FontRenderer font;

	private BrandCanvas() {
	}

	/** Wiederverwendete Instanz für das aktuelle Frame. */
	public static BrandCanvas of(FontRenderer font) {
		INSTANCE.font = font != null ? font : Minecraft.getInstance().fontRenderer;
		return INSTANCE;
	}

	public FontRenderer font() {
		return font;
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		Brand.fill(x1, y1, x2, y2, argb);
	}

	@Override
	public void text(String text, int x, int y, int argb, boolean shadow) {
		Brand.text(font, text, x, y, argb, shadow);
	}

	@Override
	public int textWidth(String text) {
		return font.getStringWidth(text);
	}

	@Override
	public int lineHeight() {
		return 9;
	}

	@Override
	public String clip(String text, int maxWidth) {
		return font.trimStringToWidth(text, Math.max(0, maxWidth));
	}

	/** 1.7.10 zeichnet Text sofort – hier ist nichts zu tun. */
	@Override
	public void flush() {
	}

	@Override
	public void scissor(int x1, int y1, int x2, int y2) {
		Brand.scissor(x1, y1, x2, y2);
	}

	@Override
	public void noScissor() {
		Brand.noScissor();
	}

	@Override
	public void raise(float z) {
		GL11.glTranslatef(0f, 0f, z);
	}

	@Override
	public void push() {
		GL11.glPushMatrix();
	}

	@Override
	public void translate(float x, float y) {
		GL11.glTranslatef(x, y, 0f);
	}

	@Override
	public void scale(float factor) {
		GL11.glScalef(factor, factor, 1f);
	}

	@Override
	public void pop() {
		GL11.glPopMatrix();
	}

	@Override
	public boolean images() {
		return true;
	}

	@Override
	public void image(TextureRef texture, float u, float v, int w, int h, int argb) {
		GfxImage.blit(texture, u, v, w, h, argb);
	}

	@Override
	public void rotate(float radians) {
		GfxImage.rotate(radians);
	}

	@Override
	public void scale(float sx, float sy) {
		GfxImage.scale(sx, sy);
	}
}

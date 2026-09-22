package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.ui.Canvas;
import net.minecraft.client.gui.FontRenderer;

/**
 * Verbindet die versionsunabhängige Oberfläche ({@link Canvas}) mit dem Zeichnen unter
 * Minecraft 1.8.9–1.12.2 ({@link Gfx}).
 */
public final class GfxCanvas implements Canvas {
	private static final GfxCanvas INSTANCE = new GfxCanvas();

	private Gfx g;
	private FontRenderer font;

	private GfxCanvas() {
	}

	/** Wiederverwendete Instanz für das aktuelle Frame. */
	public static GfxCanvas of(Gfx g, FontRenderer font) {
		INSTANCE.g = g;
		INSTANCE.font = font;
		return INSTANCE;
	}

	/** Darunterliegende Zeichenfläche (für HUD-Elemente). */
	public Gfx gfx() {
		return g;
	}

	public FontRenderer font() {
		return font;
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		g.fill(x1, y1, x2, y2, argb);
	}

	@Override
	public void text(String text, int x, int y, int argb, boolean shadow) {
		g.text(font, text, x, y, argb, shadow);
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

	/** Bis 1.12.2 zeichnet Minecraft Text sofort – hier ist nichts zu tun. */
	@Override
	public void flush() {
	}

	@Override
	public void scissor(int x1, int y1, int x2, int y2) {
		g.scissor(x1, y1, x2, y2);
	}

	@Override
	public void noScissor() {
		g.noScissor();
	}

	@Override
	public void raise(float z) {
		g.raise(z);
	}

	@Override
	public void push() {
		g.push();
	}

	@Override
	public void translate(float x, float y) {
		g.translate(x, y);
	}

	@Override
	public void scale(float factor) {
		g.scale(factor);
	}

	@Override
	public void pop() {
		g.pop();
	}
}

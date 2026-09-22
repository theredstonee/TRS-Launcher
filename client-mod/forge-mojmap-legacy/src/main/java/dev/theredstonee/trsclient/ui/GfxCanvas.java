package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.ui.Canvas;
import net.minecraft.client.gui.Font;

/**
 * Verbindet die versionsunabhängige Oberfläche ({@link Canvas}) mit dem versionsabhängigen
 * Zeichnen ({@link Gfx}). Mehr braucht {@code core.ui} nicht von Minecraft.
 */
public final class GfxCanvas implements Canvas {
	private static final GfxCanvas INSTANCE = new GfxCanvas();

	private Gfx g;
	private Font font;

	private GfxCanvas() {
	}

	/** Wiederverwendete Instanz für das aktuelle Frame (Rendern ist single-threaded). */
	public static GfxCanvas of(Gfx g, Font font) {
		INSTANCE.g = g;
		INSTANCE.font = font;
		return INSTANCE;
	}

	/** Darunterliegende Zeichenfläche (für HUD-Elemente, die Minecraft-Dinge zeichnen). */
	public Gfx gfx() {
		return g;
	}

	public Font font() {
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
		return font.width(text);
	}

	@Override
	public int lineHeight() {
		return 9;
	}

	@Override
	public String clip(String text, int maxWidth) {
		return Gfx.trim(font, text, Math.max(0, maxWidth));
	}

	/** Bis 1.19.4 zeichnet Minecraft Text sofort – hier ist nichts zu tun. */
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

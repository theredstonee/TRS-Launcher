package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.compat.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

/**
 * Dünne Zeichen-Schnittstelle mit denselben Methoden wie {@code ui/Gfx} der Fabric-Fassung, damit
 * HUD und Menüs fast unverändert übernommen werden können. Unter 1.8.9–1.12.2 steckt dahinter
 * {@link Gui#drawRect}, der {@link FontRenderer} und {@link GlStateManager} (in allen Versionen gleich).
 */
public final class Gfx {
	private static final Gfx INSTANCE = new Gfx();

	private int width;
	private int height;

	private Gfx() {
	}

	/** Wiederverwendete Instanz für das aktuelle Bild; Größe in GUI-Pixeln. */
	public static Gfx of(int width, int height) {
		INSTANCE.width = width;
		INSTANCE.height = height;
		return INSTANCE;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** Rechteck von (x1, y1) bis ausschließlich (x2, y2), Farbe ARGB. */
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		if (x1 == x2 || y1 == y2) return;
		Gui.drawRect(x1, y1, x2, y2, argb);
	}

	/** Waagerechte Linie von x1 bis einschließlich x2. */
	public void hLine(int x1, int x2, int y, int argb) {
		if (x2 < x1) {
			int t = x1;
			x1 = x2;
			x2 = t;
		}
		fill(x1, y, x2 + 1, y + 1, argb);
	}

	/** Senkrechte Linie zwischen y1 und y2 (Endpunkte exklusiv). */
	public void vLine(int x, int y1, int y2, int argb) {
		if (y2 < y1) {
			int t = y1;
			y1 = y2;
			y2 = t;
		}
		fill(x, y1 + 1, x + 1, y2, argb);
	}

	/** Rahmen mit 1 px Stärke. */
	public void outline(int x, int y, int w, int h, int argb) {
		fill(x, y, x + w, y + 1, argb);
		fill(x, y + h - 1, x + w, y + h, argb);
		fill(x, y + 1, x + 1, y + h - 1, argb);
		fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
	}

	/** Text zeichnen; setzt vorher die GL-Farbe zurück (sonst färbt drawRect nach). */
	public void text(FontRenderer font, String text, int x, int y, int argb, boolean shadow) {
		GlStateManager.color(1f, 1f, 1f, 1f);
		GlStateManager.enableBlend();
		font.drawString(text, (float) x, (float) y, argb, shadow);
	}

	/** Zentrierter Text mit Schatten. */
	public void centered(FontRenderer font, String text, int centerX, int y, int argb) {
		text(font, text, centerX - font.getStringWidth(text) / 2, y, argb, true);
	}

	/** Gegenstand als 16×16-Symbol (inkl. Stapelzahl/Haltbarkeitsbalken). */
	public void item(FontRenderer font, ItemStack stack, int x, int y) {
		Minecraft mc = Mc.mc();
		GlStateManager.enableRescaleNormal();
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
		RenderHelper.enableGUIStandardItemLighting();
		mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
		mc.getRenderItem().renderItemOverlays(font, stack, x, y);
		RenderHelper.disableStandardItemLighting();
		GlStateManager.disableRescaleNormal();
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	/** Zeichnen auf ein Rechteck begrenzen (GUI-Koordinaten), mit {@link #noScissor()} beenden. */
	public void scissor(int x1, int y1, int x2, int y2) {
		Minecraft mc = Mc.mc();
		int scale = Mc.scaledResolution().getScaleFactor();
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glScissor(x1 * scale, mc.displayHeight - y2 * scale, Math.max(0, x2 - x1) * scale, Math.max(0, y2 - y1) * scale);
	}

	public void noScissor() {
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
	}

	// --- Transformation ---

	public void push() {
		GlStateManager.pushMatrix();
	}

	/** Hebt die Zeichenebene nach vorn (Tiefe der GUI). */
	public void raise(float z) {
		GlStateManager.translate(0, 0, z);
	}

	public void translate(float x, float y) {
		GlStateManager.translate(x, y, 0f);
	}

	public void scale(float s) {
		GlStateManager.scale(s, s, 1f);
	}

	public void pop() {
		GlStateManager.popMatrix();
		GlStateManager.color(1f, 1f, 1f, 1f);
	}
}

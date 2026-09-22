package dev.theredstonee.trsclient.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else
import net.minecraft.client.gui.GuiGraphics;

/**
 * Dünne, versionsunabhängige Zeichen-Schnittstelle über Minecrafts GUI-Grafikobjekt
 * (GuiGraphics bis 1.21.11, GuiGraphicsExtractor ab 26.1). Alle HUD- und Menü-Zeichnungen
 * laufen hierüber, damit Versionsunterschiede nur an dieser einen Stelle stehen.
 * Linien und Rahmen werden aus Rechtecken gebaut (überall identisch).
 */
public final class Gfx {
	private static final Gfx INSTANCE = new Gfx();

	//? if >=26.1 {
	/*private GuiGraphicsExtractor g;

	// Wiederverwendete Instanz für das aktuelle Frame (Rendern ist single-threaded).
	public static Gfx of(GuiGraphicsExtractor g) {
		INSTANCE.g = g;
		return INSTANCE;
	}
	*///?} else {
	private GuiGraphics g;

	// Wiederverwendete Instanz für das aktuelle Frame (Rendern ist single-threaded).
	public static Gfx of(GuiGraphics g) {
		INSTANCE.g = g;
		return INSTANCE;
	}
	//?}

	private Gfx() {
	}

	public int width() {
		return g.guiWidth();
	}

	public int height() {
		return g.guiHeight();
	}

	/** Rechteck von (x1, y1) bis ausschließlich (x2, y2), Farbe ARGB. */
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		g.fill(x1, y1, x2, y2, argb);
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

	/** Senkrechte Linie zwischen y1 und y2 (Endpunkte exklusiv, wie Vanilla). */
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

	public void text(Font font, String text, int x, int y, int argb, boolean shadow) {
		//? if >=26.1 {
		/*g.text(font, text, x, y, argb, shadow);
		*///?} else
		g.drawString(font, text, x, y, argb, shadow);
	}

	public void text(Font font, Component text, int x, int y, int argb, boolean shadow) {
		//? if >=26.1 {
		/*g.text(font, text, x, y, argb, shadow);
		*///?} else
		g.drawString(font, text, x, y, argb, shadow);
	}

	public void text(Font font, FormattedCharSequence text, int x, int y, int argb, boolean shadow) {
		//? if >=26.1 {
		/*g.text(font, text, x, y, argb, shadow);
		*///?} else
		g.drawString(font, text, x, y, argb, shadow);
	}

	/** Zentrierter Text mit Schatten. */
	public void centered(Font font, String text, int centerX, int y, int argb) {
		//? if >=26.1 {
		/*g.centeredText(font, text, centerX, y, argb);
		*///?} else
		g.drawCenteredString(font, text, centerX, y, argb);
	}

	/** Zentrierter Text mit Schatten. */
	public void centered(Font font, Component text, int centerX, int y, int argb) {
		//? if >=26.1 {
		/*g.centeredText(font, text, centerX, y, argb);
		*///?} else
		g.drawCenteredString(font, text, centerX, y, argb);
	}

	/** Schneidet {@code text} auf höchstens {@code maxWidth} Pixel ab. */
	public static String clip(Font font, String text, int maxWidth) {
		return font.plainSubstrByWidth(text, maxWidth);
	}

	/** Gegenstand als 16×16-Symbol (inkl. Stapelzahl/Haltbarkeitsbalken). */
	public void item(Font font, ItemStack stack, int x, int y) {
		//? if >=26.1 {
		/*g.item(stack, x, y);
		g.itemDecorations(font, stack, x, y);
		*///?} else {
		g.renderItem(stack, x, y);
		g.renderItemDecorations(font, stack, x, y);
		//?}
	}

	/**
	 * Zeichnet gepufferten Text sofort. 1.20–1.21.5 sammelt GuiGraphics den Text und zeichnet ihn
	 * erst am Ende – ohne das läge er über später gezeichneten Flächen.
	 */
	public void flush() {
		//? if <1.21.6 {
		g.flush();
		//?}
	}

	/** Zeichnen auf ein Rechteck begrenzen (Bildschirmkoordinaten), mit {@link #noScissor()} beenden. */
	public void scissor(int x1, int y1, int x2, int y2) {
		g.enableScissor(x1, y1, x2, y2);
	}

	public void noScissor() {
		g.disableScissor();
	}

	// --- Transformation (PoseStack bis 1.21.5, Matrix3x2fStack ab 1.21.6) ---

	public void push() {
		//? if >=1.21.6 {
		/*g.pose().pushMatrix();
		*///?} else
		g.pose().pushPose();
	}

	/** Hebt die Zeichenebene nach vorn (Tiefe der GUI); ab 1.21.6 zeichnet Minecraft in Reihenfolge. */
	public void raise(float z) {
		//? if >=1.21.6 {
		/*// Ab 1.21.6 gibt es keine Tiefe mehr – die Reihenfolge entscheidet.
		*///?} elif >=1.20 {
		g.pose().translate(0, 0, z);
		//?} elif >=1.16 {
		/*pose.translate(0, 0, z);
		*///?} elif >=1.15 {
		/*RenderSystem.translatef(0, 0, z);
		*///?} else
		/*GlStateManager.translatef(0, 0, z);*/
	}

	public void translate(float x, float y) {
		//? if >=1.21.6 {
		/*g.pose().translate(x, y);
		*///?} else
		g.pose().translate(x, y, 0);
	}

	public void scale(float s) {
		//? if >=1.21.6 {
		/*g.pose().scale(s, s);
		*///?} else
		g.pose().scale(s, s, 1f);
	}

	public void pop() {
		//? if >=1.21.6 {
		/*g.pose().popMatrix();
		*///?} else
		g.pose().popPose();
	}
}

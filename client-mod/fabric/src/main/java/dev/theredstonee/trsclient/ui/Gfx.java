package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.compat.Mc;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} elif >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?}
//? if >=1.16 {
import net.minecraft.util.FormattedCharSequence;
//?}
//? if >=1.16 && <1.20 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
*///?} elif >=1.15 && <1.16 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiComponent;
import org.lwjgl.opengl.GL11;
*///?} elif <1.15 {
/*import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.gui.GuiComponent;
import org.lwjgl.opengl.GL11;
*///?}
//? if >=1.16 && <1.17
/*import org.lwjgl.opengl.GL11;*/

/**
 * Dünne, versionsunabhängige Zeichen-Schnittstelle. Alle HUD- und Menü-Zeichnungen laufen hierüber,
 * damit Versionsunterschiede nur an dieser einen Stelle stehen:
 * GuiGraphicsExtractor ab 26.1, GuiGraphics 1.20–1.21.11, PoseStack + GuiComponent 1.16–1.19.4,
 * OpenGL-Matrix + GuiComponent 1.14–1.15. Linien und Rahmen werden aus Rechtecken gebaut (überall identisch).
 */
public final class Gfx {
	private static final Gfx INSTANCE = new Gfx();

	// Wiederverwendete Instanz für das aktuelle Frame (Rendern ist single-threaded).
	//? if >=26.1 {
	/*private GuiGraphicsExtractor g;

	public static Gfx of(GuiGraphicsExtractor g) {
		INSTANCE.g = g;
		return INSTANCE;
	}
	*///?} elif >=1.20 {
	private GuiGraphics g;

	public static Gfx of(GuiGraphics g) {
		INSTANCE.g = g;
		return INSTANCE;
	}
	//?} elif >=1.16 {
	/*private PoseStack pose;

	public static Gfx of(PoseStack pose) {
		INSTANCE.pose = pose;
		return INSTANCE;
	}
	*///?} else {
	/*public static Gfx of() {
		return INSTANCE;
	}
	*///?}

	private Gfx() {
	}

	/** Minecrafts Zeichenobjekt dieses Bilds (GuiGraphics/GuiGraphicsExtractor; PoseStack bis 1.19.4; sonst null). */
	public Object raw() {
		//? if >=1.20 {
		return g;
		//?} elif >=1.16 {
		/*return pose;
		*///?} else
		/*return null;*/
	}

	public int width() {
		//? if >=1.20 {
		return g.guiWidth();
		//?} else
		/*return Mc.window().getGuiScaledWidth();*/
	}

	public int height() {
		//? if >=1.20 {
		return g.guiHeight();
		//?} else
		/*return Mc.window().getGuiScaledHeight();*/
	}

	/** Rechteck von (x1, y1) bis ausschließlich (x2, y2), Farbe ARGB. */
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		//? if >=1.20 {
		g.fill(x1, y1, x2, y2, argb);
		//?} elif >=1.16 {
		/*GuiComponent.fill(pose, x1, y1, x2, y2, argb);
		*///?} else
		/*GuiComponent.fill(x1, y1, x2, y2, argb);*/
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

	//? if >=1.21.6 {
	/*// Ab 1.21.6 rechnet {@code drawString(String)} bei JEDEM Aufruf die Anzeige-Reihenfolge des Textes neu
	// (ICU-Bidi, viele kurzlebige Objekte). HUD-Texte ändern sich selten – die Reihenfolge wird je Text gemerkt
	// (nur Render-Thread, höchstens 512 Einträge, neu bei Sprachwechsel). Ergebnis identisch zu Vanilla.
	private static final java.util.LinkedHashMap<String, FormattedCharSequence> ORDER =
			new java.util.LinkedHashMap<String, FormattedCharSequence>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(java.util.Map.Entry<String, FormattedCharSequence> eldest) {
					return size() > 512;
				}
			};
	private static Object orderLanguage;

	private static FormattedCharSequence visualOrder(String text) {
		net.minecraft.locale.Language lang = net.minecraft.locale.Language.getInstance();
		if (lang != orderLanguage) {
			ORDER.clear();
			orderLanguage = lang;
		}
		FormattedCharSequence seq = ORDER.get(text);
		if (seq == null) {
			seq = lang.getVisualOrder(net.minecraft.network.chat.FormattedText.of(text));
			ORDER.put(text, seq);
		}
		return seq;
	}
	*///?}

	public void text(Font font, String text, int x, int y, int argb, boolean shadow) {
		if (text == null) return;
		//? if >=26.1 {
		/*g.text(font, visualOrder(text), x, y, argb, shadow);
		*///?} elif >=1.21.6 {
		/*g.drawString(font, visualOrder(text), x, y, argb, shadow);
		*///?} elif >=1.20 {
		g.drawString(font, text, x, y, argb, shadow);
		//?} elif >=1.16 {
		/*if (shadow) font.drawShadow(pose, text, x, y, argb);
		else font.draw(pose, text, x, y, argb);
		*///?} else {
		/*if (shadow) font.drawShadow(text, x, y, argb);
		else font.draw(text, x, y, argb);
		*///?}
	}

	public void text(Font font, Component text, int x, int y, int argb, boolean shadow) {
		//? if >=26.1 {
		/*g.text(font, text, x, y, argb, shadow);
		*///?} elif >=1.20 {
		g.drawString(font, text, x, y, argb, shadow);
		//?} elif >=1.16 {
		/*if (shadow) font.drawShadow(pose, text, x, y, argb);
		else font.draw(pose, text, x, y, argb);
		*///?} else
		/*text(font, text.getColoredString(), x, y, argb, shadow);*/
	}

	/** Zentrierter Text mit Schatten. */
	public void centered(Font font, String text, int centerX, int y, int argb) {
		//? if >=26.1 {
		/*g.centeredText(font, text, centerX, y, argb);
		*///?} elif >=1.20 {
		g.drawCenteredString(font, text, centerX, y, argb);
		//?} else
		/*text(font, text, centerX - font.width(text) / 2, y, argb, true);*/
	}

	/** Zentrierter Text mit Schatten. */
	public void centered(Font font, Component text, int centerX, int y, int argb) {
		//? if >=26.1 {
		/*g.centeredText(font, text, centerX, y, argb);
		*///?} elif >=1.20 {
		g.drawCenteredString(font, text, centerX, y, argb);
		//?} elif >=1.16 {
		/*text(font, text, centerX - font.width(text) / 2, y, argb, true);
		*///?} else
		/*centered(font, text.getColoredString(), centerX, y, argb);*/
	}

	/**
	 * Mehrzeiliger Text, umbrochen auf {@code maxWidth}, Zeilenhöhe {@code lineHeight}.
	 * @return y unter der letzten Zeile
	 */
	public int paragraph(Font font, String text, int x, int y, int maxWidth, int lineHeight, int argb) {
		//? if >=1.16 {
		List<FormattedCharSequence> lines = font.split(Mc.text(text), maxWidth);
		for (FormattedCharSequence line : lines) {
			//? if >=26.1 {
			/*g.text(font, line, x, y, argb, false);
			*///?} elif >=1.20 {
			g.drawString(font, line, x, y, argb, false);
			//?} else
			/*font.draw(pose, line, x, y, argb);*/
			y += lineHeight;
		}
		//?} else {
		/*List<String> lines = font.split(text, maxWidth);
		for (String line : lines) {
			text(font, line, x, y, argb, false);
			y += lineHeight;
		}
		*///?}
		return y;
	}

	/** Breite einer (formatierten) Komponente in Pixeln. */
	public static int width(Font font, Component text) {
		//? if >=1.16 {
		return font.width(text);
		//?} else
		/*return font.width(text.getColoredString());*/
	}

	/** Schneidet {@code text} auf höchstens {@code maxWidth} Pixel ab. */
	public static String clip(Font font, String text, int maxWidth) {
		//? if >=1.16 {
		return font.plainSubstrByWidth(text, maxWidth);
		//?} else
		/*return font.substrByWidth(text, maxWidth);*/
	}

	/** Gegenstand als 16×16-Symbol (inkl. Stapelzahl/Haltbarkeitsbalken). */
	public void item(Font font, ItemStack stack, int x, int y) {
		//? if >=26.1 {
		/*g.item(stack, x, y);
		g.itemDecorations(font, stack, x, y);
		*///?} elif >=1.20 {
		g.renderItem(stack, x, y);
		g.renderItemDecorations(font, stack, x, y);
		//?} elif >=1.19.4 {
		/*Mc.mc().getItemRenderer().renderAndDecorateItem(pose, stack, x, y);
		Mc.mc().getItemRenderer().renderGuiItemDecorations(pose, font, stack, x, y);
		*///?} elif >=1.17 {
		/*// Gegenstände zeichnen bis 1.19.3 über die Modelview-Matrix – dort die aktuelle Transformation einrechnen.
		PoseStack modelView = RenderSystem.getModelViewStack();
		modelView.pushPose();
		modelView.mulPoseMatrix(pose.last().pose());
		RenderSystem.applyModelViewMatrix();
		Mc.mc().getItemRenderer().renderAndDecorateItem(stack, x, y);
		Mc.mc().getItemRenderer().renderGuiItemDecorations(font, stack, x, y);
		modelView.popPose();
		RenderSystem.applyModelViewMatrix();
		*///?} elif >=1.16 {
		/*// 1.16: Gegenstände nutzen die OpenGL-Matrix – aktuelle Transformation dort einrechnen.
		RenderSystem.pushMatrix();
		RenderSystem.multMatrix(pose.last().pose());
		Mc.mc().getItemRenderer().renderAndDecorateItem(stack, x, y);
		Mc.mc().getItemRenderer().renderGuiItemDecorations(font, stack, x, y);
		RenderSystem.popMatrix();
		*///?} else {
		/*Mc.mc().getItemRenderer().renderAndDecorateItem(stack, x, y);
		Mc.mc().getItemRenderer().renderGuiItemDecorations(font, stack, x, y);
		*///?}
	}

	/**
	 * Zeichnet alles in {@code draw} gesammelt: 1.20–1.21.1 schicken sonst jedes Rechteck einzeln an
	 * die Grafikkarte (flushIfUnmanaged) – bei Hunderten Rechtecken des TRS-Menüs teuer.
	 */
	public void managed(Runnable draw) {
		//? if >=1.20 && <1.21.2 {
		g.drawManaged(draw);
		//?} else
		/*draw.run();*/
	}

	/**
	 * Zeichnet gepufferten Text sofort. 1.20–1.21.5 sammelt GuiGraphics den Text und zeichnet ihn
	 * erst am Ende – ohne das läge er über später gezeichneten Flächen. Davor und ab 1.21.6
	 * wird sofort bzw. in Reihenfolge gezeichnet.
	 */
	public void flush() {
		//? if >=1.20 && <1.21.6 {
		g.flush();
		//?}
	}

	/**
	 * Alles Folgende über dem bisher Gezeichneten (Überblendungen): ab 1.21.6 nächste Schicht, 1.20–1.21.5
	 * gesammelten Text vorher zeichnen (sonst läge er obenauf). Tiefe zusätzlich über {@link #raise}.
	 */
	public void overlayLayer() {
		//? if >=1.21.6 {
		/*g.nextStratum();
		*///?} elif >=1.20 {
		g.flush();
		//?}
	}

	/** Zeichnen auf ein Rechteck begrenzen (Bildschirmkoordinaten), mit {@link #noScissor()} beenden. */
	public void scissor(int x1, int y1, int x2, int y2) {
		//? if >=1.20 {
		g.enableScissor(x1, y1, x2, y2);
		//?} elif >=1.17 {
		/*int[] r = windowRect(x1, y1, x2, y2);
		RenderSystem.enableScissor(r[0], r[1], r[2], r[3]);
		*///?} else {
		/*int[] r = windowRect(x1, y1, x2, y2);
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glScissor(r[0], r[1], r[2], r[3]);
		*///?}
	}

	/** Bis 1.19.4 erwartet der Scissor Fensterpixel mit Ursprung unten links: {x, y, Breite, Höhe}. */
	private static int[] windowRect(int x1, int y1, int x2, int y2) {
		double s = Mc.window().getGuiScale();
		int wx = (int) (x1 * s);
		int wy = (int) (Mc.window().getHeight() - y2 * s);
		int ww = Math.max(0, (int) ((x2 - x1) * s));
		int wh = Math.max(0, (int) ((y2 - y1) * s));
		return new int[]{wx, wy, ww, wh};
	}

	public void noScissor() {
		//? if >=1.20 {
		g.disableScissor();
		//?} elif >=1.17 {
		/*RenderSystem.disableScissor();
		*///?} else
		/*GL11.glDisable(GL11.GL_SCISSOR_TEST);*/
	}

	// --- Transformation (Matrix3x2fStack ab 1.21.6, PoseStack 1.16–1.21.5, OpenGL-Matrix 1.14–1.15) ---

	public void push() {
		//? if >=1.21.6 {
		/*g.pose().pushMatrix();
		*///?} elif >=1.20 {
		g.pose().pushPose();
		//?} elif >=1.16 {
		/*pose.pushPose();
		*///?} elif >=1.15 {
		/*RenderSystem.pushMatrix();
		*///?} else
		/*GlStateManager.pushMatrix();*/
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
		*///?} elif >=1.20 {
		g.pose().translate(x, y, 0);
		//?} elif >=1.16 {
		/*pose.translate(x, y, 0);
		*///?} elif >=1.15 {
		/*RenderSystem.translatef(x, y, 0);
		*///?} else
		/*GlStateManager.translatef(x, y, 0);*/
	}

	public void scale(float s) {
		//? if >=1.21.6 {
		/*g.pose().scale(s, s);
		*///?} elif >=1.20 {
		g.pose().scale(s, s, 1f);
		//?} elif >=1.16 {
		/*pose.scale(s, s, 1f);
		*///?} elif >=1.15 {
		/*RenderSystem.scalef(s, s, 1f);
		*///?} else
		/*GlStateManager.scalef(s, s, 1f);*/
	}

	public void pop() {
		//? if >=1.21.6 {
		/*g.pose().popMatrix();
		*///?} elif >=1.20 {
		g.pose().popPose();
		//?} elif >=1.16 {
		/*pose.popPose();
		*///?} elif >=1.15 {
		/*RenderSystem.popMatrix();
		*///?} else
		/*GlStateManager.popMatrix();*/
	}
}

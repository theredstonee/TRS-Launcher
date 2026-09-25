package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.compat.Mc;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
//? if >=1.16 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
//?} elif >=1.15 {
/*import com.mojang.blaze3d.systems.RenderSystem;
*///?} else {
/*import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
*///?}
// Scissor über RenderSystem erst ab 1.16.4, davor direkt über OpenGL.
//? if <1.16.4
/*import org.lwjgl.opengl.GL11;*/

/**
 * Dünne, versionsunabhängige Zeichen-Schnittstelle. Alle HUD- und Menü-Zeichnungen laufen hierüber,
 * damit die Versionsunterschiede nur an dieser einen Stelle stehen:
 * <ul>
 *     <li>bis 1.15.2: Immediate-GL (GuiComponent.fill ohne Matrix, Transformation über die GL-Matrix)</li>
 *     <li>ab 1.16: PoseStack (MatrixStack) wird an fill/Font übergeben</li>
 *     <li>Gegenstände: GL-Matrix bis 1.16.5, Model-View-Stack 1.17–1.19.3, PoseStack ab 1.19.4</li>
 * </ul>
 * Text wird nur als String gezeichnet ("§l" für fett) – Component/FormattedCharSequence
 * unterscheiden sich zu stark zwischen den Versionen.
 */
public final class Gfx {
	private static final Gfx INSTANCE = new Gfx();

	//? if >=1.16 {
	private PoseStack pose;

	/** Wiederverwendete Instanz für das aktuelle Frame (Rendern ist single-threaded). */
	public static Gfx of(PoseStack pose) {
		INSTANCE.pose = pose;
		return INSTANCE;
	}
	//?} else {
	/*/^* Wiederverwendete Instanz (bis 1.15.2 zeichnet alles über die globale GL-Matrix). ^/
	public static Gfx of() {
		return INSTANCE;
	}
	*///?}

	private Gfx() {
	}

	/** Minecrafts Zeichenobjekt dieses Bilds (GuiGraphics/GuiGraphicsExtractor; PoseStack bis 1.19.4; sonst null). */
	public Object raw() {
		//? if >=1.16 {
		return pose;
		//?} else
		/*return null;*/
	}

	public int width() {
		return Mc.window().getGuiScaledWidth();
	}

	public int height() {
		return Mc.window().getGuiScaledHeight();
	}

	/** Rechteck von (x1, y1) bis ausschließlich (x2, y2), Farbe ARGB. */
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		//? if >=1.16 {
		GuiComponent.fill(pose, x1, y1, x2, y2, argb);
		//?} else
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

	public void text(Font font, String text, int x, int y, int argb, boolean shadow) {
		//? if >=1.16 {
		if (shadow) font.drawShadow(pose, text, x, y, argb);
		else font.draw(pose, text, x, y, argb);
		//?} else {
		/*if (shadow) font.drawShadow(text, x, y, argb);
		else font.draw(text, x, y, argb);
		*///?}
	}

	/** Zentrierter Text mit Schatten. */
	public void centered(Font font, String text, int centerX, int y, int argb) {
		text(font, text, centerX - font.width(text) / 2, y, argb, true);
	}

	/** Gegenstand als 16×16-Symbol (inkl. Stapelzahl/Haltbarkeitsbalken) im aktuellen Koordinatensystem. */
	public void item(Font font, ItemStack stack, int x, int y) {
		net.minecraft.client.renderer.entity.ItemRenderer items = Mc.mc().getItemRenderer();
		//? if >=1.19.4 {
		/*items.renderAndDecorateItem(pose, stack, x, y);
		items.renderGuiItemDecorations(pose, font, stack, x, y);
		*///?} elif >=1.17 {
		/*PoseStack modelView = RenderSystem.getModelViewStack();
		modelView.pushPose();
		modelView.mulPoseMatrix(pose.last().pose());
		RenderSystem.applyModelViewMatrix();
		items.renderAndDecorateItem(stack, x, y);
		items.renderGuiItemDecorations(font, stack, x, y);
		modelView.popPose();
		RenderSystem.applyModelViewMatrix();
		*///?} elif >=1.16 {
		// Bis 1.16.5 zeichnet der ItemRenderer über die GL-Matrix – die aktuelle Pose dort anwenden.
		RenderSystem.pushMatrix();
		RenderSystem.multMatrix(pose.last().pose());
		items.renderAndDecorateItem(stack, x, y);
		items.renderGuiItemDecorations(font, stack, x, y);
		RenderSystem.popMatrix();
		//?} elif >=1.15 {
		/*items.renderAndDecorateItem(stack, x, y);
		items.renderGuiItemDecorations(font, stack, x, y);
		*///?} else {
		/*Lighting.turnOnGui();
		items.renderAndDecorateItem(stack, x, y);
		items.renderGuiItemDecorations(font, stack, x, y);
		Lighting.turnOff();
		*///?}
	}

	/** Bis 1.19.4 zeichnet Minecraft sofort – nichts zu sammeln. */
	public void managed(Runnable draw) {
		draw.run();
	}

	/** Bis 1.19.4 wird sofort gezeichnet – Überblendungen brauchen nur {@link #raise}. */
	public void overlayLayer() {
	}

	public void flush() {
	}

	/** Schneidet {@code text} auf höchstens {@code maxWidth} Pixel ab. */
	public static String clip(Font font, String text, int maxWidth) {
		//? if >=1.16 {
		return font.plainSubstrByWidth(text, maxWidth);
		//?} else
		/*return font.substrByWidth(text, maxWidth);*/
	}

	/** Zeichnen auf ein Rechteck begrenzen (GUI-Koordinaten), mit {@link #noScissor()} beenden. */
	public void scissor(int x1, int y1, int x2, int y2) {
		double scale = Mc.window().getGuiScale();
		int sx = (int) (x1 * scale);
		int sy = (int) (Mc.window().getHeight() - y2 * scale);
		int sw = Math.max(0, (int) ((x2 - x1) * scale));
		int sh = Math.max(0, (int) ((y2 - y1) * scale));
		//? if >=1.16.4 {
		RenderSystem.enableScissor(sx, sy, sw, sh);
		//?} else {
		/*GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glScissor(sx, sy, sw, sh);
		*///?}
	}

	public void noScissor() {
		//? if >=1.16.4 {
		RenderSystem.disableScissor();
		//?} else
		/*GL11.glDisable(GL11.GL_SCISSOR_TEST);*/
	}

	// --- Transformation (PoseStack ab 1.16, GL-Matrix davor) ---

	public void push() {
		//? if >=1.16 {
		pose.pushPose();
		//?} elif >=1.15 {
		/*RenderSystem.pushMatrix();
		*///?} else
		/*GlStateManager.pushMatrix();*/
	}

	/** Hebt die Zeichenebene nach vorn (die Minecraft-GUI hat eine Tiefe). */
	public void raise(float z) {
		//? if >=1.16 {
		pose.translate(0, 0, z);
		//?} elif >=1.15 {
		/*RenderSystem.translatef(0, 0, z);
		*///?} else
		/*GlStateManager.translatef(0, 0, z);*/
	}

	public void translate(float x, float y) {
		//? if >=1.16 {
		pose.translate(x, y, 0);
		//?} elif >=1.15 {
		/*RenderSystem.translatef(x, y, 0);
		*///?} else
		/*GlStateManager.translatef(x, y, 0);*/
	}

	public void scale(float s) {
		//? if >=1.16 {
		pose.scale(s, s, 1f);
		//?} elif >=1.15 {
		/*RenderSystem.scalef(s, s, 1f);
		*///?} else
		/*GlStateManager.scalef(s, s, 1f);*/
	}

	public void pop() {
		//? if >=1.16 {
		pose.popPose();
		//?} elif >=1.15 {
		/*RenderSystem.popMatrix();
		*///?} else
		/*GlStateManager.popMatrix();*/
	}

	// --- Text-Hilfen (Methodennamen der Font-Klasse haben sich geändert) ---

	/** Kürzt Text auf die Breite (ohne "…"). */
	public static String trim(Font font, String text, int width) {
		//? if >=1.16 {
		return font.plainSubstrByWidth(text, width);
		//?} else
		/*return font.substrByWidth(text, width);*/
	}

	/** Bricht Text an Wortgrenzen auf die Breite um. */
	public static List<String> wrap(Font font, String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (font.width(candidate) <= width || line.length() == 0) {
				line.setLength(0);
				line.append(candidate);
			} else {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}
}

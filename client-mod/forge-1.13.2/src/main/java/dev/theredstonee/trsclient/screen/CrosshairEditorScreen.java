package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.hud.CrosshairRenderer;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.SettingRows;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Fadenkreuz-Editor: große Live-Vorschau (hell und dunkel) links, Form/Farbe/Größe/Abstand/Stärke rechts.
 * Rechtsklick auf eine Auswahl blättert rückwärts.
 */
public final class CrosshairEditorScreen extends GuiScreen {
	private static final String TITLE = TextFormatting.BOLD + "Fadenkreuz bearbeiten";
	private static final int PREVIEW_SCALE = 4;

	private final GuiScreen parent;
	private final Module module = TrsClient.get().modules().crosshair;
	private final CrosshairRenderer renderer = TrsClient.get().hud().crosshair();
	private final Hotspots hot = new Hotspots();

	public CrosshairEditorScreen(GuiScreen parent) {
		this.parent = parent;
	}

	@Override
	public void render(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		hot.clear();
		int pw = Math.min(380, width - 16);
		int ph = Math.min(210, height - 16);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		Brand.rect(px, py, pw, ph, Brand.BG);
		Brand.outline(px - 1, py - 1, pw + 2, ph + 2, Brand.BORDER);
		Brand.rect(px, py, pw, 24, Brand.SURFACE);
		Brand.rect(px, py, 3, 24, Brand.RED);
		Brand.text(fontRenderer, TITLE, px + 11, py + 8, Brand.TEXT, false);
		int pillX = px + pw - 36;
		Brand.pill(fontRenderer, pillX, py + 7, module.isEnabled(), SettingRows.inside(mouseX, mouseY, pillX, py + 7, 26, 11));
		hot.add(pillX, py + 7, 26, 11, module::toggle);

		// Vorschau: oben heller Himmel, unten dunkler Boden – das Fadenkreuz muss auf beidem lesbar sein.
		int prevW = (int) (pw * 0.45);
		int prevX = px + 10;
		int prevY = py + 32;
		int prevH = ph - 42;
		Brand.rect(prevX, prevY, prevW, prevH / 2, 0xFF8FB8F0);
		Brand.fill(prevX, prevY + prevH / 2, prevX + prevW, prevY + prevH, 0xFF3B5D2A);
		Brand.outline(prevX - 1, prevY - 1, prevW + 2, prevH + 2, Brand.BORDER);
		Brand.scissor(prevX, prevY, prevX + prevW, prevY + prevH);
		drawScaled(prevX + prevW / 2, prevY + prevH / 4);
		drawScaled(prevX + prevW / 2, prevY + prevH * 3 / 4);
		Brand.noScissor();
		Brand.text(fontRenderer, "Vorschau ×" + PREVIEW_SCALE, prevX + 3, prevY + prevH - 10, 0xFFFFFFFF, true);

		// Einstellungen
		int sx = prevX + prevW + 12;
		int sw = px + pw - 10 - sx;
		SettingRows.draw(fontRenderer, module.settings(), sx, prevY, sw, py + ph - 26, mouseX, mouseY, hot);

		int by = py + ph - 22;
		String reset = "Zurücksetzen";
		int rw = fontRenderer.getStringWidth(reset) + 10;
		Brand.button(fontRenderer, sx, by, rw, 14, reset, false, SettingRows.inside(mouseX, mouseY, sx, by, rw, 14));
		hot.add(sx, by, rw, 14, () -> {
			boolean on = module.isEnabled();
			module.reset();
			module.setEnabled(on);
		});
		int dw = 50;
		int dx = px + pw - 10 - dw;
		Brand.button(fontRenderer, dx, by, dw, 14, "Fertig", true, SettingRows.inside(mouseX, mouseY, dx, by, dw, 14));
		hot.add(dx, by, dw, 14, this::close);
	}

	/** Fadenkreuz vergrößert zeichnen (Mittelpunkt in Bildschirmkoordinaten). */
	private void drawScaled(int cx, int cy) {
		GlStateManager.pushMatrix();
		GlStateManager.translatef(cx, cy, 0);
		GlStateManager.scalef(PREVIEW_SCALE, PREVIEW_SCALE, 1);
		renderer.draw(0, 0);
		GlStateManager.popMatrix();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (hot.click((int) mouseX, (int) mouseY, button)) {
			TrsMenuScreen.clickSound(this);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	/** Zurück zum vorherigen Bildschirm (auch bei Esc). */
	@Override
	public void close() {
		mc.displayGuiScreen(parent);
	}

	@Override
	public void onGuiClosed() {
		TrsClient.get().saveConfig();
	}

	@Override
	public boolean doesGuiPauseGame() {
		return false;
	}
}

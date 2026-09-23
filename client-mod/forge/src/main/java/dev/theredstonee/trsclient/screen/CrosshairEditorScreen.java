package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.hud.CrosshairRenderer;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.SettingRows;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Fadenkreuz-Editor: große Live-Vorschau (hell und dunkel) links, Form/Farbe/Größe/Abstand/Stärke rechts.
 * Rechtsklick auf eine Auswahl blättert rückwärts.
 */
public final class CrosshairEditorScreen extends TrsScreen {
	private final Component TITLE = Component.literal(I18n.tr("crosshairEditor.title")).withStyle(ChatFormatting.BOLD);
	private static final int PREVIEW_SCALE = 4;

	private final Screen parent;
	private final Module module = TrsClient.get().modules().crosshair;
	private final CrosshairRenderer renderer = TrsClient.get().hud().crosshair();
	private final Hotspots hot = new Hotspots();

	public CrosshairEditorScreen(Screen parent) {
		super(Component.literal(I18n.tr("crosshairEditor.title")));
		this.parent = parent;
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		hot.clear();
		int pw = Math.min(380, width - 16);
		int ph = Math.min(210, height - 16);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		g.fill(px, py, px + pw, py + ph, Brand.BG);
		Brand.outline(g, px - 1, py - 1, pw + 2, ph + 2, Brand.BORDER);
		g.fill(px, py, px + pw, py + 24, Brand.SURFACE);
		g.fill(px, py, px + 3, py + 24, Brand.RED);
		g.text(font, TITLE, px + 11, py + 8, Brand.TEXT, false);
		int pillX = px + pw - 36;
		Brand.pill(g, font, pillX, py + 7, module.isEnabled(), inside(mouseX, mouseY, pillX, py + 7, 26, 11));
		hot.add(pillX, py + 7, 26, 11, module::toggle);

		// Vorschau: oben heller Himmel, unten dunkler Boden – das Fadenkreuz muss auf beidem lesbar sein.
		int prevW = (int) (pw * 0.45);
		int prevX = px + 10;
		int prevY = py + 32;
		int prevH = ph - 42;
		g.fill(prevX, prevY, prevX + prevW, prevY + prevH / 2, 0xFF8FB8F0);
		g.fill(prevX, prevY + prevH / 2, prevX + prevW, prevY + prevH, 0xFF3B5D2A);
		Brand.outline(g, prevX - 1, prevY - 1, prevW + 2, prevH + 2, Brand.BORDER);
		g.scissor(prevX, prevY, prevX + prevW, prevY + prevH);
		drawScaled(g, prevX + prevW / 2, prevY + prevH / 4, PREVIEW_SCALE);
		drawScaled(g, prevX + prevW / 2, prevY + prevH * 3 / 4, PREVIEW_SCALE);
		g.noScissor();
		g.text(font, I18n.tr("crosshairEditor.preview", PREVIEW_SCALE), prevX + 3, prevY + prevH - 10, 0xFFFFFFFF, true);

		// Einstellungen
		int sx = prevX + prevW + 12;
		int sw = px + pw - 10 - sx;
		SettingRows.draw(g, font, module.settings(), sx, prevY, sw, py + ph - 26, mouseX, mouseY, hot);

		int by = py + ph - 22;
		String reset = I18n.tr("common.reset");
		int rw = font.width(reset) + 10;
		Brand.button(g, font, sx, by, rw, 14, reset, false, inside(mouseX, mouseY, sx, by, rw, 14));
		hot.add(sx, by, rw, 14, () -> {
			boolean on = module.isEnabled();
			module.reset();
			module.setEnabled(on);
		});
		int dw = 50;
		int dx = px + pw - 10 - dw;
		Brand.button(g, font, dx, by, dw, 14, I18n.tr("common.done"), true, inside(mouseX, mouseY, dx, by, dw, 14));
		hot.add(dx, by, dw, 14, this::onClose);
	}

	/** Fadenkreuz vergrößert zeichnen (Mittelpunkt in Bildschirmkoordinaten). */
	private void drawScaled(Gfx g, int cx, int cy, int scale) {
		g.push();
		g.translate(cx, cy);
		g.scale(scale);
		renderer.draw(g, 0, 0);
		g.pop();
	}

	@Override
	protected boolean onClick(double mouseX, double mouseY, int button) {
		if (hot.click(mouseX, mouseY, button)) {
			clickSound();
			return true;
		}
		return false;
	}

	@Override
	public void onClose() {
		open(parent);
	}

	@Override
	public void removed() {
		save();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

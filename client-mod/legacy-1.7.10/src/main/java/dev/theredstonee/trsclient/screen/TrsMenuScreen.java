package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.SettingRows;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Hauptmenü des TRS Clients (Rechte Umschalttaste): Modul-Karten links (scrollbar),
 * Einstellungen des gewählten Moduls rechts, unten "HUD bearbeiten" und "Schließen".
 * Einfaches Immediate-Mode-UI: Klickflächen werden beim Zeichnen registriert.
 */
public final class TrsMenuScreen extends GuiScreen {
	private static final String TITLE = EnumChatFormatting.BOLD + "TRS Client";
	private static final int HEADER_H = 26;
	private static final int FOOTER_H = 24;
	private static final int CARD_H = 26;
	private static final int GAP = 4;

	private final GuiScreen parent;
	private final TrsModules modules = TrsClient.get().modules();
	/** Nur die Module, die es in dieser Minecraft-Version gibt. */
	private final List<Module> shown = new ArrayList<Module>();
	private final Hotspots hot = new Hotspots();
	private Module selected;
	/** Scroll-Versatz der Kartenliste in Pixeln. */
	private int scroll;
	private int maxScroll;
	private int cardsX;
	private int cardsY;
	private int cardsW;
	private int cardsH;

	public TrsMenuScreen(GuiScreen parent) {
		this.parent = parent;
		for (Module m : modules.registry.all()) if (TrsClient.supported(m)) shown.add(m);
		this.selected = shown.get(0);
	}

	/** Öffnet das Menü direkt mit einem ausgewählten Modul (z. B. aus dem Autotest). */
	public TrsMenuScreen select(Module module) {
		this.selected = module;
		return this;
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		// Im Spiel abgedunkelt, im Hauptmenü der Schmutz-Hintergrund.
		drawDefaultBackground();
		hot.clear();

		int pw = Math.min(420, width - 16);
		int ph = Math.min(250, height - 16);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;

		// Rahmen + Kopfzeile
		Brand.rect(px, py, pw, ph, Brand.BG);
		Brand.outline(px - 1, py - 1, pw + 2, ph + 2, Brand.BORDER);
		Brand.rect(px, py, pw, HEADER_H, Brand.SURFACE);
		Brand.rect(px, py, 3, HEADER_H, Brand.RED);
		Brand.text(fontRendererObj, TITLE, px + 11, py + 9, Brand.TEXT, false);
		int active = 0;
		for (Module m : shown) if (m.isEnabled()) active++;
		String status = active + "/" + shown.size() + " aktiv";
		int statusW = fontRendererObj.getStringWidth(status);
		Brand.text(fontRendererObj, status, px + pw - 10 - statusW, py + 9, Brand.TEXT_DIM, false);
		// "Lampe" neben dem Status – leuchtet, sobald etwas aktiv ist
		Brand.rect(px + pw - 18 - statusW, py + 10, 4, 4, active > 0 ? Brand.AMBER : Brand.OFF);

		int bodyY = py + HEADER_H + 8;
		int footerY = py + ph - FOOTER_H;
		int leftW = (int) (pw * 0.55);

		drawCards(mouseX, mouseY, px + 10, bodyY, leftW - 14, footerY - 6 - bodyY);
		drawSettings(mouseX, mouseY, px + leftW + 2, bodyY, pw - leftW - 12, footerY - 6 - bodyY);

		// Fußzeile
		Brand.rect(px, footerY, pw, 1, Brand.BORDER);
		int bh = 16;
		int by = footerY + 4;
		int closeW = 58;
		int editW = 86;
		int closeX = px + pw - 10 - closeW;
		int editX = closeX - 6 - editW;
		String hint = "Taste: " + GameSettings.getKeyDisplayString(TrsKeys.menu.getKeyCode());
		if (px + 10 + fontRendererObj.getStringWidth(hint) < editX - 6) {
			Brand.text(fontRendererObj, hint, px + 10, footerY + 8, Brand.TEXT_DIM, false);
		}
		button(mouseX, mouseY, editX, by, editW, bh, "HUD bearbeiten", true, () -> mc.displayGuiScreen(new HudEditorScreen(this)));
		button(mouseX, mouseY, closeX, by, closeW, bh, "Schließen", false, this::close);
	}

	private void drawCards(int mx, int my, int x, int y, int w, int h) {
		Brand.text(fontRendererObj, "Module", x, y, Brand.TEXT_DIM, false);
		y += 12;
		h -= 12;
		cardsX = x;
		cardsY = y;
		cardsW = w;
		cardsH = h;
		int cardW = (w - GAP) / 2;
		int rows = (shown.size() + 1) / 2;
		maxScroll = Math.max(0, rows * (CARD_H + GAP) - GAP - h);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		boolean mouseInList = SettingRows.inside(mx, my, x, y, w, h);

		Brand.scissor(x, y, x + w, y + h);
		for (int i = 0; i < shown.size(); i++) {
			final Module m = shown.get(i);
			int cx = x + (i % 2) * (cardW + GAP);
			int cy = y + (i / 2) * (CARD_H + GAP) - scroll;
			if (cy + CARD_H < y || cy > y + h) continue;
			boolean hover = mouseInList && SettingRows.inside(mx, my, cx, cy, cardW, CARD_H);
			Brand.rect(cx, cy, cardW, CARD_H, hover ? Brand.SURFACE_HOVER : Brand.SURFACE);
			if (m == selected) Brand.outline(cx, cy, cardW, CARD_H, Brand.RED);
			// Lampe: leuchtet bernsteinfarben, wenn "bestromt"
			Brand.rect(cx + cardW - 8, cy + 5, 4, 4, m.isEnabled() ? Brand.AMBER : Brand.OFF);
			Brand.text(fontRendererObj, fontRendererObj.trimStringToWidth(m.name(), cardW - 16), cx + 6, cy + 4, Brand.TEXT, false);
			if (mouseInList) {
				int top = Math.max(cy, y);
				hot.add(cx, top, cardW, Math.min(cy + CARD_H, y + h) - top, () -> selected = m);
			}

			int pillX = cx + 6;
			int pillY = cy + CARD_H - 12;
			Brand.pill(fontRendererObj, pillX, pillY, m.isEnabled(), mouseInList && SettingRows.inside(mx, my, pillX, pillY, 26, 11));
			if (mouseInList && pillY >= y && pillY + 11 <= y + h) {
				hot.add(pillX, pillY, 26, 11, () -> {
					m.toggle();
					selected = m;
				});
			}
			if (m.isHud()) {
				Brand.text(fontRendererObj, "HUD", cx + cardW - 6 - fontRendererObj.getStringWidth("HUD"), pillY + 2, Brand.TEXT_DIM, false);
			}
		}
		Brand.noScissor();

		// Scrollleiste
		if (maxScroll > 0) {
			int trackX = x + w + 2;
			int barH = Math.max(12, h * h / (h + maxScroll));
			int barY = y + (h - barH) * scroll / maxScroll;
			Brand.rect(trackX, y, 2, h, Brand.SURFACE);
			Brand.rect(trackX, barY, 2, barH, Brand.TEXT_DIM);
		}
	}

	private void drawSettings(int mx, int my, int x, int y, int w, int h) {
		Brand.rect(x, y, w, h, Brand.SURFACE);
		Brand.rect(x, y, w, 1, selected.isEnabled() ? Brand.AMBER : Brand.RED);
		int ix = x + 7;
		int iw = w - 14;
		int cy = y + 7;

		Brand.text(fontRendererObj, EnumChatFormatting.BOLD + selected.name(), ix, cy, Brand.TEXT, false);
		int pillX = x + w - 7 - 26;
		Brand.pill(fontRendererObj, pillX, cy - 1, selected.isEnabled(), SettingRows.inside(mx, my, pillX, cy - 1, 26, 11));
		final Module sel = selected;
		hot.add(pillX, cy - 1, 26, 11, sel::toggle);
		cy += 13;

		@SuppressWarnings("unchecked")
		List<String> lines = fontRendererObj.listFormattedStringToWidth(selected.description(), iw);
		for (String line : lines) {
			Brand.text(fontRendererObj, line, ix, cy, Brand.TEXT_DIM, false);
			cy += 10;
		}
		cy += 4;
		Brand.rect(ix, cy, iw, 1, Brand.BORDER);
		cy += 5;

		boolean hasAction = selected instanceof HudModule || selected == modules.crosshair;
		if (selected.settings().isEmpty()) {
			Brand.text(fontRendererObj, "Keine Einstellungen", ix, cy + 3, Brand.TEXT_DIM, false);
		}
		SettingRows.draw(fontRendererObj, selected.settings(), ix, cy, iw, y + h - (hasAction ? 18 : 0), mx, my, hot);

		int by = y + h - 17;
		if (selected instanceof HudModule) {
			final HudModule hudModule = (HudModule) selected;
			String label = "Position zurücksetzen";
			button(mx, my, ix, by, fontRendererObj.getStringWidth(label) + 10, 13, label, false, hudModule::resetPosition);
		} else if (selected == modules.crosshair) {
			String label = "Fadenkreuz bearbeiten";
			button(mx, my, ix, by, fontRendererObj.getStringWidth(label) + 10, 13, label, true,
					() -> mc.displayGuiScreen(new CrosshairEditorScreen(this)));
		}
	}

	private void button(int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
		Brand.button(fontRendererObj, x, y, w, h, label, primary, SettingRows.inside(mx, my, x, y, w, h));
		hot.add(x, y, w, h, action);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		if (hot.click(mouseX, mouseY, button)) {
			clickSound(this);
			return;
		}
		super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void handleMouseInput() {
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if (wheel == 0) return;
		int mx = Mouse.getEventX() * width / mc.displayWidth;
		int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
		if (!SettingRows.inside(mx, my, cardsX, cardsY, cardsW + 4, cardsH)) return;
		scroll = Math.max(0, Math.min(maxScroll, scroll - Integer.signum(wheel) * (CARD_H + GAP)));
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) {
		if (keyCode != 0 && keyCode == TrsKeys.menu.getKeyCode()) {
			close();
			return;
		}
		if (keyCode == 1) { // Esc
			close();
			return;
		}
		super.keyTyped(typedChar, keyCode);
	}

	/** Klick-Geräusch wie bei Vanilla-Knöpfen. */
	static void clickSound(GuiScreen screen) {
		screen.mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
	}

	private void close() {
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

package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.Hotspots;
import dev.theredstonee.trsclient.ui.SettingRows;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Hauptmenü des TRS Clients (Rechte Umschalttaste): Modul-Karten links (scrollbar),
 * Einstellungen des gewählten Moduls rechts, unten "Resourcepacks", "HUD bearbeiten" und "Schließen".
 * Einfaches Immediate-Mode-UI: Klickflächen werden beim Zeichnen registriert.
 */
public final class TrsMenuScreen extends TrsScreen {
	private static final Component TITLE = Mc.text("TRS Client").withStyle(ChatFormatting.BOLD);
	private static final int HEADER_H = 26;
	private static final int FOOTER_H = 24;
	private static final int CARD_H = 26;
	private static final int GAP = 4;

	private final Screen parent;
	private final TrsModules modules = TrsClient.get().modules();
	private final Hotspots hot = new Hotspots();
	private Module selected;
	/** Scroll-Versatz der Kartenliste in Pixeln. */
	private int scroll;
	private int maxScroll;
	/** Kartenbereich (für Mausrad/Clipping), zuletzt gezeichnet. */
	private int cardsX;
	private int cardsY;
	private int cardsW;
	private int cardsH;

	public TrsMenuScreen(Screen parent) {
		super(Mc.text("TRS Client"));
		this.parent = parent;
		this.selected = modules.registry.all().get(0);
	}

	/** Öffnet das Menü direkt mit einem ausgewählten Modul (z. B. aus dem Autotest). */
	public TrsMenuScreen select(Module module) {
		this.selected = module;
		return this;
	}

	@Override
	protected void draw(Gfx g, int mouseX, int mouseY, float partialTick) {
		hot.clear();

		int pw = Math.min(420, width - 16);
		int ph = Math.min(250, height - 16);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;

		// Rahmen + Kopfzeile
		g.fill(px, py, px + pw, py + ph, Brand.BG);
		Brand.outline(g, px - 1, py - 1, pw + 2, ph + 2, Brand.BORDER);
		g.fill(px, py, px + pw, py + HEADER_H, Brand.SURFACE);
		g.fill(px, py, px + 3, py + HEADER_H, Brand.RED);
		g.text(font, TITLE, px + 11, py + 9, Brand.TEXT, false);
		int active = 0;
		for (Module m : modules.registry.all()) if (m.isEnabled()) active++;
		String status = active + "/" + modules.registry.all().size() + " aktiv";
		int statusW = font.width(status);
		g.text(font, status, px + pw - 10 - statusW, py + 9, Brand.TEXT_DIM, false);
		// "Lampe" neben dem Status – leuchtet, sobald etwas aktiv ist
		g.fill(px + pw - 18 - statusW, py + 10, px + pw - 14 - statusW, py + 14, active > 0 ? Brand.AMBER : Brand.OFF);

		int bodyY = py + HEADER_H + 8;
		int footerY = py + ph - FOOTER_H;
		int leftW = (int) (pw * 0.55);

		drawCards(g, mouseX, mouseY, px + 10, bodyY, leftW - 14, footerY - 6 - bodyY);
		drawSettings(g, mouseX, mouseY, px + leftW + 2, bodyY, pw - leftW - 12, footerY - 6 - bodyY);

		// Fußzeile
		g.hLine(px, px + pw - 1, footerY, Brand.BORDER);
		int bh = 16;
		int by = footerY + 4;
		int closeW = 58;
		int editW = 86;
		int packsW = 80;
		int closeX = px + pw - 10 - closeW;
		int editX = closeX - 6 - editW;
		int packsX = editX - 6 - packsW;
		String hint = "Taste: " + Mc.keyName(TrsKeys.menu);
		if (px + 10 + font.width(hint) < packsX - 6) {
			g.text(font, hint, px + 10, footerY + 8, Brand.TEXT_DIM, false);
		}
		button(g, mouseX, mouseY, packsX, by, packsW, bh, "Resourcepacks", false, () -> open(new PackScreen(this)));
		button(g, mouseX, mouseY, editX, by, editW, bh, "HUD bearbeiten", true, () -> open(new HudEditorScreen(this)));
		button(g, mouseX, mouseY, closeX, by, closeW, bh, "Schließen", false, this::onClose);
	}

	private void drawCards(Gfx g, int mx, int my, int x, int y, int w, int h) {
		g.text(font, "Module", x, y, Brand.TEXT_DIM, false);
		y += 12;
		h -= 12;
		cardsX = x;
		cardsY = y;
		cardsW = w;
		cardsH = h;
		int cardW = (w - GAP) / 2;
		List<Module> all = modules.registry.all();
		int rows = (all.size() + 1) / 2;
		maxScroll = Math.max(0, rows * (CARD_H + GAP) - GAP - h);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		boolean mouseInList = inside(mx, my, x, y, w, h);

		g.scissor(x, y, x + w, y + h);
		for (int i = 0; i < all.size(); i++) {
			Module m = all.get(i);
			int cx = x + (i % 2) * (cardW + GAP);
			int cy = y + (i / 2) * (CARD_H + GAP) - scroll;
			if (cy + CARD_H < y || cy > y + h) continue;
			boolean hover = mouseInList && inside(mx, my, cx, cy, cardW, CARD_H);
			g.fill(cx, cy, cx + cardW, cy + CARD_H, hover ? Brand.SURFACE_HOVER : Brand.SURFACE);
			if (m == selected) Brand.outline(g, cx, cy, cardW, CARD_H, Brand.RED);
			// Lampe: leuchtet bernsteinfarben, wenn "bestromt"
			g.fill(cx + cardW - 8, cy + 5, cx + cardW - 4, cy + 9, m.isEnabled() ? Brand.AMBER : Brand.OFF);
			g.text(font, Gfx.clip(font, m.name(), cardW - 16), cx + 6, cy + 4, Brand.TEXT, false);
			if (mouseInList) hot.add(cx, Math.max(cy, y), cardW, Math.min(cy + CARD_H, y + h) - Math.max(cy, y), () -> selected = m);

			int pillX = cx + 6;
			int pillY = cy + CARD_H - 12;
			Brand.pill(g, font, pillX, pillY, m.isEnabled(), mouseInList && inside(mx, my, pillX, pillY, 26, 11));
			if (mouseInList && pillY >= y && pillY + 11 <= y + h) {
				hot.add(pillX, pillY, 26, 11, () -> {
					m.toggle();
					selected = m;
				});
			}
			if (m.isHud()) g.text(font, "HUD", cx + cardW - 6 - font.width("HUD"), pillY + 2, Brand.TEXT_DIM, false);
		}
		g.noScissor();

		// Scrollleiste
		if (maxScroll > 0) {
			int trackX = x + w + 2;
			int barH = Math.max(12, h * h / (h + maxScroll));
			int barY = y + (h - barH) * scroll / maxScroll;
			g.fill(trackX, y, trackX + 2, y + h, Brand.SURFACE);
			g.fill(trackX, barY, trackX + 2, barY + barH, Brand.TEXT_DIM);
		}
	}

	private void drawSettings(Gfx g, int mx, int my, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, Brand.SURFACE);
		g.fill(x, y, x + w, y + 1, selected.isEnabled() ? Brand.AMBER : Brand.RED);
		int ix = x + 7;
		int iw = w - 14;
		int cy = y + 7;

		g.text(font, Mc.text(selected.name()).withStyle(ChatFormatting.BOLD), ix, cy, Brand.TEXT, false);
		int pillX = x + w - 7 - 26;
		Brand.pill(g, font, pillX, cy - 1, selected.isEnabled(), inside(mx, my, pillX, cy - 1, 26, 11));
		Module sel = selected;
		hot.add(pillX, cy - 1, 26, 11, sel::toggle);
		cy += 13;

		cy = g.paragraph(font, selected.description(), ix, cy, iw, 10, Brand.TEXT_DIM);
		cy += 4;
		g.hLine(ix, ix + iw - 1, cy, Brand.BORDER);
		cy += 5;

		boolean hasAction = selected instanceof HudModule || selected == modules.crosshair;
		if (selected.settings().isEmpty()) {
			g.text(font, "Keine Einstellungen", ix, cy + 3, Brand.TEXT_DIM, false);
		}
		SettingRows.draw(g, font, selected.settings(), ix, cy, iw, y + h - (hasAction ? 18 : 0), mx, my, hot);

		int by = y + h - 17;
		if (selected instanceof HudModule) {
			HudModule hud = (HudModule) selected;
			String label = "Position zurücksetzen";
			button(g, mx, my, ix, by, font.width(label) + 10, 13, label, false, hud::resetPosition);
		} else if (selected == modules.crosshair) {
			String label = "Fadenkreuz bearbeiten";
			button(g, mx, my, ix, by, font.width(label) + 10, 13, label, true, () -> open(new CrosshairEditorScreen(this)));
		}
	}

	private void button(Gfx g, int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
		Brand.button(g, font, x, y, w, h, label, primary, inside(mx, my, x, y, w, h));
		hot.add(x, y, w, h, action);
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
	protected boolean onScroll(double mouseX, double mouseY, double amount) {
		if (!inside(mouseX, mouseY, cardsX, cardsY, cardsW + 4, cardsH)) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount) * (CARD_H + GAP)));
		return true;
	}

	@Override
	protected boolean onKey(int key, int modifiers) {
		if (key == TrsKeys.boundKey(TrsKeys.menu)) {
			onClose();
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

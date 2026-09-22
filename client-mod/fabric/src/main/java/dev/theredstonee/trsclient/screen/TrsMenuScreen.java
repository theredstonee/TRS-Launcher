package dev.theredstonee.trsclient.screen;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.module.ColorSetting;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.NumberSetting;
import dev.theredstonee.trsclient.core.module.Setting;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.ui.Brand;
import dev.theredstonee.trsclient.ui.Gfx;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.9 {
/*import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
*///?}

/**
 * Hauptmenü des TRS Clients (Rechte Umschalttaste): Modul-Karten links,
 * Einstellungen des gewählten Moduls rechts, unten "HUD bearbeiten" und "Schließen".
 * Einfaches Immediate-Mode-UI: Klickflächen werden beim Zeichnen registriert.
 */
public final class TrsMenuScreen extends Screen {
	private static final Component TITLE = Component.literal("TRS Client").withStyle(ChatFormatting.BOLD);
	private static final int HEADER_H = 26;
	private static final int FOOTER_H = 24;
	private static final int CARD_H = 30;
	private static final int GAP = 4;
	private static final int ROW_H = 15;

	private final Screen parent;
	private final TrsModules modules = TrsClient.get().modules();
	private final List<Hotspot> hotspots = new ArrayList<>();
	private Module selected;

	/** Klickfläche mit Aktion. */
	private record Hotspot(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public TrsMenuScreen(Screen parent) {
		super(Component.literal("TRS Client"));
		this.parent = parent;
		this.selected = modules.registry.all().get(0);
	}

	// --- Versionsabhängige Einstiegspunkte (Zeichnen/Eingabe) → neutrale Methoden ---

	//? if >=26.1 {
	/*@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		draw(Gfx.of(g), mouseX, mouseY);
	}
	*///?} else {
	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		//? if <1.20.2
		/*renderBackground(g);*/
		super.render(g, mouseX, mouseY, partialTick);
		draw(Gfx.of(g), mouseX, mouseY);
	}
	//?}

	//? if >=1.21.9 {
	/*@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return onClick(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (TrsKeys.menu.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
	*///?} else {
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return onClick(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (TrsKeys.menu.matches(keyCode, scanCode)) {
			onClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
	//?}

	// --- Neutrale Logik ---

	private void draw(Gfx g, int mouseX, int mouseY) {
		hotspots.clear();

		int pw = Math.min(400, width - 16);
		int ph = Math.min(236, height - 16);
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
		int leftW = (int) (pw * 0.56);

		renderCards(g, mouseX, mouseY, px + 10, bodyY, leftW - 14);
		renderSettings(g, mouseX, mouseY, px + leftW + 2, bodyY, pw - leftW - 12, footerY - 6 - bodyY);

		// Fußzeile
		g.hLine(px, px + pw - 1, footerY, Brand.BORDER);
		int bh = 16;
		int by = footerY + 4;
		int closeW = 58;
		int editW = 86;
		int closeX = px + pw - 10 - closeW;
		int editX = closeX - 6 - editW;
		String hint = "Taste: " + TrsKeys.menu.getTranslatedKeyMessage().getString();
		if (px + 10 + font.width(hint) < editX - 6) {
			g.text(font, hint, px + 10, footerY + 8, Brand.TEXT_DIM, false);
		}
		button(g, mouseX, mouseY, editX, by, editW, bh, "HUD bearbeiten", true,
				() -> Mc.setScreen(new HudEditorScreen(this)));
		button(g, mouseX, mouseY, closeX, by, closeW, bh, "Schließen", false, this::onClose);
	}

	private void renderCards(Gfx g, int mx, int my, int x, int y, int w) {
		g.text(font, "Module", x, y, Brand.TEXT_DIM, false);
		y += 12;
		int cardW = (w - GAP) / 2;
		List<Module> all = modules.registry.all();
		for (int i = 0; i < all.size(); i++) {
			Module m = all.get(i);
			int cx = x + (i % 2) * (cardW + GAP);
			int cy = y + (i / 2) * (CARD_H + GAP);
			boolean hover = inside(mx, my, cx, cy, cardW, CARD_H);
			g.fill(cx, cy, cx + cardW, cy + CARD_H, hover ? Brand.SURFACE_HOVER : Brand.SURFACE);
			if (m == selected) Brand.outline(g, cx, cy, cardW, CARD_H, Brand.RED);
			// Lampe: leuchtet bernsteinfarben, wenn "bestromt"
			g.fill(cx + cardW - 8, cy + 5, cx + cardW - 4, cy + 9, m.isEnabled() ? Brand.AMBER : Brand.OFF);
			g.text(font, font.plainSubstrByWidth(m.name(), cardW - 16), cx + 6, cy + 5, Brand.TEXT, false);
			hotspots.add(new Hotspot(cx, cy, cardW, CARD_H, () -> selected = m));

			int pillX = cx + 6;
			int pillY = cy + CARD_H - 14;
			boolean pillHover = inside(mx, my, pillX, pillY, 26, 11);
			Brand.pill(g, font, pillX, pillY, m.isEnabled(), pillHover);
			hotspots.add(new Hotspot(pillX, pillY, 26, 11, () -> {
				m.toggle();
				selected = m;
			}));
			if (m.isHud()) g.text(font, "HUD", cx + cardW - 6 - font.width("HUD"), pillY + 2, Brand.TEXT_DIM, false);
		}
	}

	private void renderSettings(Gfx g, int mx, int my, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, Brand.SURFACE);
		g.fill(x, y, x + w, y + 1, selected.isEnabled() ? Brand.AMBER : Brand.RED);
		int ix = x + 7;
		int iw = w - 14;
		int cy = y + 7;

		g.text(font, Component.literal(selected.name()).withStyle(ChatFormatting.BOLD), ix, cy, Brand.TEXT, false);
		int pillX = x + w - 7 - 26;
		Brand.pill(g, font, pillX, cy - 1, selected.isEnabled(), inside(mx, my, pillX, cy - 1, 26, 11));
		Module sel = selected;
		hotspots.add(new Hotspot(pillX, cy - 1, 26, 11, sel::toggle));
		cy += 13;

		for (FormattedCharSequence line : font.split(Component.literal(selected.description()), iw)) {
			g.text(font, line, ix, cy, Brand.TEXT_DIM, false);
			cy += 10;
		}
		cy += 4;
		g.hLine(ix, ix + iw - 1, cy, Brand.BORDER);
		cy += 5;

		if (selected.settings().isEmpty()) {
			g.text(font, "Keine Einstellungen", ix, cy + 3, Brand.TEXT_DIM, false);
		}
		for (Setting s : selected.settings()) {
			if (cy + ROW_H > y + h - (selected.isHud() ? 18 : 0)) break;
			g.text(font, s.label(), ix, cy + 3, Brand.TEXT, false);
			int right = ix + iw;
			if (s instanceof BoolSetting b) {
				int bx = right - 26;
				Brand.pill(g, font, bx, cy + 1, b.get(), inside(mx, my, bx, cy + 1, 26, 11));
				hotspots.add(new Hotspot(bx, cy + 1, 26, 11, b::toggle));
			} else if (s instanceof NumberSetting n) {
				numberControl(g, mx, my, right, cy + 1, n);
			} else if (s instanceof ColorSetting c) {
				int sx = right - 24;
				boolean hover = inside(mx, my, sx, cy + 1, 24, 11);
				g.fill(sx, cy + 1, sx + 24, cy + 12, c.argb());
				Brand.outline(g, sx - 1, cy, 26, 13, hover ? Brand.AMBER : Brand.BORDER);
				hotspots.add(new Hotspot(sx, cy + 1, 24, 11, c::cycle));
			}
			cy += ROW_H;
		}

		if (selected instanceof HudModule hud) {
			String label = "Position zurücksetzen";
			int bw = font.width(label) + 10;
			int by = y + h - 17;
			button(g, mx, my, ix, by, bw, 13, label, false, hud::resetPosition);
		}
	}

	private void numberControl(Gfx g, int mx, int my, int right, int y, NumberSetting n) {
		int box = 11;
		int valueW = 30;
		int plusX = right - box;
		int valueX = plusX - valueW;
		int minusX = valueX - box;
		smallButton(g, mx, my, minusX, y, box, "-", () -> n.nudge(-1));
		g.centered(font, n.display(), valueX + valueW / 2, y + 2, Brand.AMBER);
		smallButton(g, mx, my, plusX, y, box, "+", () -> n.nudge(1));
	}

	private void smallButton(Gfx g, int mx, int my, int x, int y, int size, String label, Runnable action) {
		boolean hover = inside(mx, my, x, y, size, size);
		g.fill(x, y, x + size, y + size, hover ? Brand.RED : Brand.OFF);
		g.centered(font, label, x + size / 2 + 1, y + 2, Brand.TEXT);
		hotspots.add(new Hotspot(x, y, size, size, action));
	}

	private void button(Gfx g, int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
		Brand.button(g, font, x, y, w, h, label, primary, inside(mx, my, x, y, w, h));
		hotspots.add(new Hotspot(x, y, w, h, action));
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private boolean onClick(double mouseX, double mouseY, int button) {
		if (button == 0) {
			// Rückwärts: später gezeichnete (kleinere) Flächen liegen oben.
			for (int i = hotspots.size() - 1; i >= 0; i--) {
				Hotspot h = hotspots.get(i);
				if (h.contains(mouseX, mouseY)) {
					minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
					h.action().run();
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void onClose() {
		Mc.setScreen(parent);
	}

	@Override
	public void removed() {
		TrsClient.get().saveConfig();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

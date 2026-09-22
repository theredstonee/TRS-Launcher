package dev.theredstonee.trsclient.screen;

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
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hauptmenü des TRS Clients (Rechte Umschalttaste): Modul-Karten links,
 * Einstellungen des gewählten Moduls rechts, unten "HUD bearbeiten" und "Schließen".
 * Einfaches Immediate-Mode-UI: Klickflächen werden beim Zeichnen registriert.
 */
public final class TrsMenuScreen extends GuiScreen {
	private static final String TITLE = EnumChatFormatting.BOLD + "TRS Client";
	private static final int HEADER_H = 26;
	private static final int FOOTER_H = 24;
	private static final int CARD_H = 30;
	private static final int GAP = 4;
	private static final int ROW_H = 15;

	private final GuiScreen parent;
	private final TrsModules modules = TrsClient.get().modules();
	private final List<Hotspot> hotspots = new ArrayList<Hotspot>();
	private Module selected;

	/** Klickfläche mit Aktion. */
	private static final class Hotspot {
		final int x;
		final int y;
		final int w;
		final int h;
		final Runnable action;

		Hotspot(int x, int y, int w, int h, Runnable action) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.action = action;
		}

		boolean contains(int mx, int my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public TrsMenuScreen(GuiScreen parent) {
		this.parent = parent;
		this.selected = modules.registry.all().get(0);
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		// Im Spiel abgedunkelt, im Hauptmenü der Schmutz-Hintergrund.
		drawDefaultBackground();
		hotspots.clear();

		int pw = Math.min(400, width - 16);
		int ph = Math.min(236, height - 16);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;

		// Rahmen + Kopfzeile
		Brand.rect(px, py, pw, ph, Brand.BG);
		Brand.outline(px - 1, py - 1, pw + 2, ph + 2, Brand.BORDER);
		Brand.rect(px, py, pw, HEADER_H, Brand.SURFACE);
		Brand.rect(px, py, 3, HEADER_H, Brand.RED);
		Brand.text(fontRendererObj, TITLE, px + 11, py + 9, Brand.TEXT, false);
		int active = 0;
		for (Module m : modules.registry.all()) if (m.isEnabled()) active++;
		String status = active + "/" + modules.registry.all().size() + " aktiv";
		int statusW = fontRendererObj.getStringWidth(status);
		Brand.text(fontRendererObj, status, px + pw - 10 - statusW, py + 9, Brand.TEXT_DIM, false);
		// "Lampe" neben dem Status – leuchtet, sobald etwas aktiv ist
		Brand.rect(px + pw - 18 - statusW, py + 10, 4, 4, active > 0 ? Brand.AMBER : Brand.OFF);

		int bodyY = py + HEADER_H + 8;
		int footerY = py + ph - FOOTER_H;
		int leftW = (int) (pw * 0.56);

		renderCards(mouseX, mouseY, px + 10, bodyY, leftW - 14);
		renderSettings(mouseX, mouseY, px + leftW + 2, bodyY, pw - leftW - 12, footerY - 6 - bodyY);

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
		button(mouseX, mouseY, editX, by, editW, bh, "HUD bearbeiten", true, new Runnable() {
			@Override
			public void run() {
				mc.displayGuiScreen(new HudEditorScreen(TrsMenuScreen.this));
			}
		});
		button(mouseX, mouseY, closeX, by, closeW, bh, "Schließen", false, new Runnable() {
			@Override
			public void run() {
				close();
			}
		});
	}

	private void renderCards(int mx, int my, int x, int y, int w) {
		Brand.text(fontRendererObj, "Module", x, y, Brand.TEXT_DIM, false);
		y += 12;
		int cardW = (w - GAP) / 2;
		List<Module> all = modules.registry.all();
		for (int i = 0; i < all.size(); i++) {
			final Module m = all.get(i);
			int cx = x + (i % 2) * (cardW + GAP);
			int cy = y + (i / 2) * (CARD_H + GAP);
			boolean hover = inside(mx, my, cx, cy, cardW, CARD_H);
			Brand.rect(cx, cy, cardW, CARD_H, hover ? Brand.SURFACE_HOVER : Brand.SURFACE);
			if (m == selected) Brand.outline(cx, cy, cardW, CARD_H, Brand.RED);
			// Lampe: leuchtet bernsteinfarben, wenn "bestromt"
			Brand.rect(cx + cardW - 8, cy + 5, 4, 4, m.isEnabled() ? Brand.AMBER : Brand.OFF);
			Brand.text(fontRendererObj, fontRendererObj.trimStringToWidth(m.name(), cardW - 16), cx + 6, cy + 5, Brand.TEXT, false);
			hotspots.add(new Hotspot(cx, cy, cardW, CARD_H, new Runnable() {
				@Override
				public void run() {
					selected = m;
				}
			}));

			int pillX = cx + 6;
			int pillY = cy + CARD_H - 14;
			Brand.pill(fontRendererObj, pillX, pillY, m.isEnabled(), inside(mx, my, pillX, pillY, 26, 11));
			hotspots.add(new Hotspot(pillX, pillY, 26, 11, new Runnable() {
				@Override
				public void run() {
					m.toggle();
					selected = m;
				}
			}));
			if (m.isHud()) {
				Brand.text(fontRendererObj, "HUD", cx + cardW - 6 - fontRendererObj.getStringWidth("HUD"), pillY + 2, Brand.TEXT_DIM, false);
			}
		}
	}

	private void renderSettings(int mx, int my, int x, int y, int w, int h) {
		Brand.rect(x, y, w, h, Brand.SURFACE);
		Brand.rect(x, y, w, 1, selected.isEnabled() ? Brand.AMBER : Brand.RED);
		int ix = x + 7;
		int iw = w - 14;
		int cy = y + 7;

		Brand.text(fontRendererObj, EnumChatFormatting.BOLD + selected.name(), ix, cy, Brand.TEXT, false);
		int pillX = x + w - 7 - 26;
		Brand.pill(fontRendererObj, pillX, cy - 1, selected.isEnabled(), inside(mx, my, pillX, cy - 1, 26, 11));
		final Module sel = selected;
		hotspots.add(new Hotspot(pillX, cy - 1, 26, 11, new Runnable() {
			@Override
			public void run() {
				sel.toggle();
			}
		}));
		cy += 13;

		for (String line : fontRendererObj.listFormattedStringToWidth(selected.description(), iw)) {
			Brand.text(fontRendererObj, line, ix, cy, Brand.TEXT_DIM, false);
			cy += 10;
		}
		cy += 4;
		Brand.rect(ix, cy, iw, 1, Brand.BORDER);
		cy += 5;

		if (selected.settings().isEmpty()) {
			Brand.text(fontRendererObj, "Keine Einstellungen", ix, cy + 3, Brand.TEXT_DIM, false);
		}
		for (Setting s : selected.settings()) {
			if (cy + ROW_H > y + h - (selected.isHud() ? 18 : 0)) break;
			Brand.text(fontRendererObj, s.label(), ix, cy + 3, Brand.TEXT, false);
			int right = ix + iw;
			if (s instanceof BoolSetting) {
				final BoolSetting b = (BoolSetting) s;
				int bx = right - 26;
				Brand.pill(fontRendererObj, bx, cy + 1, b.get(), inside(mx, my, bx, cy + 1, 26, 11));
				hotspots.add(new Hotspot(bx, cy + 1, 26, 11, new Runnable() {
					@Override
					public void run() {
						b.toggle();
					}
				}));
			} else if (s instanceof NumberSetting) {
				numberControl(mx, my, right, cy + 1, (NumberSetting) s);
			} else if (s instanceof ColorSetting) {
				final ColorSetting c = (ColorSetting) s;
				int sx = right - 24;
				boolean hover = inside(mx, my, sx, cy + 1, 24, 11);
				Brand.rect(sx, cy + 1, 24, 11, c.argb());
				Brand.outline(sx - 1, cy, 26, 13, hover ? Brand.AMBER : Brand.BORDER);
				hotspots.add(new Hotspot(sx, cy + 1, 24, 11, new Runnable() {
					@Override
					public void run() {
						c.cycle();
					}
				}));
			}
			cy += ROW_H;
		}

		if (selected instanceof HudModule) {
			final HudModule hud = (HudModule) selected;
			String label = "Position zurücksetzen";
			int bw = fontRendererObj.getStringWidth(label) + 10;
			int by = y + h - 17;
			button(mx, my, ix, by, bw, 13, label, false, new Runnable() {
				@Override
				public void run() {
					hud.resetPosition();
				}
			});
		}
	}

	private void numberControl(int mx, int my, int right, int y, final NumberSetting n) {
		int box = 11;
		int valueW = 30;
		int plusX = right - box;
		int valueX = plusX - valueW;
		int minusX = valueX - box;
		smallButton(mx, my, minusX, y, box, "-", new Runnable() {
			@Override
			public void run() {
				n.nudge(-1);
			}
		});
		Brand.centered(fontRendererObj, n.display(), valueX + valueW / 2, y + 2, Brand.AMBER, true);
		smallButton(mx, my, plusX, y, box, "+", new Runnable() {
			@Override
			public void run() {
				n.nudge(1);
			}
		});
	}

	private void smallButton(int mx, int my, int x, int y, int size, String label, Runnable action) {
		boolean hover = inside(mx, my, x, y, size, size);
		Brand.rect(x, y, size, size, hover ? Brand.RED : Brand.OFF);
		Brand.centered(fontRendererObj, label, x + size / 2 + 1, y + 2, Brand.TEXT, true);
		hotspots.add(new Hotspot(x, y, size, size, action));
	}

	private void button(int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
		Brand.button(fontRendererObj, x, y, w, h, label, primary, inside(mx, my, x, y, w, h));
		hotspots.add(new Hotspot(x, y, w, h, action));
	}

	private static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
		if (button == 0) {
			// Rückwärts: später gezeichnete (kleinere) Flächen liegen oben.
			for (int i = hotspots.size() - 1; i >= 0; i--) {
				Hotspot h = hotspots.get(i);
				if (h.contains(mouseX, mouseY)) {
					mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
					h.action.run();
					return;
				}
			}
		}
		super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) throws IOException {
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

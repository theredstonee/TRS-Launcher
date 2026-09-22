package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.module.ChoiceSetting;
import dev.theredstonee.trsclient.core.module.ColorSetting;
import dev.theredstonee.trsclient.core.module.NumberSetting;
import dev.theredstonee.trsclient.core.module.Setting;
import dev.theredstonee.trsclient.core.module.TextSetting;
import net.minecraft.client.gui.Font;

import java.util.List;

/** Zeichnet Einstellungszeilen (Schalter, Zahl mit −/+, Farbe, Auswahl) und registriert ihre Klickflächen. */
public final class SettingRows {
	public static final int ROW_H = 15;

	private SettingRows() {
	}

	/**
	 * Zeichnet die Zeilen ab {@code y} in der Breite {@code w}, höchstens bis {@code maxY}.
	 * @return y nach der letzten gezeichneten Zeile
	 */
	public static int draw(Gfx g, Font font, List<Setting> settings, int x, int y, int w, int maxY,
			double mx, double my, Hotspots hot) {
		int right = x + w;
		for (Setting s : settings) {
			if (y + ROW_H > maxY) break;
			// Bei Textzeilen ist rechts ein breites Eingabefeld – den Namen davor abschneiden.
			int labelWidth = s instanceof TextSetting ? w - Math.min(140, w / 2) - 6 : w;
			g.text(font, Gfx.clip(font, s.label(), labelWidth), x, y + 3, Brand.TEXT, false);
			if (s instanceof BoolSetting) {
				BoolSetting b = (BoolSetting) s;
				int bx = right - 26;
				Brand.pill(g, font, bx, y + 1, b.get(), inside(mx, my, bx, y + 1, 26, 11));
				hot.add(bx, y + 1, 26, 11, b::toggle);
			} else if (s instanceof NumberSetting) {
				NumberSetting n = (NumberSetting) s;
				number(g, font, right, y + 1, n, mx, my, hot);
			} else if (s instanceof ColorSetting) {
				ColorSetting c = (ColorSetting) s;
				int sx = right - 24;
				boolean hover = inside(mx, my, sx, y + 1, 24, 11);
				g.fill(sx, y + 1, sx + 24, y + 12, c.argb());
				Brand.outline(g, sx - 1, y, 26, 13, hover ? Brand.AMBER : Brand.BORDER);
				hot.add(sx, y + 1, 24, 11, c::cycle);
			} else if (s instanceof TextSetting) {
				TextSetting t = (TextSetting) s;
				int tw = Math.min(140, w / 2);
				int tx = right - tw;
				boolean hover = inside(mx, my, tx, y + 1, tw, 11);
				g.fill(tx, y + 1, tx + tw, y + 12, hover ? Brand.SURFACE_HOVER : Brand.BG);
				Brand.outline(g, tx, y + 1, tw, 11, hover ? Brand.AMBER : Brand.BORDER);
				g.text(font, Gfx.clip(font, t.display(), tw - 6), tx + 3, y + 3,
						t.isEmpty() ? Brand.TEXT_DIM : Brand.TEXT, false);
				// Klick öffnet den Eingabedialog; danach geht es zum aufrufenden Bildschirm zurück.
				hot.add(tx, y + 1, tw, 11, () -> openEditor(t));
			} else if (s instanceof ChoiceSetting) {
				ChoiceSetting<?> c = (ChoiceSetting<?>) s;
				String label = "‹ " + c.display() + " ›";
				int cw = font.width(label) + 8;
				int cx = right - cw;
				boolean hover = inside(mx, my, cx, y + 1, cw, 11);
				g.fill(cx, y + 1, cx + cw, y + 12, hover ? Brand.SURFACE_HOVER : Brand.BG);
				Brand.outline(g, cx, y + 1, cw, 11, hover ? Brand.AMBER : Brand.BORDER);
				g.text(font, label, cx + 4, y + 3, Brand.AMBER, false);
				hot.add(cx, y + 1, cw, 11, () -> c.cycle(1));
				hot.add(cx, y + 1, cw, 11, 1, () -> c.cycle(-1));
			}
			y += ROW_H;
		}
		return y;
	}

	/** Öffnet den Eingabedialog einer Text-Einstellung (zurück geht es zum aktuellen Bildschirm). */
	private static void openEditor(TextSetting setting) {
		dev.theredstonee.trsclient.compat.Mc.setScreen(
				new dev.theredstonee.trsclient.screen.TextInputScreen(
						dev.theredstonee.trsclient.compat.Mc.screen(), setting));
	}

	private static void number(Gfx g, Font font, int right, int y, NumberSetting n, double mx, double my, Hotspots hot) {
		int box = 11;
		int valueW = 30;
		int plusX = right - box;
		int valueX = plusX - valueW;
		int minusX = valueX - box;
		small(g, font, minusX, y, box, "-", mx, my);
		hot.add(minusX, y, box, box, () -> n.nudge(-1));
		g.centered(font, n.display(), valueX + valueW / 2, y + 2, Brand.AMBER);
		small(g, font, plusX, y, box, "+", mx, my);
		hot.add(plusX, y, box, box, () -> n.nudge(1));
	}

	private static void small(Gfx g, Font font, int x, int y, int size, String label, double mx, double my) {
		boolean hover = inside(mx, my, x, y, size, size);
		g.fill(x, y, x + size, y + size, hover ? Brand.RED : Brand.OFF);
		g.centered(font, label, x + size / 2 + 1, y + 2, Brand.TEXT);
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}

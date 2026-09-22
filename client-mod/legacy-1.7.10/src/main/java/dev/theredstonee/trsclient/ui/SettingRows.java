package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.module.ChoiceSetting;
import dev.theredstonee.trsclient.core.module.ColorSetting;
import dev.theredstonee.trsclient.core.module.NumberSetting;
import dev.theredstonee.trsclient.core.module.Setting;
import net.minecraft.client.gui.FontRenderer;

import java.util.List;

/** Zeichnet Einstellungszeilen (Schalter, Zahl mit −/+, Farbe, Auswahl) und registriert ihre Klickflächen. */
public final class SettingRows {
	public static final int ROW_H = 15;

	private SettingRows() {
	}

	/**
	 * Zeichnet die Zeilen ab {@code y} in der Breite {@code w}, höchstens bis {@code maxY}.
	 * Einstellungen, die es in dieser Minecraft-Version nicht gibt, werden übersprungen.
	 * @return y nach der letzten gezeichneten Zeile
	 */
	public static int draw(FontRenderer font, List<Setting> settings, int x, int y, int w, int maxY,
			int mx, int my, Hotspots hot) {
		int right = x + w;
		for (Setting s : settings) {
			if (!TrsClient.supported(s)) continue;
			if (y + ROW_H > maxY) break;
			Brand.text(font, s.label(), x, y + 3, Brand.TEXT, false);
			if (s instanceof BoolSetting) {
				final BoolSetting b = (BoolSetting) s;
				int bx = right - 26;
				Brand.pill(font, bx, y + 1, b.get(), inside(mx, my, bx, y + 1, 26, 11));
				hot.add(bx, y + 1, 26, 11, b::toggle);
			} else if (s instanceof NumberSetting) {
				number(font, right, y + 1, (NumberSetting) s, mx, my, hot);
			} else if (s instanceof ColorSetting) {
				final ColorSetting c = (ColorSetting) s;
				int sx = right - 24;
				boolean hover = inside(mx, my, sx, y + 1, 24, 11);
				Brand.rect(sx, y + 1, 24, 11, c.argb());
				Brand.outline(sx - 1, y, 26, 13, hover ? Brand.AMBER : Brand.BORDER);
				hot.add(sx, y + 1, 24, 11, c::cycle);
			} else if (s instanceof ChoiceSetting) {
				final ChoiceSetting<?> c = (ChoiceSetting<?>) s;
				String label = "< " + c.display() + " >";
				int cw = font.getStringWidth(label) + 8;
				int cx = right - cw;
				boolean hover = inside(mx, my, cx, y + 1, cw, 11);
				Brand.rect(cx, y + 1, cw, 11, hover ? Brand.SURFACE_HOVER : Brand.BG);
				Brand.outline(cx, y + 1, cw, 11, hover ? Brand.AMBER : Brand.BORDER);
				Brand.text(font, label, cx + 4, y + 3, Brand.AMBER, false);
				hot.add(cx, y + 1, cw, 11, () -> c.cycle(1));
				hot.add(cx, y + 1, cw, 11, 1, () -> c.cycle(-1));
			}
			y += ROW_H;
		}
		return y;
	}

	private static void number(FontRenderer font, int right, int y, final NumberSetting n, int mx, int my, Hotspots hot) {
		int box = 11;
		int valueW = 30;
		int plusX = right - box;
		int valueX = plusX - valueW;
		int minusX = valueX - box;
		small(font, minusX, y, box, "-", mx, my);
		hot.add(minusX, y, box, box, () -> n.nudge(-1));
		Brand.centered(font, n.display(), valueX + valueW / 2, y + 2, Brand.AMBER, true);
		small(font, plusX, y, box, "+", mx, my);
		hot.add(plusX, y, box, box, () -> n.nudge(1));
	}

	private static void small(FontRenderer font, int x, int y, int size, String label, int mx, int my) {
		boolean hover = inside(mx, my, x, y, size, size);
		Brand.rect(x, y, size, size, hover ? Brand.RED : Brand.OFF);
		Brand.centered(font, label, x + size / 2 + 1, y + 2, Brand.TEXT, true);
	}

	public static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}

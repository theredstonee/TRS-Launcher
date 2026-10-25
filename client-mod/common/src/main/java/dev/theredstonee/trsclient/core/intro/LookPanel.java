package dev.theredstonee.trsclient.core.intro;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

/**
 * „Aussehen“ auf den Seiten „Startbildschirm“ und „Menü-Stil“ des TRS-Menüs: Design (Redstone | Winternacht | Fuchsbau) mit
 * Vorschau, Farbschema und Akzent – dieselbe Wahl wie im ersten Schritt der Einführung.
 */
public final class LookPanel implements ModulePanel {
	private final TrsModules modules;

	public LookPanel(TrsModules modules) {
		this.modules = modules;
	}

	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mouseX, int mouseY, final Runnable click) {
		Theme t = Theme.get();
		c.text(c.clip(I18n.tr("look.design"), w), x, y, t.text, false);
		int ny = Paint.paragraph(c, I18n.tr("look.design." + t.designName + ".hint"), x, y + 11, Math.min(w, 360), 10, t.textDim);
		ny += 3;
		ny += LookPicker.drawDesigns(c, hits, modules, x, ny, Math.min(w, LookPicker.DESIGNS_WIDTH), mouseX, mouseY, click) + 8;

		c.text(c.clip(I18n.tr("intro.theme"), w), x, ny, t.text, false);
		ny += 11;
		int gap = 4;
		int bh = 16;
		int tw = Math.min(80, (Math.min(w, 304) - gap * 2) / 3);
		for (int i = 0; i < LookPicker.THEMES.length; i++) {
			final String theme = LookPicker.THEMES[i];
			int bx = x + i * (tw + gap);
			boolean sel = theme.equals(t.themeName);
			boolean hover = inside(mouseX, mouseY, bx, ny, tw, bh);
			String label = c.clip(I18n.tr("intro.theme." + theme), tw - 6);
			if (sel) {
				Redstone.lampButton(c, bx, ny, tw, bh, label, 1f, 0f);
			} else {
				Redstone.stone(c, bx, ny, tw, bh, hover ? t.surfaceHover : t.surfaceHigh, hover ? ColorMath.lerp(t.border, t.textDim, 0.5f) : t.border);
				c.text(label, bx + (tw - c.textWidth(label)) / 2, ny + (bh - 8) / 2, t.text, false);
			}
			hits.add(bx, ny, tw, bh, new Runnable() {
				@Override
				public void run() {
					if (click != null) click.run();
					LookPicker.choose(modules, theme, null, null, null);
				}
			});
		}
		int ax = x + LookPicker.THEMES.length * (tw + gap) + 8;
		int size = 16;
		if (ax + LookPicker.ACCENTS.length * (size + 4) > x + w) {
			ny += bh + gap + 2;
			ax = x;
		}
		for (int i = 0; i < LookPicker.ACCENTS.length; i++) {
			final String accent = LookPicker.ACCENTS[i];
			int sx = ax + i * (size + 4);
			boolean sel = accent.equals(t.accentName);
			boolean hover = inside(mouseX, mouseY, sx, ny, size, size);
			int color = Theme.of("dark", accent, null).accent;
			if (sel) Redstone.glow(c, sx, ny, size, size, color, 0.9f);
			Redstone.block(c, sx, ny, size, size, sel ? 0xFFFFFFFF : (hover ? t.textDim : t.border));
			c.fill(sx + 2, ny + 2, sx + size - 2, ny + size - 2, color);
			hits.add(sx, ny, size, size, new Runnable() {
				@Override
				public void run() {
					if (click != null) click.run();
					LookPicker.choose(modules, null, accent, null, null);
				}
			});
		}
		ny += bh + 6;
		ny = Paint.paragraph(c, I18n.tr("look.design.note"), x, ny, Math.min(w, 360), 10, t.textDim);
		return ny + 6;
	}

	private static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}

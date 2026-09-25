package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.BoolSetting;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

/**
 * Zusatzbereich auf den Seiten „Minimap“ und „Weltkarte“: der EINE Fair-Play-Schalter (gilt für beide Karten),
 * ein Hinweis, wenn der Server Fair Play verlangt, und auf der Weltkarten-Seite die Tastenbelegung.
 */
public final class MapPanel implements ModulePanel {
	private static final int LINE = 10;
	private final MapEngine engine;
	private final boolean showSwitch;

	/**
	 * @param showSwitch Schalter zeigen (auf der Minimap-Seite steht er schon als normale Einstellung)
	 */
	public MapPanel(MapEngine engine, boolean showSwitch) {
		this.engine = engine;
		this.showSwitch = showSwitch;
	}

	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mx, int my, final Runnable click) {
		Theme t = Theme.get();
		int textW = Math.min(w, 420);
		final BoolSetting fair = engine.modules().minimapFairPlay;
		FairPlay fp = engine.fairPlay();
		if (showSwitch) {
			int h = 30;
			boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
			Redstone.stone(c, x, y, w, h, hov ? t.surfaceHover : t.surface, t.border);
			Redstone.toggle(c, x + w - 34, y + 9, 26, 12, fair.get() ? 1f : 0f, hov);
			c.text(I18n.tr("map.panel.fairPlay"), x + 6, y + 5, fair.get() ? (0xFF000000 | (t.dustOn & 0xFFFFFF)) : t.text, false);
			Paint.textClipped(c, I18n.tr(fair.get() ? "map.panel.fairPlayOn" : "map.panel.fairPlayOff"), x + 6, y + 16,
					w - 46, t.textDim, false);
			hits.add(x, y, w, h, () -> {
				click.run();
				fair.toggle();
			});
			y += h + 4;
		}
		if (fp.serverFair()) {
			y = Paint.paragraph(c, I18n.tr("map.panel.server"), x, y, textW, LINE, 0xFF000000 | (t.dustOn & 0xFFFFFF)) + 2;
		}
		if (fp.serverNoMinimap()) {
			y = Paint.paragraph(c, I18n.tr("map.panel.noMinimap"), x, y, textW, LINE, 0xFF000000 | (t.dustOn & 0xFFFFFF)) + 2;
		}
		return y;
	}
}

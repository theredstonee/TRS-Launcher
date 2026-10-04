package dev.theredstonee.trsclient.core.connect;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

/** Seite „Schnell verbinden“: was beim letzten Verbinden gemessen wurde – keine Versprechen. */
public final class FastConnectPanel implements ModulePanel {
	private static final int LINE = 10;

	private FastConnectPanel() {
	}

	public static void register(Module module) {
		if (module != null && ModulePanel.Registry.of(module) == null) ModulePanel.Registry.set(module, new FastConnectPanel());
	}

	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mx, int my, Runnable click) {
		Theme t = Theme.get();
		int textW = Math.min(w, 420);
		int good = ColorMath.lerp(t.text, t.dustOn, 0.5f);
		if (FastConnect.pausedReason() != null) {
			y = Paint.paragraph(c, I18n.tr("fastConnect.panel.paused"), x, y, textW, LINE, t.textDim);
		}
		FastConnect.Last last = FastConnect.LAST;
		if (last.host == null) {
			y = Paint.paragraph(c, I18n.tr("fastConnect.panel.none"), x, y, textW, LINE, t.textDim);
		} else if (last.ok) {
			y = Paint.paragraph(c, I18n.tr("fastConnect.panel.last", last.host, last.ip, last.v6 ? "IPv6" : "IPv4", last.ms,
					last.attempts, last.addresses), x, y, textW, LINE, good);
		} else {
			y = Paint.paragraph(c, I18n.tr("fastConnect.panel.failed", last.addresses, last.host), x, y, textW, LINE, t.textDim);
		}
		y = Paint.paragraph(c, I18n.tr("fastConnect.panel.cache", FastConnect.CACHE.size()), x, y, textW, LINE, t.textDim);
		if (FastSwitch.lastMs() >= 0) {
			y = Paint.paragraph(c, I18n.tr("fastConnect.panel.switch", FastSwitch.lastMs()), x, y, textW, LINE, t.textDim);
		}
		String preload = ServerPacks.LAST_PRELOAD;
		if (preload != null) y = Paint.paragraph(c, I18n.tr("fastConnect.panel.preload", preload), x, y, textW, LINE, t.textDim);
		y = Paint.paragraph(c, I18n.tr("fastConnect.panel.honest"), x, y + 2, textW, LINE, t.textDim);
		return y + 6;
	}
}

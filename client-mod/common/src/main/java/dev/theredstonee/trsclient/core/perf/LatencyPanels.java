package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.net.NetBoost;
import dev.theredstonee.trsclient.core.net.NetStats;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

import java.util.Locale;

/**
 * Live-Werte auf den Seiten „Netzwerk-Optimierung“ und „Niedrige Eingabeverzögerung“: was gerade wirkt und was es
 * gemessen bringt – damit niemand sich auf Versprechen verlassen muss.
 */
public final class LatencyPanels {
	private static final int LINE = 10;

	private LatencyPanels() {
	}

	/** Für Loader ohne Leistungs-Steuerung (Forge 1.7.10, 1.13.2): nur die Netzwerk-Seite. */
	public static void registerNetOnly(Module netOptimize) {
		if (ModulePanel.Registry.of(netOptimize) == null) ModulePanel.Registry.set(netOptimize, new Net(null, netOptimize));
	}

	static String fmt(double v) {
		return String.format(Locale.ROOT, v >= 10 ? "%.0f" : "%.1f", v);
	}

	/** Seite „Netzwerk-Optimierung“. */
	static final class Net implements ModulePanel {
		private final Performance perf;
		private final Module module;

		Net(Performance perf, Module module) {
			this.perf = perf;
			this.module = module;
		}

		@Override
		public int draw(Canvas c, Hits hits, int x, int y, int w, int mx, int my, Runnable click) {
			Theme t = Theme.get();
			int textW = Math.min(w, 420);
			int start = y;
			if (perf != null) y = new PerfPanel.Notice(perf, module).draw(c, hits, x, y, w, mx, my, click);
			int good = ColorMath.lerp(t.text, t.dustOn, 0.5f);
			NetStats s = NetBoost.STATS;
			if (NetBoost.channel() == null) {
				y = Paint.paragraph(c, I18n.tr("net.panel.offline"), x, y, textW, LINE, t.textDim);
			} else {
				String crypt = s.fastDecrypt ? I18n.tr("net.panel.crypt.trs") : s.encrypted ? I18n.tr("net.panel.crypt.vanilla")
						: I18n.tr("net.panel.crypt.none");
				y = Paint.paragraph(c, crypt, x, y, textW, LINE, s.fastDecrypt ? good : t.textDim);
				boolean lean = NetBoost.platform() != null && NetBoost.platform().inflateAlreadyLean();
				String zip = s.fastInflate ? I18n.tr("net.panel.zip.trs")
						: s.compressed ? I18n.tr(lean ? "net.panel.zip.lean" : "net.panel.zip.vanilla") : I18n.tr("net.panel.zip.none");
				y = Paint.paragraph(c, zip, x, y, textW, LINE, s.fastInflate ? good : t.textDim);
				if (s.noDelay >= 0) {
					String nd = s.noDelay == 2 ? I18n.tr("net.panel.nodelay.trs") : s.noDelay == 1 ? I18n.tr("net.panel.nodelay.on")
							: I18n.tr("net.panel.nodelay.off");
					y = Paint.paragraph(c, nd, x, y, textW, LINE, s.noDelay == 2 ? good : t.textDim);
				}
			}
			if (s.decryptCalls.get() > 0) {
				y = Paint.paragraph(c, I18n.tr("net.panel.decrypted", fmt(s.decryptBytes.get() / 1e6),
						fmt(NetStats.megabytesPerSecond(s.decryptBytes, s.decryptNanos))), x, y, textW, LINE, t.textDim);
			}
			if (s.inflateCalls.get() > 0) {
				y = Paint.paragraph(c, I18n.tr("net.panel.inflated", s.inflateCalls.get(),
						fmt(NetStats.microsPerCall(s.inflateNanos, s.inflateCalls))), x, y, textW, LINE, t.textDim);
			}
			y = Paint.paragraph(c, I18n.tr("net.panel.honest"), x, y + 2, textW, LINE, t.textDim);
			return y == start ? y : y + 6;
		}
	}

	/** Seite „Niedrige Eingabeverzögerung“. */
	static final class Input implements ModulePanel {
		private final Performance perf;
		private final Module module;
		private long windowAt;
		private LowLatency.Stats shown;

		Input(Performance perf, Module module) {
			this.perf = perf;
			this.module = module;
		}

		@Override
		public int draw(Canvas c, Hits hits, int x, int y, int w, int mx, int my, Runnable click) {
			Theme t = Theme.get();
			int textW = Math.min(w, 420);
			int start = y;
			y = new PerfPanel.Notice(perf, module).draw(c, hits, x, y, w, mx, my, click);
			LowLatency ll = LowLatency.current();
			long now = System.currentTimeMillis();
			if (ll == null || !ll.available()) {
				y = Paint.paragraph(c, I18n.tr("latency.panel.unavailable"), x, y, textW, LINE, t.textDim);
			} else {
				ll.watch(now);
				// Werte der letzten zwei Sekunden zeigen.
				if (now - windowAt > 2000 || shown == null) {
					LowLatency.Stats st = ll.stats();
					if (st.frames > 0) shown = st;
					ll.resetStats();
					windowAt = now;
				}
				if (shown != null && shown.frames > 0) {
					y = Paint.paragraph(c, I18n.tr("latency.panel.queue", fmt(shown.avgQueued), fmt(shown.avgWaitMillis)), x, y,
							textW, LINE, t.text);
					if (shown.avgInputAgeMillis >= 0) {
						y = Paint.paragraph(c, I18n.tr("latency.panel.age", fmt(shown.avgInputAgeMillis)), x, y, textW, LINE, t.text);
					}
				} else {
					y = Paint.paragraph(c, I18n.tr("latency.panel.measuring"), x, y, textW, LINE, t.textDim);
				}
			}
			if (ll != null) {
				String raw = ll.rawMouse < 0 ? I18n.tr("latency.panel.raw.none") : ll.rawMouse == 1 ? I18n.tr("latency.panel.raw.on")
						: I18n.tr("latency.panel.raw.off");
				y = Paint.paragraph(c, raw, x, y, textW, LINE, ll.rawMouse == 0 ? t.dustOn : t.textDim);
				if (!ll.latePollSupported) {
					y = Paint.paragraph(c, I18n.tr("latency.panel.latePoll.none"), x, y, textW, LINE, t.textDim);
				}
			}
			y = Paint.paragraph(c, I18n.tr("latency.panel.honest"), x, y + 2, textW, LINE, t.textDim);
			return y == start ? y : y + 6;
		}
	}
}

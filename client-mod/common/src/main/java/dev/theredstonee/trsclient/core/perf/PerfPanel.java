package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menu.ModulePanel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Einstellungsseite „FPS-Boost“: Bildrate jetzt und vorher/nachher, die drei Boost-Stufen mit
 * „Rückgängig“, der Leistungs-Check mit „Beheben“ je Fund und die erkannten Leistungs-Mods.
 */
final class PerfPanel implements ModulePanel {
	private static final int LINE = 10;
	private static final int BUTTON_H = 17;

	private final Performance perf;

	PerfPanel(Performance perf) {
		this.perf = perf;
	}

	@Override
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mx, int my, final Runnable click) {
		Theme t = Theme.get();
		final long now = System.currentTimeMillis();
		int textW = Math.min(w, 420);

		if (!perf.modules().fpsBoost.isEnabled()) {
			y = Paint.paragraph(c, I18n.tr("perf.master.off"), x, y, textW, LINE, t.dustOn) + 4;
		}

		// --- Bildrate ---
		FpsMeter meter = perf.meter();
		double current = meter.recent(now, 1500);
		Redstone.stone(c, x, y, w, 30, t.surface, t.border);
		String fpsText = current > 0 ? I18n.tr("perf.fps.now", Math.round(current)) : I18n.tr("perf.fps.unknown");
		c.text(fpsText, x + 6, y + 5, t.text, false);
		String compare;
		int compareColor = t.textDim;
		switch (meter.compareState()) {
			case MEASURING:
				compare = I18n.tr("perf.fps.measuring", meter.reason());
				int barW = Math.max(20, w - 12);
				c.fill(x + 6, y + 24, x + 6 + barW, y + 25, t.dustOff);
				c.fill(x + 6, y + 24, x + 6 + Math.round(barW * meter.progress(now)), y + 25, t.dustOn);
				break;
			case DONE:
				double pct = meter.percent();
				String sign = pct >= 0 ? "+" : "";
				compare = I18n.tr("perf.fps.result", Math.round(meter.before()), Math.round(meter.after()),
						sign + String.format(Locale.ROOT, "%.0f", pct) + " %", meter.reason());
				compareColor = pct >= 3 ? t.dustOn : t.textDim;
				break;
			default:
				compare = I18n.tr("perf.fps.none");
				break;
		}
		Paint.textClipped(c, compare, x + 6, y + 15, w - 12, compareColor, false);
		y += 36;

		// --- Stufen ---
		c.text(I18n.tr("perf.boost.title"), x, y + 4, t.text, false);
		int bx = x + Math.min(c.textWidth(I18n.tr("perf.boost.title")) + 8, w / 3);
		int by = y;
		for (final BoostPreset p : BoostPreset.values()) {
			String label = p.label();
			int bw = c.textWidth(label) + 16;
			if (bx + bw > x + w) {
				bx = x;
				by += BUTTON_H + 4;
			}
			boolean hover = inside(mx, my, bx, by, bw, BUTTON_H);
			Paint.button(c, bx, by, bw, BUTTON_H, label, p == BoostPreset.HIGH, hover);
			hits.add(bx, by, bw, BUTTON_H, new Runnable() {
				@Override
				public void run() {
					click.run();
					perf.applyPreset(p, System.currentTimeMillis());
				}
			});
			bx += bw + 4;
		}
		if (perf.canUndo()) {
			String undo = I18n.tr("perf.boost.undo");
			int uw = c.textWidth(undo) + 16;
			if (bx + uw > x + w) {
				bx = x;
				by += BUTTON_H + 4;
			}
			boolean hover = inside(mx, my, bx, by, uw, BUTTON_H);
			Paint.button(c, bx, by, uw, BUTTON_H, undo, false, hover);
			hits.add(bx, by, uw, BUTTON_H, new Runnable() {
				@Override
				public void run() {
					click.run();
					perf.undo(System.currentTimeMillis());
				}
			});
		}
		y = by + BUTTON_H + 4;
		y = Paint.paragraph(c, I18n.tr("perf.boost.hint"), x, y, textW, LINE, t.textDim) + 2;
		String message = perf.message(now);
		if (message != null) y = Paint.paragraph(c, message, x, y, textW, LINE, t.dustOn) + 2;

		// --- Leistungs-Check ---
		y += 4;
		Redstone.dustH(c, x, x + w, y, t.dustOff, 0f);
		y += 6;
		final List<PerfCheck.Finding> findings = perf.findings(now);
		int fixable = 0;
		for (PerfCheck.Finding f : findings) if (f.fixable()) fixable++;
		c.text(I18n.tr("perf.check.title"), x, y + 4, t.text, false);
		if (fixable >= 2) {
			String all = I18n.tr("perf.check.fixAll");
			int aw = c.textWidth(all) + 16;
			boolean hover = inside(mx, my, x + w - aw, y, aw, BUTTON_H);
			Paint.button(c, x + w - aw, y, aw, BUTTON_H, all, true, hover);
			hits.add(x + w - aw, y, aw, BUTTON_H, new Runnable() {
				@Override
				public void run() {
					click.run();
					perf.applyFixes(PerfCheck.allFixes(findings), System.currentTimeMillis());
				}
			});
		}
		y += BUTTON_H + 3;
		if (perf.game() == null) {
			y = Paint.paragraph(c, I18n.tr("perf.check.unavailable"), x, y, textW, LINE, t.textDim) + 2;
		} else if (findings.isEmpty()) {
			y = Paint.paragraph(c, I18n.tr("perf.check.none"), x, y, textW, LINE, t.textDim) + 2;
		}
		String fixLabel = I18n.tr("perf.check.fix");
		int fixW = c.textWidth(fixLabel) + 14;
		for (final PerfCheck.Finding f : findings) {
			int tw = w - 12 - (f.fixable() ? fixW + 6 : 0);
			List<String> lines = Paint.wrap(c, f.text, tw);
			int rowH = Math.max(BUTTON_H, lines.size() * LINE + 2);
			float lit = f.level == PerfCheck.Level.HIGH ? 1f : (f.level == PerfCheck.Level.MEDIUM ? 0.5f : 0f);
			Redstone.pip(c, x, y + 3, 6, lit);
			int ly = y + (lines.size() == 1 ? 4 : 1);
			for (String line : lines) {
				c.text(line, x + 10, ly, f.level == PerfCheck.Level.TIP ? t.textDim : t.text, false);
				ly += LINE;
			}
			if (f.fixable()) {
				int fx = x + w - fixW;
				boolean hover = inside(mx, my, fx, y, fixW, BUTTON_H);
				Paint.button(c, fx, y, fixW, BUTTON_H, fixLabel, false, hover);
				hits.add(fx, y, fixW, BUTTON_H, new Runnable() {
					@Override
					public void run() {
						click.run();
						perf.applyFixes(f.fix, System.currentTimeMillis());
					}
				});
			}
			y += rowH + 3;
		}

		// --- Leistungs-Mods ---
		y += 3;
		Redstone.dustH(c, x, x + w, y, t.dustOff, 0f);
		y += 6;
		c.text(I18n.tr("perf.mods.title"), x, y, t.text, false);
		y += LINE + 3;
		List<PerfMod> mods = perf.compat().detected();
		if (mods.isEmpty()) {
			y = Paint.paragraph(c, I18n.tr("perf.mods.none"), x, y, textW, LINE, t.textDim);
		}
		for (PerfMod mod : mods) {
			List<String> taken = new ArrayList<String>();
			for (PerfFeature f : mod.takesOver()) {
				if (perf.compat().owner(f) == mod && perf.compat().supported(f)) taken.add(f.label());
			}
			String line = taken.isEmpty() ? I18n.tr("perf.mods.detected", mod.displayName())
					: I18n.tr("perf.mods.takesOver", mod.displayName(), join(taken));
			Redstone.pip(c, x, y + 1, 6, taken.isEmpty() ? 0f : 1f);
			y = Paint.paragraph(c, line, x + 10, y, textW - 10, LINE, taken.isEmpty() ? t.textDim : t.text) + 2;
		}
		return y + 6;
	}

	static String join(List<String> parts) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(parts.get(i));
		}
		return sb.toString();
	}

	static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/**
	 * Hinweise auf den Seiten der anderen Leistungs-Module: was ein Mod übernimmt, was es in
	 * dieser Version nicht gibt, ob der Hauptschalter aus ist; bei Dynamische FPS der Zustand.
	 */
	static final class Notice implements ModulePanel {
		private final Performance perf;
		private final Module module;

		Notice(Performance perf, Module module) {
			this.perf = perf;
			this.module = module;
		}

		@Override
		public int draw(Canvas c, Hits hits, int x, int y, int w, int mx, int my, Runnable click) {
			Theme t = Theme.get();
			int textW = Math.min(w, 420);
			int start = y;
			if (!perf.modules().fpsBoost.isEnabled()) {
				y = Paint.paragraph(c, I18n.tr("perf.master.offShort"), x, y, textW, LINE, t.dustOn);
			}
			Map<PerfMod, List<String>> owned = new LinkedHashMap<PerfMod, List<String>>();
			List<String> unsupported = new ArrayList<String>();
			for (PerfFeature f : PerfFeature.values()) {
				if (perf.moduleOf(f) != module) continue;
				if (!perf.compat().supported(f)) {
					unsupported.add(f.label());
					continue;
				}
				PerfMod owner = perf.compat().owner(f);
				if (owner == null) continue;
				List<String> list = owned.get(owner);
				if (list == null) owned.put(owner, list = new ArrayList<String>());
				list.add(f.label());
			}
			for (Map.Entry<PerfMod, List<String>> e : owned.entrySet()) {
				y = Paint.paragraph(c, I18n.tr("perf.notice.owner", e.getKey().displayName(), join(e.getValue())), x, y, textW,
						LINE, ColorMath.lerp(t.text, t.dustOn, 0.5f));
			}
			if (!unsupported.isEmpty()) {
				y = Paint.paragraph(c, I18n.tr("perf.notice.unsupported", join(unsupported)), x, y, textW, LINE, t.textDim);
			}
			if (module == perf.modules().dynamicFps && perf.active(PerfFeature.DYNAMIC_FPS)) {
				String state = I18n.tr("perf.state." + perf.dynamicFps().state().name().toLowerCase(Locale.ROOT));
				int limit = perf.currentLimit();
				String text = limit > 0 ? I18n.tr("perf.state.limited", state, limit) : I18n.tr("perf.state.free", state);
				y = Paint.paragraph(c, text, x, y, textW, LINE, t.textDim);
			}
			return y == start ? y : y + 6;
		}
	}
}

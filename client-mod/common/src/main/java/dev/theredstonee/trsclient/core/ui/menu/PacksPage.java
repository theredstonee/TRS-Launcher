package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.ModulePacks;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.util.List;

/**
 * Auswahl der Modul-Pakete ({@link ModulePacks}): Karten je Paket, Vorschau der Änderungen, Schalter
 * „HUD-Vorlage übernehmen“ und – im TRS-Menü – „Anwenden“/„Rückgängig“. Wird vom Menü und von der Einführung
 * genutzt (Immediate Mode wie das restliche Menü).
 */
public final class PacksPage {
	private final TrsModules modules;
	private final ModulePacks.Support support;
	private final Runnable click;
	private ModulePacks.Pack selected;
	private boolean withHud = true;
	private TrsConfig undo;
	private String applied;
	private String message;
	private long messageUntil;

	public PacksPage(TrsModules modules, ModulePacks.Support support, Runnable click) {
		this.modules = modules;
		this.support = support;
		this.click = click;
	}

	public ModulePacks.Pack selected() {
		return selected;
	}

	public void select(ModulePacks.Pack pack) {
		selected = pack;
	}

	public boolean withHud() {
		return withHud;
	}

	/** Wendet das gewählte Paket an (merkt sich den Stand davor für „Rückgängig“). */
	public boolean applySelected() {
		if (selected == null) return false;
		undo = ModulePacks.apply(modules, selected, withHud, support);
		applied = selected.id;
		say(I18n.tr("packs.mod.applied", selected.name()));
		return true;
	}

	public boolean canUndo() {
		return undo != null;
	}

	public void undo() {
		if (undo == null) return;
		ModulePacks.undo(modules, undo);
		undo = null;
		applied = null;
		say(I18n.tr("packs.mod.undone"));
	}

	private void say(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 5000;
	}

	/**
	 * Zeichnet die Seite in (x, y, w, h).
	 *
	 * @param buttons „Anwenden“/„Rückgängig“ zeigen (TRS-Menü); in der Einführung wendet der Aufrufer an
	 */
	public void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my, boolean buttons, Runnable save) {
		Theme t = Theme.get();
		List<ModulePacks.Pack> packs = ModulePacks.all();
		boolean wide = w >= 380;
		int cardsW = wide ? Math.min(220, Math.round(w * 0.48f)) : w;
		int columns = wide ? 1 : 2;
		int cardH = wide ? 34 : 30;
		int gap = 5;
		int cardW = columns == 1 ? cardsW : (cardsW - gap) / 2;
		for (int i = 0; i < packs.size(); i++) {
			final ModulePacks.Pack p = packs.get(i);
			int cx = x + (i % columns) * (cardW + gap);
			int cy = y + (i / columns) * (cardH + gap);
			card(c, hits, p, cx, cy, cardW, cardH, mx, my);
		}
		int rows = (packs.size() + columns - 1) / columns;
		int px = wide ? x + cardsW + 10 : x;
		int py = wide ? y : y + rows * (cardH + gap) + 4;
		int pw = wide ? w - cardsW - 10 : w;
		int bottom = y + h - (buttons ? 22 : 0);
		preview(c, hits, px, py, pw, bottom - py, mx, my);

		if (!buttons) return;
		int by = y + h - 17;
		String applyLabel = I18n.tr("packs.mod.apply");
		int aw = Math.min(w / 2, c.textWidth(applyLabel) + 24);
		boolean canApply = selected != null;
		boolean aHover = canApply && inside(mx, my, x, by, aw, 17);
		Paint.button(c, x, by, aw, 17, applyLabel, canApply, aHover);
		if (canApply) {
			hits.add(x, by, aw, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					applySelected();
					if (save != null) save.run();
				}
			});
		}
		if (undo != null) {
			String undoLabel = I18n.tr("packs.mod.undo");
			int uw = Math.min(w / 2 - 6, c.textWidth(undoLabel) + 20);
			int ux = x + aw + 6;
			Paint.button(c, ux, by, uw, 17, undoLabel, false, inside(mx, my, ux, by, uw, 17));
			hits.add(ux, by, uw, 17, new Runnable() {
				@Override
				public void run() {
					click.run();
					undo();
					if (save != null) save.run();
				}
			});
		}
		if (message != null && System.currentTimeMillis() < messageUntil) {
			int tx = x + aw + (undo != null ? 6 + Math.min(w / 2 - 6, c.textWidth(I18n.tr("packs.mod.undo")) + 20) : 0) + 8;
			Paint.textClipped(c, message, tx, by + 5, x + w - tx, t.dustOn, false);
		}
	}

	private void card(Canvas c, Hits hits, final ModulePacks.Pack p, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		boolean sel = p == selected;
		boolean hover = inside(mx, my, x, y, w, h);
		boolean isApplied = p.id.equals(applied);
		int fill = sel ? ColorMath.lerp(t.surfaceHover, t.accent, 0.12f) : (hover ? t.surfaceHover : t.surface);
		int edge = sel ? ColorMath.lerp(t.border, t.accent, 0.85f) : (hover ? ColorMath.lerp(t.border, t.textDim, 0.5f) : t.border);
		if (sel) Redstone.glow(c, x, y, w, h, t.glow, 0.7f);
		Redstone.stone(c, x, y, w, h, fill, edge);
		int iconY = y + (h - 14) / 2;
		Redstone.iconWell(c, x + 5, iconY, 1, p.icon, sel ? t.dustOn : t.textDim, sel ? 1f : 0f);
		int tx = x + 24;
		int tw = w - 30;
		String name = p.name();
		if (isApplied) {
			dev.theredstonee.trsclient.core.ui.Icons.draw(c, "check", x + w - 12, y + 4, 1, t.dustOn);
			tw -= 10;
		}
		Paint.textClipped(c, name, tx, y + (h >= 34 ? 6 : 4), tw, sel ? t.text : ColorMath.lerp(t.text, t.textDim, 0.2f), false);
		Paint.textClipped(c, p.description(), tx, y + (h >= 34 ? 18 : 16), tw, t.textDim, false);
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				click.run();
				selected = selected == p ? null : p;
			}
		});
	}

	private void preview(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		Redstone.well(c, x, y, w, Math.max(20, h), t.border);
		int ix = x + 6;
		int iw = w - 12;
		int ry = y + 6;
		int limit = y + h - 22;
		if (selected == null) {
			Paint.paragraph(c, I18n.tr("packs.mod.choose"), ix, ry, iw, 10, t.textDim);
			return;
		}
		ModulePacks.Preview p = ModulePacks.preview(modules, selected, withHud, support);
		Paint.textClipped(c, I18n.tr("packs.mod.previewTitle", selected.name()), ix, ry, iw, t.text, false);
		ry += 13;
		if (p.isEmpty()) {
			ry = Paint.paragraph(c, I18n.tr("packs.mod.nothing"), ix, ry, iw, 10, t.textDim) + 2;
		} else {
			if (!p.enable.isEmpty() && ry < limit) ry = list(c, "+ " + I18n.tr("packs.mod.enable"), p.enable, ix, ry, iw, limit, t.dustOn);
			if (!p.disable.isEmpty() && ry < limit) ry = list(c, "- " + I18n.tr("packs.mod.disable"), p.disable, ix, ry, iw, limit, t.textDim);
			if (!p.moved.isEmpty() && ry < limit) {
				ry = Paint.paragraph(c, I18n.tr("packs.mod.moved", p.moved.size()), ix, ry, iw, 10, t.text) + 2;
			}
		}
		// Schalter „HUD-Vorlage übernehmen“ unten im Feld.
		int ty = y + h - 18;
		if (ty > ry - 4) {
			Paint.textClipped(c, I18n.tr("packs.mod.withHud"), ix, ty + 4, iw - 34, t.text, false);
			int tx = x + w - 6 - 24;
			boolean hover = inside(mx, my, tx - 3, ty, 30, 16);
			Paint.toggle(c, tx, ty + 2, 24, 12, withHud ? 1f : 0f, hover);
			hits.add(tx - 3, ty, 30, 16, new Runnable() {
				@Override
				public void run() {
					click.run();
					withHud = !withHud;
				}
			});
		}
	}

	private static int list(Canvas c, String title, List<Module> modules, int x, int y, int w, int limit, int color) {
		Theme t = Theme.get();
		c.text(c.clip(title, w), x, y, color, false);
		y += 10;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < modules.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(modules.get(i).name());
		}
		List<String> lines = Paint.wrap(c, sb.toString(), w - 6);
		for (int i = 0; i < lines.size() && y + 9 <= limit; i++) {
			c.text(lines.get(i), x + 6, y, t.textDim, false);
			y += 10;
		}
		return y + 3;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}

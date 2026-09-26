package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Kontextmenü (Nachricht, Freund, Unterhaltung): Einträge mit Symbol, Trennlinien, optional eine Reaktionsleiste
 * oben. Wird am Ankerpunkt geöffnet und in den Bildschirm geschoben.
 */
public final class PopupMenu {
	static final int ROW_H = 14;
	static final int REACT_H = 16;

	/** Ein Eintrag. */
	public static final class Entry {
		final String icon;
		final String label;
		final Runnable action;
		final boolean danger;
		final boolean separator;

		Entry(String icon, String label, Runnable action, boolean danger, boolean separator) {
			this.icon = icon;
			this.label = label;
			this.action = action;
			this.danger = danger;
			this.separator = separator;
		}
	}

	/** Reaktion auswählen. */
	public interface ReactionPick {
		void pick(String emoji);
	}

	private final List<Entry> entries = new ArrayList<Entry>();
	private final int anchorX;
	private final int anchorY;
	private ReactionPick reactions;
	private List<String> mine = new ArrayList<String>();
	private final int[] rect = new int[4];

	public PopupMenu(int x, int y) {
		this.anchorX = x;
		this.anchorY = y;
	}

	public PopupMenu add(String icon, String label, Runnable action) {
		entries.add(new Entry(icon, label, action, false, false));
		return this;
	}

	public PopupMenu danger(String icon, String label, Runnable action) {
		entries.add(new Entry(icon, label, action, true, false));
		return this;
	}

	public PopupMenu separator() {
		if (!entries.isEmpty() && !entries.get(entries.size() - 1).separator) entries.add(new Entry(null, null, null, false, true));
		return this;
	}

	/** Reaktionsleiste oben (die eigenen sind hervorgehoben). */
	public PopupMenu reactions(ReactionPick pick, List<String> own) {
		this.reactions = pick;
		this.mine = own == null ? new ArrayList<String>() : own;
		return this;
	}

	public boolean isEmpty() {
		return entries.isEmpty() && reactions == null;
	}

	/** Liegt der Punkt im Menü? */
	public boolean contains(double mx, double my) {
		return Kit.inside(mx, my, rect[0], rect[1], rect[2], rect[3]);
	}

	/** Zeichnet das Menü und legt die Klickflächen an; {@code close} läuft nach jeder Auswahl. */
	public void draw(Canvas c, Kit kit, int screenW, int screenH, int mx, int my, final Runnable close) {
		Theme t = Theme.get();
		// Nachlaufende Trennlinie weg.
		while (!entries.isEmpty() && entries.get(entries.size() - 1).separator) entries.remove(entries.size() - 1);
		int w = 60;
		for (Entry e : entries) if (!e.separator) w = Math.max(w, c.textWidth(e.label) + 30);
		int reactW = reactions == null ? 0 : Chat.REACTIONS.length * 12 + 6;
		w = Math.max(w, Math.min(reactW, screenW - 8));
		int h = 4;
		if (reactions != null) h += REACT_H + 3;
		for (Entry e : entries) h += e.separator ? 5 : ROW_H;
		int x = Math.max(4, Math.min(anchorX, screenW - w - 4));
		int y = anchorY + h > screenH - 4 ? Math.max(4, anchorY - h) : anchorY;
		rect[0] = x;
		rect[1] = y;
		rect[2] = w;
		rect[3] = h;
		c.push();
		c.raise(200f);
		Paint.shadow(c, x, y, w, h, 2, 0.6f);
		Redstone.stone(c, x, y, w, h, t.surfaceHigh, t.border);
		// Klicks im Menü, die keinen Eintrag treffen, nicht durchreichen.
		kit.quiet(x, y, w, h, new Runnable() {
			@Override
			public void run() {
			}
		});
		int cy = y + 2;
		if (reactions != null) {
			int per = Math.max(1, (w - 6) / 12);
			int rx = x + 3;
			for (int i = 0; i < Chat.REACTIONS.length && i < per; i++) {
				final String id = Chat.REACTIONS[i];
				int bx = rx + i * 12;
				boolean hov = Kit.inside(mx, my, bx, cy, 12, REACT_H);
				boolean own = mine.contains(id);
				if (own) Redstone.block(c, bx, cy, 12, REACT_H, ColorMath.withAlpha(t.accent, 90));
				else if (hov) c.fill(bx, cy, bx + 12, cy + REACT_H, t.surfaceHover);
				Icons.draw(c, Reactions.icon(id), bx + 2, cy + 4, 1, Reactions.color(id));
				kit.area(bx, cy, 12, REACT_H, new Runnable() {
					@Override
					public void run() {
						reactions.pick(id);
						close.run();
					}
				});
			}
			cy += REACT_H + 1;
			c.fill(x + 3, cy, x + w - 3, cy + 1, t.border);
			cy += 2;
		}
		for (final Entry e : entries) {
			if (e.separator) {
				c.fill(x + 3, cy + 2, x + w - 3, cy + 3, t.border);
				cy += 5;
				continue;
			}
			boolean hov = Kit.inside(mx, my, x + 1, cy, w - 2, ROW_H);
			if (hov) c.fill(x + 1, cy, x + w - 1, cy + ROW_H, t.surfaceHover);
			int color = e.danger ? t.dustOn : t.text;
			if (e.icon != null) Icons.draw(c, e.icon, x + 5, cy + 3, 1, color);
			Paint.textClipped(c, e.label, x + 18, cy + 3, w - 22, color, false);
			kit.area(x + 1, cy, w - 2, ROW_H, new Runnable() {
				@Override
				public void run() {
					close.run();
					if (e.action != null) e.action.run();
				}
			});
			cy += ROW_H;
		}
		c.pop();
	}
}

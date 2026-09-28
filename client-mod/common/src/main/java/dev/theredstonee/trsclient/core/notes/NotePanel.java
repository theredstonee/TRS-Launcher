package dev.theredstonee.trsclient.core.notes;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.TextWidth;
import dev.theredstonee.trsclient.core.ui.social.ChatLayout;

import java.util.List;

/**
 * HUD-Anzeige „Angeheftete Notiz“: Titel (mit Checklisten-Stand) und die ersten Zeilen der in dieser Welt
 * angehefteten Notiz, Kästchen der Checkliste gezeichnet. Gezeichnet über {@link Canvas} bei (0, 0), unskaliert
 * (Position/Größe setzt der HUD-Manager des Baums). Umbrüche werden nur bei Änderung neu berechnet.
 */
public final class NotePanel {
	private static final int PAD_X = 5;
	private static final int PAD_Y = 4;
	private static final int LINE_H = 10;
	private static final int TITLE_GAP = 3;
	private static final int CHECK_ON = 0xFF55D86A;

	private final TrsModules modules;
	private final HudModule module;

	// Zwischenspeicher der Anzeige.
	private Note cachedNote;
	private long cachedUpdated = -1;
	private int cachedWidth = -1;
	private int cachedLines = -1;
	private int cachedGeneration = -1;
	private boolean cachedPreview;
	private String title;
	private String progress;
	private String text;
	private List<NoteLayout.Row> rows;
	private boolean more;

	public NotePanel(TrsModules modules) {
		this.modules = modules;
		this.module = modules.notes.pinnedNote;
	}

	/** Angeheftete Notiz der aktuellen Welt oder null. */
	private static Note pinned() {
		Notes n = Notes.get();
		if (n == null) return null;
		NoteWorld w = n.currentWorld();
		return w == null ? null : n.store().pinned(n.store().existing(w.key()));
	}

	/** Im Spiel sichtbar? */
	public boolean visible() {
		return modules.notes.notes.isEnabled() && pinned() != null;
	}

	private int contentWidth() {
		return (int) Math.round(modules.notes.hudWidth.get());
	}

	private int maxLines() {
		return Math.max(1, (int) Math.round(modules.notes.hudLines.get()));
	}

	private Note sampleNote;
	private int sampleGeneration = -1;

	/** Beispiel für den HUD-Editor, solange keine Notiz angeheftet ist (je Sprache einmal gebaut). */
	private Note sample() {
		if (sampleNote != null && sampleGeneration == I18n.generation()) return sampleNote;
		sampleGeneration = I18n.generation();
		Note n = new Note();
		sampleNote = n;
		n.id = "0000000000000000";
		n.title = I18n.tr("notes.hud.sampleTitle");
		n.text = I18n.tr("notes.hud.sampleText");
		n.updated = 1;
		return n;
	}

	private void refresh(final TextWidth tw, boolean preview) {
		Note n = pinned();
		if (n == null && preview) n = sample();
		int width = contentWidth();
		int lines = maxLines();
		int gen = I18n.generation();
		if (n == cachedNote && n != null && n.updated == cachedUpdated && width == cachedWidth && lines == cachedLines
				&& gen == cachedGeneration && preview == cachedPreview) {
			return;
		}
		cachedNote = n;
		cachedUpdated = n == null ? -1 : n.updated;
		cachedWidth = width;
		cachedLines = lines;
		cachedGeneration = gen;
		cachedPreview = preview;
		if (n == null) {
			rows = null;
			return;
		}
		String t = n.displayTitle();
		title = t == null ? I18n.tr("notes.untitled") : t;
		int[] p = NoteText.progress(n.text);
		progress = p[1] > 0 ? p[0] + "/" + p[1] : null;
		text = n.text == null ? "" : n.text;
		// Ohne eigenen Titel steht die erste Zeile schon oben – nicht doppelt zeigen.
		List<NoteLayout.Row> all = NoteLayout.layout(text, width, new ChatLayout.Measure() {
			@Override
			public int width(String s) {
				return tw.width(s);
			}
		});
		if ((n.title == null || n.title.trim().isEmpty()) && t != null) {
			while (!all.isEmpty() && all.get(0).line == 0) all.remove(0);
		}
		while (!all.isEmpty() && all.get(all.size() - 1).start == all.get(all.size() - 1).end
				&& all.get(all.size() - 1).check < 0) {
			all.remove(all.size() - 1);
		}
		more = all.size() > lines;
		rows = more ? all.subList(0, lines) : all;
	}

	public int width(TextWidth tw, boolean preview) {
		refresh(tw, preview);
		return contentWidth() + PAD_X * 2;
	}

	public int height(TextWidth tw, boolean preview) {
		refresh(tw, preview);
		int n = rows == null ? 0 : rows.size();
		return PAD_Y * 2 + 8 + (n > 0 ? TITLE_GAP + 1 + n * LINE_H : 0);
	}

	public void draw(Canvas c, TextWidth tw, boolean preview) {
		refresh(tw, preview);
		if (rows == null) return;
		int w = width(tw, preview);
		int h = height(tw, preview);
		int bg = module.backgroundArgb();
		if (bg != 0) c.fill(0, 0, w, h, bg);
		int color = module.textColor.argb() | 0xFF000000;
		int dim = ColorMath.withAlpha(color, 150);
		boolean shadow = module.shadow();
		int cw = contentWidth();
		int progressW = progress == null ? 0 : tw.width(progress) + 4;
		c.text(c.clip(title, cw - progressW), PAD_X, PAD_Y, color, shadow);
		if (progress != null) c.text(progress, PAD_X + cw - tw.width(progress), PAD_Y, dim, shadow);
		if (rows.isEmpty()) return;
		int y = PAD_Y + 8 + TITLE_GAP;
		c.fill(PAD_X, y - 1, PAD_X + cw, y, ColorMath.withAlpha(color, 60));
		y += 1;
		for (int i = 0; i < rows.size(); i++) {
			NoteLayout.Row r = rows.get(i);
			if (r.check >= 0) box(c, PAD_X, y, r.check == 1, color);
			String s = text.substring(r.start, r.end);
			if (more && i == rows.size() - 1) s = c.clip(s + " …", cw - r.indent);
			c.text(s, PAD_X + r.indent, y, r.checkedItem ? dim : color, shadow);
			y += LINE_H;
		}
	}

	/** Kästchen 7×7 (abgehakt: grüner Haken). */
	public static void box(Canvas c, int x, int y, boolean checked, int color) {
		int edge = ColorMath.withAlpha(color, 200);
		c.fill(x, y, x + 7, y + 1, edge);
		c.fill(x, y + 6, x + 7, y + 7, edge);
		c.fill(x, y, x + 1, y + 7, edge);
		c.fill(x + 6, y, x + 7, y + 7, edge);
		if (checked) {
			c.fill(x + 2, y + 3, x + 3, y + 5, CHECK_ON);
			c.fill(x + 3, y + 4, x + 4, y + 5, CHECK_ON);
			c.fill(x + 4, y + 2, x + 5, y + 4, CHECK_ON);
			c.fill(x + 5, y + 1, x + 6, y + 3, CHECK_ON);
		}
	}
}

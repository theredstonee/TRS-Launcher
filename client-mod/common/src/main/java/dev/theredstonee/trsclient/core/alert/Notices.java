package dev.theredstonee.trsclient.core.alert;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.TextWidth;

import java.util.ArrayList;
import java.util.List;

/**
 * Kurze, dezente Hinweise im HUD (Warnungen, Warteschlange, Hintergrund-Meldungen): höchstens drei gleichzeitig, jeder
 * wenige Sekunden sichtbar, gleicher Schlüssel = gleicher Hinweis (Zeit beginnt neu). Zeichnet versionsunabhängig über
 * {@link Canvas}; nur aus dem Spiel-/Render-Thread benutzen.
 */
public final class Notices {
	public enum Level {
		INFO(0xFF4BE38A, "info"),
		WARN(0xFFFFB02E, "info"),
		DANGER(0xFFFF4D4D, "heart");

		public final int color;
		public final String icon;

		Level(int color, String icon) {
			this.color = color;
			this.icon = icon;
		}
	}

	public static final int MAX_VISIBLE = 3;
	public static final long DURATION_MS = 4000;
	private static final long FADE_MS = 400;
	private static final int PAD = 4;
	private static final int ROW_H = 16;
	private static final int GAP = 2;

	/** Ein Hinweis. */
	public static final class Notice {
		public final String key;
		public final Level level;
		public String text;
		public String icon;
		long shownAt;

		Notice(String key, Level level, String text, String icon, long now) {
			this.key = key;
			this.level = level;
			this.text = text;
			this.icon = icon;
			this.shownAt = now;
		}
	}

	private final List<Notice> items = new ArrayList<Notice>();

	/** Neuer Hinweis (oder vorhandener mit gleichem Schlüssel aufgefrischt). */
	public void show(String key, Level level, String text, String icon, long now) {
		if (text == null || text.isEmpty()) return;
		for (int i = 0; i < items.size(); i++) {
			Notice n = items.get(i);
			if (key != null && key.equals(n.key)) {
				n.text = text;
				n.icon = icon;
				n.shownAt = now;
				return;
			}
		}
		items.add(new Notice(key, level, text, icon == null ? level.icon : icon, now));
		while (items.size() > MAX_VISIBLE) items.remove(0);
	}

	/** Abgelaufene Hinweise entfernen. */
	public void prune(long now) {
		for (int i = items.size() - 1; i >= 0; i--) {
			if (now - items.get(i).shownAt > DURATION_MS) items.remove(i);
		}
	}

	public boolean isEmpty() {
		return items.isEmpty();
	}

	public List<Notice> items() {
		return items;
	}

	public void clear() {
		items.clear();
	}

	/** Deckkraft 0–1 (am Ende ausblenden). */
	static float alpha(Notice n, long now) {
		long left = DURATION_MS - (now - n.shownAt);
		if (left <= 0) return 0f;
		return left >= FADE_MS ? 1f : left / (float) FADE_MS;
	}

	// --- Zeichnen ---

	/** Breite der Liste (unskaliert). {@code preview} = Beispielhinweis für den HUD-Editor. */
	public int width(TextWidth font, boolean preview, String previewText) {
		int w = 0;
		if (preview && items.isEmpty()) return rowWidth(font, previewText);
		for (int i = 0; i < items.size(); i++) w = Math.max(w, rowWidth(font, items.get(i).text));
		return w;
	}

	public int height(boolean preview) {
		int n = preview && items.isEmpty() ? 1 : items.size();
		return n <= 0 ? 0 : n * ROW_H + (n - 1) * GAP;
	}

	private static int rowWidth(TextWidth font, String text) {
		return PAD + 8 + 4 + font.width(text) + PAD + 2;
	}

	/** Zeichnet die Hinweise untereinander ab (0, 0), rechtsbündig ausgerichtet an {@code width}. */
	public void draw(Canvas c, TextWidth font, long now, boolean preview, String previewText, int bgAlpha, boolean shadow) {
		if (preview && items.isEmpty()) {
			row(c, font, previewText, Level.WARN, Level.WARN.icon, 1f, 0, bgAlpha, shadow);
			return;
		}
		int y = 0;
		for (int i = 0; i < items.size(); i++) {
			Notice n = items.get(i);
			float a = alpha(n, now);
			if (a > 0.02f) row(c, font, n.text, n.level, n.icon, a, y, bgAlpha, shadow);
			y += ROW_H + GAP;
		}
	}

	private static void row(Canvas c, TextWidth font, String text, Level level, String icon, float a, int y, int bgAlpha,
			boolean shadow) {
		int w = rowWidth(font, text);
		int bg = ((int) (Math.max(40, bgAlpha) * a) << 24) | 0x101014;
		Paint.roundRect(c, 0, y, w, ROW_H, 3, bg);
		c.fill(0, y + 2, 2, y + ROW_H - 2, fade(level.color, a));
		Icons.draw(c, icon, PAD + 1, y + 4, 1, fade(level.color, a));
		c.text(text, PAD + 12, y + 4, fade(0xFFFFFFFF, a), shadow);
	}

	private static int fade(int argb, float a) {
		int alpha = (int) (((argb >>> 24) & 0xFF) * a);
		return (Math.max(4, alpha) << 24) | (argb & 0xFFFFFF);
	}
}

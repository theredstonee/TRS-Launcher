package dev.theredstonee.trsclient.core.panorama;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

/**
 * Toast der Panorama-Aufnahme (oben rechts im HUD): „wird gespeichert …“, „Panorama gespeichert“ mit Ordnername und
 * dem Hinweis „[Taste] Ordner öffnen“ bzw. Fehler. Zeichnet über {@link Canvas}, gleich in allen Versionen.
 */
public final class PanoramaToast {
	public static final int WIDTH = 168;
	public static final int MAX_WIDTH = 260;

	/** Momentaufnahme für das Zeichnen. */
	public static final class Data {
		public final String title;
		public final String text;
		public final boolean error;
		public final boolean saving;
		/** Ergebnis da: „Ordner öffnen“ anbieten. */
		public final boolean openable;
		public final long age;
		/** Verbleibende Zeit (ms) oder -1 = unbegrenzt. */
		public final long left;

		public Data(String title, String text, boolean error, boolean saving, boolean openable, long age, long left) {
			this.title = title;
			this.text = text;
			this.error = error;
			this.saving = saving;
			this.openable = openable;
			this.age = age;
			this.left = left;
		}
	}

	private PanoramaToast() {
	}

	/**
	 * @param keyLabel Name der Panorama-Taste oder null/leer (unbelegt → Hinweis aufs Menü)
	 */
	public static void draw(Canvas c, int screenW, int screenH, Data d, String keyLabel) {
		if (d == null) return;
		Theme t = Theme.get();
		boolean hint = d.openable;
		// Breite nach Inhalt (mindestens WIDTH, höchstens MAX_WIDTH bzw. Bildschirm).
		String hintText = hint ? I18n.tr(keyLabel != null && !keyLabel.isEmpty() ? "panorama.openFolder" : "panorama.openInMenu") : "";
		int need = Math.max(c.textWidth(d.title), c.textWidth(d.text == null ? "" : d.text));
		if (hint) need = Math.max(need, c.textWidth(hintText) + (keyLabel != null && !keyLabel.isEmpty() ? Math.min(60, c.textWidth(keyLabel) + 8) + 4 : 11));
		int w = Math.min(Math.max(WIDTH, need + 34), Math.min(MAX_WIDTH, screenW - 8));
		int h = hint ? 44 : 32;
		float in = Math.min(1f, d.age / 180f);
		float out = d.left < 0 ? 1f : Math.min(1f, d.left / 250f);
		float vis = Math.min(in, out);
		int x = screenW - w - 4 + Math.round((1 - vis) * (w + 8));
		int y = 4;
		c.push();
		c.raise(200f);
		Paint.shadow(c, x, y, w, h, 3, 0.35f * vis);
		Redstone.stone(c, x, y, w, h, t.surface, d.error ? t.dustOn : ColorMath.lerp(t.border, t.accent, 0.6f));
		Redstone.iconWell(c, x + 5, y + 6, 1, d.error ? "info" : "image", d.error ? t.dustOn : t.accent, d.saving ? 0.6f : 0.25f);
		int tx = x + 26;
		int tw = w - 30;
		Paint.textClipped(c, d.title, tx, y + 6, tw, t.text, false);
		Paint.textClipped(c, d.text == null ? "" : d.text, tx, y + 18, tw, d.error ? t.dustOn : t.textDim, false);
		if (d.saving) {
			// Unbestimmter Fortschritt: laufender Staub-Balken.
			int bx = tx, bw = tw - 2, by = y + h - 5;
			c.fill(bx, by, bx + bw, by + 2, t.dustOff);
			int seg = Math.max(12, bw / 4);
			int pos = (int) ((d.age / 6) % (bw + seg)) - seg;
			c.fill(bx + Math.max(0, pos), by, bx + Math.min(bw, pos + seg), by + 2, t.dustOn);
		}
		if (hint) {
			int hy = y + 30;
			if (keyLabel != null && !keyLabel.isEmpty()) {
				int kw = Redstone.keycap(c, tx, hy - 1, keyLabel, 60);
				Paint.textClipped(c, I18n.tr("panorama.openFolder"), tx + kw + 4, hy + 1, tw - kw - 4, t.text, false);
			} else {
				Icons.draw(c, "folder", tx, hy + 1, 1, t.textDim);
				Paint.textClipped(c, I18n.tr("panorama.openInMenu"), tx + 11, hy + 1, tw - 11, t.textDim, false);
			}
		}
		c.flush();
		c.pop();
	}
}

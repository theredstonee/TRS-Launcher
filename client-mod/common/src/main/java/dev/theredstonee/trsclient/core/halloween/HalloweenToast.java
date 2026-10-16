package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.halloween.Sprites;

import java.util.ArrayList;
import java.util.List;

/**
 * Halloween-Toast (oben rechts, wie {@code #toast} im Entwurf): Kürbis bzw. Kürbisjäger-Kürbis, Titel in Mondsilber,
 * Text gedämpft. Wird in TRS-Bildschirmen ({@code UiScreen}) und im HUD gezeichnet – Kürbisse findet man in Menüs.
 * Höchstens drei gleichzeitig; Aufrufe aus jedem Thread.
 */
public final class HalloweenToast {
	public static final String PUMPKIN = "pumpkin";
	public static final String HUNTER = "pk8";
	public static final String GHOST = "ghost";
	static final long SHOW_MS = 3200L;
	static final long IN_MS = 220L;
	static final long OUT_MS = 300L;
	static final int WIDTH = 190;
	static final int MAX_WIDTH = 240;
	static final int HEIGHT = 34;

	private static final class Toast {
		final String sprite;
		final String title;
		final String text;
		long start;

		Toast(String sprite, String title, String text) {
			this.sprite = sprite;
			this.title = title;
			this.text = text;
		}
	}

	private static final List<Toast> QUEUE = new ArrayList<Toast>();

	private HalloweenToast() {
	}

	/** Neuer Toast (Sprite-ID aus {@link Sprites}). */
	public static void show(String sprite, String title, String text) {
		synchronized (QUEUE) {
			if (QUEUE.size() >= 6) QUEUE.remove(0);
			QUEUE.add(new Toast(sprite, title, text));
		}
	}

	/** Gibt es gerade etwas zu zeichnen? */
	public static boolean any() {
		synchronized (QUEUE) {
			return !QUEUE.isEmpty();
		}
	}

	/** Zeichnet die sichtbaren Toasts in der rechten oberen Ecke. */
	public static void draw(Canvas c, int width, int height) {
		List<Toast> visible;
		long now = System.currentTimeMillis();
		synchronized (QUEUE) {
			if (QUEUE.isEmpty()) return;
			visible = new ArrayList<Toast>();
			for (int i = 0; i < QUEUE.size() && visible.size() < 3; i++) {
				Toast t = QUEUE.get(i);
				if (t.start == 0) t.start = now;
				if (now - t.start > SHOW_MS + OUT_MS) {
					QUEUE.remove(i--);
					continue;
				}
				visible.add(t);
			}
		}
		int y = 6;
		c.push();
		c.raise(400f);
		for (Toast t : visible) {
			long age = now - t.start;
			float in = Math.min(1f, age / (float) IN_MS);
			float out = age > SHOW_MS ? 1f - Math.min(1f, (age - SHOW_MS) / (float) OUT_MS) : 1f;
			float vis = Math.min(in, out);
			int w = Math.min(Math.max(WIDTH, Math.max(c.textWidth(t.title), c.textWidth(t.text == null ? "" : t.text)) + 36),
					Math.min(MAX_WIDTH, Math.max(120, width - 12)));
			java.util.List<String> lines = t.text == null ? java.util.Collections.<String>emptyList()
					: dev.theredstonee.trsclient.core.ui.Paint.wrap(c, t.text, w - 32);
			int h = lines.size() > 1 ? HEIGHT + 10 : HEIGHT;
			int x = width - w - 6 + Math.round((1 - vis) * (w + 10));
			panel(c, x, y, w, h, vis);
			Sprites.Sprite s = Sprites.get(t.sprite);
			if (s != null) Sprites.draw(c, s, x + 7, y + (h - s.h) / 2, 1, vis);
			int tx = x + 26;
			int tw = w - 32;
			c.text(c.clip(t.title, tw), tx, y + 6, ColorMath.fade(0xFFF3F5FB, Math.max(0.05f, vis)), false);
			for (int i = 0; i < lines.size() && i < 2; i++) {
				c.text(c.clip(lines.get(i), tw), tx, y + 19 + i * 10, ColorMath.fade(0xFF9A94B4, Math.max(0.05f, vis)), false);
			}
			y += h + 4;
		}
		c.pop();
	}

	/** Dunkle Fläche mit Silberkante und violettem Schein (Halloween-Karte). */
	static void panel(Canvas c, int x, int y, int w, int h, float alpha) {
		c.fill(x - 2, y - 2, x + w + 2, y + h + 2, ColorMath.fade(0x30B9A2EA, alpha));
		c.fill(x, y, x + w, y + h, ColorMath.fade(0xF0110D1D, alpha));
		int edge = ColorMath.fade(0xFF5B5280, alpha);
		c.fill(x, y, x + w, y + 1, ColorMath.fade(0xFFCFD5E3, alpha * 0.8f));
		c.fill(x, y + h - 1, x + w, y + h, edge);
		c.fill(x, y, x + 1, y + h, edge);
		c.fill(x + w - 1, y, x + w, y + h, edge);
	}

	/** Nur Tests: alles weg. */
	static void clear() {
		synchronized (QUEUE) {
			QUEUE.clear();
		}
	}
}

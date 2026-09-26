package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;

/**
 * Modales Fenster über dem Sozial-Bildschirm (Rückfrage, Melden, Gruppe, Bild groß, Einstellungen). Zeichnet einen
 * Schleier, das Fenster in der Mitte und darin {@link #body}.
 */
public abstract class Dialog {
	protected boolean closed;

	public boolean closed() {
		return closed;
	}

	public void close() {
		closed = true;
	}

	/** Fenstergröße {w, h} für diese Bildschirmgröße. */
	protected abstract int[] size(int screenW, int screenH);

	protected abstract String title();

	/** Inhalt: (x, y, w, h) unter dem Titel. */
	protected abstract void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my);

	public void draw(Canvas c, Kit kit, int screenW, int screenH, int mx, int my) {
		Theme t = Theme.get();
		c.push();
		c.raise(250f);
		c.fill(0, 0, screenW, screenH, ColorMath.withAlpha(0xFF000000, 150));
		// Klicks neben dem Fenster schließen nicht versehentlich etwas darunter.
		kit.quiet(0, 0, screenW, screenH, new Runnable() {
			@Override
			public void run() {
			}
		});
		int[] s = size(screenW, screenH);
		int w = Math.min(s[0], screenW - 12);
		int h = Math.min(s[1], screenH - 12);
		int x = (screenW - w) / 2;
		int y = (screenH - h) / 2;
		Paint.shadow(c, x, y, w, h, 2, 0.7f);
		Redstone.window(c, x, y, w, h);
		String title = title();
		int top = y + 6;
		if (title != null) {
			c.fill(x + 1, y + 2, x + w - 1, y + 20, t.surfaceHigh);
			Paint.textClipped(c, title, x + 8, y + 7, w - 30, t.text, false);
			kit.icon(c, x + w - 18, y + 4, 14, "close", false, mx, my, new Runnable() {
				@Override
				public void run() {
					close();
				}
			});
			top = y + 24;
		}
		body(c, kit, x + 8, top, w - 16, y + h - 8 - top, mx, my);
		c.pop();
	}

	/** Taste; true = verbraucht. Esc schließt standardmäßig. */
	public boolean keyPressed(UiKey key, String paste) {
		if (key == UiKey.ESCAPE) {
			close();
			return true;
		}
		return true;
	}

	public boolean charTyped(char ch) {
		return true;
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		return true;
	}

	/** Klick irgendwo (vor den Klickflächen) – z. B. um Eingabefelder zu entfokussieren. */
	public void mouseClicked(double mx, double my) {
	}
}

package dev.theredstonee.trsclient.core.ui.menus;

import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.UiScreen;

import java.util.List;

/**
 * Gemeinsamer Rahmen der TRS-Fenster (Freunde, Clips &amp; Bilder, Server-Info): abgedunkelter Hintergrund,
 * Redstone-Fenster mit TRS-Schriftzug, Titel und Schließen-Knopf, Ein-/Ausblenden. Unterklassen zeichnen nur
 * den Inhalt in {@link #content}.
 */
public abstract class WindowUi extends UiScreen {
	protected static final int HEADER_H = 30;
	protected static final int PAD = 10;

	/** Fensterrechteck des letzten Bilds {x, y, w, h}. */
	protected final int[] window = new int[4];

	protected abstract String title();

	protected abstract void playClick();

	/** Fenstergröße für diese Bildschirmgröße {w, h}. */
	protected int[] size(int width, int height) {
		int pw = Math.min(width - 16, Math.max(Math.min(320, width - 16), Math.min(480, Math.round(width * 0.72f))));
		int ph = Math.min(height - 16, Math.max(Math.min(230, height - 16), Math.min(380, Math.round(height * 0.86f))));
		return new int[]{pw, ph};
	}

	/** Inhalt unter der Kopfzeile: (x, y, w, h). */
	protected abstract void content(Canvas c, int x, int y, int w, int h, int mouseX, int mouseY, float dt);

	/** Zusätzliche Kopfzeilen-Elemente links vom Schließen-Knopf; Rückgabe: neue rechte Kante. */
	protected int headerExtras(Canvas c, int right, int y, int mouseX, int mouseY) {
		return right;
	}

	@Override
	protected final void draw(Canvas raw, int width, int height, int mouseX, int mouseY, float dt) {
		Theme t = Theme.get();
		Canvas c = FadeCanvas.of(raw, alpha());
		c.fill(0, 0, width, height, t.scrim);
		int[] s = size(width, height);
		int pw = s[0];
		int ph = s[1];
		int px = (width - pw) / 2;
		int py = (height - ph) / 2 + Math.round((1 - Anim.easeOut(open)) * 14);
		window[0] = px;
		window[1] = py;
		window[2] = pw;
		window[3] = ph;
		c.push();
		c.raise(300f);
		Redstone.window(c, px, py, pw, ph);
		header(c, px, py, pw, mouseX, mouseY);
		content(c, px + PAD, py + HEADER_H + 6, pw - PAD * 2, ph - HEADER_H - 6 - PAD, mouseX, mouseY, dt);
		c.pop();
	}

	private void header(Canvas c, int px, int py, int pw, int mx, int my) {
		Theme t = Theme.get();
		c.fill(px + 1, py + 2, px + pw - 1, py + HEADER_H, t.surfaceHigh);
		c.fill(px + 1, py + HEADER_H - 1, px + pw - 1, py + HEADER_H, t.border);
		int lx = px + PAD;
		int ly = py + (HEADER_H - PixelFont.HEIGHT * 2) / 2;
		List<int[]> rects = PixelFont.rects("TRS");
		int glow = ColorMath.withAlpha(t.glow, 40);
		for (int[] r : rects) c.fill(lx + r[0] * 2 - 1, ly + r[1] * 2 - 1, lx + r[2] * 2 + 1, ly + r[3] * 2 + 1, glow);
		int light = ColorMath.lerp(t.accent, 0xFFFFFFFF, 0.3f);
		for (int[] r : rects) c.fill(lx + r[0] * 2, ly + r[1] * 2, lx + r[2] * 2, ly + r[3] * 2, r[1] == 0 ? light : t.accent);
		int titleX = lx + PixelFont.width("TRS") * 2 + 5;
		int closeSize = 16;
		int closeX = px + pw - PAD - closeSize;
		int closeY = py + (HEADER_H - closeSize) / 2;
		int right = headerExtras(c, closeX - 4, closeY, mx, my);
		Paint.textClipped(c, title(), titleX, py + (HEADER_H - 8) / 2, right - titleX - 6, t.text, false);
		Paint.iconButton(c, closeX, closeY, closeSize, "close", inside(mx, my, closeX, closeY, closeSize, closeSize), false);
		hits.add(closeX, closeY, closeSize, closeSize, new Runnable() {
			@Override
			public void run() {
				playClick();
				requestClose();
			}
		});
	}

	protected static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	/** Knopf mit Aktion (Stein bzw. Lampe) inklusive Klickfläche. */
	protected void button(Canvas c, int x, int y, int w, int h, String label, boolean primary, boolean enabled, int mx, int my,
			final Runnable action) {
		Theme t = Theme.get();
		boolean hov = enabled && inside(mx, my, x, y, w, h);
		if (enabled) {
			Redstone.button(c, x, y, w, h, label, primary, hov);
			hits.add(x, y, w, h, new Runnable() {
				@Override
				public void run() {
					playClick();
					action.run();
				}
			});
		} else {
			Redstone.stone(c, x, y, w, h, t.surface, t.border);
			String s = c.clip(label, w - 6);
			c.text(s, x + (w - c.textWidth(s)) / 2, y + (h - 8) / 2, t.textDim, false);
		}
	}

	/** Symbol-Knopf mit Aktion. */
	protected void iconButton(Canvas c, int x, int y, int size, String icon, boolean primary, int mx, int my, final Runnable action) {
		Paint.iconButton(c, x, y, size, icon, inside(mx, my, x, y, size, size), primary);
		hits.add(x, y, size, size, new Runnable() {
			@Override
			public void run() {
				playClick();
				action.run();
			}
		});
	}

	/** Reiter-Knopf (aktiv = bestromt). */
	protected void tab(Canvas c, int x, int y, int w, int h, String label, boolean active, int mx, int my, final Runnable action) {
		Theme t = Theme.get();
		boolean hov = inside(mx, my, x, y, w, h);
		if (active) {
			Redstone.block(c, x, y, w, h, ColorMath.withAlpha(t.accent, 50));
			Redstone.dustH(c, x + 2, x + w - 2, y + h - 2, t.dustOn, 1f);
		} else if (hov) {
			Redstone.block(c, x, y, w, h, t.surfaceHover);
			Redstone.dustH(c, x + 2, x + w - 2, y + h - 2, t.dustOff, 0f);
		}
		String s = c.clip(label, w - 6);
		c.text(s, x + (w - c.textWidth(s)) / 2, y + (h - 8) / 2 - 1, active || hov ? t.text : t.textDim, false);
		hits.add(x, y, w, h, new Runnable() {
			@Override
			public void run() {
				playClick();
				action.run();
			}
		});
	}

	/** Senkrechte Bildlaufleiste rechts neben einer Liste. */
	protected static void scrollbar(Canvas c, int x, int y, int h, int scroll, int maxScroll, int content) {
		if (maxScroll <= 0) return;
		Theme t = Theme.get();
		int barH = Math.max(12, h * h / Math.max(1, content));
		int barY = y + (h - barH) * scroll / Math.max(1, maxScroll);
		c.fill(x, barY, x + 2, barY + barH, t.border);
	}

	@Override
	public boolean keyPressed(int rawKey, UiKey key, boolean shift) {
		if (key == UiKey.ESCAPE) {
			requestClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean pausesGame() {
		return true;
	}
}

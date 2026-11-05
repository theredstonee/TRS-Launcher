package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;

/**
 * Das Kästchen eines Wegpunkts, überall gleich (Karte, Minimap, Liste, in der Welt): farbige Fläche mit dunklem Rand,
 * darin ein bis zwei Zeichen oder ein Pixel-Symbol. Die Mitte liegt bei (x, y), {@code k} ist die Größe (1 = 12 GUI-Pixel).
 */
public final class WaypointBadge {
	/** Kantenlänge bei {@code k = 1} (mit Rand). */
	public static final int SIZE = 12;

	private WaypointBadge() {
	}

	/** Breite bei {@code k = 1}: lange Kürzel machen das Kästchen breiter. */
	public static int width(Canvas c, Waypoint w) {
		if (!w.badgeSymbol().isEmpty()) return SIZE;
		return Math.max(SIZE, c.textWidth(w.badgeText()) + 4);
	}

	public static void draw(Canvas c, Waypoint w, float x, float y, float k, int alpha) {
		draw(c, w.badgeSymbol(), w.badgeText(), w.color, x, y, k, alpha);
	}

	/** Für die Vorschau im Dialog (noch ohne fertigen Wegpunkt). */
	public static void draw(Canvas c, String symbol, String text, int rgb, float x, float y, float k, int alpha) {
		if (alpha <= 0) return;
		boolean hasSymbol = symbol != null && !symbol.isEmpty();
		int w = hasSymbol ? SIZE : Math.max(SIZE, c.textWidth(text) + 4);
		int h = SIZE;
		c.push();
		c.translate(x, y);
		if (k != 1f) c.scale(k);
		int x0 = -w / 2, y0 = -h / 2;
		int a = Math.min(255, alpha);
		int color = ColorMath.withAlpha(0xFF000000 | (rgb & 0xFFFFFF), a);
		c.fill(x0, y0, x0 + w, y0 + h, ColorMath.withAlpha(0xFF101014, Math.min(a, 0xF0)));
		c.fill(x0 + 1, y0 + 1, x0 + w - 1, y0 + h - 1, color);
		// Obere Kante etwas heller, untere dunkler – wie ein kleiner Block.
		c.fill(x0 + 1, y0 + 1, x0 + w - 1, y0 + 2, ColorMath.lerp(color, ColorMath.withAlpha(0xFFFFFFFF, a), 0.3f));
		c.fill(x0 + 1, y0 + h - 2, x0 + w - 1, y0 + h - 1, ColorMath.lerp(color, ColorMath.withAlpha(0xFF000000, a), 0.3f));
		int ink = ColorMath.withAlpha(ColorMath.contrastText(0xFF000000 | (rgb & 0xFFFFFF)), a);
		if (hasSymbol) {
			WaypointSymbols.draw(c, symbol, x0 + 2, y0 + 2, 1, ink);
		} else if (a >= 16 && text != null && !text.isEmpty()) {
			int tw = c.textWidth(text);
			c.text(text, x0 + (w - tw) / 2 + (tw % 2 == 0 ? 0 : 1), y0 + 2, ink, false);
		}
		c.pop();
	}
}

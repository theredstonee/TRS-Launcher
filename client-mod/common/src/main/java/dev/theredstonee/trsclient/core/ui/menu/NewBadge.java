package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

/** Kleines „NEU“-Schild (leuchtende Redstone-Lampe) für Kacheln, Einstellungszeilen und die Leiste. */
public final class NewBadge {
	public static final int HEIGHT = 10;

	private NewBadge() {
	}

	public static String label() {
		return I18n.tr("intro.newBadge");
	}

	/** Breite des Schilds. */
	public static int width(Canvas c) {
		return c.textWidth(label()) + 6;
	}

	/** Zeichnet das Schild mit der linken oberen Ecke (x, y); liefert die Breite. */
	public static int draw(Canvas c, int x, int y) {
		Theme t = Theme.get();
		int w = width(c);
		// Sanftes Pulsieren, damit neue Einträge ins Auge fallen (ohne zu blinken).
		float pulse = 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 380.0);
		Redstone.glow(c, x, y, w, HEIGHT, t.lampGlow, 0.35f * pulse);
		Redstone.block(c, x, y, w, HEIGHT, t.lampOnEdge);
		c.fill(x + 1, y + 1, x + w - 1, y + HEIGHT - 1, ColorMath.lerp(t.lampOn, t.lampHot, 0.25f * pulse));
		c.text(label(), x + 3, y + 1, t.lampTextLit, false);
		return w;
	}

	/** Kleiner Punkt (Leiste: „hier gibt es Neues“). */
	public static void dot(Canvas c, int x, int y) {
		Redstone.pip(c, x, y, 5, 1f);
	}
}

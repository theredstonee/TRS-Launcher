package dev.theredstonee.trsclient.core.ui.winter;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

/**
 * Bausteine des Designs „Winternacht“ – das Gegenstück zu {@link Redstone} (das bei {@link Theme#winter} hierher
 * verzweigt): Eislaternen statt Redstone-Lampen (gefrorenes Glas, Reif in den Ecken, eisblaues Licht), Frostlinien
 * mit Eiskristallen statt Staub, ein Eiskristall statt der Fackel, die Pixel-Schneeflocke des Launchers und der
 * Eisbalken, der beim Laden von links zufriert. Alles aus Rechtecken im GUI-Raster wie im Redstone-Stil.
 */
public final class Frost {
	private Frost() {
	}

	private static final int WHITE = 0xFFFFFFFF;

	// --- Eislaterne ---

	/**
	 * Eislaterne als Knopf: dunkles, gefrorenes Glas in einem Metallrahmen, Reif oben links und unten rechts, feine
	 * Risse; {@code lit} 1 = eisblaues Licht mit hellem Kern und Schein, {@code flash} = kurzes Aufblitzen beim Drücken.
	 * Oben liegt eine dünne Schneekante.
	 */
	public static void lantern(Canvas c, int x, int y, int w, int h, float lit, float flash) {
		Theme t = Theme.get();
		float l = ColorMath.clamp01(lit);
		if (l > 0.02f) Redstone.glow(c, x, y, w, h, t.lampGlow, l);
		Redstone.block(c, x, y, w, h, ColorMath.lerp(t.lampOffEdge, t.lampOnEdge, l));
		int face = ColorMath.lerp(t.lampOff, t.lampOn, l);
		if (w < 8 || h < 8) {
			c.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
			return;
		}
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
		// Licht von innen: der Kern ist heller als der Rand.
		if (l > 0.02f) c.fill(x + 4, y + 4, x + w - 4, y + h - 4, ColorMath.withAlpha(t.lampHot, Math.round(110 * l)));
		// Innenrahmen aus bereiftem Metall, Stege oben und unten.
		int lattice = ColorMath.lerp(0xFF243A5C, 0xFFBFE8FF, l);
		Redstone.frame(c, x + 2, y + 2, w - 4, h - 4, lattice);
		int panes = Math.max(1, (w - 8) / 44);
		for (int i = 1; i < panes + 1 && w > 30; i++) {
			int px = x + i * w / (panes + 1);
			c.fill(px, y + 2, px + 1, y + 4, lattice);
			c.fill(px, y + h - 4, px + 1, y + h - 2, lattice);
		}
		// Glaskante oben (Licht), dunkle Kante unten.
		c.fill(x + 1, y + 1, x + w - 1, y + 2, ColorMath.lerp(0xFF2E4668, 0xFFF2FBFF, l));
		c.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, ColorMath.lerp(0xFF0A1220, 0xFF2F86BD, l));
		// Reif in den Ecken: von der Ecke aus kürzer werdende Zeilen.
		int rime = ColorMath.withAlpha(WHITE, Math.round(70 + 90 * l));
		int rows = Math.min(5, (h - 4) / 2);
		for (int i = 0; i < rows; i++) {
			int len = Math.max(1, 6 - i - (i > 1 ? 1 : 0));
			c.fill(x + 2, y + 2 + i, x + 2 + len, y + 3 + i, rime);
			c.fill(x + w - 2 - len, y + h - 3 - i, x + w - 2, y + h - 2 - i, rime);
		}
		// Feine Risse im Eis (fest je Breite, damit sie nicht flackern).
		int crack = ColorMath.withAlpha(WHITE, Math.round(40 + 40 * l));
		if (w >= 40 && h >= 14) {
			int cx = x + w * 3 / 10;
			int cy = y + h - 4;
			for (int k = 0; k < Math.min(5, h - 8); k++) c.fill(cx + k, cy - k, cx + k + 1, cy - k + 1, crack);
			int dx = x + w * 7 / 10;
			for (int k = 0; k < Math.min(4, h - 9); k++) c.fill(dx - k, y + 4 + k, dx - k + 1, y + 5 + k, crack);
		}
		// Schnee auf der Oberkante.
		int snow = ColorMath.lerp(0xFFC9DCF5, WHITE, 0.4f + 0.4f * l);
		c.fill(x + 2, y, x + w - 2, y + 1, snow);
		for (int sx = x + 5; sx < x + w - 6; sx += 13 + ((sx * 7) & 7)) c.fill(sx, y - 1, sx + 2, y, ColorMath.withAlpha(snow, 0xB0));
		float f = ColorMath.clamp01(flash);
		if (f > 0.02f) c.fill(x + 1, y + 1, x + w - 1, y + h - 1, ColorMath.withAlpha(0xFFEAF9FF, Math.round(170 * f)));
	}

	// --- Frost statt Staub ---

	/** Waagerechte Frostlinie (2 px) mit Eiskristallen; leuchtet bei Signal. */
	public static void lineH(Canvas c, int x1, int x2, int y, int argb, float glow) {
		if (x2 <= x1) return;
		c.fill(x1, y, x2, y + 2, argb);
		int crystal = ColorMath.lerp(argb, WHITE, 0.55f);
		for (int x = x1 + 3 - Math.floorMod(x1, 8) % 8; x < x2 - 1; x += 8) {
			if (x >= x1) c.fill(x, y, x + 1, y + 1, crystal);
		}
		if (glow > 0.02f) {
			int g = ColorMath.withAlpha(Theme.get().glow, Math.round(40 * ColorMath.clamp01(glow)));
			c.fill(x1, y - 1, x2, y, g);
			c.fill(x1, y + 2, x2, y + 3, g);
		}
	}

	/** Senkrechte Frostlinie (2 px) mit Eiskristallen. */
	public static void lineV(Canvas c, int x, int y1, int y2, int argb, float glow) {
		if (y2 <= y1) return;
		c.fill(x, y1, x + 2, y2, argb);
		int crystal = ColorMath.lerp(argb, WHITE, 0.55f);
		for (int y = y1 + 3 - Math.floorMod(y1, 8) % 8; y < y2 - 1; y += 8) {
			if (y >= y1) c.fill(x, y, x + 1, y + 1, crystal);
		}
		if (glow > 0.02f) {
			int g = ColorMath.withAlpha(Theme.get().glow, Math.round(40 * ColorMath.clamp01(glow)));
			c.fill(x - 1, y1, x, y2, g);
			c.fill(x + 2, y1, x + 3, y2, g);
		}
	}

	/**
	 * Eiskristall auf einem Eiszapfen-Stiel (statt der Redstone-Fackel); (x, y) = Mitte des Fußes. {@code lit} 0..1,
	 * {@code flicker} 0..1 lässt den Kristall leicht funkeln.
	 */
	public static void crystal(Canvas c, int x, int y, float lit, float flicker) {
		Theme t = Theme.get();
		float l = ColorMath.clamp01(lit);
		c.fill(x - 1, y - 6, x + 1, y, 0xFF5D7FA8);
		c.fill(x - 1, y - 6, x, y, 0xFF8EB0D6);
		int head = ColorMath.lerp(0xFF2B4A6E, t.dustOn, l * (0.82f + 0.18f * flicker));
		if (l > 0.05f) {
			c.fill(x - 3, y - 12, x + 3, y - 5, ColorMath.withAlpha(t.glow, Math.round(32 * l)));
			c.fill(x - 4, y - 11, x + 4, y - 6, ColorMath.withAlpha(t.glow, Math.round(18 * l)));
		}
		// Rhombus: 2 – 4 – 4 – 4 – 2 Pixel breit.
		c.fill(x - 1, y - 11, x + 1, y - 10, head);
		c.fill(x - 2, y - 10, x + 2, y - 7, head);
		c.fill(x - 1, y - 7, x + 1, y - 6, head);
		if (l > 0.05f) {
			int hi = ColorMath.lerp(head, WHITE, 0.65f * l);
			c.fill(x - 1, y - 10, x, y - 8, hi);
			c.fill(x - 1, y - 11, x, y - 10, ColorMath.lerp(head, WHITE, 0.85f * l));
		}
	}

	// --- Schneeflocke (wie neben dem Logo im Launcher) ---

	/**
	 * Pixel-Schneeflocke 9 × 9 Punkte ({@code px} GUI-Pixel je Punkt). {@code glint} 0..1 lässt die Spitzen und die
	 * Mitte weiß aufleuchten.
	 */
	public static void snowflake(Canvas c, int x, int y, int px, int argb, float glint) {
		c.fill(x + 4 * px, y, x + 5 * px, y + 9 * px, argb);
		c.fill(x, y + 4 * px, x + 9 * px, y + 5 * px, argb);
		c.fill(x + 3 * px, y + 3 * px, x + 6 * px, y + 6 * px, argb);
		int[][] diag = {{1, 1}, {7, 1}, {1, 7}, {7, 7}, {2, 2}, {6, 2}, {2, 6}, {6, 6}};
		for (int[] d : diag) c.fill(x + d[0] * px, y + d[1] * px, x + (d[0] + 1) * px, y + (d[1] + 1) * px, argb);
		int g = ColorMath.withAlpha(WHITE, Math.round(255 * (0.55f + 0.45f * ColorMath.clamp01(glint))));
		int[][] tips = {{4, 4}, {4, 0}, {0, 4}, {8, 4}, {4, 8}};
		for (int[] d : tips) c.fill(x + d[0] * px, y + d[1] * px, x + (d[0] + 1) * px, y + (d[1] + 1) * px, g);
	}

	/** Glitzern der Schneeflocke: alle 5 s kurz an (wie im Launcher). */
	public static float glint(float seconds) {
		float p = (seconds % 5f) / 5f;
		return p >= 0.9f && p < 0.96f ? 1f : 0f;
	}

	// --- Eisbalken (Fortschritt) ---

	/**
	 * Fortschritt als Eisbalken, der von links zufriert (Launcher: FrostProgress): Spur aus dunklem Eis mit Fugen,
	 * Füllung eisblau mit heller Oberkante und wanderndem Glitzern, an der Gefrierkante ein Eiskristall.
	 * {@code progress} &lt; 0 = unbekannt (ein Frostschimmer läuft durch).
	 */
	public static void bar(Canvas c, int cx, int y, int maxWidth, float progress, float time) {
		Theme t = Theme.get();
		int w = Math.max(24, Math.min(maxWidth, 200));
		int h = 6;
		int x0 = cx - w / 2;
		int a500 = t.accent;
		int a400 = ColorMath.lerp(a500, WHITE, 0.38f);
		int a300 = ColorMath.lerp(a500, WHITE, 0.62f);
		int a600 = ColorMath.scaleRgb(a500, 0.75f);
		int a900 = ColorMath.lerp(ColorMath.scaleRgb(a500, 0.22f), 0xFF000000, 0.25f);
		Redstone.block(c, x0 - 1, y - 1, w + 2, h + 2, 0xFF050A14);
		c.fill(x0, y, x0 + w, y + h, a900);
		c.fill(x0, y + h - 1, x0 + w, y + h, 0x40000000);
		int joint = ColorMath.withAlpha(a300, 0x24);
		for (int jx = x0 + 13; jx < x0 + w; jx += 14) c.fill(jx, y, jx + 1, y + h, joint);
		if (progress < 0) {
			// Unbekannter Fortschritt: ein Frostschimmer läuft durch.
			int band = Math.max(12, w * 35 / 100);
			float phase = (time / 1.6f) % 1f;
			int bx = x0 - band + Math.round(phase * band * 3.9f);
			int[] cols = {a500, a400, WHITE, a400, a500};
			int[] alpha = {0x50, 0xA0, 0xE0, 0xA0, 0x50};
			int seg = Math.max(1, band / 5);
			for (int i = 0; i < 5; i++) {
				int sx = Math.max(x0, bx + i * seg);
				int ex = Math.min(x0 + w, bx + (i + 1) * seg);
				if (ex > sx) c.fill(sx, y, ex, y + h, ColorMath.withAlpha(cols[i], alpha[i]));
			}
			// Ein Eiskristall reitet auf dem Schimmer.
			int tip = bx + band / 2;
			if (tip > x0 + 2 && tip < x0 + w - 2) {
				diamond(c, tip, y + h / 2, 3, ColorMath.withAlpha(a300, 0xD0));
				diamond(c, tip, y + h / 2, 1, 0xFFF2FAFF);
			}
			return;
		}
		float p = ColorMath.clamp01(progress);
		int fw = Math.round(w * p);
		if (fw <= 0) return;
		// Schein um das Eis.
		c.fill(x0, y - 2, x0 + fw, y - 1, ColorMath.withAlpha(a400, 0x30));
		c.fill(x0, y + h + 1, x0 + fw, y + h + 2, ColorMath.withAlpha(a400, 0x30));
		// Verlauf a600 → a500 (40 %) → a400 in flächigen Stufen.
		int steps = Math.max(1, Math.min(10, fw / 8));
		for (int i = 0; i < steps; i++) {
			int sx = x0 + fw * i / steps;
			int ex = x0 + fw * (i + 1) / steps;
			float u = (sx - x0 + (ex - sx) / 2f) / (float) w;
			int col = u < 0.4f ? ColorMath.lerp(a600, a500, u / 0.4f) : ColorMath.lerp(a500, a400, (u - 0.4f) / 0.6f);
			c.fill(sx, y, ex, y + h, col);
		}
		c.fill(x0, y, x0 + fw, y + 1, ColorMath.lerp(WHITE, a300, 0.3f));
		int ice = ColorMath.withAlpha(WHITE, 0x70);
		for (int jx = x0 + 13; jx < x0 + fw; jx += 14) c.fill(jx, y + 1, jx + 1, y + h, ice);
		// Glitzer: einzelne helle Pixel wandern über das Eis.
		int shift = Math.round(time * 25f);
		int[][] glitter = {{5, 2, 2}, {54, 1, 2}, {31, 3, 1}, {43, 2, 1}};
		for (int[] g : glitter) {
			for (int base = 0; base < fw; base += 60) {
				int gx = x0 + base + Math.floorMod(g[0] + shift, 60);
				if (gx + g[2] > x0 + fw || gx < x0) continue;
				c.fill(gx, y + g[1], gx + g[2], y + g[1] + g[2], ColorMath.withAlpha(WHITE, g[2] == 2 ? 0xE0 : 0xB0));
			}
		}
		// Eiskristall an der Gefrierkante.
		if (p < 1f) {
			int tx = x0 + fw;
			int ty = y + h / 2;
			c.fill(tx - 6, ty - 6, tx + 6, ty + 6, ColorMath.withAlpha(a400, 0x22));
			diamond(c, tx, ty, 4, a300);
			diamond(c, tx, ty, 2, 0xFFF2FAFF);
		}
	}

	/** Rhombus mit „Radius“ {@code r} um (cx, cy). */
	private static void diamond(Canvas c, int cx, int cy, int r, int argb) {
		for (int i = -r; i <= r; i++) {
			int half = r - Math.abs(i);
			c.fill(cx - half, cy + i, cx + half + 1, cy + i + 1, argb);
		}
	}
}

package dev.theredstonee.trsclient.core.ui.halloween;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;

import java.util.List;

/**
 * Bausteine des Halloween-Looks aus Rechtecken (wie {@code core.ui.Redstone}): Knöpfe aus dunklem Pflaumenglas mit
 * Silberkante ({@code .mcb}), Kerze statt Fackel, Silberfaden statt Staub, Schalter wie im Entwurf, Blut-Tropfen an
 * Kanten, Spinnennetz in Ecken, hängende Spinne, Schriftzug in Mondsilber mit Pflaumen-Schatten.
 */
public final class HalloweenLook {
	private HalloweenLook() {
	}

	// --- Knöpfe ---

	/**
	 * Knopf (Titel-Lampe, TRS-Knöpfe): {@code lit} 0 = ruhig (dunkles Pflaumenglas, Kante Violettgrau), 1 = vom Mond
	 * beschienen (Pflaume, Silberkante, leichter Schein); {@code flash} = kurz hell (Drücken).
	 */
	public static void lamp(Canvas c, int x, int y, int w, int h, float lit, float flash) {
		float l = ColorMath.clamp01(lit);
		if (l > 0.02f) Redstone.glow(c, x, y, w, h, HalloweenPalette.SILVER, l * 0.5f);
		int fill = ColorMath.lerp(HalloweenPalette.BUTTON, HalloweenPalette.BUTTON_HOVER, l);
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
		Redstone.frame(c, x, y, w, h, ColorMath.lerp(HalloweenPalette.BUTTON_EDGE, HalloweenPalette.SILVER_LIGHT, l));
		if (w > 4 && h > 4) c.fill(x + 1, y + 1, x + w - 1, y + 2, ColorMath.withAlpha(HalloweenPalette.SILVER_LIGHT, 0x14 + Math.round(0x20 * l)));
		float f = ColorMath.clamp01(flash);
		if (f > 0.02f) c.fill(x + 1, y + 1, x + w - 1, y + h - 1, ColorMath.withAlpha(0xFFF3F5FB, Math.round(150 * f)));
	}

	/** Fläche eines Vanilla-Knopfs ({@code .mcb}): Minecraft schreibt weiß darauf. */
	public static void vanillaButton(Canvas c, int x, int y, int w, int h, boolean hot, boolean active) {
		if (!active) {
			c.fill(x + 1, y + 1, x + w - 1, y + h - 1, HalloweenPalette.BUTTON_OFF);
			Redstone.frame(c, x, y, w, h, 0xFF2A2440);
			return;
		}
		lamp(c, x, y, w, h, hot ? 1f : 0f, 0f);
	}

	// --- Linien, Kerze ---

	/** Silberfaden (statt Staubleitung): 1 px Linie, darunter ein Hauch. */
	public static void lineH(Canvas c, int x1, int x2, int y, int argb, float glow) {
		c.fill(x1, y, x2, y + 1, argb);
		c.fill(x1, y + 1, x2, y + 2, ColorMath.withAlpha(argb, Math.min(0x60, (argb >>> 24) / 3)));
		if (glow > 0.02f) c.fill(x1, y - 1, x2, y, ColorMath.withAlpha(HalloweenPalette.VIOLET_LIGHT, Math.round(30 * ColorMath.clamp01(glow))));
	}

	public static void lineV(Canvas c, int x, int y1, int y2, int argb, float glow) {
		c.fill(x, y1, x + 1, y2, argb);
		c.fill(x + 1, y1, x + 2, y2, ColorMath.withAlpha(argb, Math.min(0x60, (argb >>> 24) / 3)));
		if (glow > 0.02f) c.fill(x - 1, y1, x, y2, ColorMath.withAlpha(HalloweenPalette.VIOLET_LIGHT, Math.round(30 * ColorMath.clamp01(glow))));
	}

	/** Kerze (statt Redstone-Fackel); (x, y) = Mitte des Fußes. */
	public static void candle(Canvas c, int x, int y, float lit, float flicker) {
		float l = ColorMath.clamp01(lit);
		c.fill(x - 1, y - 5, x + 1, y, 0xFFD9D2C0);
		c.fill(x, y - 5, x + 1, y, 0xFFB7AF9C);
		c.fill(x, y - 6, x + 1, y - 5, 0xFF2A2130);
		if (l < 0.05f) return;
		float f = 0.7f + 0.3f * flicker;
		c.fill(x - 3, y - 11, x + 3, y - 5, ColorMath.withAlpha(0xFFFFAA50, Math.round(26 * l * f)));
		c.fill(x - 1, y - 9, x + 1, y - 6, ColorMath.withAlpha(0xFFFFD27A, Math.round(255 * l)));
		c.fill(x - 1, y - 10, x, y - 9, ColorMath.withAlpha(0xFFFFE9B0, Math.round(200 * l * f)));
	}

	/** Schalter wie {@code .sw} im Entwurf: Schiene Violett/Dunkel, Knopf Silber. */
	public static void toggle(Canvas c, int x, int y, int w, int h, float progress, boolean hover) {
		float p = ColorMath.clamp01(progress);
		int track = ColorMath.lerp(0xFF2A2440, HalloweenPalette.VIOLET, p);
		Redstone.block(c, x, y, w, h, hover ? ColorMath.lerp(track, HalloweenPalette.SILVER, 0.35f) : ColorMath.lerp(track, 0xFF000000, 0.25f));
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, track);
		int k = Math.max(3, h - 4);
		int kx = x + 2 + Math.round((w - k - 4) * p);
		int ky = y + (h - k) / 2;
		Redstone.block(c, kx, ky, k, k, HalloweenPalette.SILVER_LIGHT);
		c.fill(kx + 1, ky + k - 2, kx + k - 1, ky + k - 1, HalloweenPalette.SILVER);
	}

	// --- Grusel-Schmuck ---

	/**
	 * Blut-Tropfen an einer Oberkante ({@code y}) zwischen {@code x1} und {@code x2}: feste Lage aus {@code seed}, einige
	 * laufen langsam nach (wenn {@code time} &gt;= 0).
	 */
	public static void drips(Canvas c, int x1, int x2, int y, int seed, float time) {
		int w = x2 - x1;
		if (w < 20) return;
		int n = Math.max(3, Math.min(14, w / 40));
		for (int i = 0; i < n; i++) {
			int h = hash(seed * 31 + i);
			int x = x1 + 3 + Math.floorMod(h, Math.max(1, w - 6));
			int len = 2 + Math.floorMod(h >> 8, 7);
			int dw = 1 + (Math.floorMod(h >> 4, 3) == 0 ? 1 : 0);
			if (time >= 0 && Math.floorMod(h >> 12, 3) == 0) {
				double period = 5 + Math.floorMod(h >> 16, 9);
				double ph = ((time + Math.floorMod(h, 97) / 10.0) % period) / period;
				len += (int) Math.round(Math.sin(ph * Math.PI) * 5);
			}
			c.fill(x, y, x + dw, y + len, 0xD07A0F18);
			c.fill(x, y + len, x + dw, y + len + 1, 0xE0C9424A);
			c.fill(x - 1, y, x + dw + 1, y + 1, 0xC07A0F18);
		}
	}

	/**
	 * Spinnennetz in einer Ecke (Radius {@code r}); {@code corner} 0 = oben links, 1 = oben rechts, 2 = unten links,
	 * 3 = unten rechts.
	 */
	public static void cobweb(Canvas c, int x, int y, int r, int corner, int argb) {
		int sx = corner == 1 || corner == 3 ? -1 : 1;
		int sy = corner >= 2 ? -1 : 1;
		for (int a = 0; a <= 4; a++) {
			double ang = (a / 4.0) * Math.PI / 2;
			for (int i = 0; i < r; i++) {
				int px = x + sx * (int) Math.round(Math.cos(ang) * i);
				int py = y + sy * (int) Math.round(Math.sin(ang) * i);
				c.fill(px, py, px + 1, py + 1, argb);
			}
		}
		for (int ring = 1; ring <= 3; ring++) {
			double rr = ring * r / 3.4;
			for (double a = 0; a < 90; a += 3) {
				double ang = a * Math.PI / 180;
				double sag = Math.sin((a % 22.5) / 22.5 * Math.PI) * ring * 0.8;
				int px = x + sx * (int) Math.round(Math.cos(ang) * (rr - sag));
				int py = y + sy * (int) Math.round(Math.sin(ang) * (rr - sag));
				c.fill(px, py, px + 1, py + 1, argb);
			}
		}
	}

	/** Hängende Spinne am Faden von {@code top}; die Fadenlänge schwankt langsam. */
	public static void spider(Canvas c, int x, int top, int len, int px, float time) {
		int l = Math.round(len * (0.75f + 0.25f * (float) Math.sin(time * 0.8)));
		c.fill(x + px * 3, top, x + px * 3 + 1, top + l, 0x59CFD5E3);
		Sprites.draw(c, Sprites.SPIDER, x, top + l, px, 1f);
	}

	// --- Schriftzug ---

	/**
	 * Pixel-Schriftzug in Mondsilber mit doppeltem Pflaumen-Schatten nach unten und violettem Schein
	 * ({@code text-shadow: 0 3px 0 #3a1f5c, 0 6px 0 #1a1030, 0 0 30px violett}).
	 */
	public static void silverWord(Canvas c, String word, int x, int y, int scale, float alpha, float pulse) {
		List<int[]> rects = PixelFont.rects(word);
		int far = ColorMath.withAlpha(HalloweenPalette.VIOLET_LIGHT, Math.round(14 * alpha * pulse));
		int near = ColorMath.withAlpha(HalloweenPalette.VIOLET_LIGHT, Math.round(40 * alpha * pulse));
		int g1 = Math.max(1, scale / 2);
		int g2 = Math.max(2, scale);
		for (int[] r : rects) c.fill(x + r[0] * scale - g2, y + r[1] * scale - g2 / 2, x + r[2] * scale + g2, y + r[3] * scale + g2, far);
		for (int[] r : rects) c.fill(x + r[0] * scale - g1, y + r[1] * scale - g1, x + r[2] * scale + g1, y + r[3] * scale + g1, near);
		int off = Math.max(1, Math.round(scale * 0.4f));
		int deep = ColorMath.fade(0xFF1A1030, alpha);
		int plum = ColorMath.fade(HalloweenPalette.PLUM, alpha);
		for (int[] r : rects) c.fill(x + r[0] * scale, y + r[1] * scale + 2 * off, x + r[2] * scale, y + r[3] * scale + 2 * off, deep);
		for (int[] r : rects) c.fill(x + r[0] * scale, y + r[1] * scale + off, x + r[2] * scale, y + r[3] * scale + off, plum);
		int face = ColorMath.fade(HalloweenPalette.SILVER_LIGHT, alpha);
		int shade = ColorMath.fade(HalloweenPalette.SILVER, alpha);
		for (int[] r : rects) {
			int col = r[1] == PixelFont.HEIGHT - 1 ? shade : face;
			c.fill(x + r[0] * scale, y + r[1] * scale, x + r[2] * scale, y + r[3] * scale, col);
		}
	}

	/**
	 * Schmuck für die Kopfzeile des TRS-Menüs (für den Aufrufer, der das Menü zeichnet): Netz in der rechten oberen Ecke,
	 * ein paar Blut-Tropfen an der Unterkante, kleine Kürbislaterne rechts neben dem Schriftzug ({@code logoRight}).
	 */
	public static void menuHeader(Canvas c, int x, int y, int w, int h, int logoRight, float seconds) {
		cobweb(c, x + w - 2, y + 1, Math.min(18, h - 4), 1, 0x38CFD5E3);
		drips(c, x + 20, x + w - 8, y + h - 1, 7, seconds);
		if (logoRight > 0) {
			float fl = 0.75f + 0.25f * (float) (Math.sin(seconds * 9) * Math.sin(seconds * 3.7));
			int jx = logoRight + 5;
			int jy = y + (h - Sprites.JACK.h) / 2;
			c.fill(jx - 3, jy - 2, jx + Sprites.JACK.w + 3, jy + Sprites.JACK.h + 2, ColorMath.withAlpha(HalloweenPalette.PUMPKIN_LIGHT, Math.round(30 * fl)));
			Sprites.draw(c, Sprites.JACK, jx, jy, 1, 1f);
		}
	}

	private static int hash(int v) {
		int h = v * 0x9E3779B1;
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return h & 0x7FFFFFFF;
	}
}

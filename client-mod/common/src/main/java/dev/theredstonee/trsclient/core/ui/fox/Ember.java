package dev.theredstonee.trsclient.core.ui.fox;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.util.List;

/**
 * Bausteine des Designs „Fuchsbau“ – das Gegenstück zu {@link Redstone} (das bei {@link Theme#fox} hierher verzweigt)
 * wie {@code Frost} für die Winternacht: Laternen mit warmem Glutlicht statt Redstone-Lampen (Eisenrahmen, Glut im
 * Glas, ein Zweig mit Laub obenauf), glimmende Äste mit Blättchen statt Staub, eine kleine Flamme statt der Fackel, das
 * Pixel-Fuchsgesicht des Launchers (blinzelt) und der glimmende Ast, der beim Laden von links durchglüht
 * ({@code EmberProgress} im Launcher). Alles aus Rechtecken im GUI-Raster.
 */
public final class Ember {
	private Ember() {
	}

	private static final int WHITE = 0xFFFFFFFF;
	/** Flammen innen → außen und Glut (wie das Lagerfeuer der Szene). */
	public static final int FLAME_CORE = 0xFFFFF2A8;
	public static final int FLAME_MID = 0xFFFFB340;
	public static final int FLAME_OUT = 0xFFFF6A1A;
	public static final int GLOW = 0xFFFF8A2A;
	/** „Unter Strom“ im Fuchsbau: golden wie Glut (Launcher lamp-300/400). */
	public static final int GOLD_LIGHT = 0xFFFFE1A6;
	public static final int GOLD = 0xFFFFC362;
	/** Laub: Fichtengrün, Herbstblatt, Kirschblüte. */
	public static final int LEAF_DARK = 0xFF2F5A34;
	public static final int LEAF = 0xFF4C7A34;
	public static final int LEAF_LIGHT = 0xFF74A048;
	public static final int[] FALL = {0xFFE07A3A, 0xFFF0A050, 0xFFB4502A, 0xFFE8B04A};
	public static final int PETAL = 0xFFF3A6C8;

	// --- Laterne ---

	/**
	 * Laterne als Knopf: dunkles Glas in einem Eisenrahmen mit Stegen, darin ein paar Glutpunkte; {@code lit} 1 =
	 * warmes, goldenes Licht (unten in der Mitte am hellsten) mit Schein, {@code flash} = kurzes Aufglühen beim
	 * Drücken. Auf der Oberkante liegt ein Zweig mit Laub.
	 */
	public static void lantern(Canvas c, int x, int y, int w, int h, float lit, float flash) {
		Theme t = Theme.get();
		float l = ColorMath.clamp01(lit);
		if (l > 0.02f) Redstone.glow(c, x, y, w, h, t.lampGlow, l);
		Redstone.block(c, x, y, w, h, ColorMath.lerp(t.lampOffEdge, t.lampOnEdge, l));
		// Rand des Lichts: Gold, zum Akzent hin getönt (wie die Lampe im Launcher).
		int warm = ColorMath.lerp(t.lampOn, t.accent, 0.3f);
		int face = ColorMath.lerp(t.lampOff, warm, l);
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
		if (w < 8 || h < 8) return;
		if (l > 0.02f) {
			// Licht von innen: innen heller, im unteren Drittel fast weiß (Bänder über die ganze Breite – keine Kästen
			// hinter der Schrift).
			c.fill(x + 3, y + 3, x + w - 3, y + h - 3, ColorMath.withAlpha(GOLD_LIGHT, Math.round(90 * l)));
			if (h >= 14) c.fill(x + 3, y + h * 3 / 5, x + w - 3, y + h - 3, ColorMath.withAlpha(t.lampHot, Math.round(60 * l)));
		}
		// Glut im dunklen Glas (fest je Breite, damit sie nicht flackert); mit Licht verschwindet sie darin.
		float dim = 1f - Math.min(1f, l * 2f);
		if (dim > 0.05f && h >= 12) {
			// Nur im unteren Teil, wie Glut am Boden der Laterne – sonst wirkt das Glas fleckig.
			int emberA = ColorMath.withAlpha(GOLD, Math.round(110 * dim));
			int emberB = ColorMath.withAlpha(GLOW, Math.round(80 * dim));
			int band = Math.max(2, (h - 6) * 2 / 5);
			int[][] dots = {{7, 2, 0}, {33, 0, 1}, {54, 3, 0}, {21, 4, 1}, {44, 1, 0}};
			for (int base = 0; base < w; base += 60) {
				for (int[] d : dots) {
					int dx = x + base + d[0];
					int dy = y + h - 4 - Math.floorMod(d[1] + base / 7, band);
					if (dx < x + 3 || dx >= x + w - 3) continue;
					c.fill(dx, dy, dx + 1, dy + 1, d[2] == 0 ? emberA : emberB);
				}
			}
		}
		// Eisenrahmen: Innenkante, Nieten in den Ecken, Stege oben und unten (nicht durch die Schrift).
		int iron = ColorMath.lerp(0xFF2A1C1E, 0xFF8A4A1C, l);
		Redstone.frame(c, x + 2, y + 2, w - 4, h - 4, iron);
		int rivet = ColorMath.lerp(0xFF4A3432, 0xFFFFE1A6, l);
		c.fill(x + 2, y + 2, x + 3, y + 3, rivet);
		c.fill(x + w - 3, y + 2, x + w - 2, y + 3, rivet);
		c.fill(x + 2, y + h - 3, x + 3, y + h - 2, rivet);
		c.fill(x + w - 3, y + h - 3, x + w - 2, y + h - 2, rivet);
		int struts = Math.max(1, (w - 8) / 40);
		for (int i = 1; i < struts + 1 && w > 30; i++) {
			int px = x + i * w / (struts + 1);
			c.fill(px, y + 2, px + 1, y + 4, iron);
			c.fill(px, y + h - 4, px + 1, y + h - 2, iron);
		}
		// Glaskante oben (Licht), dunkle Kante unten.
		c.fill(x + 1, y + 1, x + w - 1, y + 2, ColorMath.lerp(0xFF5A3A36, 0xFFFFF1CC, l));
		c.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, ColorMath.lerp(0xFF140C0E, 0xFFA8501C, l));
		// Zweig mit Laub auf der Oberkante (fest je Lage).
		sprig(c, x, y, w);
		float f = ColorMath.clamp01(flash);
		if (f > 0.02f) c.fill(x + 1, y + 1, x + w - 1, y + h - 1, ColorMath.withAlpha(0xFFFFF6DC, Math.round(170 * f)));
	}

	/** Kleiner Zweig mit zwei, drei Blättern auf einer Oberkante (Breite {@code w} ab {@code x}). */
	public static void sprig(Canvas c, int x, int y, int w) {
		if (w < 24) return;
		int sx = x + 3 + Math.floorMod(x * 5 + y * 3, 5);
		int len = Math.min(9, w / 6);
		c.fill(sx, y, sx + len, y + 1, 0xFF3B2614);
		c.fill(sx + 1, y - 1, sx + 3, y, LEAF);
		c.fill(sx + 2, y - 2, sx + 3, y - 1, LEAF_LIGHT);
		c.fill(sx + len - 3, y - 1, sx + len - 1, y, LEAF_DARK);
		int fall = FALL[Math.floorMod(x + y, FALL.length)];
		c.fill(sx + len - 1, y - 1, sx + len, y, fall);
		// Ein Herbstblatt weiter rechts, ab und zu eine Kirschblüte.
		int lx = x + w - 7 - Math.floorMod(x * 3 + y, 9);
		if (lx > sx + len + 3) {
			boolean petal = Math.floorMod(x * 7 + y, 4) == 0;
			c.fill(lx, y, lx + 2, y + 1, petal ? PETAL : fall);
			c.fill(lx + 1, y - 1, lx + 2, y, ColorMath.lerp(petal ? PETAL : fall, WHITE, 0.25f));
		}
	}

	// --- Glimmende Äste statt Staub ---

	/**
	 * Waagerechter Ast (2 px): oben Holz in der Leitungsfarbe, unten dunkler, Maserung alle 9 px, ab und zu ein Blatt;
	 * bei Signal glimmen Glutpunkte und ein warmer Schein.
	 */
	public static void lineH(Canvas c, int x1, int x2, int y, int argb, float glow) {
		if (x2 <= x1) return;
		c.fill(x1, y, x2, y + 1, argb);
		c.fill(x1, y + 1, x2, y + 2, ColorMath.scaleRgb(argb, 0.68f));
		int knot = ColorMath.scaleRgb(argb, 0.55f);
		for (int x = x1 + 9 - Math.floorMod(x1, 10); x < x2; x += 10) if (x >= x1) c.fill(x, y, x + 1, y + 1, knot);
		float g = ColorMath.clamp01(glow);
		if (g > 0.02f) {
			int ember = ColorMath.lerp(argb, FLAME_CORE, 0.65f);
			for (int x = x1 + 4 - Math.floorMod(x1, 12) % 12; x < x2 - 1; x += 12) if (x >= x1) c.fill(x, y, x + 2, y + 1, ember);
			int a = ColorMath.withAlpha(Theme.get().glow, Math.round(40 * g));
			c.fill(x1, y - 1, x2, y, a);
			c.fill(x1, y + 2, x2, y + 3, a);
		}
		// Blättchen am Ast (nur auf längeren Ästen).
		if (x2 - x1 >= 30) {
			for (int x = x1 + 17 - Math.floorMod(x1, 26); x < x2 - 2; x += 26) {
				if (x < x1 + 2) continue;
				boolean up = Math.floorMod(x / 26, 2) == 0;
				int col = Math.floorMod(x / 26, 5) == 3 ? FALL[Math.floorMod(x, FALL.length)] : LEAF;
				if (up) {
					c.fill(x, y - 1, x + 2, y, col);
					c.fill(x + 1, y - 2, x + 2, y - 1, ColorMath.lerp(col, WHITE, 0.2f));
				} else {
					c.fill(x, y + 2, x + 2, y + 3, ColorMath.scaleRgb(col, 0.85f));
				}
			}
		}
	}

	/** Senkrechter Ast (2 px). */
	public static void lineV(Canvas c, int x, int y1, int y2, int argb, float glow) {
		if (y2 <= y1) return;
		c.fill(x, y1, x + 1, y2, argb);
		c.fill(x + 1, y1, x + 2, y2, ColorMath.scaleRgb(argb, 0.68f));
		int knot = ColorMath.scaleRgb(argb, 0.55f);
		for (int y = y1 + 9 - Math.floorMod(y1, 10); y < y2; y += 10) if (y >= y1) c.fill(x, y, x + 1, y + 1, knot);
		float g = ColorMath.clamp01(glow);
		if (g > 0.02f) {
			int ember = ColorMath.lerp(argb, FLAME_CORE, 0.65f);
			for (int y = y1 + 4 - Math.floorMod(y1, 12) % 12; y < y2 - 1; y += 12) if (y >= y1) c.fill(x, y, x + 1, y + 2, ember);
			int a = ColorMath.withAlpha(Theme.get().glow, Math.round(40 * g));
			c.fill(x - 1, y1, x, y2, a);
			c.fill(x + 2, y1, x + 3, y2, a);
		}
		if (y2 - y1 >= 30) {
			for (int y = y1 + 17 - Math.floorMod(y1, 26); y < y2 - 2; y += 26) {
				if (y < y1 + 2) continue;
				boolean right = Math.floorMod(y / 26, 2) == 0;
				int lx = right ? x + 2 : x - 2;
				c.fill(lx, y, lx + 2, y + 1, LEAF);
				c.fill(right ? lx + 1 : lx, y - 1, right ? lx + 2 : lx + 1, y, LEAF_LIGHT);
			}
		}
	}

	// --- Flamme statt Fackel ---

	/**
	 * Kleine Flamme auf einem Holzscheit (statt der Redstone-Fackel); (x, y) = Mitte des Fußes. {@code lit} 0..1,
	 * {@code flicker} 0..1 lässt die Spitze tanzen.
	 */
	public static void flame(Canvas c, int x, int y, float lit, float flicker) {
		Theme t = Theme.get();
		float l = ColorMath.clamp01(lit);
		c.fill(x - 1, y - 6, x + 1, y, 0xFF6B4A2B);
		c.fill(x - 1, y - 6, x, y, 0xFF7E5A36);
		if (l > 0.05f) {
			c.fill(x - 3, y - 13, x + 3, y - 5, ColorMath.withAlpha(t.lampGlow, Math.round(30 * l)));
			c.fill(x - 4, y - 11, x + 4, y - 6, ColorMath.withAlpha(t.lampGlow, Math.round(18 * l)));
		}
		int out = ColorMath.lerp(0xFF3A2014, FLAME_OUT, l);
		int mid = ColorMath.lerp(0xFF4A2A1A, FLAME_MID, l);
		int core = ColorMath.lerp(0xFF5A3420, FLAME_CORE, l * (0.85f + 0.15f * flicker));
		// Unten breit (4), oben schmal (2), Spitze tanzt je nach Flackern.
		c.fill(x - 2, y - 9, x + 2, y - 6, out);
		c.fill(x - 1, y - 11, x + 1, y - 9, out);
		int tip = flicker > 0.5f ? 0 : -1;
		c.fill(x + tip, y - 12, x + tip + 1, y - 11, out);
		c.fill(x - 1, y - 9, x + 1, y - 6, mid);
		c.fill(x - 1, y - 8, x, y - 6, core);
		// Glut am Fuß.
		c.fill(x - 2, y - 6, x + 2, y - 5, ColorMath.lerp(0xFF3B2614, GLOW, l));
	}

	// --- Fuchsgesicht (wie neben dem Logo im Launcher) ---

	/**
	 * Pixel-Fuchsgesicht 9 × 7 Punkte ({@code px} GUI-Pixel je Punkt), Fell in {@code fur}, weiße Wangen;
	 * {@code blink} = Augen zu.
	 */
	public static void foxMark(Canvas c, int x, int y, int px, int fur, boolean blink) {
		int dark = 0xFF2A1A16;
		int white = 0xFFFFF4EA;
		r(c, x, y, px, 1, 0, 1, 1, dark);
		r(c, x, y, px, 7, 0, 1, 1, dark);
		r(c, x, y, px, 1, 1, 2, 1, fur);
		r(c, x, y, px, 6, 1, 2, 1, fur);
		r(c, x, y, px, 0, 2, 9, 3, fur);
		r(c, x, y, px, 2, 5, 5, 1, fur);
		r(c, x, y, px, 4, 5, 1, 1, dark);
		r(c, x, y, px, 0, 4, 2, 1, white);
		r(c, x, y, px, 7, 4, 2, 1, white);
		r(c, x, y, px, 1, 5, 1, 1, white);
		r(c, x, y, px, 7, 5, 1, 1, white);
		r(c, x, y, px, 2, 6, 5, 1, white);
		if (!blink) {
			r(c, x, y, px, 2, 3, 1, 1, dark);
			r(c, x, y, px, 6, 3, 1, 1, dark);
		}
	}

	private static void r(Canvas c, int x, int y, int px, int gx, int gy, int gw, int gh, int argb) {
		c.fill(x + gx * px, y + gy * px, x + (gx + gw) * px, y + (gy + gh) * px, argb);
	}

	/** Blinzeln: alle 6 s kurz die Augen zu (wie im Launcher). */
	public static boolean blink(float seconds) {
		float p = (seconds % 6f) / 6f;
		return p >= 0.94f && p < 0.97f;
	}

	/** Fuchsgesicht mit warmem Schein dahinter (Logo-Zeilen). */
	public static void foxMarkGlow(Canvas c, int x, int y, int px, int fur, float seconds, boolean animated, float pulse) {
		Theme t = Theme.get();
		c.fill(x - 1, y - 1, x + 9 * px + 1, y + 7 * px + 1, ColorMath.withAlpha(t.glow, Math.round(30 * pulse)));
		foxMark(c, x, y, px, fur, animated && blink(seconds));
	}

	// --- Laub auf dem Schriftzug ---

	/**
	 * Fuchsbau: ein paar Blätter und eine Blüte liegen auf dem Schriftzug – auf einigen nach oben offenen Pixelkanten
	 * ein kleines Herbstblatt, an einer Kante ein Zweig. Sparsam, damit die Schrift ruhig bleibt.
	 */
	public static void leafCaps(Canvas c, List<int[]> rects, int x, int y, int px) {
		int gw = 0;
		for (int[] r : rects) gw = Math.max(gw, r[2]);
		boolean[][] on = new boolean[PixelFont.HEIGHT][gw];
		for (int[] r : rects) {
			for (int col = r[0]; col < r[2]; col++) on[r[1]][col] = true;
		}
		int cap = Math.max(1, Math.round(px * 0.4f));
		int n = 0;
		for (int row = 0; row < PixelFont.HEIGHT; row++) {
			for (int col = 0; col < gw; col++) {
				boolean edge = on[row][col] && (row == 0 || !on[row - 1][col]);
				if (!edge || (col * 7 + row * 3) % 6 != 2) continue;
				int lx = x + col * px + Math.max(0, (px - 2 * cap) / 2);
				int ly = y + row * px;
				int colr = (n % 4 == 3) ? PETAL : FALL[n % FALL.length];
				// Blatt: flach aufliegend, die Spitze hebt sich, ein dunkler Stiel; darunter ein Hauch Schatten.
				c.fill(lx, ly - cap, lx + 2 * cap, ly, colr);
				c.fill(lx + cap, ly - 2 * cap, lx + 2 * cap, ly - cap, ColorMath.lerp(colr, WHITE, 0.3f));
				c.fill(lx - Math.max(1, cap / 2), ly - Math.max(1, cap / 2), lx, ly, ColorMath.scaleRgb(colr, 0.55f));
				c.fill(lx, ly, lx + 2 * cap, ly + Math.max(1, cap / 2), ColorMath.withAlpha(0xFF000000, 0x40));
				n++;
			}
		}
	}

	// --- Glimmender Ast (Fortschritt) ---

	/**
	 * Fortschritt als glimmender Ast (Launcher: EmberProgress): von links frisst sich die Glut durch das dunkle Holz, vorn
	 * sitzt eine kleine Flamme, aus der ein Funke steigt. {@code progress} &lt; 0 = unbekannt (ein Funke läuft durch).
	 */
	public static void bar(Canvas c, int cx, int y, int maxWidth, float progress, float time) {
		Theme t = Theme.get();
		int w = Math.max(24, Math.min(maxWidth, 200));
		int h = 6;
		int x0 = cx - w / 2;
		int a500 = t.accent;
		int a400 = ColorMath.lerp(a500, WHITE, 0.38f);
		int a300 = ColorMath.lerp(a500, WHITE, 0.62f);
		int a600 = ColorMath.scaleRgb(a500, 0.78f);
		int a900 = ColorMath.lerp(ColorMath.scaleRgb(a500, 0.22f), 0xFF000000, 0.25f);
		// Spur: verkohltes Holz mit Maserung.
		Redstone.block(c, x0 - 1, y - 1, w + 2, h + 2, 0xFF0C0608);
		c.fill(x0, y, x0 + w, y + h, ColorMath.lerp(a900, 0xFF1A0F08, 0.2f));
		c.fill(x0, y, x0 + w, y + 1, ColorMath.withAlpha(a300, 0x1A));
		c.fill(x0, y + h - 1, x0 + w, y + h, 0x4D000000);
		for (int gx = x0 + 9; gx < x0 + w; gx += 17) {
			c.fill(gx, y, gx + 1, y + h, 0x4D000000);
			if (gx + 8 < x0 + w) c.fill(gx + 8, y + 1, gx + 9, y + h - 1, ColorMath.withAlpha(a300, 0x1A));
		}
		if (progress < 0) {
			// Unbekannter Fortschritt: ein Funke läuft durch.
			int band = Math.max(12, w * 30 / 100);
			float phase = (time / 1.6f) % 1f;
			float ease = phase < 0.5f ? 2 * phase * phase : 1 - (float) Math.pow(-2 * phase + 2, 2) / 2;
			int bx = x0 - band + Math.round(ease * (w + band));
			int[] cols = {a600, a500, a400, GOLD_LIGHT, a500};
			int[] alpha = {0x60, 0xB0, 0xE0, 0xF0, 0x60};
			int seg = Math.max(1, band / 5);
			for (int i = 0; i < 5; i++) {
				int sx = Math.max(x0, bx + i * seg);
				int ex = Math.min(x0 + w, bx + (i + 1) * seg);
				if (ex > sx) c.fill(sx, y, ex, y + h, ColorMath.withAlpha(cols[i], alpha[i]));
			}
			int head = bx + seg * 4;
			if (head > x0 && head < x0 + w - 1) {
				c.fill(head - 3, y - 2, head + 3, y + h + 2, ColorMath.withAlpha(a400, 0x30));
				c.fill(head, y + 1, head + 1, y + h - 1, FLAME_CORE);
			}
			return;
		}
		float p = ColorMath.clamp01(progress);
		int fw = Math.round(w * p);
		if (fw <= 0) return;
		// Schein um die Glut.
		c.fill(x0, y - 2, x0 + fw, y - 1, ColorMath.withAlpha(a400, 0x30));
		c.fill(x0, y + h + 1, x0 + fw, y + h + 2, ColorMath.withAlpha(a400, 0x30));
		// Von dunkelrot (a600) über den Akzent (45 %) zu golden – in flächigen Stufen.
		int steps = Math.max(1, Math.min(12, fw / 7));
		for (int i = 0; i < steps; i++) {
			int sx = x0 + fw * i / steps;
			int ex = x0 + fw * (i + 1) / steps;
			float u = (sx - x0 + (ex - sx) / 2f) / (float) Math.max(1, fw);
			int col = u < 0.45f ? ColorMath.lerp(a600, a500, u / 0.45f) : ColorMath.lerp(a500, GOLD, (u - 0.45f) / 0.55f);
			c.fill(sx, y, ex, y + h, col);
		}
		c.fill(x0, y, x0 + fw, y + 1, ColorMath.lerp(0xFFFFF3C8, GOLD_LIGHT, 0.25f));
		int grain = ColorMath.withAlpha(0xFF3A1404, 0x59);
		for (int gx = x0 + 9; gx < x0 + fw; gx += 10) c.fill(gx, y + 1, gx + 1, y + h, grain);
		// Glut flimmert: helle Pixel wandern durch.
		int shift = Math.round((float) Math.floor(time / 0.2f) * 48f / 9f);
		int[][] sparks = {{4, 3, 2, 0}, {29, 2, 1, 0}, {17, 4, 1, 1}, {39, 3, 2, 1}};
		for (int[] s : sparks) {
			for (int base = 0; base < fw; base += 48) {
				int gx = x0 + base + Math.floorMod(s[0] + shift, 48);
				if (gx < x0 || gx + s[2] > x0 + fw) continue;
				c.fill(gx, y + s[1], gx + s[2], y + s[1] + 1, s[3] == 0 ? FLAME_CORE : ColorMath.withAlpha(FLAME_MID, 0xCC));
			}
		}
		if (p < 1f) {
			// Kleine Pixel-Flamme an der Glutkante, darüber ein aufsteigender Funke.
			int tx = x0 + fw;
			int px = 2;
			int fx = tx - 2 * px;
			int fy = y + h / 2 - Math.round(6 * px * 0.78f);
			boolean low = Math.floorMod((int) (time / 0.25f), 2) == 1;
			// Weicher Schein um die Flamme (gestuft, ohne harte Kastenkante).
			c.fill(tx - 6, y - 1, tx + 6, y + h + 1, ColorMath.withAlpha(GOLD, 0x16));
			c.fill(tx - 4, y - 3, tx + 4, y + h + 2, ColorMath.withAlpha(GOLD, 0x16));
			c.fill(tx - 2, y - 5, tx + 2, y + h + 3, ColorMath.withAlpha(GOLD, 0x14));
			flameTip(c, fx, fy + (low ? 1 : 0), px, low);
			float rise = (time % 1.2f) / 1.2f;
			int sy = fy - 2 - Math.round(rise * 10);
			if (rise < 0.85f) c.fill(fx + 3 + Math.round(rise * 2), sy, fx + 5 + Math.round(rise * 2), sy + 2, ColorMath.withAlpha(GOLD_LIGHT, Math.round(255 * (1 - rise))));
		}
	}

	/** Flamme 4 × 6 Punkte (wie die SVG im Launcher); {@code low} = zusammengezogen. */
	static void flameTip(Canvas c, int x, int y, int px, boolean low) {
		int top = low ? 1 : 0;
		r(c, x, y, px, 1, top, 1, 1, FLAME_OUT);
		r(c, x, y, px, 1, 1, 2, 1, FLAME_OUT);
		r(c, x, y, px, 0, 2, 4, 3, FLAME_OUT);
		r(c, x, y, px, 1, 5, 2, 1, FLAME_OUT);
		r(c, x, y, px, 1, 2, 2, 3, FLAME_MID);
		r(c, x, y, px, 1, 3, 1, 2, FLAME_CORE);
	}
}

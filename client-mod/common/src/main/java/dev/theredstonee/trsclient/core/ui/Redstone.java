package dev.theredstonee.trsclient.core.ui;

/**
 * Bausteine des Redstone-Stils: Steinflächen mit Pixelkante, Mulden, Leuchten, Redstone-Lampen,
 * Staubleitungen, Fackeln, Schalter und Tastenkappen. Alles aus Rechtecken im GUI-Raster
 * (1 Bildpunkt = 1 GUI-Pixel, wie die Vanilla-Texturen) – es skaliert also mit der GUI-Größe und
 * sieht in jeder Minecraft-Version gleich aus. Keine Mojang-Texturen.
 */
public final class Redstone {
	private Redstone() {
	}

	// --- Flächen ---

	/** Rechteck mit abgeschnittenen Ecken (1 px) – die Grundform des Pixel-Stils. */
	public static void block(Canvas c, int x, int y, int w, int h, int argb) {
		if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
		if (w < 3 || h < 3) {
			c.fill(x, y, x + w, y + h, argb);
			return;
		}
		c.fill(x + 1, y, x + w - 1, y + 1, argb);
		c.fill(x, y + 1, x + w, y + h - 1, argb);
		c.fill(x + 1, y + h - 1, x + w - 1, y + h, argb);
	}

	/** Rahmen (1 px) mit abgeschnittenen Ecken. */
	public static void frame(Canvas c, int x, int y, int w, int h, int argb) {
		if (w < 3 || h < 3 || (argb >>> 24) == 0) return;
		c.fill(x + 1, y, x + w - 1, y + 1, argb);
		c.fill(x + 1, y + h - 1, x + w - 1, y + h, argb);
		c.fill(x, y + 1, x + 1, y + h - 1, argb);
		c.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
	}

	/** Steinfläche: Kante, Fläche, Licht oben, Schatten unten. */
	public static void stone(Canvas c, int x, int y, int w, int h, int fill, int edge) {
		Theme t = Theme.get();
		block(c, x, y, w, h, edge);
		if (w < 3 || h < 4) return;
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
		c.fill(x + 1, y + 1, x + w - 1, y + 2, ColorMath.lerp(fill, t.bevelLight, 0.7f));
		c.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, ColorMath.lerp(fill, t.bevelDark, 0.7f));
	}

	/** Mulde (eingelassene Fläche): Schatten oben, Licht unten – für Felder und Schalter-Schienen. */
	public static void well(Canvas c, int x, int y, int w, int h, int edge) {
		Theme t = Theme.get();
		block(c, x, y, w, h, edge);
		if (w < 3 || h < 4) return;
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, t.deep);
		c.fill(x + 1, y + 1, x + w - 1, y + 2, t.bevelDark);
		c.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, ColorMath.lerp(t.deep, t.bevelLight, 0.35f));
	}

	/** Fenster: weicher Pixelschatten, Kante, Fläche mit Lichtkante oben. */
	public static void window(Canvas c, int x, int y, int w, int h) {
		Theme t = Theme.get();
		for (int i = 4; i >= 1; i--) block(c, x - i, y - i + 2, w + 2 * i, h + 2 * i, (28 / i) << 24);
		block(c, x, y, w, h, t.border);
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, t.background);
		c.fill(x + 1, y + 1, x + w - 1, y + 2, ColorMath.lerp(t.background, t.bevelLight, 0.6f));
	}

	/** Symbol in einer kleinen Mulde (Kachel, Kopfzeile); {@code px} = Pixel je Symbolpunkt. */
	public static void iconWell(Canvas c, int x, int y, int px, String icon, int argb, float glow) {
		Theme t = Theme.get();
		int size = px * 8 + 6;
		well(c, x, y, size, size, t.border);
		if (glow > 0.02f) c.fill(x + 2, y + 2, x + size - 2, y + size - 2, ColorMath.withAlpha(t.glow, Math.round(38 * ColorMath.clamp01(glow))));
		Icons.draw(c, icon, x + 3, y + 3, px, argb);
	}

	/**
	 * Leuchten um ein Rechteck: drei Pixelringe, die nach außen verblassen. Wird nach der Fläche
	 * gezeichnet, überdeckt sie also nicht.
	 */
	public static void glow(Canvas c, int x, int y, int w, int h, int argb, float strength) {
		float s = ColorMath.clamp01(strength);
		if (s < 0.02f) return;
		frame(c, x - 1, y - 1, w + 2, h + 2, ColorMath.withAlpha(argb, Math.round(96 * s)));
		frame(c, x - 2, y - 2, w + 4, h + 4, ColorMath.withAlpha(argb, Math.round(44 * s)));
		frame(c, x - 3, y - 3, w + 6, h + 6, ColorMath.withAlpha(argb, Math.round(18 * s)));
	}

	// --- Redstone-Lampe ---

	/**
	 * Redstone-Lampe als Knopf. {@code lit} 0 = aus (dunkles Glas im Rahmen), 1 = an (warmes Licht
	 * mit Leuchten); {@code flash} überblendet kurz weiß-heiß (Drücken).
	 */
	public static void lamp(Canvas c, int x, int y, int w, int h, float lit, float flash) {
		Theme t = Theme.get();
		float l = ColorMath.clamp01(lit);
		if (l > 0.02f) glow(c, x, y, w, h, t.lampGlow, l);
		block(c, x, y, w, h, ColorMath.lerp(t.lampOffEdge, t.lampOnEdge, l));
		if (w < 8 || h < 8) {
			c.fill(x + 1, y + 1, x + w - 1, y + h - 1, ColorMath.lerp(t.lampOff, t.lampOn, l));
			return;
		}
		int face = ColorMath.lerp(t.lampOff, t.lampOn, l);
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
		// Heißer Kern: die Mitte leuchtet stärker als der Rand.
		if (l > 0.02f) c.fill(x + 4, y + 4, x + w - 4, y + h - 4, ColorMath.withAlpha(t.lampHot, Math.round(120 * l)));
		// Rahmen der Lampe: Innenkante, Nieten in den Ecken, Stege oben und unten.
		int lattice = ColorMath.lerp(0xFF4C3726, 0xFFB86E26, l);
		frame(c, x + 2, y + 2, w - 4, h - 4, lattice);
		int rivet = ColorMath.lerp(0xFF5E4630, t.lampHot, l);
		c.fill(x + 2, y + 2, x + 4, y + 4, rivet);
		c.fill(x + w - 4, y + 2, x + w - 2, y + 4, rivet);
		c.fill(x + 2, y + h - 4, x + 4, y + h - 2, rivet);
		c.fill(x + w - 4, y + h - 4, x + w - 2, y + h - 2, rivet);
		int panes = Math.max(1, (w - 8) / 44);
		for (int i = 1; i < panes + 1 && w > 30; i++) {
			int px = x + i * w / (panes + 1);
			c.fill(px, y + 2, px + 1, y + 4, lattice);
			c.fill(px, y + h - 4, px + 1, y + h - 2, lattice);
		}
		// Lichtkante oben (Glas), dunkle Kante unten.
		c.fill(x + 1, y + 1, x + w - 1, y + 2, ColorMath.lerp(0xFF5A4431, 0xFFFFF1CC, l));
		c.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, ColorMath.lerp(0xFF1C140D, 0xFFA95F1E, l));
		float f = ColorMath.clamp01(flash);
		if (f > 0.02f) c.fill(x + 1, y + 1, x + w - 1, y + h - 1, ColorMath.withAlpha(0xFFFFF6DC, Math.round(170 * f)));
	}

	/** Schriftfarbe auf einer Lampe mit Leuchtstärke {@code lit}. */
	public static int lampTextColor(float lit) {
		Theme t = Theme.get();
		float l = ColorMath.clamp01(lit);
		// Kurz vor der Mitte umschalten, damit die Schrift nie grau auf Grau steht.
		float k = l < 0.35f ? 0f : (l > 0.6f ? 1f : (l - 0.35f) / 0.25f);
		return ColorMath.lerp(t.lampText, t.lampTextLit, k);
	}

	/** Beschriftete Lampe (Knopf). */
	public static void lampButton(Canvas c, int x, int y, int w, int h, String label, float lit, float flash) {
		lamp(c, x, y, w, h, lit, flash);
		int color = lampTextColor(lit);
		String s = c.textWidth(label) <= w - 10 ? label : c.clip(label, w - 10);
		c.text(s, x + (w - c.textWidth(s)) / 2, y + (h - 8) / 2, color, lit < 0.35f);
	}

	// --- Staub, Fackel ---

	/** Waagerechte Staubleitung (2 px) mit Leuchten bei Signal. */
	public static void dustH(Canvas c, int x1, int x2, int y, int argb, float glow) {
		if (x2 <= x1) return;
		c.fill(x1, y, x2, y + 2, argb);
		if (glow > 0.02f) {
			int g = ColorMath.withAlpha(Theme.get().glow, Math.round(40 * ColorMath.clamp01(glow)));
			c.fill(x1, y - 1, x2, y, g);
			c.fill(x1, y + 2, x2, y + 3, g);
		}
	}

	/** Senkrechte Staubleitung (2 px) mit Leuchten bei Signal. */
	public static void dustV(Canvas c, int x, int y1, int y2, int argb, float glow) {
		if (y2 <= y1) return;
		c.fill(x, y1, x + 2, y2, argb);
		if (glow > 0.02f) {
			int g = ColorMath.withAlpha(Theme.get().glow, Math.round(40 * ColorMath.clamp01(glow)));
			c.fill(x - 1, y1, x, y2, g);
			c.fill(x + 2, y1, x + 3, y2, g);
		}
	}

	/**
	 * Redstone-Fackel (Seitenansicht): Stiel und Kopf; (x, y) = Mitte des Stielfußes.
	 * {@code lit} 0..1, {@code flicker} 0..1 zusätzliches Flackern des Kopfes.
	 */
	public static void torch(Canvas c, int x, int y, float lit, float flicker) {
		Theme t = Theme.get();
		float l = ColorMath.clamp01(lit);
		c.fill(x - 1, y - 6, x + 1, y, 0xFF6B4A2B);
		c.fill(x - 1, y - 6, x, y, 0xFF7E5A36);
		int head = ColorMath.lerp(0xFF4A1410, t.dustOn, l * (0.82f + 0.18f * flicker));
		if (l > 0.05f) {
			c.fill(x - 3, y - 11, x + 3, y - 5, ColorMath.withAlpha(t.glow, Math.round(34 * l)));
			c.fill(x - 4, y - 10, x + 4, y - 6, ColorMath.withAlpha(t.glow, Math.round(20 * l)));
		}
		c.fill(x - 2, y - 9, x + 2, y - 5, head);
		if (l > 0.05f) c.fill(x - 1, y - 8, x + 1, y - 6, ColorMath.lerp(head, 0xFFFFE0C0, 0.6f * l));
	}

	// --- Bedienelemente ---

	/** Schalter im Redstone-Stil: Mulde mit Staub, der Knopf ist eine kleine Lampe. */
	public static void toggle(Canvas c, int x, int y, int w, int h, float progress, boolean hover) {
		Theme t = Theme.get();
		float p = ColorMath.clamp01(progress);
		well(c, x, y, w, h, hover ? ColorMath.lerp(t.border, t.textDim, 0.6f) : t.border);
		int mid = y + h / 2 - 1;
		c.fill(x + 3, mid, x + w - 3, mid + 2, ColorMath.lerp(t.dustOff, t.dustOn, p));
		if (p > 0.02f) glow(c, x, y, w, h, t.glow, p * 0.7f);
		int k = Math.max(3, h - 4);
		int kx = x + 2 + Math.round((w - k - 4) * p);
		int ky = y + (h - k) / 2;
		int knob = ColorMath.lerp(hover ? 0xFF8E8E96 : 0xFF7A7A82, t.lampOn, p);
		block(c, kx, ky, k, k, knob);
		c.fill(kx + 1, ky + 1, kx + k - 1, ky + 2, ColorMath.lerp(knob, 0xFFFFFFFF, 0.3f));
		c.fill(kx + 1, ky + k - 2, kx + k - 1, ky + k - 1, ColorMath.lerp(knob, 0xFF000000, 0.25f));
		int core = Math.max(1, k / 3);
		int cx = kx + (k - core) / 2;
		int cy = ky + (k - core) / 2;
		c.fill(cx, cy, cx + core, cy + core, ColorMath.lerp(0xFF3A1210, t.lampHot, p));
	}

	/**
	 * Schieberegler: Staubleitung, deren Signal zum Knopf hin stärker wird; der Knopf ist ein
	 * kleiner Verstärker mit Fackel.
	 */
	public static void slider(Canvas c, int x, int y, int w, int h, float fraction, boolean hover) {
		Theme t = Theme.get();
		float f = ColorMath.clamp01(fraction);
		int trackY = y + h / 2 - 1;
		c.fill(x, trackY, x + w, trackY + 2, t.dustOff);
		int filled = Math.round(w * f);
		if (filled > 0) {
			int segments = Math.max(1, Math.min(8, filled / 6));
			for (int i = 0; i < segments; i++) {
				int sx = x + filled * i / segments;
				int ex = x + filled * (i + 1) / segments;
				int level = 15 - (segments - 1 - i);
				c.fill(sx, trackY, ex, trackY + 2, t.dust(level));
			}
			int g = ColorMath.withAlpha(t.glow, 36);
			c.fill(x, trackY - 1, x + filled, trackY, g);
			c.fill(x, trackY + 2, x + filled, trackY + 3, g);
		}
		int kw = 6;
		int kh = Math.min(h, 10);
		int kx = x + filled - kw / 2;
		int ky = y + (h - kh) / 2;
		if (hover) glow(c, kx, ky, kw, kh, t.glow, 0.8f);
		stone(c, kx, ky, kw, kh, hover ? 0xFF9A9AA2 : 0xFF84848C, 0xFF2A2A30);
		c.fill(kx + 2, ky + 2, kx + 4, ky + 4, hover ? t.lampHot : t.dustOn);
	}

	/** Knopf: {@code primary} = leuchtende Lampe, sonst Stein mit Staubkante beim Überfahren. */
	public static void button(Canvas c, int x, int y, int w, int h, String label, boolean primary, boolean hover) {
		Theme t = Theme.get();
		if (primary) {
			lamp(c, x, y, w, h, hover ? 1f : 0.82f, 0f);
			String s = c.clip(label, w - 6);
			c.text(s, x + (w - c.textWidth(s)) / 2, y + (h - 8) / 2 + (h >= 16 ? 0 : 1), lampTextColor(1f), false);
			return;
		}
		stone(c, x, y, w, h, hover ? t.surfaceHover : t.surfaceHigh, hover ? ColorMath.lerp(t.border, t.textDim, 0.5f) : t.border);
		if (hover) dustH(c, x + 3, x + w - 3, y + h - 3, t.dustOn, 0f);
		String s = c.clip(label, w - 6);
		c.text(s, x + (w - c.textWidth(s)) / 2, y + (h - 8) / 2 + (h >= 16 ? 0 : 1) - (hover ? 1 : 0), t.text, false);
	}

	/** Quadratischer Symbol-Knopf. */
	public static void iconButton(Canvas c, int x, int y, int size, String icon, boolean hover, boolean primary) {
		Theme t = Theme.get();
		int px = Math.max(1, (size - 4) / 8);
		int iconSize = px * 8;
		int ix = x + (size - iconSize) / 2;
		int iy = y + (size - iconSize) / 2;
		if (primary) {
			lamp(c, x, y, size, size, hover ? 1f : 0.82f, 0f);
			Icons.draw(c, icon, ix, iy, px, t.lampTextLit);
			return;
		}
		stone(c, x, y, size, size, hover ? t.surfaceHover : t.surfaceHigh, hover ? ColorMath.lerp(t.border, t.textDim, 0.5f) : t.border);
		if (hover) glow(c, x, y, size, size, t.glow, 0.45f);
		Icons.draw(c, icon, ix, iy, px, hover ? t.dustOn : t.text);
	}

	/** Tastenkappe mit Beschriftung; liefert die Breite. */
	public static int keycap(Canvas c, int x, int y, String label, int maxWidth) {
		Theme t = Theme.get();
		String s = c.textWidth(label) + 8 <= maxWidth ? label : c.clip(label, Math.max(0, maxWidth - 8));
		int w = Math.min(maxWidth, c.textWidth(s) + 8);
		block(c, x, y, w, 14, t.bevelDark);
		block(c, x, y, w, 12, t.border);
		c.fill(x + 1, y + 1, x + w - 1, y + 11, t.surfaceHigh);
		c.fill(x + 1, y + 1, x + w - 1, y + 2, ColorMath.lerp(t.surfaceHigh, t.bevelLight, 0.7f));
		c.text(s, x + 4, y + 2, t.text, false);
		return w;
	}

	/** Tastenkappe fester Breite, Beschriftung mittig (Tastenbelegung in den Einstellungen). */
	public static void keycap(Canvas c, int x, int y, int w, String label, boolean hover) {
		Theme t = Theme.get();
		int face = hover ? t.surfaceHover : t.surfaceHigh;
		block(c, x, y, w, 15, t.bevelDark);
		block(c, x, y, w, 13, hover ? ColorMath.lerp(t.border, t.textDim, 0.5f) : t.border);
		c.fill(x + 1, y + 1, x + w - 1, y + 12, face);
		c.fill(x + 1, y + 1, x + w - 1, y + 2, ColorMath.lerp(face, t.bevelLight, 0.7f));
		String s = c.clip(label, Math.max(0, w - 6));
		c.text(s, x + (w - c.textWidth(s)) / 2, y + 3, t.text, false);
	}

	/** Kleine Lampe als Statusanzeige (z. B. "an"); {@code size} in Pixeln. */
	public static void pip(Canvas c, int x, int y, int size, float lit) {
		Theme t = Theme.get();
		float l = ColorMath.clamp01(lit);
		if (l > 0.05f) glow(c, x, y, size, size, t.lampGlow, l * 0.8f);
		block(c, x, y, size, size, ColorMath.lerp(t.lampOffEdge, t.lampOnEdge, l));
		if (size > 2) c.fill(x + 1, y + 1, x + size - 1, y + size - 1, ColorMath.lerp(t.lampOff, t.lampOn, l));
		if (size > 4 && l > 0.05f) c.fill(x + 2, y + 2, x + size - 2, y + size - 2, ColorMath.lerp(t.lampOn, t.lampHot, l));
	}
}

package dev.theredstonee.trsclient.core.ui.title;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Theme;

/**
 * Redstone-Drehscheibe unter der Spielerfigur: eine runde Plattform aus Redstone-Block (Seitenband mit
 * wanderndem Pixelmuster), obenauf Stein mit einem Ring aus glühendem Staub und Verstärkern, die sich
 * mitdrehen. Alles aus Rechtecken im Pixelraster ({@code px} GUI-Pixel je Bildpunkt).
 *
 * <p>Drehung: langsam von selbst ({@link #AUTO_SPEED}); per Ziehen mit der Maus mit Schwung, der danach
 * wieder in die Eigendrehung übergeht. Gezeichnet wird in zwei Durchgängen – hinterer Teil vor der Figur,
 * vorderer Teil (Verstärker vorn, Rand) danach.
 */
public final class Turntable {
	/** Eigendrehung in Grad je Sekunde (eine Runde in 24 s). */
	public static final float AUTO_SPEED = 15f;
	/** Grad je GUI-Pixel beim Ziehen. */
	static final float DRAG_DEG_PER_PX = 1.1f;
	private static final int REPEATERS = 6;
	private static final int DUST_DOTS = 28;

	private float angle = 20f;
	private float velocity = AUTO_SPEED;
	private boolean dragging;
	private double lastX;
	private long lastDragNanos;

	/** Aktueller Drehwinkel in Grad (0 = Figur schaut zum Betrachter). */
	public float angle() {
		return angle;
	}

	/** Winkel setzen (Selbsttest: feste Ansicht für Screenshots). */
	public void setAngle(float degrees) {
		angle = wrap(degrees);
	}

	public boolean dragging() {
		return dragging;
	}

	/** Zeitschritt: Eigendrehung bzw. Nachlauf nach dem Ziehen. */
	public void tick(float dt, boolean animated) {
		if (dragging) return;
		float target = animated ? AUTO_SPEED : 0f;
		// Schwung klingt in ~0,6 s auf die Eigendrehung ab.
		float k = Math.min(1f, dt / 0.6f);
		velocity += (target - velocity) * k;
		angle = wrap(angle + velocity * dt);
	}

	/** Ziehen beginnt bei {@code x}. */
	public void grab(double x) {
		dragging = true;
		lastX = x;
		lastDragNanos = System.nanoTime();
		velocity = 0f;
	}

	/** Maus bewegt sich beim Ziehen. */
	public void drag(double x) {
		if (!dragging) return;
		long now = System.nanoTime();
		float dx = (float) (x - lastX);
		float dt = Math.max(0.004f, (now - lastDragNanos) / 1_000_000_000f);
		angle = wrap(angle + dx * DRAG_DEG_PER_PX);
		float v = dx * DRAG_DEG_PER_PX / dt;
		velocity = velocity * 0.5f + Math.max(-720f, Math.min(720f, v)) * 0.5f;
		lastX = x;
		lastDragNanos = now;
	}

	/** Loslassen: der Schwung bleibt und geht in die Eigendrehung über. */
	public void release() {
		if (!dragging) return;
		dragging = false;
		// Lange still gehalten → kein Schwung.
		if (System.nanoTime() - lastDragNanos > 120_000_000L) velocity = 0f;
	}

	static float wrap(float deg) {
		float a = deg % 360f;
		return a < 0 ? a + 360f : a;
	}

	// --- Zeichnen ---

	/**
	 * Hinterer Teil: Schein, Seitenband, Oberfläche, Staubring, hintere Verstärker.
	 *
	 * @param cx     Mitte
	 * @param cy     Mitte der Oberseite (hier stehen die Füße)
	 * @param radius Radius in GUI-Pixeln
	 * @param squash Höhe/Breite der Ellipse (Blick von schräg oben)
	 * @param px     Pixelgröße
	 * @param time   Sekunden (Animation des Signals)
	 */
	public void drawBack(Canvas c, int cx, int cy, int radius, float squash, int px, float time) {
		Theme t = Theme.get();
		int ry = Math.max(px, Math.round(radius * squash));
		int thick = Math.max(2 * px, Math.round(radius * 0.22f / px) * px);
		if (t.halloween) {
			graveBack(c, cx, cy, radius, ry, thick, px, time);
			return;
		}
		if (t.winter) {
			floeBack(c, t, cx, cy, radius, ry, thick, squash, px, time);
			return;
		}
		// Leuchten unter der Scheibe
		int glow = t.glow;
		for (int i = 3; i >= 1; i--) {
			ellipse(c, cx, cy + thick, radius + i * 3 * px, ry + i * px, px, ColorMath.withAlpha(glow, 10 + (3 - i) * 8));
		}
		// Seitenband (Redstone-Block): untere Ellipsenhälfte nach unten verlängert
		band(c, cx, cy, radius, ry, thick, px, time);
		// Oberfläche: glatter Stein mit Kante
		ellipse(c, cx, cy, radius, ry, px, 0xFF4A4A50);
		ellipse(c, cx, cy, radius - px, Math.max(px, ry - px), px, 0xFF6B6B73);
		ellipse(c, cx, cy, radius - 3 * px, Math.max(px, ry - 2 * px), px, 0xFF5E5E66);
		// Staubring mit umlaufendem Signal
		dustRing(c, t, cx, cy, radius, squash, px, time, true);
		repeaters(c, t, cx, cy, radius, squash, px, time, true);
		// Mittelplatte (hier steht die Figur): Redstone-Lampe, glüht leicht
		int pr = Math.max(2 * px, Math.round(radius * 0.36f / px) * px);
		int pry = Math.max(px, Math.round(pr * squash));
		float pulse = 0.55f + 0.45f * (float) (0.5 + 0.5 * Math.sin(time * 2.1));
		ellipse(c, cx, cy, pr + px, pry + px, px, 0xFF2B1A10);
		ellipse(c, cx, cy, pr, pry, px, ColorMath.lerp(t.lampOff, t.lampOn, 0.55f * pulse));
		ellipse(c, cx, cy, Math.max(px, pr - 2 * px), Math.max(px, pry - px), px, ColorMath.withAlpha(t.lampHot, Math.round(70 * pulse)));
	}

	/** Vorderer Teil (nach der Figur): vordere Hälfte des Staubrings und die vorderen Verstärker. */
	public void drawFront(Canvas c, int cx, int cy, int radius, float squash, int px, float time) {
		Theme t = Theme.get();
		if (t.halloween) {
			graveFront(c, cx, cy, radius, Math.max(px, Math.round(radius * squash)), px, time);
			return;
		}
		if (t.winter) {
			frostRing(c, t, cx, cy, radius, squash, px, time, false);
			mounds(c, t, cx, cy, radius, squash, px, time, false);
			return;
		}
		dustRing(c, t, cx, cy, radius, squash, px, time, false);
		repeaters(c, t, cx, cy, radius, squash, px, time, false);
	}

	/**
	 * Halloween: Grabhügel aus dunkler Erde mit Moos und Grasbüscheln, ein schiefer Grabstein dahinter, zwei Kerzen
	 * (Licht flackert), violetter Mondschein darunter. Die Lage der Büschel hängt am Winkel – die Drehung bleibt sichtbar.
	 */
	private void graveBack(Canvas c, int cx, int cy, int rx, int ry, int thick, int px, float time) {
		for (int i = 3; i >= 1; i--) {
			ellipse(c, cx, cy + thick, rx + i * 3 * px, ry + i * px, px, ColorMath.withAlpha(0xFFB9A2EA, 8 + (3 - i) * 6));
		}
		// Grabstein hinter der Figur (leicht schief, Kreuz eingeritzt)
		int sw = Math.max(4 * px, Math.round(rx * 0.42f / px) * px);
		int sh = Math.max(6 * px, Math.round(rx * 0.7f / px) * px);
		int sx = cx + rx / 3 - sw / 2;
		int sy = cy - ry / 2 - sh;
		c.fill(sx, sy + px, sx + sw, sy + sh, 0xFF1C1730);
		c.fill(sx + px, sy, sx + sw - px, sy + px, 0xFF1C1730);
		c.fill(sx + sw - px, sy + px, sx + sw, sy + sh, 0xFF2D2648);
		int kx = sx + sw / 2 - px / 2;
		c.fill(kx, sy + 2 * px, kx + px, sy + 2 * px + Math.max(3 * px, sh / 3), 0xFF3B3851);
		c.fill(kx - px, sy + 3 * px, kx + 2 * px, sy + 4 * px, 0xFF3B3851);
		// Erdband (Seite) und Oberfläche
		for (int y = 0; y < ry + thick; y += px) {
			int hw;
			if (y < thick) {
				hw = rx;
			} else {
				float fy = (y - thick + px / 2f) / (float) ry;
				float f = 1f - fy * fy;
				if (f <= 0f) continue;
				hw = Math.round(rx * (float) Math.sqrt(f) / px) * px;
			}
			if (hw <= 0) continue;
			c.fill(cx - hw, cy + y, cx + hw, cy + y + px, y < thick / 2 ? 0xFF1A1426 : 0xFF120E1C);
		}
		ellipse(c, cx, cy, rx, ry, px, 0xFF1E1832);
		ellipse(c, cx, cy, rx - px, Math.max(px, ry - px), px, 0xFF262040);
		ellipse(c, cx, cy, rx - 3 * px, Math.max(px, ry - 2 * px), px, 0xFF2B2348);
		// Moos/Grasbüschel auf der Oberfläche, wandern mit der Drehung
		float spin = -(float) Math.toRadians(angle);
		for (int i = 0; i < 14; i++) {
			double a = spin + i * (Math.PI * 2 / 14);
			int x = cx + (int) Math.round(Math.cos(a) * rx * 0.78 / px) * px;
			int y = cy + (int) Math.round(Math.sin(a) * ry * 0.78 / px) * px;
			int col = (i % 3 == 0) ? 0xFF2F4A35 : 0xFF3F5A3A;
			c.fill(x, y - px, x + px, y + px, col);
			if (i % 2 == 0) c.fill(x + px, y - 2 * px, x + 2 * px, y, col);
		}
		// Kerzen links und rechts
		float f1 = 0.6f + 0.4f * (float) (Math.sin(time * 9) * Math.sin(time * 5.3));
		float f2 = 0.6f + 0.4f * (float) (Math.sin(time * 8.1 + 2) * Math.sin(time * 4.7 + 1));
		dev.theredstonee.trsclient.core.ui.halloween.HalloweenLook.candle(c, cx - rx * 2 / 3, cy - ry / 3, 1f, f1);
		dev.theredstonee.trsclient.core.ui.halloween.HalloweenLook.candle(c, cx + rx * 2 / 3, cy - ry / 4, 1f, f2);
	}

	/** Halloween vorn: Nebel, der langsam über den vorderen Rand zieht. */
	private static void graveFront(Canvas c, int cx, int cy, int rx, int ry, int px, float time) {
		for (int band = 0; band < 3; band++) {
			int y = cy + ry / 2 + band * px * 2;
			for (int x = cx - rx - 6 * px; x < cx + rx + 6 * px; x += px) {
				double v = 0.5 + 0.5 * Math.sin((x * 0.11) + time * (0.8 + band * 0.3) + band * 1.7) * Math.sin(x * 0.05 - time * 0.4);
				int a = (int) Math.round(Math.max(0, v) * (26 - band * 6));
				if (a <= 2) continue;
				c.fill(x, y, x + px, y + px * 2, (a << 24) | 0x9690B6);
			}
		}
	}

	/** Gefüllte Ellipse aus waagerechten Streifen im Pixelraster. */
	static void ellipse(Canvas c, int cx, int cy, int rx, int ry, int px, int argb) {
		if (rx <= 0 || ry <= 0 || (argb >>> 24) == 0) return;
		for (int y = -ry; y < ry; y += px) {
			float fy = (y + px / 2f) / (float) ry;
			float f = 1f - fy * fy;
			if (f <= 0f) continue;
			int hw = Math.round(rx * (float) Math.sqrt(f) / px) * px;
			if (hw <= 0) continue;
			c.fill(cx - hw, cy + y, cx + hw, cy + y + px, argb);
		}
	}

	/** Seitenband: Redstone-Block mit Pixelmuster, das mit der Drehung wandert. */
	private void band(Canvas c, int cx, int cy, int rx, int ry, int thick, int px, float time) {
		int base = 0xFFA3170F;
		int dark = 0xFF6E0B06;
		int light = 0xFFD8321F;
		// Zylindermantel: zwischen der Mitte der Oberseite und der unteren Hälfte der um thick tieferen Bodenellipse.
		// Die Oberseite wird danach darübergezeichnet.
		for (int y = 0; y < ry + thick; y += px) {
			int hw;
			if (y < thick) {
				hw = rx;
			} else {
				float fy = (y - thick + px / 2f) / (float) ry;
				float f = 1f - fy * fy;
				if (f <= 0f) continue;
				hw = Math.round(rx * (float) Math.sqrt(f) / px) * px;
			}
			if (hw <= 0) continue;
			// Unterkante dunkler, dazu eine helle Lichtkante links
			float fy2 = (y - thick + px / 2f) / (float) ry;
			boolean bottom = y >= thick && 1f - (fy2 + px / (float) ry) * (fy2 + px / (float) ry) <= 0.35f;
			c.fill(cx - hw, cy + y, cx + hw, cy + y + px, bottom ? dark : base);
			c.fill(cx - hw, cy + y, cx - hw + px, cy + y + px, dark);
			c.fill(cx + hw - px, cy + y, cx + hw, cy + y + px, dark);
		}
		// Muster: Punkte auf dem sichtbaren Vorderrand; Lage hängt am Winkel → Drehung sichtbar.
		float phase = -(float) Math.toRadians(angle);
		int rows = Math.max(1, thick / px);
		for (int i = 0; i < 18; i++) {
			double a = phase + i * (Math.PI * 2 / 18);
			double s = Math.sin(a);
			if (s <= 0.05) continue; // Rückseite
			int x = cx + (int) Math.round(Math.cos(a) * (rx - px) / px) * px;
			int row = (i * 7) % rows;
			int y = cy + (int) Math.round(s * ry / px) * px + row * px;
			if (y + px > cy + ry + thick - px) y = cy + ry + thick - 2 * px;
			c.fill(x, y, x + px, y + px, (i & 1) == 0 ? light : dark);
		}
	}

	/** Staubring: Punkte auf einer Ellipse, ein Signal läuft im Kreis (Stärke fällt dahinter ab). */
	private void dustRing(Canvas c, Theme t, int cx, int cy, int rx, float squash, int px, float time, boolean back) {
		float r = rx * 0.72f;
		float spin = -(float) Math.toRadians(angle);
		float head = (time * 1.6f) % 1f;
		for (int i = 0; i < DUST_DOTS; i++) {
			double a = spin + i * (Math.PI * 2 / DUST_DOTS);
			double s = Math.sin(a);
			if (back != (s < 0)) continue;
			int x = cx + (int) Math.round(Math.cos(a) * r / px) * px;
			int y = cy + (int) Math.round(s * r * squash / px) * px;
			float pos = i / (float) DUST_DOTS;
			float behind = (head - pos + 1f) % 1f;
			int level = Math.max(1, 15 - Math.round(behind * 15f));
			int color = t.dust(level);
			int size = px * 2;
			if (level > 9) c.fill(x - px, y - px, x + size + px, y + size, ColorMath.withAlpha(t.glow, 26 + (level - 9) * 8));
			c.fill(x, y, x + size, y + px, color);
		}
	}

	/** Verstärker (Draufsicht, schräg): Steinplatte mit zwei Fackeln, abwechselnd an. */
	private void repeaters(Canvas c, Theme t, int cx, int cy, int rx, float squash, int px, float time, boolean back) {
		float r = rx * 0.9f;
		float spin = -(float) Math.toRadians(angle);
		for (int i = 0; i < REPEATERS; i++) {
			double a = spin + (i + 0.5) * (Math.PI * 2 / REPEATERS);
			double s = Math.sin(a);
			if (back != (s < 0)) continue;
			int w = px * 4;
			int h = px * 2;
			int x = cx + (int) Math.round(Math.cos(a) * r / px) * px - w / 2;
			int y = cy + (int) Math.round(s * r * squash / px) * px - h / 2;
			boolean on = ((int) (time * 2.5f) + i) % 3 != 0;
			c.fill(x, y, x + w, y + h, 0xFF9A9AA2);
			c.fill(x, y + h - px, x + w, y + h, 0xFF6A6A72);
			int torch = on ? t.dustOn : 0xFF4A1410;
			c.fill(x + px / 2, y - px, x + px / 2 + px, y + px, torch);
			c.fill(x + w - px - px / 2, y - px, x + w - px / 2, y + px, torch);
			if (on) c.fill(x - px, y - 2 * px, x + w + px, y + px, ColorMath.withAlpha(t.glow, 30));
		}
	}

	// --- Winternacht: Eisscholle ---

	/**
	 * Winternacht: statt der Redstone-Drehscheibe eine Eisscholle auf dunklem Wasser – Seitenband aus geschichtetem Eis
	 * mit Rissen und Luftblasen, die mitwandern, obenauf Schnee, ein Ring aus Reif mit umlaufendem Glitzern, kleine
	 * Schneehügel mit Eiskristallen und in der Mitte eine Eislaterne im Boden.
	 */
	private void floeBack(Canvas c, Theme t, int cx, int cy, int radius, int ry, int thick, float squash, int px, float time) {
		// Wasser: dunkle Kreise um die Scholle, eine Welle läuft langsam nach außen.
		float wave = (time * 0.35f) % 1f;
		int waterY = cy + thick + px;
		ellipse(c, cx, waterY, radius + 4 * px, ry + 2 * px, px, t.light ? 0x40275A86 : 0x700A1A30);
		int wr = radius + Math.round((2 + 4 * wave) * px);
		ellipse(c, cx, waterY, wr, ry + Math.round((1 + 2 * wave) * px), px, ColorMath.withAlpha(0xFF9FD8FF, Math.round(30 * (1 - wave))));
		ellipse(c, cx, waterY, wr - px, Math.max(px, ry + Math.round((1 + 2 * wave) * px) - px), px, t.light ? 0x40275A86 : 0x700A1A30);
		// Leuchten unter der Scholle.
		for (int i = 3; i >= 1; i--) {
			ellipse(c, cx, cy + thick, radius + i * 3 * px, ry + i * px, px, ColorMath.withAlpha(t.lampGlow, 8 + (3 - i) * 6));
		}
		iceBand(c, cx, cy, radius, ry, thick, px);
		// Oberfläche: Schnee, Kante im Schatten, Licht von rechts.
		ellipse(c, cx, cy, radius, ry, px, 0xFF9FB8DA);
		ellipse(c, cx, cy, radius - px, Math.max(px, ry - px), px, 0xFFDCE9F8);
		ellipse(c, cx + px, cy, radius - 3 * px, Math.max(px, ry - 2 * px), px, 0xFFF2F8FF);
		frostRing(c, t, cx, cy, radius, squash, px, time, true);
		mounds(c, t, cx, cy, radius, squash, px, time, true);
		// Mitte: Eislaterne im Schnee, pulsiert leise.
		int pr = Math.max(2 * px, Math.round(radius * 0.36f / px) * px);
		int pry = Math.max(px, Math.round(pr * squash));
		float pulse = 0.55f + 0.45f * (float) (0.5 + 0.5 * Math.sin(time * 2.1));
		ellipse(c, cx, cy, pr + px, pry + px, px, 0xFF2A4A70);
		ellipse(c, cx, cy, pr, pry, px, ColorMath.lerp(t.lampOff, t.lampOn, 0.55f * pulse));
		ellipse(c, cx, cy, Math.max(px, pr - 2 * px), Math.max(px, pry - px), px, ColorMath.withAlpha(t.lampHot, Math.round(80 * pulse)));
	}

	/** Seitenband der Scholle: Eisschichten (oben hell, unten tief blau), Risse und Blasen wandern mit der Drehung. */
	private void iceBand(Canvas c, int cx, int cy, int rx, int ry, int thick, int px) {
		int base = 0xFF7FB6E0;
		int deep = 0xFF3C76A8;
		int edge = 0xFF2A5A88;
		for (int y = 0; y < ry + thick; y += px) {
			int hw;
			if (y < thick) {
				hw = rx;
			} else {
				float fy = (y - thick + px / 2f) / (float) ry;
				float f = 1f - fy * fy;
				if (f <= 0f) continue;
				hw = Math.round(rx * (float) Math.sqrt(f) / px) * px;
			}
			if (hw <= 0) continue;
			float depth = y / (float) Math.max(px, ry + thick);
			c.fill(cx - hw, cy + y, cx + hw, cy + y + px, ColorMath.lerp(base, deep, depth));
			c.fill(cx - hw, cy + y, cx - hw + px, cy + y + px, edge);
			c.fill(cx + hw - px, cy + y, cx + hw, cy + y + px, edge);
		}
		// Schichtfuge knapp unter der Oberkante.
		int seamY = cy + Math.max(px, thick / 2);
		c.fill(cx - rx + px, seamY, cx + rx - px, seamY + px, 0x50FFFFFF);
		float phase = -(float) Math.toRadians(angle);
		int rows = Math.max(1, thick / px);
		for (int i = 0; i < 18; i++) {
			double a = phase + i * (Math.PI * 2 / 18);
			double s = Math.sin(a);
			if (s <= 0.05) continue;
			int x = cx + (int) Math.round(Math.cos(a) * (rx - px) / px) * px;
			int row = (i * 7) % rows;
			int y = cy + (int) Math.round(s * ry / px) * px + row * px;
			if (y + px > cy + ry + thick - px) y = cy + ry + thick - 2 * px;
			if ((i & 1) == 0) {
				c.fill(x, y, x + px, y + px, 0xC0FFFFFF);
			} else {
				// Kurzer Riss schräg nach unten.
				c.fill(x, y, x + px, y + px, 0xFF2F6596);
				c.fill(x + px, y + px, x + 2 * px, y + 2 * px, 0xFF2F6596);
			}
		}
	}

	/** Ring aus Reif: Punkte auf einer Ellipse, ein Glitzern läuft im Kreis (statt des Signals im Staub). */
	private void frostRing(Canvas c, Theme t, int cx, int cy, int rx, float squash, int px, float time, boolean back) {
		float r = rx * 0.72f;
		float spin = -(float) Math.toRadians(angle);
		float head = (time * 0.8f) % 1f;
		for (int i = 0; i < DUST_DOTS; i++) {
			double a = spin + i * (Math.PI * 2 / DUST_DOTS);
			double s = Math.sin(a);
			if (back != (s < 0)) continue;
			int x = cx + (int) Math.round(Math.cos(a) * r / px) * px;
			int y = cy + (int) Math.round(s * r * squash / px) * px;
			float pos = i / (float) DUST_DOTS;
			float behind = (head - pos + 1f) % 1f;
			float glint = Math.max(0f, 1f - behind * 6f);
			int color = ColorMath.lerp(0xFFAFCDEB, 0xFFFFFFFF, glint);
			if (glint > 0.3f) c.fill(x - px, y - px, x + 3 * px, y + px, ColorMath.withAlpha(t.lampGlow, Math.round(50 * glint)));
			c.fill(x, y, x + px * 2, y + px, color);
		}
	}

	/** Kleine Schneehügel mit je einem Eiskristall obenauf, die abwechselnd funkeln (statt der Verstärker). */
	private void mounds(Canvas c, Theme t, int cx, int cy, int rx, float squash, int px, float time, boolean back) {
		float r = rx * 0.9f;
		float spin = -(float) Math.toRadians(angle);
		for (int i = 0; i < REPEATERS; i++) {
			double a = spin + (i + 0.5) * (Math.PI * 2 / REPEATERS);
			double s = Math.sin(a);
			if (back != (s < 0)) continue;
			int w = px * 4;
			int x = cx + (int) Math.round(Math.cos(a) * r / px) * px - w / 2;
			int y = cy + (int) Math.round(s * r * squash / px) * px - px;
			boolean on = ((int) (time * 1.5f) + i) % 3 != 0;
			c.fill(x, y, x + w, y + px, 0xFFFFFFFF);
			c.fill(x + px / 2, y - px, x + w - px / 2, y, 0xFFF2F8FF);
			c.fill(x, y + px, x + w, y + 2 * px, 0xFFB7CCE6);
			int crystal = on ? 0xFFDFF5FF : 0xFF7FAFDA;
			c.fill(x + px + px / 2, y - 3 * px, x + 2 * px + px / 2, y - px, crystal);
			if (on) c.fill(x, y - 4 * px, x + w, y - px, ColorMath.withAlpha(t.lampGlow, 34));
		}
	}
}

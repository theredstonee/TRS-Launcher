package dev.theredstonee.trsclient.core.ui.halloween;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;

/**
 * Ladebalken „Mondlauf“ (ersetzt Lampenreihe, Eisbalken und Hexe, solange Halloween läuft): der Mond wandert über eine
 * silberne Schiene und nimmt dabei zu – 0 % Neumond, 100 % Vollmond mit pulsierendem Hof. Unbestimmt (−1) gleitet er
 * hin und her und durchläuft dabei die Phasen (wie {@code moonPhase}/{@code setBar} im Entwurf).
 */
public final class MoonBar {
	/** Kantenlänge des Mondes in Sprite-Pixeln. */
	public static final int MOON = 13;
	/** Höhe des Balkens bei {@code px} = 1. */
	public static final int HEIGHT = 15;

	private MoonBar() {
	}

	/**
	 * Mondphase {@code f} (0 Neumond … 1 Vollmond) als 13×13-Pixelbild bei (x, y), {@code px} GUI-Pixel je Punkt.
	 */
	public static void moon(Canvas c, int x, int y, int px, float f, float alpha) {
		double term0 = 1 - 2 * f;
		for (int yy = 0; yy < MOON; yy++) {
			double v = (yy - 6) / 6.0;
			double term = term0 * Math.sqrt(Math.max(0, 1 - v * v));
			int runStart = -1;
			int runColor = 0;
			for (int xx = 0; xx <= MOON; xx++) {
				int col = 0;
				if (xx < MOON) {
					double u = (xx - 6) / 6.0;
					if (u * u + v * v <= 1.05) {
						boolean lit = u > term;
						col = lit ? (u - term < 0.25 ? 0xFFB9BFD0 : 0xFFEEF1F8) : 0xFF2A2342;
					}
				}
				if (col != runColor || xx == MOON) {
					if (runColor != 0 && runStart >= 0) {
						c.fill(x + runStart * px, y + yy * px, x + xx * px, y + (yy + 1) * px, ColorMath.fade(runColor, alpha));
					}
					runColor = col;
					runStart = xx;
				}
			}
		}
	}

	/**
	 * Mondlauf zwischen {@code x} und {@code x + w}, oberer Rand {@code y}. {@code progress} 0..1, &lt; 0 = unbestimmt.
	 * {@code time} = Sekunden (Animation), {@code animated} = false → ruhig.
	 */
	public static void draw(Canvas c, int x, int y, int w, float progress, float time, boolean animated) {
		if (w < 20) return;
		int px = 1;
		int mid = y + HEIGHT / 2;
		// Schiene: dünne Silberlinie mit gestricheltem Hof (rail + rail::before)
		c.fill(x, mid - 1, x + w, mid, 0x24CFD5E3);
		for (int sx = x; sx < x + w; sx += 6) {
			c.fill(sx, mid - 4, Math.min(x + w, sx + 2), mid - 3, 0x14B9A2EA);
			c.fill(sx, mid + 2, Math.min(x + w, sx + 2), mid + 3, 0x14B9A2EA);
		}
		float phase;
		int moonX;
		boolean full = false;
		if (progress < 0) {
			float k = animated ? (float) (0.5 - 0.5 * Math.cos(time * 1.2)) : 0.62f;
			phase = animated ? (float) (0.5 - 0.5 * Math.cos(time * 0.7)) : 0.62f;
			moonX = x + Math.round(k * w);
			// kurzer Schweif hinter dem gleitenden Mond
			fill(c, x, mid, moonX, Math.max(x, moonX - w / 4), mid);
		} else {
			float p = Math.max(0f, Math.min(1f, progress));
			phase = p;
			moonX = x + Math.round(p * w);
			full = p >= 0.999f;
			fill(c, x, mid, moonX, x, mid);
		}
		int size = MOON * px;
		int mx = moonX - size / 2;
		int my = mid - size / 2;
		// Hof um den Mond (drop-shadow), beim Vollmond pulsierend
		float halo = full && animated ? 0.55f + 0.45f * (float) (0.5 + 0.5 * Math.sin(time * Math.PI * 2 / 2.4)) : (full ? 0.8f : 0.45f);
		for (int i = 3; i >= 1; i--) {
			int a = Math.round((10 + (3 - i) * 6) * halo);
			ring(c, mx - i, my - i, size + 2 * i, ColorMath.withAlpha(0xFFF3F5FB, a));
		}
		moon(c, mx, my, px, phase, 1f);
	}

	/** Gefüllter Teil: Verlauf von fast durchsichtigem Violett über helles Violett zu Silber, mit Schein. */
	private static void fill(Canvas c, int x0, int mid, int x1, int from, int unused) {
		int start = Math.min(from, x1);
		int len = x1 - start;
		if (len <= 0) return;
		int steps = Math.max(1, Math.min(48, len / 2));
		for (int i = 0; i < steps; i++) {
			int sx = start + len * i / steps;
			int ex = start + len * (i + 1) / steps;
			float k = (i + 0.5f) / steps;
			int col;
			if (k < 0.6f) col = ColorMath.lerp(0x0D8E6CC9, 0x8CB9A2EA, k / 0.6f);
			else col = ColorMath.lerp(0x8CB9A2EA, 0xFFF3F5FB, (k - 0.6f) / 0.4f);
			c.fill(sx, mid - 2, ex, mid + 2, col);
			int glow = ColorMath.withAlpha(0xFFCFD5E3, Math.round(30 * k));
			c.fill(sx, mid - 3, ex, mid - 2, glow);
			c.fill(sx, mid + 2, ex, mid + 3, glow);
		}
	}

	/** Runde Scheibe (Hof) aus waagerechten Streifen. */
	private static void ring(Canvas c, int x, int y, int s, int argb) {
		double r = s / 2.0;
		double cx = x + r;
		for (int yy = 0; yy < s; yy++) {
			double dy = yy + 0.5 - r;
			double hw = Math.sqrt(Math.max(0, r * r - dy * dy));
			int a = (int) Math.round(cx - hw), b = (int) Math.round(cx + hw);
			if (b > a) c.fill(a, y + yy, b, y + yy + 1, argb);
		}
	}
}

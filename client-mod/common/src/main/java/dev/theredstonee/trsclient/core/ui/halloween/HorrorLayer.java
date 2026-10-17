package dev.theredstonee.trsclient.core.ui.halloween;

import dev.theredstonee.trsclient.core.halloween.HalloweenOverlay;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.util.Random;

/**
 * Halloween-Schicht über Menüs (wie {@code .horror} im Entwurf): Vignette (immer), Filmkorn und die Aufhellung beim
 * Blitz – die beiden nur mit „Flackern &amp; Rauschen“ und erlaubter Animation. Das Korn ist eine kleine Rauschtextur
 * (160×90), die alle ~90 ms neu gewürfelt und gestreckt sehr schwach darübergelegt wird.
 */
public final class HorrorLayer {
	static final int GRAIN_W = 160;
	static final int GRAIN_H = 90;
	static final long GRAIN_MS = 90L;
	private static final Random RANDOM = new Random();
	private static final int[] NOISE = new int[GRAIN_W * GRAIN_H];
	private static TextureRef grain;
	private static long grainAt;

	private HorrorLayer() {
	}

	/** Über einen ganzen Bildschirm ({@code inWorld} = Welt dahinter sichtbar → schwächer, kein Blitz). */
	public static void draw(Canvas c, int width, int height, boolean inWorld) {
		if (width <= 0 || height <= 0) return;
		boolean motion = HalloweenOverlay.flicker() && animated();
		float base = inWorld ? 0.32f : 0.5f;
		// Langsamer Vignetten-Puls (nur mit erlaubter Animation; ohne bleibt die Vignette ruhig).
		float strength = motion ? base + 0.06f * (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 2600.0)) : base;
		vignette(c, width, height, strength);
		if (!motion) return;
		grain(c, width, height);
		if (!inWorld) {
			double bt = NightScene.shared().lightningAge();
			lightningSilhouette(c, width, height, bt);
			flash(c, width, height, bt);
		}
	}

	/**
	 * Ferner Blitz: eine gezackte, dunkle Skyline (Bäume/Hügel) im unteren Drittel, kurz von violettem Licht
	 * hinterleuchtet – nur am Anfang eines Blitzes ({@code bt} &lt; 0,22), seltener aber stärker als das Korn.
	 */
	public static void lightningSilhouette(Canvas c, int width, int height, double bt) {
		if (bt < 0 || bt >= 0.22) return;
		float a = bt < 0.08 ? 1f : (float) Math.max(0, 1 - (bt - 0.08) / 0.14);
		int horizon = Math.round(height * 0.64f);
		// Hinterleuchtung über der Skyline.
		int glow = ColorMath.withAlpha(0xFFB9A2EA, Math.round(70 * a));
		c.fill(0, horizon - Math.max(10, height / 9), width, horizon, glow);
		// Gezackte Silhouette mit festem Muster (kein Flackern der Form).
		int span = Math.max(6, width / 48);
		int dark = ColorMath.withAlpha(0xFF0A0812, Math.round(235 * a));
		int seed = 1013904223;
		for (int x = 0; x < width; x += span) {
			seed = seed * 1664525 + 1013904223;
			int peak = (seed >>> 8) % (height / 7 + 1);
			int top = horizon - Math.max(2, height / 40) - peak;
			int w = Math.min(span, width - x);
			c.fill(x, top, x + w, horizon + Math.max(2, height / 30), dark);
		}
	}

	/** Animation erlaubt? (TRS-Einstellung „Animierter Hintergrund“ der Menüs.) */
	static boolean animated() {
		try {
			return dev.theredstonee.trsclient.core.menus.MenuStyle.animated();
		} catch (RuntimeException e) {
			return true;
		}
	}

	/** Dunkle Ränder (radialer Verlauf aus Rahmen-Streifen). */
	public static void vignette(Canvas c, int width, int height, float strength) {
		int bands = 10;
		int depth = Math.max(8, Math.min(width, height) / 3);
		int step = Math.max(1, depth / bands);
		for (int i = 0; i < bands; i++) {
			float k = 1f - i / (float) bands;
			int a = Math.round(255 * strength * k * k / bands * 2.2f);
			if (a <= 0) continue;
			int col = a << 24;
			int o = i * step;
			if (o * 2 >= width || o * 2 >= height) break;
			c.fill(o, o, width - o, o + step, col);
			c.fill(o, height - o - step, width - o, height - o, col);
			c.fill(o, o + step, o + step, height - o - step, col);
			c.fill(width - o - step, o + step, width - o, height - o - step, col);
		}
	}

	/** Filmkorn: Rauschtextur, gestreckt, ~4,5 % deckend. */
	static void grain(Canvas c, int width, int height) {
		Textures.Store store = Textures.store();
		long now = System.currentTimeMillis();
		if (store != null && c.images()) {
			if (grain == null || now - grainAt >= GRAIN_MS) {
				grainAt = now;
				for (int i = 0; i < NOISE.length; i++) {
					int v = RANDOM.nextInt(256);
					NOISE[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
				}
				grain = store.upload("halloween/grain", GRAIN_W, GRAIN_H, NOISE);
			}
			if (grain != null) {
				c.push();
				c.scale(Math.max(width / (float) GRAIN_W, height / (float) GRAIN_H));
				c.image(grain, 0, 0, GRAIN_W, GRAIN_H, 0x0CFFFFFF);
				c.pop();
				return;
			}
		}
		// Ohne Texturen: ein paar helle und dunkle Pünktchen.
		Random r = new Random(now / GRAIN_MS);
		for (int i = 0; i < 120; i++) {
			int x = r.nextInt(width), y = r.nextInt(height);
			c.fill(x, y, x + 1, y + 1, r.nextBoolean() ? 0x10FFFFFF : 0x14000000);
		}
	}

	/** Aufhellung beim Blitz (Kurve wie im Entwurf: hell, kurz dunkel, hell, abklingen). */
	public static void flash(Canvas c, int width, int height, double bt) {
		if (bt >= 0.7 || bt < 0) return;
		double a = bt < 0.07 ? 0.55 : bt < 0.13 ? 0.05 : bt < 0.2 ? 0.42 : Math.max(0, 0.42 * (1 - (bt - 0.2) / 0.5));
		a *= 0.6;
		if (a <= 0.01) return;
		c.fill(0, 0, width, height, ColorMath.withAlpha(0xFFD6CEFF, (int) Math.round(255 * a)));
	}
}

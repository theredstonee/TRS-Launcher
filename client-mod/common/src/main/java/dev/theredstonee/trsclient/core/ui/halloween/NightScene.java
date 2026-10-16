package dev.theredstonee.trsclient.core.ui.halloween;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

/**
 * Hintergrund zu Halloween: die Mondnacht als Pixel-Textur (gleiche Zahlen wie im Launcher und auf der Website).
 * Wie bei der Winternacht wird in Texeln gemalt (ein Texel = 1 GUI-Pixel, bei sehr großen Fenstern mehr), alle 80 ms
 * neu zusammengesetzt und hochgeladen, dazwischen nur die Textur gezeichnet. Die Szene tickt nur, solange sie
 * gezeichnet wird; ohne Animation zeigt sie ein ruhiges Standbild ohne Blitze.
 */
public final class NightScene {
	/** Seed und Mondlage des Titelbildschirms im Entwurf ({@code data-scene="3"}, {@code data-moon="0.87"}). */
	public static final int SEED = 3 * 13 + 5;
	public static final double MOON_X = 0.87;
	public static final double TICK = 0.08;
	private static final int MAX_COLS = 640;
	private static final int MAX_ROWS = 400;

	private static final NightScene SHARED = new NightScene("halloween/scene", SEED, MOON_X);

	/** Gemeinsame Szene für Startbildschirm, Menüs und Ladebildschirme. */
	public static NightScene shared() {
		return SHARED;
	}

	private final String textureName;
	private final int seed;
	private final double moonX;
	private NightPainter painter;
	private int[] frame;
	private TextureRef texture;
	private int builtCols = -1;
	private int builtRows = -1;
	private boolean dirty = true;
	private boolean wasAnimated = true;
	private long lastNanos;
	private double acc;
	private double seconds;
	/** Wanduhr (ms) des letzten Blitzes, für die Aufhellung im {@link HorrorLayer}. */
	private volatile long boltWall;
	private double lastBoltAge = 99;
	private long lastDrawWall;
	private float avgMicros;
	private int frames;

	public NightScene(String textureName, int seed, double moonX) {
		this.textureName = textureName;
		this.seed = seed;
		this.moonX = moonX;
	}

	/** Mittlere CPU-Zeit je Bild (µs, gleitend). */
	public float averageMicros() {
		return avgMicros;
	}

	/** Szenenuhr setzen (Selbsttest: z. B. kurz vor dem Grinsen). */
	public void setSeconds(double s) {
		seconds = Math.max(0, s);
		dirty = true;
	}

	/** Sekunden seit dem letzten Blitz – groß, wenn keiner war oder die Szene gerade nicht gezeichnet wird. */
	public double lightningAge() {
		long now = System.currentTimeMillis();
		if (boltWall == 0 || now - lastDrawWall > 500) return 99;
		return (now - boltWall) / 1000.0;
	}

	static int texelFor(int width, int height) {
		return Math.max(1, Math.max((width + MAX_COLS - 1) / MAX_COLS, (height + MAX_ROWS - 1) / MAX_ROWS));
	}

	/**
	 * Zeichnet die Szene über die ganze Fläche.
	 *
	 * @param animated false = ruhiges Standbild (Animation aus / weniger Bewegung)
	 */
	public void draw(Canvas c, int width, int height, boolean animated) {
		if (width <= 0 || height <= 0) return;
		long start = System.nanoTime();
		lastDrawWall = System.currentTimeMillis();
		int texel = texelFor(width, height);
		int cols = (width + texel - 1) / texel;
		int rows = (height + texel - 1) / texel;
		if (cols != builtCols || rows != builtRows) layout(cols, rows, animated);
		if (animated != wasAnimated) {
			wasAnimated = animated;
			if (!animated) layout(cols, rows, false);
			dirty = true;
		}
		if (animated) advance(start);
		if (dirty) {
			dirty = false;
			Textures.Store store = Textures.store();
			texture = store != null && c.images() ? store.upload(textureName, cols, rows, frame) : null;
		}
		if (texture != null) {
			c.push();
			c.scale(texel);
			c.image(texture, 0, 0, cols, rows, 0xFFFFFFFF);
			c.pop();
		} else {
			fallback(c, width, height);
		}
		float us = (System.nanoTime() - start) / 1000f;
		frames++;
		avgMicros = frames == 1 ? us : avgMicros + (us - avgMicros) * 0.05f;
	}

	private void advance(long now) {
		double dt = lastNanos == 0 ? TICK : (now - lastNanos) / 1e9;
		lastNanos = now;
		if (dt < 0 || dt > 0.5) dt = TICK;
		acc += dt;
		int steps = 0;
		while (acc >= TICK && steps < 3) {
			acc -= TICK;
			steps++;
			seconds += TICK;
		}
		if (acc > TICK * 3) acc = 0;
		if (steps > 0 || dirty) {
			painter.frame(frame, seconds, true);
			double age = painter.boltAge(seconds);
			if (age < lastBoltAge) boltWall = System.currentTimeMillis() - (long) (age * 1000);
			lastBoltAge = age;
			dirty = true;
		}
	}

	private void layout(int cols, int rows, boolean animated) {
		builtCols = cols;
		builtRows = rows;
		painter = new NightPainter(cols, rows, seed, moonX);
		frame = new int[cols * rows];
		painter.frame(frame, 0, false);
		// Standbild wie im Entwurf bei „weniger Bewegung“: Bild zur Zeit 4 s, ohne Blitz.
		painter.frame(frame, animated ? seconds : 4, false);
		lastBoltAge = 99;
		dirty = true;
	}

	/** Ohne Texturen (1.7.10-Notfall): Nachthimmel, Mond und Boden als Flächen. */
	private void fallback(Canvas c, int width, int height) {
		int bands = 6;
		int[] sky = {0xFF06050B, 0xFF0B0816, 0xFF110C20, 0xFF19122D, 0xFF21173A, 0xFF2E2348};
		int horizon = height * 70 / 100;
		for (int i = 0; i < bands; i++) c.fill(0, horizon * i / bands, width, horizon * (i + 1) / bands, sky[i]);
		c.fill(0, horizon, width, height, 0xFF120E20);
		int mr = Math.max(4, height * 11 / 100);
		int mx = (int) (width * moonX), my = height * 24 / 100;
		for (int y = -mr; y <= mr; y++) {
			int hw = (int) Math.sqrt(Math.max(0, mr * mr - y * y));
			c.fill(mx - hw, my + y, mx + hw, my + y + 1, 0xFFD8553B);
		}
		c.fill(0, height * 93 / 100, width, height, 0xFF08070E);
	}
}

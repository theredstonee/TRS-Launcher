package dev.theredstonee.trsclient.core.ui.winter;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.core.ui.Theme;

/**
 * Hintergrund im Design „Winternacht“ (Gegenstück zu {@code app/components/WinterScene.vue}): eine verschneite
 * Pixel-Landschaft von der Seite – Berge mit Schneegrenze, Waldstreifen, Tannen, die Blockhütte mit warmen Fenstern,
 * Rauch und dem Pinguin, Mond und Sterne, Schneefall in drei Tiefen und hin und wieder grünes Polarlicht; im hellen
 * Farbschema ein heller Wintertag mit Sonne und Wolken.
 *
 * <p>Gemalt wird in Texeln (ein Texel = 1 GUI-Pixel, bei sehr großen Fenstern mehr) in einen eigenen Puffer, der als
 * Textur hochgeladen und pixelig hochskaliert gezeichnet wird. Wie im Launcher läuft ein eigener Takt von 80 ms: nur
 * dann wird das Bild neu zusammengesetzt und hochgeladen, dazwischen nur die Textur gezeichnet. Die Szene tickt nur,
 * solange sie gezeichnet wird (also sichtbar ist); ohne Animation zeigt sie ein ruhiges Standbild.
 */
public final class WinterScene {
	/** Seed der Landschaft (wie im Launcher). */
	public static final int SEED = 0x5a0;
	/** Ein Bild alle 80 ms – genug für ruhigen Schneefall. */
	public static final double TICK = 0.08;
	/** Größte Texturbreite/-höhe in Texeln (darüber werden die Texel größer). */
	private static final int MAX_COLS = 640;
	private static final int MAX_ROWS = 400;

	private static final WinterScene SHARED = new WinterScene("winter/scene");

	/** Gemeinsame Szene für Startbildschirm und Menüs (der Pinguin bleibt beim Wechsel derselbe). */
	public static WinterScene shared() {
		return SHARED;
	}

	private final String textureName;
	private final WinterMath.Rng rand = new WinterMath.Rng((int) System.nanoTime() ^ 0x51);
	private Landscape land;
	private WinterPainter painter;
	private Snow.Fall snow;
	private Snow.Smoke smoke;
	private Penguin penguin;
	private final WinterPainter.Frame state = new WinterPainter.Frame();
	private int[] frame;
	private TextureRef texture;
	private boolean dirty = true;
	private int builtCols = -1;
	private int builtRows = -1;
	private String paletteKey = "";
	private long lastNanos;
	private double acc;
	private double seconds;
	private int tick;
	private boolean wasAnimated = true;
	private float avgMicros;
	private int frames;

	public WinterScene(String textureName) {
		this.textureName = textureName;
	}

	/** Mittlere CPU-Zeit je Bild in Mikrosekunden (inkl. Zusammensetzen und Hochladen, gemittelt). */
	public float averageMicros() {
		return avgMicros;
	}

	/** Landschaft des letzten Aufbaus (Tests) oder null. */
	public Landscape landscape() {
		return land;
	}

	/** Der Pinguin kommt sofort heraus (Selbsttest; im Launcher Strg+Alt+P). */
	public void penguinOut() {
		if (penguin != null) penguin.trigger();
	}

	/** Phase des Pinguins ({@link Penguin#WINDOW} …) oder -1 (keine Hütte). */
	public int penguinPhase() {
		return penguin == null ? -1 : penguin.phase();
	}

	/** Polarlicht-Uhr auf {@code s} Sekunden stellen (Selbsttest: Polarlicht sofort zeigen). */
	public void setSeconds(double s) {
		seconds = Math.max(0, s);
		dirty = true;
	}

	/** Texel-Größe in GUI-Pixeln für ein Fenster dieser Größe. */
	static int texelFor(int width, int height) {
		return Math.max(1, Math.max((width + MAX_COLS - 1) / MAX_COLS, (height + MAX_ROWS - 1) / MAX_ROWS));
	}

	/**
	 * Zeichnet die Szene über die ganze Fläche.
	 *
	 * @param animated false = ruhiges Standbild (Einstellung „Animierter Hintergrund“ aus)
	 */
	public void draw(Canvas c, int width, int height, boolean animated) {
		if (width <= 0 || height <= 0) return;
		long start = System.nanoTime();
		Theme t = Theme.get();
		int texel = texelFor(width, height);
		int cols = (width + texel - 1) / texel;
		int rows = (height + texel - 1) / texel;
		String key = t.themeName + "/" + t.accentName + "/" + Integer.toHexString(t.accent);
		if (cols != builtCols || rows != builtRows) {
			layout(cols, rows, t);
			paletteKey = key;
		} else if (!key.equals(paletteKey)) {
			paletteKey = key;
			repaint(t);
		}
		if (animated != wasAnimated) {
			wasAnimated = animated;
			if (penguin != null) penguin.setStill(!animated);
			dirty = true;
		}
		advance(start, animated);
		if (dirty) {
			dirty = false;
			state.tick = tick;
			state.seconds = seconds;
			state.aurora = animated ? Landscape.auroraEnvelope(seconds, SEED) : 0.75;
			state.penguin = penguin;
			state.flakes = snow;
			state.smoke = smoke;
			painter.compose(frame, state);
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

	/** Takt: je 80 ms ein Schritt (Schnee, Rauch, Pinguin); nach einer Pause geht es ohne Sprung weiter. */
	private void advance(long now, boolean animated) {
		double dt = lastNanos == 0 ? TICK : (now - lastNanos) / 1e9;
		lastNanos = now;
		if (dt < 0 || dt > 0.5) dt = TICK;
		if (!animated) return;
		seconds += dt;
		acc += dt;
		int steps = 0;
		while (acc >= TICK && steps < 3) {
			acc -= TICK;
			steps++;
			step();
		}
		if (acc > TICK * 3) acc = 0;
		if (steps > 0) dirty = true;
	}

	private void step() {
		tick++;
		if (snow != null) snow.step(tick);
		int[] ch = land.chimney();
		if (smoke != null) smoke.step(ch != null, ch == null ? 0 : ch[0], ch == null ? 0 : ch[1], tick);
		if (penguin != null) penguin.step();
	}

	private void layout(int cols, int rows, Theme t) {
		builtCols = cols;
		builtRows = rows;
		land = Landscape.build(cols, rows, SEED, -1, true);
		snow = new Snow.Fall(land.w, land.h, rand, 1);
		smoke = new Snow.Smoke(rand);
		WinterPalette p = WinterPalette.of(t.themeName, t.accentName, t.accent);
		boolean day = p.variant == WinterPalette.LIGHT;
		// Der Pinguin bleibt bei Größenänderungen derselbe (seine Lage ist relativ zur Hütte).
		if (!land.hasCabin()) penguin = null;
		else if (penguin == null) penguin = new Penguin(rand, day, !wasAnimated);
		// Etwas Rauch schon da, damit er nicht erst aus dem Nichts aufsteigt.
		int[] ch = land.chimney();
		for (int i = 0; i < 60; i++) smoke.step(ch != null, ch == null ? 0 : ch[0], ch == null ? 0 : ch[1], i);
		frame = new int[land.w * land.h];
		painter = new WinterPainter(land, p);
		if (penguin != null) penguin.setDay(day);
		dirty = true;
	}

	private void repaint(Theme t) {
		WinterPalette p = WinterPalette.of(t.themeName, t.accentName, t.accent);
		painter = new WinterPainter(land, p);
		if (penguin != null) penguin.setDay(p.variant == WinterPalette.LIGHT);
		dirty = true;
	}

	/** Ohne Texturen (sollte es nicht geben): Himmel, Berge und Schnee als Flächen. */
	private void fallback(Canvas c, int width, int height) {
		WinterPalette p = painter.P;
		int horizon = height * land.horizon / Math.max(1, land.h);
		int bands = p.sky.length;
		for (int i = 0; i < bands; i++) {
			c.fill(0, horizon * i / bands, width, horizon * (i + 1) / bands, p.sky[i]);
		}
		c.fill(0, horizon, width, height, p.snow);
		c.fill(0, horizon, width, horizon + 2, p.snowLit);
	}
}

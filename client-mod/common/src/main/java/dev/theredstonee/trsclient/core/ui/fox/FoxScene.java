package dev.theredstonee.trsclient.core.ui.fox;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.winter.Snow;
import dev.theredstonee.trsclient.core.ui.winter.WinterMath;

/**
 * Hintergrund im Design „Fuchsbau“ (Gegenstück zu {@code app/components/FoxScene.vue}): ein Minecraft-Wald in der
 * Abenddämmerung von der Seite – blockige Hügel im rosa Dunst, Fichten und einzelne Kirschbäume, die eckige Sonne geht
 * unter. Am Fuchsbau brennt ein Lagerfeuer, daneben schläft ein Fuchs und atmet ruhig; Glühwürmchen, treibende Blätter
 * und ein paar Kirschblüten. Im hellen Farbschema die goldene Stunde.
 *
 * <p>Wie die Winternacht: gemalt wird in Texeln (ein Texel = 1 GUI-Pixel, bei sehr großen Fenstern mehr) in einen
 * eigenen Puffer, der als Textur hochgeladen und pixelig hochskaliert gezeichnet wird. Eigener Takt von 80 ms – nur dann
 * wird das Bild neu zusammengesetzt; die Szene tickt nur, solange sie gezeichnet wird. Ohne Animation ein ruhiges
 * Standbild (Feuer brennt ruhig, Glühwürmchen stehen, der Fuchs atmet nicht sichtbar).
 */
public final class FoxScene {
	/** Seed der Landschaft (wie im Launcher). */
	public static final int SEED = 0xf0c5;
	/**
	 * Lage der Sonne: im Launcher steht der Text links, die Sonne in der Mitte (0,56). Im Spiel stehen Schriftzug und
	 * Knöpfe in der Mitte – die Sonne geht rechts neben den Knöpfen unter (vor dem Bau), damit man sie sieht.
	 */
	public static final double SUN_X = 0.77;
	/** Ein Bild alle 80 ms – genug für Flammen und treibende Blätter. */
	public static final double TICK = 0.08;
	private static final int MAX_COLS = 640;
	private static final int MAX_ROWS = 400;

	private static final FoxScene SHARED = new FoxScene("fox/scene");

	/** Gemeinsame Szene für Startbildschirm und Menüs. */
	public static FoxScene shared() {
		return SHARED;
	}

	private final String textureName;
	private final WinterMath.Rng rand = new WinterMath.Rng((int) System.nanoTime() ^ 0xf0);
	private FoxLandscape land;
	private FoxPainter painter;
	private FoxParticles.Leaves leaves;
	private Snow.Smoke smoke;
	private FoxParticles.Sparks sparks;
	private final FoxPainter.Frame state = new FoxPainter.Frame();
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

	public FoxScene(String textureName) {
		this.textureName = textureName;
	}

	/** Mittlere CPU-Zeit je Bild in Mikrosekunden (inkl. Zusammensetzen und Hochladen, gemittelt). */
	public float averageMicros() {
		return avgMicros;
	}

	/** Landschaft des letzten Aufbaus (Tests) oder null. */
	public FoxLandscape landscape() {
		return land;
	}

	/** Uhr auf {@code s} Sekunden stellen (Selbsttest: z. B. eingeatmet zeigen). */
	public void setSeconds(double s) {
		seconds = Math.max(0, s);
		dirty = true;
	}

	/** Texel-Größe in GUI-Pixeln für ein Fenster dieser Größe. */
	static int texelFor(int width, int height) {
		return Math.max(1, Math.max((width + MAX_COLS - 1) / MAX_COLS, (height + MAX_ROWS - 1) / MAX_ROWS));
	}

	/** Lage des Fuchses in GUI-Pixeln {x, y, Breite, Höhe} beim letzten Zeichnen (Selbsttest) oder null. */
	public int[] foxBounds(int width, int height) {
		FoxLandscape.Parts p = land == null ? null : land.parts();
		if (p == null || !p.hasFox) return null;
		int texel = texelFor(width, height);
		return new int[]{p.foxX * texel, (p.foxY - FoxPainter.FOX_H) * texel, FoxPainter.FOX_W * texel, FoxPainter.FOX_H * texel};
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
			painter = new FoxPainter(land, palette(t));
			dirty = true;
		}
		if (animated != wasAnimated) {
			wasAnimated = animated;
			dirty = true;
		}
		advance(start, animated);
		if (dirty) {
			dirty = false;
			boolean calm = !animated;
			state.tick = tick;
			state.seconds = seconds;
			state.fire = 0.5;
			state.calm = calm;
			state.inhale = FoxParticles.inhale(seconds, calm);
			state.twitch = FoxParticles.twitch(seconds, calm);
			state.smoke = smoke;
			state.sparks = sparks;
			state.leaves = leaves;
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

	private static FoxPalette palette(Theme t) {
		return FoxPalette.of(t.themeName, t.accentName, t.accent);
	}

	/** Takt: je 80 ms ein Schritt (Blätter, Rauch, Funken); nach einer Pause geht es ohne Sprung weiter. */
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

	private double[] origin() {
		int[] top = FoxLandscape.fireTop(land.parts());
		return top == null ? null : new double[]{top[0] + top[2] / 2.0, top[1], top[2]};
	}

	private void step() {
		tick++;
		if (leaves != null) leaves.step(tick);
		double[] o = origin();
		if (smoke != null) smoke.step(o != null, o == null ? 0 : o[0], o == null ? 0 : o[1], tick);
		if (sparks != null) sparks.step(o, tick, 0.5);
	}

	private void layout(int cols, int rows, Theme t) {
		builtCols = cols;
		builtRows = rows;
		land = FoxLandscape.build(cols, rows, SEED, true, SUN_X);
		leaves = new FoxParticles.Leaves(land.w, land.h, rand, 1);
		smoke = new Snow.Smoke(rand);
		sparks = new FoxParticles.Sparks(rand);
		// Etwas Rauch schon da, damit er nicht erst aus dem Nichts aufsteigt.
		double[] o = origin();
		for (int i = 0; i < 60; i++) {
			smoke.step(o != null, o == null ? 0 : o[0], o == null ? 0 : o[1], i);
			sparks.step(o, i, 0.5);
		}
		frame = new int[land.w * land.h];
		painter = new FoxPainter(land, palette(t));
		dirty = true;
	}

	/** Ohne Texturen (sollte es nicht geben): Himmel, Hügel und Wiese als Flächen. */
	private void fallback(Canvas c, int width, int height) {
		FoxPalette p = painter.P;
		int horizon = height * land.horizon / Math.max(1, land.h);
		int bands = p.sky.length;
		for (int i = 0; i < bands; i++) {
			c.fill(0, horizon * i / bands, width, horizon * (i + 1) / bands, p.sky[i]);
		}
		c.fill(0, horizon, width, height, p.meadow[2]);
		c.fill(0, horizon, width, horizon + 2, p.meadow[0]);
	}
}

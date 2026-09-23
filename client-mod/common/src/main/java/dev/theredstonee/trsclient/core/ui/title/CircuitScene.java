package dev.theredstonee.trsclient.core.ui.title;

import dev.theredstonee.trsclient.core.ui.Anim;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Hintergrund des TRS-Startbildschirms: eine prozedural gebaute, animierte Redstone-Schaltung auf
 * Tiefenschiefer. Fackeln takten Signale in Staubleitungen (die Signalstärke sinkt je Block),
 * Verstärker frischen sie mit ihrer Verzögerung auf, am Ende schalten Lampen an oder Kolben fahren aus.
 *
 * <p>Alles wird aus Rechtecken im GUI-Raster gezeichnet (ein Block = {@value #CELL} GUI-Pixel wie ein
 * Gegenstand im Inventar) und skaliert so mit der GUI-Größe. Die Schaltung wird nur bei einer neuen
 * Fenstergröße neu gebaut; pro Bild wird für jedes Feld nur ein Zeitvergleich gerechnet. Ist das
 * Zeichnen zu teuer (alte Versionen zeichnen jedes Rechteck einzeln), schaltet die Szene selbst auf
 * eine sparsamere Darstellung um.</p>
 */
public final class CircuitScene {
	/** Kantenlänge eines Blocks in GUI-Pixeln. */
	public static final int CELL = 16;
	/** Signal-Laufzeit je Staub-Block (Sekunden) – im Spiel sofort, hier sichtbar gemacht. */
	static final float STEP = 1f / 24f;
	/** Ein Redstone-Tick in Sekunden. */
	static final float TICK = 0.1f;
	/** Ab dieser mittleren Zeichenzeit (Mikrosekunden) wird die sparsame Darstellung genutzt. */
	private static final float BUDGET_MICROS = 2500f;

	static final int DUST = 0;
	static final int TORCH = 1;
	static final int REPEATER = 2;
	static final int LAMP = 3;
	static final int PISTON = 4;

	private static final int[] DX = {1, 0, -1, 0};
	private static final int[] DY = {0, 1, 0, -1};

	private static final int BASE = 0xFF18181C;
	private static final int SEAM = 0xFF121215;
	private static final int STREAK_LIGHT = 0xFF202025;
	private static final int STREAK_DARK = 0xFF141417;

	/** Ein Feld der Schaltung. */
	static final class Node {
		final int cx;
		final int cy;
		int kind;
		/** Richtung, in der das Signal ankommt bzw. weiterläuft (0 O, 1 S, 2 W, 3 N; -1 = keine). */
		int dirIn = -1;
		int dirOut = -1;
		/** Signalstärke 1..15 (Staub). */
		int level;
		/** Ankunft des Signals (Sekunden nach dem Takt der Quelle). */
		float arrival;
		/** Nur Verstärker: Ausgang nach der Verzögerung. */
		float outArrival;
		int delay;

		Node(int cx, int cy, int kind) {
			this.cx = cx;
			this.cy = cy;
			this.kind = kind;
		}
	}

	/** Eine Leitung von einer Fackel bis zu einer Lampe oder einem Kolben. */
	static final class Circuit {
		final List<Node> nodes = new ArrayList<Node>();
		float period;
		float pulse;
		float phase;
		float flickerSeed;
	}

	private final List<Circuit> circuits = new ArrayList<Circuit>();
	/** Schiefer-Maserung: je Streifen {x, y, Länge, Farbe}. */
	private int[] streaks = new int[0];
	private int builtW = -1;
	private int builtH = -1;
	private int builtKey;
	private boolean simple;
	private boolean autoSimple;
	private float avgMicros;
	private int frames;

	/** Sparsame Darstellung erzwingen (alte Minecraft-Versionen). */
	public void setSimple(boolean simple) {
		this.simple = simple;
		builtW = -1;
	}

	public boolean isSimple() {
		return simple || autoSimple;
	}

	/** Mittlere Zeichenzeit der Szene in Mikrosekunden (CPU). */
	public float averageMicros() {
		return avgMicros;
	}

	/** Anzahl der Leitungen (Tests). */
	int circuitCount() {
		return circuits.size();
	}

	List<Circuit> circuits() {
		return circuits;
	}

	/**
	 * Zeichnet die Szene.
	 * @param time Sekunden (fortlaufend)
	 * @param reserved Rechtecke {x1, y1, x2, y2}, die frei bleiben (Schriftzug, Knöpfe, Fußzeile)
	 */
	public void draw(Canvas c, int width, int height, float time, int[][] reserved) {
		long start = System.nanoTime();
		int key = Arrays.deepHashCode(reserved);
		if (width != builtW || height != builtH || key != builtKey) {
			build(width, height, reserved, isSimple());
			builtW = width;
			builtH = height;
			builtKey = key;
		}
		background(c, width, height);
		Theme t = Theme.get();
		boolean lite = isSimple();
		for (int i = 0; i < circuits.size(); i++) drawCircuit(c, circuits.get(i), time, t, lite);
		vignette(c, width, height, lite);
		// Gepufferte Rechtecke jetzt zeichnen – so zählt ihr Hochladen mit zur gemessenen Zeit.
		c.flush();
		measure(System.nanoTime() - start);
	}

	private void measure(long nanos) {
		float micros = nanos / 1000f;
		frames++;
		avgMicros = frames == 1 ? micros : avgMicros + (micros - avgMicros) * 0.05f;
		// Erst nach einer Anlaufphase entscheiden (JIT, Schrift-Cache).
		if (!autoSimple && !simple && frames > 90 && avgMicros > BUDGET_MICROS) {
			autoSimple = true;
			builtW = -1;
		}
	}

	// --- Aufbau ---

	/** Baut die Schaltung für eine Fenstergröße (deterministisch: gleiche Größe = gleiche Schaltung). */
	void build(int width, int height, int[][] reserved, boolean lite) {
		circuits.clear();
		int cols = Math.max(1, (width + CELL - 1) / CELL);
		int rows = Math.max(1, (height + CELL - 1) / CELL);
		// 0 = frei, -1 = gesperrt, >0 = Leitung Nr.
		int[] grid = new int[cols * rows];
		if (reserved != null) {
			for (int[] r : reserved) {
				if (r == null || r.length < 4) continue;
				int x1 = Math.max(0, r[0] / CELL - 1);
				int y1 = Math.max(0, r[1] / CELL - 1);
				int x2 = Math.min(cols - 1, (r[2] + CELL - 1) / CELL);
				int y2 = Math.min(rows - 1, (r[3] + CELL - 1) / CELL);
				for (int y = y1; y <= y2; y++) {
					for (int x = x1; x <= x2; x++) grid[y * cols + x] = -1;
				}
			}
		}
		int free = 0;
		for (int v : grid) if (v == 0) free++;
		Random rnd = new Random(0x7125_0000L + cols * 131L + rows);
		int target = Math.max(3, Math.min(lite ? 8 : 22, free / (lite ? 150 : 70)));
		int attempts = 0;
		while (circuits.size() < target && attempts < target * 16) {
			attempts++;
			Circuit circuit = tryCircuit(grid, cols, rows, rnd, circuits.size() + 1);
			if (circuit != null) circuits.add(circuit);
		}
		buildStreaks(width, height, lite, rnd);
	}

	private Circuit tryCircuit(int[] grid, int cols, int rows, Random rnd, int id) {
		int sx = rnd.nextInt(cols);
		int sy = rnd.nextInt(rows);
		if (!free(grid, cols, rows, sx, sy, -1, -1)) return null;
		int dir = rnd.nextInt(4);
		List<int[]> cells = new ArrayList<int[]>();
		cells.add(new int[]{sx, sy, -1});
		grid[sy * cols + sx] = id;
		int x = sx;
		int y = sy;
		int run = 0;
		int runTarget = 3 + rnd.nextInt(6);
		int length = 9 + rnd.nextInt(24);
		while (cells.size() < length) {
			if (run >= runTarget) {
				dir = (dir + (rnd.nextBoolean() ? 1 : 3)) % 4;
				run = 0;
				runTarget = 3 + rnd.nextInt(7);
			}
			int nd = dir;
			if (!free(grid, cols, rows, x + DX[nd], y + DY[nd], x, y)) {
				nd = (dir + 1) % 4;
				if (!free(grid, cols, rows, x + DX[nd], y + DY[nd], x, y)) {
					nd = (dir + 3) % 4;
					if (!free(grid, cols, rows, x + DX[nd], y + DY[nd], x, y)) break;
				}
				// Nach einer erzwungenen Kurve mindestens zwei Blöcke geradeaus (Verstärker brauchen Platz).
				run = 0;
				runTarget = Math.max(2, runTarget);
			}
			dir = nd;
			x += DX[dir];
			y += DY[dir];
			grid[y * cols + x] = id;
			cells.add(new int[]{x, y, dir});
			run++;
		}
		// Kolben brauchen einen freien Block für den Kopf.
		boolean piston = rnd.nextInt(10) < 3 && cells.size() >= 2
				&& free(grid, cols, rows, x + DX[dir], y + DY[dir], x, y);
		if (cells.size() < 5) {
			for (int[] cell : cells) grid[cell[1] * cols + cell[0]] = 0;
			return null;
		}
		if (piston) grid[(y + DY[dir]) * cols + (x + DX[dir])] = id;

		Circuit circuit = new Circuit();
		for (int i = 0; i < cells.size(); i++) {
			int[] cell = cells.get(i);
			Node n = new Node(cell[0], cell[1], i == 0 ? TORCH : DUST);
			n.dirIn = cell[2];
			circuit.nodes.add(n);
		}
		for (int i = 0; i + 1 < circuit.nodes.size(); i++) circuit.nodes.get(i).dirOut = circuit.nodes.get(i + 1).dirIn;
		Node last = circuit.nodes.get(circuit.nodes.size() - 1);
		last.kind = piston ? PISTON : LAMP;
		last.dirOut = last.dirIn;

		// Verstärker auf geraden Stücken; spätestens bevor das Signal zu schwach wird.
		int since = 0;
		int next = 4 + rnd.nextInt(5);
		for (int i = 1; i < circuit.nodes.size() - 2; i++) {
			Node n = circuit.nodes.get(i);
			since++;
			boolean straight = n.dirIn == n.dirOut && circuit.nodes.get(i - 1).kind == DUST;
			if (straight && (since >= next || since >= 11)) {
				n.kind = REPEATER;
				n.delay = 1 + rnd.nextInt(4);
				since = 0;
				next = 5 + rnd.nextInt(5);
			}
		}
		// Signalstärke und Ankunftszeiten
		float time = 0f;
		int level = 16;
		for (int i = 0; i < circuit.nodes.size(); i++) {
			Node n = circuit.nodes.get(i);
			if (i > 0) time += STEP;
			n.arrival = time;
			if (n.kind == TORCH) {
				n.level = 15;
				n.outArrival = time;
				level = 16;
			} else if (n.kind == REPEATER) {
				n.level = Math.max(1, level - 1);
				n.outArrival = time + n.delay * TICK;
				time = n.outArrival;
				level = 16;
			} else {
				level = Math.max(1, level - 1);
				n.level = level;
				n.outArrival = time;
			}
		}
		circuit.period = 2.4f + rnd.nextFloat() * 3.2f;
		circuit.pulse = Math.min(circuit.period - 0.5f, 0.6f + rnd.nextFloat() * 1.3f);
		circuit.phase = rnd.nextFloat() * circuit.period;
		circuit.flickerSeed = rnd.nextFloat() * 100f;
		return circuit;
	}

	/** Frei und ohne Nachbarn anderer Leitungen (sonst verbände sich der Staub optisch)? */
	private static boolean free(int[] grid, int cols, int rows, int x, int y, int fromX, int fromY) {
		if (x < 0 || y < 0 || x >= cols || y >= rows) return false;
		if (grid[y * cols + x] != 0) return false;
		for (int d = 0; d < 4; d++) {
			int nx = x + DX[d];
			int ny = y + DY[d];
			if (nx == fromX && ny == fromY) continue;
			if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue;
			if (grid[ny * cols + nx] > 0) return false;
		}
		return true;
	}

	private void buildStreaks(int width, int height, boolean lite, Random rnd) {
		if (lite) {
			streaks = new int[0];
			return;
		}
		int count = Math.max(0, width * height / 3600);
		streaks = new int[count * 4];
		for (int i = 0; i < count; i++) {
			streaks[i * 4] = rnd.nextInt(Math.max(1, width));
			streaks[i * 4 + 1] = rnd.nextInt(Math.max(1, height));
			streaks[i * 4 + 2] = 5 + rnd.nextInt(22);
			streaks[i * 4 + 3] = rnd.nextInt(3) == 0 ? STREAK_DARK : STREAK_LIGHT;
		}
	}

	// --- Zustand ---

	/**
	 * Wie stark ein Feld mit Ankunftszeit {@code arrival} gerade Signal führt (0..1), mit kurzem
	 * Ein- und Ausblenden. Periodisch: jede Leitung taktet mit eigener Periode und Phase.
	 */
	static float power(Circuit circuit, float arrival, float time, float extraOn, float rise, float fall) {
		float local = time - circuit.phase - arrival;
		local = local - (float) Math.floor(local / circuit.period) * circuit.period;
		float on = circuit.pulse + extraOn;
		if (local < on) return rise <= 0 ? 1f : Math.min(1f, local / rise);
		return fall <= 0 ? 0f : Math.max(0f, 1f - (local - on) / fall);
	}

	// --- Zeichnen ---

	private void background(Canvas c, int width, int height) {
		c.fill(0, 0, width, height, BASE);
		// Fugen der Schieferplatten (zwei Blöcke groß) – durchgehende Linien: ein Rechteck je Fuge.
		int tile = CELL * 2;
		for (int y = 0; y < height; y += tile) c.fill(0, y, width, y + 1, SEAM);
		for (int x = 0; x < width; x += tile) c.fill(x, 0, x + 1, height, SEAM);
		for (int i = 0; i + 3 < streaks.length; i += 4) {
			c.fill(streaks[i], streaks[i + 1], streaks[i] + streaks[i + 2], streaks[i + 1] + 1, streaks[i + 3]);
		}
	}

	/** Zusammengefasster unbestromter, gerader Staub {x1, y1, x2, y2}; ein Rechteck statt eines je Block. */
	private final int[] run = new int[4];
	private int runDir = -1;

	private void flushRun(Canvas c, Theme t) {
		if (runDir >= 0) c.fill(run[0], run[1], run[2], run[3], t.dustOff);
		runDir = -1;
	}

	private void drawCircuit(Canvas c, Circuit circuit, float time, Theme t, boolean lite) {
		List<Node> nodes = circuit.nodes;
		for (int i = 0; i < nodes.size(); i++) {
			Node n = nodes.get(i);
			int px = n.cx * CELL;
			int py = n.cy * CELL;
			if (n.kind == DUST && n.dirIn == n.dirOut && power(circuit, n.arrival, time, 0f, 0.04f, 0.08f) <= 0f) {
				boolean horizontal = n.dirIn == 0 || n.dirIn == 2;
				int x1 = horizontal ? px : px + 7;
				int y1 = horizontal ? py + 7 : py;
				int x2 = horizontal ? px + CELL : px + 9;
				int y2 = horizontal ? py + 9 : py + CELL;
				if (runDir == n.dirIn) {
					run[0] = Math.min(run[0], x1);
					run[1] = Math.min(run[1], y1);
					run[2] = Math.max(run[2], x2);
					run[3] = Math.max(run[3], y2);
				} else {
					flushRun(c, t);
					run[0] = x1;
					run[1] = y1;
					run[2] = x2;
					run[3] = y2;
					runDir = n.dirIn;
				}
				continue;
			}
			flushRun(c, t);
			switch (n.kind) {
				case TORCH: {
					float v = power(circuit, 0f, time, 0f, 0.05f, 0.1f);
					arm(c, px, py, n.dirOut, t.dust(15), v, lite, t);
					float flicker = flicker(time, circuit.flickerSeed);
					Redstone.torch(c, px + 8, py + 14, 0.25f + 0.75f * v, flicker);
					break;
				}
				case REPEATER:
					repeater(c, circuit, n, px, py, time, t, lite);
					break;
				case LAMP: {
					float v = power(circuit, n.arrival, time, 0.2f, 0.04f, 0.12f);
					lamp(c, px, py, v, lite, t);
					break;
				}
				case PISTON: {
					float ext = power(circuit, n.arrival, time, 0f, 0.1f, 0.1f);
					piston(c, px, py, n.dirIn, Anim.easeInOut(ext), t);
					break;
				}
				default: {
					float v = power(circuit, n.arrival, time, 0f, 0.04f, 0.08f);
					int color = ColorMath.lerp(t.dustOff, t.dust(n.level), v);
					float glow = lite ? 0f : v * n.level / 15f;
					int back = n.dirIn < 0 ? -1 : (n.dirIn + 2) % 4;
					if (n.dirIn == n.dirOut) {
						straight(c, px, py, n.dirIn, color, glow, t);
					} else {
						arm(c, px, py, back, color, glow, lite, t);
						arm(c, px, py, n.dirOut, color, glow, lite, t);
						c.fill(px + 6, py + 6, px + 10, py + 10, color);
					}
					break;
				}
			}
		}
		flushRun(c, t);
	}

	private static float flicker(float time, float seed) {
		double a = Math.sin(time * 9.1 + seed) * 0.5 + Math.sin(time * 23.7 + seed * 1.7) * 0.3 + Math.sin(time * 3.3 + seed * 0.3) * 0.2;
		return ColorMath.clamp01((float) (0.5 + a * 0.5));
	}

	/** Durchgehender Staub über den ganzen Block. */
	private static void straight(Canvas c, int px, int py, int dir, int color, float glow, Theme t) {
		if (dir == 0 || dir == 2) Redstone.dustH(c, px, px + CELL, py + 7, color, glow);
		else Redstone.dustV(c, px + 7, py, py + CELL, color, glow);
	}

	/** Staub von der Blockmitte zur Kante in Richtung {@code dir}. */
	private static void arm(Canvas c, int px, int py, int dir, int color, float glow, boolean lite, Theme t) {
		float g = lite ? 0f : glow;
		switch (dir) {
			case 0: Redstone.dustH(c, px + 7, px + CELL, py + 7, color, g); break;
			case 2: Redstone.dustH(c, px, px + 9, py + 7, color, g); break;
			case 1: Redstone.dustV(c, px + 7, py + 7, py + CELL, color, g); break;
			case 3: Redstone.dustV(c, px + 7, py, py + 9, color, g); break;
			default: break;
		}
	}

	private static void repeater(Canvas c, Circuit circuit, Node n, int px, int py, float time, Theme t, boolean lite) {
		float in = power(circuit, n.arrival, time, 0f, 0.03f, 0.06f);
		float out = power(circuit, n.outArrival, time, 0f, 0.03f, 0.06f);
		// Steinplatte
		Redstone.block(c, px + 1, py + 1, 14, 14, 0xFF2A2A2F);
		c.fill(px + 2, py + 2, px + 14, py + 14, 0xFF5E5E66);
		c.fill(px + 2, py + 2, px + 14, py + 3, 0xFF74747C);
		c.fill(px + 2, py + 13, px + 14, py + 14, 0xFF46464D);
		int dir = n.dirIn;
		// Kanal: hinten bis zur hinteren Fackel mit dem Eingang, danach mit dem Ausgang gefärbt.
		int inColor = ColorMath.lerp(t.dustOff, t.dust(n.level), in);
		int outColor = ColorMath.lerp(t.dustOff, t.dust(15), out);
		rectFS(c, px, py, dir, 0, 7, 5, 9, inColor);
		// Verzögerung sichtbar: die vordere Fackel steht je Tick weiter vorn (wie im Spiel), dazwischen
		// läuft das Signal in Tick-Schritten nach vorn.
		int front = 5 + n.delay * 2;
		rectFS(c, px, py, dir, 6, 7, front, 9, 0xFF3A3A40);
		float progress = out > 0.5f ? 1f : ColorMath.clamp01(sinceOn(circuit, n.arrival, time) / (n.delay * TICK));
		int ticks = (int) Math.floor(progress * n.delay + 0.0001f);
		int lit = (front - 6) * ticks / n.delay;
		if (lit > 0) rectFS(c, px, py, dir, 6, 7, 6 + lit, 9, t.dust(15));
		rectFS(c, px, py, dir, front + 3, 7, 16, 9, outColor);
		// Fackeln (von oben: Kopf als Quadrat)
		int torchOff = 0xFF4A1410;
		int rear = ColorMath.lerp(torchOff, t.dustOn, out);
		int frontC = ColorMath.lerp(torchOff, t.dustOn, out);
		rectFS(c, px, py, dir, 3, 6, 6, 10, rear);
		rectFS(c, px, py, dir, front, 6, front + 3, 10, frontC);
		if (!lite && out > 0.05f) {
			int g = ColorMath.withAlpha(t.glow, Math.round(40 * out));
			rectFS(c, px, py, dir, 2, 5, 7, 11, g);
			rectFS(c, px, py, dir, front - 1, 5, front + 4, 11, g);
		}
	}

	/** Sekunden, seit an diesem Feld das aktuelle Signal anliegt (0, wenn keins). */
	private static float sinceOn(Circuit circuit, float arrival, float time) {
		float local = time - circuit.phase - arrival;
		local = local - (float) Math.floor(local / circuit.period) * circuit.period;
		return local < circuit.pulse ? local : 0f;
	}

	private static void lamp(Canvas c, int px, int py, float v, boolean lite, Theme t) {
		if (v > 0.02f) {
			// Licht fällt auf den Schiefer ringsum.
			c.fill(px - 8, py - 8, px + 24, py + 24, ColorMath.withAlpha(t.lampGlow, Math.round(10 * v)));
			if (!lite) c.fill(px - 4, py - 4, px + 20, py + 20, ColorMath.withAlpha(t.lampGlow, Math.round(16 * v)));
		}
		Redstone.block(c, px + 1, py + 1, 14, 14, ColorMath.lerp(t.lampOffEdge, t.lampOnEdge, v));
		c.fill(px + 2, py + 2, px + 14, py + 14, ColorMath.lerp(t.lampOff, t.lampOn, v));
		int lattice = ColorMath.lerp(0xFF4C3726, 0xFFB86E26, v);
		Redstone.frame(c, px + 3, py + 3, 10, 10, lattice);
		c.fill(px + 7, py + 3, px + 9, py + 13, lattice);
		c.fill(px + 3, py + 7, px + 13, py + 9, lattice);
		if (v > 0.02f) {
			int hot = ColorMath.withAlpha(t.lampHot, Math.round(200 * v));
			c.fill(px + 4, py + 4, px + 7, py + 7, hot);
			c.fill(px + 9, py + 9, px + 12, py + 12, hot);
			if (!lite) Redstone.glow(c, px + 1, py + 1, 14, 14, t.lampGlow, v);
		}
	}

	/** Kolben in Seitenansicht; der Kopf fährt um bis zu einen Block in Richtung {@code dir} aus. */
	private static void piston(Canvas c, int px, int py, int dir, float ext, Theme t) {
		int e = Math.round(ext * 12);
		// Gehäuse
		rectFS(c, px, py, dir, 0, 1, 12, 15, 0xFF2A2A2F);
		rectFS(c, px, py, dir, 1, 2, 11, 14, 0xFF6A6A71);
		rectFS(c, px, py, dir, 3, 5, 10, 11, 0xFF4A4A51);
		// Stange
		if (e > 0) rectFS(c, px, py, dir, 11, 7, 12 + e, 9, 0xFFB9A88A);
		// Kopf (Holz)
		rectFS(c, px, py, dir, 12 + e, 0, 16 + e, 16, 0xFF3A2A18);
		rectFS(c, px, py, dir, 12 + e, 1, 15 + e, 15, 0xFFA57E4B);
		rectFS(c, px, py, dir, 13 + e, 1, 14 + e, 15, 0xFF8A663A);
	}

	/**
	 * Rechteck in Block-Koordinaten entlang einer Richtung: {@code f} = vorwärts (0..16),
	 * {@code s} = quer (0..16). Damit gibt es Verstärker und Kolben in allen vier Richtungen.
	 */
	private static void rectFS(Canvas c, int px, int py, int dir, int f1, int s1, int f2, int s2, int argb) {
		switch (dir) {
			case 0: c.fill(px + f1, py + s1, px + f2, py + s2, argb); break;
			case 2: c.fill(px + CELL - f2, py + s1, px + CELL - f1, py + s2, argb); break;
			case 1: c.fill(px + s1, py + f1, px + s2, py + f2, argb); break;
			case 3: c.fill(px + s1, py + CELL - f2, px + s2, py + CELL - f1, argb); break;
			default: break;
		}
	}

	/** Randabdunkelung: von außen nach innen gestapelte, halbdurchsichtige Streifen. */
	private static void vignette(Canvas c, int width, int height, boolean lite) {
		int bands = lite ? 4 : 7;
		int size = Math.max(3, Math.min(width, height) / (lite ? 26 : 40));
		int a = (lite ? 30 : 18) << 24;
		for (int i = 0; i < bands; i++) {
			int d = (i + 1) * size;
			c.fill(0, 0, width, d, a);
			c.fill(0, height - d, width, height, a);
			c.fill(0, d, d, height - d, a);
			c.fill(width - d, d, width, height - d, a);
		}
	}
}

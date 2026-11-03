package dev.theredstonee.trsclient.core.minigames;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Redstone-Stapler: reine Spiellogik ohne Anzeige, genaue Übertragung von {@code app/utils/minigames/stapler.ts}. Nur
 * Ganzzahlen und fester 60-Hz-Takt, damit Launcher, Server und Client jede Partie gleich rechnen. Wer die Regeln ändert,
 * ändert alle drei Fassungen; die Testpartien in {@code minigames-vectors.json} decken Abweichungen auf.
 */
public final class Stapler {
	public static final int WIDTH = 10;
	public static final int HEIGHT = 22;
	public static final int HIDDEN = 2;
	/** Eine Partie endet spätestens nach einer Stunde (feste Obergrenze beim Nachrechnen). */
	public static final int MAX_TICKS = 216_000;

	public static final int STONE = 1;
	public static final int COBBLE = 2;
	public static final int BRICK = 3;
	public static final int COPPER = 4;
	public static final int OAK = 5;
	public static final int OBSID = 6;
	public static final int QUARTZ = 7;
	public static final int RED = 8;
	public static final int TNT = 9;
	public static final int LAMP = 10;
	public static final int PISTON = 11;
	public static final int HEAD = 12;

	public static final int K_LEFT = 1;
	public static final int K_RIGHT = 2;
	public static final int K_DOWN = 4;
	public static final int K_CW = 8;
	public static final int K_CCW = 16;
	public static final int K_HARD = 32;
	public static final int K_HOLD = 64;
	public static final int K_PULSE = 128;
	public static final int MAX_MASK = 255;

	public static final int ARE = 14;
	public static final int CLEAR_T = 12;
	private static final int LOCK_T = 30;
	private static final int LOCK_MOVES = 15;
	private static final int DAS = 10;
	private static final int ARR = 2;
	private static final int LAMP_T = 600;

	private static final int[] SHAPE_N = {4, 2, 3, 3, 3, 3, 3};
	private static final int[] SHAPE_M = {QUARTZ, COPPER, OBSID, OAK, BRICK, COBBLE, STONE};
	private static final int[][][] SHAPE_B = {
			{{0, 1}, {1, 1}, {2, 1}, {3, 1}},
			{{0, 0}, {1, 0}, {0, 1}, {1, 1}},
			{{1, 0}, {0, 1}, {1, 1}, {2, 1}},
			{{1, 0}, {2, 0}, {0, 1}, {1, 1}},
			{{0, 0}, {1, 0}, {1, 1}, {2, 1}},
			{{0, 0}, {0, 1}, {1, 1}, {2, 1}},
			{{2, 0}, {0, 1}, {1, 1}, {2, 1}},
	};
	private static final int[] GRAVITY = {48, 40, 32, 26, 20, 16, 12, 9, 7, 5, 4, 3, 2, 1};
	private static final int[][] WALL_KICKS = {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {-2, 0}, {2, 0}, {-1, -1}, {1, -1}};
	private static final int[] SPECIALS = {RED, TNT, LAMP, PISTON};
	private static final int[] LINE_POINTS = {0, 100, 300, 500, 800};

	/** Phase der Partie. */
	public enum Phase {
		ARE, FALL, RESOLVE, OVER
	}

	/** Art eines Ereignisses für Bild und Ton. */
	public enum EventType {
		MOVE, ROTATE, HOLD, WIRE, PULSE, LEVEL, OVER, SPAWN, LOCK, HARD, SWEEP, CLEAR, IGNITE, LAMP, CHARGE, PISTON, EXPLODE
	}

	/** Ereignis für Bild und Ton; beeinflusst das Spiel nicht. Welche Felder gelten, steht am jeweiligen {@code emit}. */
	public static final class Event {
		public final EventType type;
		public int x;
		public int y;
		public int d;
		public int n;
		public int from;
		public int to;
		public int removed;
		public int[] rows;
		/** {@link EventType#LOCK}: Felder (x, y); {@link EventType#CLEAR}: Materialien je Reihe. */
		public int[][] cells;

		Event(EventType type) {
			this.type = type;
		}
	}

	/** Empfängt die Ereignisse einer Partie. */
	public interface Listener {
		void onEvent(Event event);
	}

	/** Ein Feld im Stapel. */
	public static final class Cell {
		public int t;
		public int lit;
		public int fuse;
		public int pw;

		Cell(int t) {
			this.t = t;
		}
	}

	/** Ein Stein. */
	public static final class Piece {
		public final int id;
		public final int n;
		public final int[] mats;
		public int[][] b;
		public int x;
		public int y;

		Piece(int id, int n, int[] mats, int[][] b) {
			this.id = id;
			this.n = n;
			this.mats = mats;
			this.b = b;
		}
	}

	/** Reihen, die gerade ausgelöst werden. */
	public static final class Sweep {
		public final int[] rows;
		public int t;

		Sweep(int[] rows) {
			this.rows = rows;
		}
	}

	private static final class Blast {
		final int x;
		final int y;
		int t;

		Blast(int x, int y, int t) {
			this.x = x;
			this.y = y;
			this.t = t;
		}
	}

	/** Startwert, vorzeichenlos (0 bis 2^32 - 1). */
	public final long seed;
	private final Mulberry rng;
	private final Listener listener;
	public Cell[][] grid = new Cell[HEIGHT][WIDTH];
	public int tick;
	public long score;
	public int lines;
	public int level = 1;
	public int combo;
	public int signal;
	public final List<Piece> queue = new ArrayList<Piece>();
	public Piece hold;
	public boolean canHold = true;
	public Piece cur;
	public Phase phase = Phase.ARE;
	public int timer = ARE;
	public int nextX;
	public Sweep sweep;
	/** Eingaben der Partie: abwechselnd Abstand in Takten und Tastenmaske. */
	public final IntList inputs = new IntList();
	private int[] bag = new int[0];
	private int bagSize;
	private int prev;
	private int dasDir;
	private int dasT;
	private int gravT;
	private int lockT;
	private int lockMoves;
	private final List<Blast> blasts = new ArrayList<Blast>();
	private final ArrayDeque<Cell> power = new ArrayDeque<Cell>();
	private boolean clearedNow;
	private boolean forceBottom;
	private int lastMask;
	private int lastInputTick;
	private int resolveId;

	public Stapler(long seed) {
		this(seed, null);
	}

	public Stapler(long seed, Listener listener) {
		this.seed = seed & 0xFFFFFFFFL;
		this.rng = new Mulberry((int) this.seed);
		this.listener = listener;
		while (queue.size() < 4) queue.add(draw());
		nextX = pickX(queue.get(0));
	}

	public boolean finished() {
		return phase == Phase.OVER;
	}

	private void emit(EventType type) {
		if (listener != null) listener.onEvent(new Event(type));
	}

	private static int[][] copy(int[][] b) {
		int[][] out = new int[b.length][];
		for (int i = 0; i < b.length; i++) out[i] = b[i].clone();
		return out;
	}

	private Piece draw() {
		if (bagSize == 0) {
			bag = new int[] {0, 1, 2, 3, 4, 5, 6};
			bagSize = 7;
			for (int i = 6; i > 0; i--) {
				int j = rng.below(i + 1);
				int t = bag[i];
				bag[i] = bag[j];
				bag[j] = t;
			}
		}
		int id = bag[--bagSize];
		int m = SHAPE_M[id];
		int[] mats = {m, m, m, m};
		if (rng.below(100) < 42) {
			int at = rng.below(4);
			mats[at] = SPECIALS[rng.below(4)];
		}
		return new Piece(id, SHAPE_N[id], mats, copy(SHAPE_B[id]));
	}

	private boolean hits(int[][] b, int x, int y) {
		for (int[] c : b) {
			int cx = x + c[0];
			int cy = y + c[1];
			if (cx < 0 || cx >= WIDTH || cy >= HEIGHT) return true;
			if (cy >= 0 && grid[cy][cx] != null) return true;
		}
		return false;
	}

	/** Zufälliger Dropper: Ein freier wird bevorzugt, Überlauf gibt es erst, wenn wirklich kein Platz mehr ist. */
	private int pickX(Piece p) {
		int min = WIDTH;
		int max = 0;
		for (int[] c : p.b) {
			if (c[0] < min) min = c[0];
			if (c[0] > max) max = c[0];
		}
		int lo = -min;
		int span = WIDTH - 1 - max - lo + 1;
		int s = rng.below(span);
		for (int i = 0; i < span; i++) {
			int x = lo + (s + i) % span;
			if (!hits(p.b, x, 0)) return x;
		}
		return lo + s;
	}

	private void spawn(Piece piece) {
		Piece p = piece != null ? piece : queue.remove(0);
		while (queue.size() < 4) queue.add(draw());
		p.x = phase == Phase.ARE ? nextX : pickX(p);
		p.y = 0;
		cur = p;
		gravT = 0;
		lockT = 0;
		lockMoves = 0;
		if (hits(p.b, p.x, p.y)) {
			end();
			return;
		}
		phase = Phase.FALL;
		if (listener != null) {
			Event e = new Event(EventType.SPAWN);
			e.x = p.x;
			e.n = p.n;
			listener.onEvent(e);
		}
	}

	private void end() {
		phase = Phase.OVER;
		emit(EventType.OVER);
	}

	private void rotate(Piece p, int dir) {
		int[][] next = new int[p.b.length][];
		for (int i = 0; i < p.b.length; i++) {
			int[] c = p.b[i];
			next[i] = dir > 0 ? new int[] {p.n - 1 - c[1], c[0]} : new int[] {c[1], p.n - 1 - c[0]};
		}
		for (int[] k : WALL_KICKS) {
			if (!hits(next, p.x + k[0], p.y + k[1])) {
				p.b = next;
				p.x += k[0];
				p.y += k[1];
				moved();
				emit(EventType.ROTATE);
				return;
			}
		}
	}

	private void moved() {
		if (lockT > 0 && lockMoves < LOCK_MOVES) {
			lockT = 0;
			lockMoves++;
		}
	}

	private void shift(int dir) {
		Piece p = cur;
		if (!hits(p.b, p.x + dir, p.y)) {
			p.x += dir;
			moved();
			emit(EventType.MOVE);
		}
	}

	/** Zeile, in der der aktuelle Stein landen würde. */
	public int ghostY() {
		Piece p = cur;
		int y = p.y;
		while (!hits(p.b, p.x, y + 1)) y++;
		return y;
	}

	private void lock() {
		Piece p = cur;
		boolean above = true;
		for (int i = 0; i < p.b.length; i++) {
			int[] c = p.b[i];
			int y = p.y + c[1];
			int x = p.x + c[0];
			if (y >= 0) grid[y][x] = new Cell(p.mats[i]);
			if (y >= HIDDEN) above = false;
		}
		if (listener != null) {
			Event e = new Event(EventType.LOCK);
			e.cells = new int[p.b.length][];
			for (int i = 0; i < p.b.length; i++) e.cells[i] = new int[] {p.x + p.b[i][0], p.y + p.b[i][1]};
			listener.onEvent(e);
		}
		cur = null;
		canHold = true;
		if (above) {
			end();
			return;
		}
		beginResolve();
	}

	private void beginResolve() {
		phase = Phase.RESOLVE;
		clearedNow = false;
		resolveId++;
	}

	/** Ein Takt (1/60 s) mit den gedrückten Tasten. */
	public void step(int mask) {
		if (phase == Phase.OVER) return;
		if (tick >= MAX_TICKS) {
			end();
			return;
		}
		if (mask != lastMask) {
			inputs.add(tick - lastInputTick);
			inputs.add(mask);
			lastInputTick = tick;
			lastMask = mask;
		}
		tick++;
		int edge = mask & ~prev;
		prev = mask;
		if (tick % 180 == 0 && signal > 0) signal--;
		for (Cell[] row : grid) {
			for (Cell c : row) {
				if (c != null && c.lit > 0) c.lit--;
			}
		}
		if (phase == Phase.ARE) {
			if (--timer <= 0) spawn(null);
			return;
		}
		if (phase == Phase.RESOLVE) {
			stepResolve();
			return;
		}
		if ((edge & K_PULSE) != 0 && signal >= 15) {
			pulse();
			return;
		}
		if ((edge & K_HOLD) != 0 && canHold) {
			Piece p = cur;
			Piece back = hold;
			hold = new Piece(p.id, p.n, p.mats, copy(SHAPE_B[p.id]));
			canHold = false;
			emit(EventType.HOLD);
			spawn(back);
			return;
		}
		Piece c = cur;
		if ((edge & K_CW) != 0) rotate(c, 1);
		if ((edge & K_CCW) != 0) rotate(c, -1);
		int dir = ((mask & K_LEFT) != 0 ? -1 : 0) + ((mask & K_RIGHT) != 0 ? 1 : 0);
		if (dir != dasDir) {
			dasDir = dir;
			dasT = 0;
			if (dir != 0) shift(dir);
		} else if (dir != 0 && ++dasT >= DAS && (dasT - DAS) % ARR == 0) {
			shift(dir);
		}
		if ((edge & K_HARD) != 0) {
			int y = ghostY();
			score += (y - c.y) * 2;
			if (listener != null) {
				Event e = new Event(EventType.HARD);
				e.from = c.y;
				e.to = y;
				listener.onEvent(e);
			}
			c.y = y;
			lock();
			return;
		}
		if (hits(c.b, c.x, c.y + 1)) {
			if (++lockT >= LOCK_T || lockMoves >= LOCK_MOVES) lock();
		} else {
			lockT = 0;
			int every = (mask & K_DOWN) != 0 ? 2 : GRAVITY[Math.min(level - 1, GRAVITY.length - 1)];
			if (++gravT >= every) {
				gravT = 0;
				c.y++;
				if ((mask & K_DOWN) != 0) score += 1;
			}
		}
	}

	private void pulse() {
		signal = 0;
		forceBottom = true;
		beginResolve();
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				Cell c = grid[y][x];
				if (c != null && (c.t == TNT || c.t == LAMP || c.t == PISTON)) power.add(c);
			}
		}
		emit(EventType.PULSE);
	}

	/** Lage des Felds {@code target}: x in {@code at[0]}, y in {@code at[1]}; false, wenn es nicht mehr da ist. */
	private boolean find(Cell target, int[] at) {
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				if (grid[y][x] == target) {
					at[0] = x;
					at[1] = y;
					return true;
				}
			}
		}
		return false;
	}

	private void stepResolve() {
		if (sweep != null) {
			if (++sweep.t >= CLEAR_T) finishClear();
			return;
		}
		boolean busy = false;
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				Cell c = grid[y][x];
				if (c != null && c.fuse > 0) {
					busy = true;
					if (--c.fuse == 0) explode(x, y, c);
				}
			}
		}
		for (int i = blasts.size() - 1; i >= 0; i--) {
			busy = true;
			Blast blast = blasts.get(i);
			if (--blast.t <= 0) {
				blasts.remove(i);
				explode(blast.x, blast.y, null);
			}
		}
		if (!power.isEmpty()) {
			busy = true;
			Cell c = power.poll();
			int[] at = new int[2];
			if (find(c, at)) act(c, at[0], at[1]);
		}
		if (busy) return;
		int[] rows = new int[HEIGHT + 1];
		int count = 0;
		for (int y = 0; y < HEIGHT; y++) {
			if (full(grid[y])) rows[count++] = y;
		}
		if (forceBottom) {
			forceBottom = false;
			for (int y = HEIGHT - 1; y >= 0; y--) {
				if (any(grid[y])) {
					if (!contains(rows, count, y)) rows[count++] = y;
					break;
				}
			}
			Arrays.sort(rows, 0, count);
		}
		if (count > 0) {
			int[] picked = Arrays.copyOf(rows, count);
			sweep = new Sweep(picked);
			if (listener != null) {
				Event e = new Event(EventType.SWEEP);
				e.rows = picked;
				listener.onEvent(e);
			}
			return;
		}
		if (!clearedNow) combo = 0;
		if (cur != null) {
			while (hits(cur.b, cur.x, cur.y) && cur.y > -2) cur.y--;
			phase = Phase.FALL;
		} else {
			phase = Phase.ARE;
			timer = ARE;
			nextX = pickX(queue.get(0));
			emit(EventType.WIRE);
		}
	}

	private static boolean full(Cell[] row) {
		for (Cell c : row) {
			if (c == null) return false;
		}
		return true;
	}

	private static boolean any(Cell[] row) {
		for (Cell c : row) {
			if (c != null) return true;
		}
		return false;
	}

	private static boolean contains(int[] values, int count, int value) {
		for (int i = 0; i < count; i++) {
			if (values[i] == value) return true;
		}
		return false;
	}

	private void neighbours(int x, int y, int[] skip, int up) {
		for (int dy = -up; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				int yy = y + dy;
				int xx = x + dx;
				if (yy < 0 || yy >= HEIGHT || xx < 0 || xx >= WIDTH || contains(skip, skip.length, yy)) continue;
				Cell c = grid[yy][xx];
				if (c != null && c.t >= RED && c.t <= PISTON && c.pw != resolveId) {
					c.pw = resolveId;
					power.add(c);
				}
			}
		}
	}

	private static final int[] NO_ROWS = new int[0];

	private void finishClear() {
		int[] rows = sweep.rows;
		int n = rows.length;
		int lampBonus = 0;
		for (int y : rows) {
			for (int x = 0; x < WIDTH; x++) {
				Cell c = grid[y][x];
				if (c == null) continue;
				if (c.t == RED) {
					neighbours(x, y, rows, 4);
				} else if (c.t == TNT) {
					int below = 0;
					for (int r : rows) {
						if (r > y) below++;
					}
					blasts.add(new Blast(x, y + below, 4));
				} else if (c.t == LAMP) {
					lampBonus += 2;
				}
			}
		}
		if (listener != null) {
			Event e = new Event(EventType.CLEAR);
			e.rows = rows;
			e.cells = new int[n][WIDTH];
			for (int i = 0; i < n; i++) {
				for (int x = 0; x < WIDTH; x++) {
					Cell c = grid[rows[i]][x];
					e.cells[i][x] = c != null ? c.t : 0;
				}
			}
			listener.onEvent(e);
		}
		Cell[][] next = new Cell[HEIGHT][];
		int write = HEIGHT - 1;
		for (int y = HEIGHT - 1; y >= 0; y--) {
			if (!contains(rows, n, y)) next[write--] = grid[y];
		}
		while (write >= 0) next[write--] = new Cell[WIDTH];
		grid = next;
		combo++;
		clearedNow = true;
		lines += n;
		award((long) LINE_POINTS[Math.min(n, 4)] * level + 50L * (combo - 1) * level);
		signal = Math.min(15, signal + 3 * n + (combo - 1) + lampBonus);
		int newLevel = 1 + lines / 10;
		if (newLevel != level) {
			level = newLevel;
			emit(EventType.LEVEL);
		}
		sweep = null;
	}

	/** Brennende Lampen im Stapel: jede erhöht die Punkte. */
	public int litLamps() {
		int n = 0;
		for (Cell[] row : grid) {
			for (Cell c : row) {
				if (c != null && c.t == LAMP && c.lit > 0) n++;
			}
		}
		return n;
	}

	private void award(long points) {
		score += points * (2 + litLamps()) / 2;
	}

	private void act(Cell c, int x, int y) {
		if (c.t == TNT) {
			if (c.fuse == 0) {
				c.fuse = 10;
				if (listener != null) {
					Event e = new Event(EventType.IGNITE);
					e.x = x;
					e.y = y;
					listener.onEvent(e);
				}
			}
		} else if (c.t == LAMP) {
			c.lit = LAMP_T;
			if (listener != null) {
				Event e = new Event(EventType.LAMP);
				e.x = x;
				e.y = y;
				listener.onEvent(e);
			}
		} else if (c.t == RED) {
			neighbours(x, y, NO_ROWS, 1);
			if (listener != null) {
				Event e = new Event(EventType.CHARGE);
				e.x = x;
				e.y = y;
				listener.onEvent(e);
			}
		} else if (c.t == PISTON) {
			piston(c, x, y);
		}
	}

	private void piston(Cell c, int x, int y) {
		Cell[] row = grid[y];
		int best = 0;
		int dist = 99;
		for (int k = 0; k < 2; k++) {
			int d = k == 0 ? -1 : 1;
			for (int i = x + d, s = 1; i >= 0 && i < WIDTH; i += d, s++) {
				if (row[i] == null) {
					if (s < dist) {
						dist = s;
						best = d;
					}
					break;
				}
			}
		}
		c.t = STONE;
		if (best == 0) return;
		for (int i = x + best * dist; i != x + best; i -= best) row[i] = row[i - best];
		row[x + best] = new Cell(HEAD);
		if (listener != null) {
			Event e = new Event(EventType.PISTON);
			e.x = x;
			e.y = y;
			e.d = best;
			e.n = dist;
			listener.onEvent(e);
		}
	}

	private void explode(int cx, int cy, Cell self) {
		int removed = 0;
		int columns = 0;
		for (int y = cy - 1; y <= cy + 1; y++) {
			for (int x = cx - 1; x <= cx + 1; x++) {
				if (y < 0 || y >= HEIGHT || x < 0 || x >= WIDTH) continue;
				Cell c = grid[y][x];
				if (c == null) continue;
				if (c != self && c.t == TNT) {
					if (c.fuse == 0) c.fuse = 6;
					continue;
				}
				grid[y][x] = null;
				removed++;
				columns |= 1 << x;
			}
		}
		award(25L * removed * level);
		for (int x = 0; x < WIDTH; x++) {
			if ((columns & (1 << x)) == 0) continue;
			int write = Math.min(HEIGHT - 1, cy + 1);
			for (int y = write; y >= 0; y--) {
				Cell c = grid[y][x];
				if (c != null) {
					grid[y][x] = null;
					grid[write][x] = c;
					write--;
				}
			}
		}
		if (listener != null) {
			Event e = new Event(EventType.EXPLODE);
			e.x = cx;
			e.y = cy;
			e.removed = removed;
			listener.onEvent(e);
		}
	}

	/** Prüfsumme des Endstands, vorzeichenlos (0 bis 2^32 - 1). */
	public long hash() {
		int h = (int) 2166136261L ^ (int) score;
		for (Cell[] row : grid) {
			for (Cell c : row) h = (h ^ (c != null ? c.t + 1 : 0)) * 16777619;
		}
		return (((h ^ tick) * 16777619) ^ lines) & 0xFFFFFFFFL;
	}
}

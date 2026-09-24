package dev.theredstonee.trsclient.core.perf;

/**
 * „Hinter Wänden ausblenden“ (Entity-Culling light): Ist von der Kamera aus kein Punkt der
 * Hitbox zu sehen – jede Sichtlinie trifft einen vollen, undurchsichtigen Block –, wird das Wesen
 * nicht gezeichnet. Glas, Laub, Zäune usw. verdecken nicht – nur volle Blöcke wie Stein oder Erde.
 *
 * <p>Die Sichtlinien rechnet ein eigener Hintergrund-Thread („TRS-Occlusion“): Der Render-Thread
 * meldet in {@link #visible} nur Hitbox und Kamera (ein paar Feldzugriffe) und bekommt das zuletzt
 * berechnete Ergebnis zurück. Der Thread arbeitet alle gemeldeten Wesen reihum mit der jeweils
 * neuesten Kameraposition ab (höchstens {@link #RAYS_PER_PASS} Sichtlinien je Durchgang) und schläft
 * zwischendurch. Dadurch kostet das Culling im Bild keine Zeit und ruckelt nicht, egal wie viele
 * Wesen es gibt. Neue Wesen gelten bis zur ersten Rechnung als sichtbar, veraltete Ergebnisse
 * „verdeckt“ laufen nach {@link #HIDDEN_MAX_AGE_NS} ab (nie dauerhaft fälschlich verstecken).
 *
 * <p>Nahe Wesen (≤ {@link #NEAR} Blöcke), sehr kleine (Gegenstände, Pfeile, Erfahrung) und die
 * Kamera in der Hitbox werden gar nicht gerechnet – sie kosten beim Zeichnen kaum etwas.
 */
public final class Occlusion {
	/** Voller, undurchsichtiger Block an dieser Stelle? (Aus dem Hintergrund-Thread aufgerufen.) */
	public interface Blocks {
		boolean opaque(int x, int y, int z);
	}

	/** Sichtlinien je Durchgang des Hintergrund-Threads. */
	public static final int RAYS_PER_PASS = 4000;
	/** Näher als das ist immer sichtbar (Blöcke). */
	static final double NEAR = 4;
	/** Weiter weg wird nicht gerechnet (die Entfernungsgrenze greift dort). */
	static final double FAR = 96;
	/** Hitboxen, deren größte Kante kleiner ist, werden nicht gerechnet (Gegenstände, Pfeile, Erfahrung). */
	static final double SMALL = 0.7;
	/** Ein Ergebnis wird spätestens nach dieser Zeit neu gerechnet. */
	static final long REFRESH_NS = 40_000_000L;
	/** „Verdeckt“ gilt höchstens so lange ohne neue Rechnung (Thread hängt → lieber zeichnen). */
	static final long HIDDEN_MAX_AGE_NS = 500_000_000L;
	/** Nicht mehr gemeldete Wesen werden nach dieser Zeit nicht mehr gerechnet. */
	static final long FORGET_NS = 1_000_000_000L;
	/** Ohne Anfragen schläft der Thread nach dieser Zeit ganz. */
	private static final long IDLE_NS = 2_000_000_000L;
	private static final int SIZE = 4096;

	private final boolean threaded;
	private final Object lock = new Object();
	private final int[] ids = new int[SIZE];
	private final boolean[] used = new boolean[SIZE];
	/** Hitbox je Platz: minX, minY, minZ, maxX, maxY, maxZ. */
	private final double[] box = new double[SIZE * 6];
	private final long[] requested = new long[SIZE];
	private final long[] checked = new long[SIZE];
	private final boolean[] result = new boolean[SIZE];
	private final double[] px = new double[9];
	private final double[] py = new double[9];
	private final double[] pz = new double[9];

	private volatile Blocks blocks;
	private volatile double camX;
	private volatile double camY;
	private volatile double camZ;
	private volatile long lastRequest;
	private volatile boolean sleeping;
	private volatile int generation;
	private Thread worker;
	private int cursor;
	private volatile int rays;
	private volatile int hidden;

	/** Mit Hintergrund-Thread (im Spiel). */
	public Occlusion() {
		this(true);
	}

	private Occlusion(boolean threaded) {
		this.threaded = threaded;
	}

	/** Ohne Thread: gerechnet wird nur in {@link #compute} (Tests). */
	public static Occlusion manual() {
		return new Occlusion(false);
	}

	/**
	 * Zugriff auf die Welt für den Hintergrund-Thread (eigene Instanz, nicht mit dem Render-Thread teilen).
	 * {@code null} = keine Welt → nichts rechnen.
	 */
	public void setBlocks(Blocks blocks) {
		this.blocks = blocks;
	}

	/** Einmal je Client-Tick (derzeit nur für die Statistik). */
	public void tick(long tick) {
		// Die Arbeit verteilt der Hintergrund-Thread selbst.
	}

	/** Weltwechsel: alles vergessen. */
	public void clear() {
		synchronized (lock) {
			java.util.Arrays.fill(used, false);
			generation++;
		}
	}

	/** Sichtlinien seit dem letzten Aufruf (Statistik für den Autotest). */
	public int takeRays() {
		int r = rays;
		rays = 0;
		return r;
	}

	/** Zuletzt als verdeckt erkannte Wesen seit dem letzten Aufruf. */
	public int takeHidden() {
		int h = hidden;
		hidden = 0;
		return h;
	}

	/**
	 * Ist das Wesen {@code id} mit der Hitbox (min…max) von der Kamera aus zu sehen? Liefert das zuletzt
	 * berechnete Ergebnis (neu: sichtbar) und meldet die Hitbox für die nächste Rechnung. Nur Render-Thread.
	 */
	public boolean visible(int id, double camX, double camY, double camZ, double minX, double minY, double minZ,
			double maxX, double maxY, double maxZ) {
		double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5, cz = (minZ + maxZ) * 0.5;
		double dx = cx - camX, dy = cy - camY, dz = cz - camZ;
		double distSq = dx * dx + dy * dy + dz * dz;
		if (distSq < NEAR * NEAR || distSq > FAR * FAR) return true;
		double size = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
		if (size < SMALL) return true;
		// Kamera in der (etwas vergrößerten) Hitbox → sichtbar.
		if (camX > minX - 1 && camX < maxX + 1 && camY > minY - 1 && camY < maxY + 1 && camZ > minZ - 1 && camZ < maxZ + 1) {
			return true;
		}
		long now = System.nanoTime();
		int slot = (id * 0x9E3779B1 >>> 20) & (SIZE - 1);
		if (!used[slot] || ids[slot] != id) {
			ids[slot] = id;
			result[slot] = true;
			checked[slot] = 0;
			used[slot] = true;
		}
		int b = slot * 6;
		box[b] = minX;
		box[b + 1] = minY;
		box[b + 2] = minZ;
		box[b + 3] = maxX;
		box[b + 4] = maxY;
		box[b + 5] = maxZ;
		requested[slot] = now;
		this.camX = camX;
		this.camY = camY;
		this.camZ = camZ;
		lastRequest = now;
		if (threaded) ensureWorker();
		boolean seen = result[slot];
		// Veraltetes „verdeckt“ (Thread kommt nicht hinterher) → lieber zeichnen.
		if (!seen && now - checked[slot] > HIDDEN_MAX_AGE_NS) return true;
		return seen;
	}

	/**
	 * Rechnet gemeldete Wesen reihum mit der neuesten Kameraposition, höchstens {@code maxRays} Sichtlinien.
	 * Aus dem Hintergrund-Thread bzw. in Tests direkt.
	 * @return benutzte Sichtlinien
	 */
	public int compute(int maxRays) {
		Blocks world = blocks;
		if (world == null) return 0;
		long now = System.nanoTime();
		double cx = camX, cy = camY, cz = camZ;
		int budget = maxRays;
		int gen = generation;
		for (int k = 0; k < SIZE && budget > 0; k++) {
			int slot = cursor;
			cursor = (cursor + 1) & (SIZE - 1);
			if (!used[slot] || now - requested[slot] > FORGET_NS || now - checked[slot] < REFRESH_NS) continue;
			int id = ids[slot];
			int b = slot * 6;
			double minX = box[b], minY = box[b + 1], minZ = box[b + 2];
			double maxX = box[b + 3], maxY = box[b + 4], maxZ = box[b + 5];
			boolean seen;
			try {
				int before = budget;
				budget = trace(world, cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ, budget);
				seen = budget >= 0;
				if (budget < 0) budget = -budget - 1;
				rays += before - budget;
				// Budget mitten in der Prüfung aufgebraucht → später weiter, bis dahin altes Ergebnis.
				if (budget == 0 && !seen) break;
			} catch (RuntimeException | LinkageError e) {
				// Chunk wird gerade umgebaut o. ä. → sichtbar lassen, später neu.
				seen = true;
			}
			if (gen != generation || ids[slot] != id) continue;
			result[slot] = seen;
			checked[slot] = now;
			if (!seen) hidden++;
		}
		return maxRays - budget;
	}

	/**
	 * Prüft die Sichtlinien zu den Punkten der Hitbox (Mitte, oben, unten, acht Ecken). Rückgabe: Restbudget
	 * (≥ 0) wenn ein Punkt sichtbar ist, sonst {@code -(Restbudget) - 1}.
	 */
	private int trace(Blocks world, double camX, double camY, double camZ, double minX, double minY, double minZ,
			double maxX, double maxY, double maxZ, int budget) {
		double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5, cz = (minZ + maxZ) * 0.5;
		double ix = (maxX - minX) * 0.1, iy = (maxY - minY) * 0.05, iz = (maxZ - minZ) * 0.1;
		int n = 0;
		n = point(n, cx, cy, cz);
		n = point(n, cx, maxY - iy, cz);
		n = point(n, cx, minY + iy, cz);
		for (int i = 0; i < 4 && n < 9; i++) {
			double x = (i & 1) == 0 ? minX + ix : maxX - ix;
			double z = (i & 2) == 0 ? minZ + iz : maxZ - iz;
			n = point(n, x, maxY - iy, z);
			if (n < 9) n = point(n, x, minY + iy, z);
		}
		for (int i = 0; i < n; i++) {
			if (budget <= 0) return -1;
			budget--;
			if (clear(world, camX, camY, camZ, px[i], py[i], pz[i])) return budget;
		}
		return -budget - 1;
	}

	private int point(int n, double x, double y, double z) {
		px[n] = x;
		py[n] = y;
		pz[n] = z;
		return n + 1;
	}

	// --- Hintergrund-Thread ---

	private void ensureWorker() {
		Thread t = worker;
		if (t == null) {
			synchronized (lock) {
				if (worker == null) {
					t = new Thread(this::run, "TRS-Occlusion");
					t.setDaemon(true);
					t.setPriority(Thread.NORM_PRIORITY - 1);
					worker = t;
					t.start();
				}
			}
			return;
		}
		if (sleeping) {
			synchronized (lock) {
				lock.notifyAll();
			}
		}
	}

	private void run() {
		while (true) {
			try {
				long idle = System.nanoTime() - lastRequest;
				if (idle > IDLE_NS || blocks == null) {
					synchronized (lock) {
						sleeping = true;
						lock.wait(1000);
						sleeping = false;
					}
					continue;
				}
				int used = compute(RAYS_PER_PASS);
				// Viel zu tun → kurz Luft holen; sonst etwas länger (die Kamera bewegt sich in 5 ms kaum).
				Thread.sleep(used >= RAYS_PER_PASS ? 1 : 5);
			} catch (InterruptedException e) {
				return;
			} catch (RuntimeException | LinkageError e) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException ie) {
					return;
				}
			}
		}
	}

	/**
	 * Freie Linie von (sx,sy,sz) nach (tx,ty,tz)? Läuft Block für Block (Amanatides-Woo);
	 * Start- und Zielblock zählen nicht.
	 */
	public static boolean clear(Blocks blocks, double sx, double sy, double sz, double tx, double ty, double tz) {
		int x = floor(sx), y = floor(sy), z = floor(sz);
		int endX = floor(tx), endY = floor(ty), endZ = floor(tz);
		double dx = tx - sx, dy = ty - sy, dz = tz - sz;
		int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
		int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
		int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
		double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
		double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
		double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
		double tMaxX = stepX == 0 ? Double.MAX_VALUE : boundary(sx, stepX) * tDeltaX;
		double tMaxY = stepY == 0 ? Double.MAX_VALUE : boundary(sy, stepY) * tDeltaY;
		double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : boundary(sz, stepZ) * tDeltaZ;
		int max = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z) + 1;
		for (int i = 0; i < max; i++) {
			if (x == endX && y == endY && z == endZ) return true;
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				if (tMaxX > 1.0) return true;
				x += stepX;
				tMaxX += tDeltaX;
			} else if (tMaxY < tMaxZ) {
				if (tMaxY > 1.0) return true;
				y += stepY;
				tMaxY += tDeltaY;
			} else {
				if (tMaxZ > 1.0) return true;
				z += stepZ;
				tMaxZ += tDeltaZ;
			}
			if (x == endX && y == endY && z == endZ) return true;
			if (blocks.opaque(x, y, z)) return false;
		}
		return true;
	}

	private static double boundary(double v, int step) {
		double f = v - Math.floor(v);
		return step > 0 ? 1.0 - f : f;
	}

	private static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}
}

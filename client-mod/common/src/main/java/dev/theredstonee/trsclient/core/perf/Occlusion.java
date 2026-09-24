package dev.theredstonee.trsclient.core.perf;

/**
 * „Hinter Wänden ausblenden“ (Entity-Culling light): Ist von der Kamera aus kein Punkt der
 * Hitbox zu sehen – jede Sichtlinie trifft einen vollen, undurchsichtigen Block –, wird das Wesen
 * nicht gezeichnet. Das Ergebnis gilt ein paar Ticks (versetzt je Wesen), und je Tick gibt es ein
 * festes Budget an Sichtlinien; ist es aufgebraucht, gilt „sichtbar“ (nie fälschlich verstecken).
 * Glas, Laub, Zäune usw. verdecken nicht – nur volle Blöcke wie Stein oder Erde.
 */
public final class Occlusion {
	/** Voller, undurchsichtiger Block an dieser Stelle? */
	public interface Blocks {
		boolean opaque(int x, int y, int z);
	}

	/** Sichtlinien je Tick. */
	public static final int RAYS_PER_TICK = 600;
	/** Ergebnis „sichtbar“ gilt so viele Ticks, „verdeckt“ halb so lange. */
	private static final int VISIBLE_TICKS = 4;
	private static final int HIDDEN_TICKS = 2;
	/** Näher als das ist immer sichtbar (Blöcke). */
	private static final double NEAR = 2.5;
	/** Weiter weg wird nicht gerechnet (die Entfernungsgrenze greift dort). */
	private static final double FAR = 96;
	private static final int SIZE = 4096;

	private final int[] ids = new int[SIZE];
	private final long[] until = new long[SIZE];
	private final boolean[] visible = new boolean[SIZE];
	private final boolean[] used = new boolean[SIZE];
	private final double[] px = new double[9];
	private final double[] py = new double[9];
	private final double[] pz = new double[9];
	private long tick;
	private int budget = RAYS_PER_TICK;
	private int rays;
	private int hidden;

	/** Einmal je Client-Tick. */
	public void tick(long tick) {
		this.tick = tick;
		budget = RAYS_PER_TICK;
	}

	/** Weltwechsel: alles vergessen. */
	public void clear() {
		java.util.Arrays.fill(used, false);
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
	 * Ist das Wesen {@code id} mit der Hitbox (min…max) von der Kamera aus zu sehen?
	 */
	public boolean visible(int id, double camX, double camY, double camZ, double minX, double minY, double minZ,
			double maxX, double maxY, double maxZ, Blocks blocks) {
		double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5, cz = (minZ + maxZ) * 0.5;
		double dx = cx - camX, dy = cy - camY, dz = cz - camZ;
		double distSq = dx * dx + dy * dy + dz * dz;
		if (distSq < NEAR * NEAR || distSq > FAR * FAR) return true;
		// Kamera in der (etwas vergrößerten) Hitbox → sichtbar.
		if (camX > minX - 1 && camX < maxX + 1 && camY > minY - 1 && camY < maxY + 1 && camZ > minZ - 1 && camZ < maxZ + 1) {
			return true;
		}
		int slot = (id * 0x9E3779B1 >>> 20) & (SIZE - 1);
		if (used[slot] && ids[slot] == id && tick < until[slot]) return visible[slot];
		// Kein Budget mehr: altes Ergebnis weiterverwenden, sonst sichtbar.
		if (budget <= 0) return !(used[slot] && ids[slot] == id) || visible[slot];

		// Punkte: Mitte, oben, unten, dann die acht (leicht nach innen gezogenen) Ecken.
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
		boolean seen = false;
		for (int i = 0; i < n && budget > 0; i++) {
			budget--;
			rays++;
			if (clear(blocks, camX, camY, camZ, px[i], py[i], pz[i])) {
				seen = true;
				break;
			}
		}
		// Budget mitten in der Prüfung aufgebraucht → lieber sichtbar lassen.
		if (!seen && budget <= 0) seen = true;
		used[slot] = true;
		ids[slot] = id;
		visible[slot] = seen;
		until[slot] = tick + (seen ? VISIBLE_TICKS : HIDDEN_TICKS);
		if (!seen) hidden++;
		return seen;
	}

	private int point(int n, double x, double y, double z) {
		px[n] = x;
		py[n] = y;
		pz[n] = z;
		return n + 1;
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

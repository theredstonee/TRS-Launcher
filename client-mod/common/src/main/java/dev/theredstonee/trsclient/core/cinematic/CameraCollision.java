package dev.theredstonee.trsclient.core.cinematic;

/**
 * Fair Play der freien Kamera: die Kamera ist ein kleiner Würfel (halbe Kantenlänge {@link #MARGIN}), der nicht in
 * massive, undurchsichtige Blöcke eindringt. Bewegt wird Achse für Achse (Y, X, Z – wie Minecraft-Entities, so gleitet
 * die Kamera an Wänden entlang) in Teilschritten von höchstens {@link #STEP} Blöcken, damit auch hohe Geschwindigkeiten
 * nicht durch eine Wand „tunneln“. Der Abstand verhindert außerdem, dass die nahe Schnittebene der Kamera (0,05) in
 * einen Block ragt und man hineinsieht.
 *
 * <p>Welche Blöcke massiv sind, entscheidet die Minecraft-Version über {@link Blocks}. Rein rechnerisch, ohne Zustand.
 */
public final class CameraCollision {
	/** Abstand der Kamera zu massiven Blöcken (Blöcke). */
	public static final double MARGIN = 0.2;
	/** Größter Teilschritt – kleiner als der Würfel, also wird jede Blockschicht geprüft. */
	static final double STEP = 0.25;
	/** Luft zwischen Kamera-Würfel und Wand nach dem Anhalten. */
	static final double EPS = 1e-4;
	/** Suche nach einem freien Startpunkt: Schrittweite und größte Verschiebung je Achse. */
	static final double SETTLE_STEP = 0.05;
	static final double SETTLE_MAX = 0.3;

	/** Bit je Achse in {@link #move}: an dieser Achse angestoßen. */
	public static final int HIT_X = 1;
	public static final int HIT_Y = 2;
	public static final int HIT_Z = 4;

	/** Welt-Abfrage der jeweiligen Minecraft-Version. */
	public interface Blocks {
		/** Ist der Block an dieser Stelle eine Wand für die Kamera (massiv und undurchsichtig)? */
		boolean solid(int x, int y, int z);
	}

	private CameraCollision() {
	}

	/** Berührt der Würfel um (x, y, z) mit halber Kante {@code m} einen massiven Block? */
	public static boolean blocked(Blocks blocks, double x, double y, double z, double m) {
		int x0 = floor(x - m), x1 = floor(x + m);
		int y0 = floor(y - m), y1 = floor(y + m);
		int z0 = floor(z - m), z1 = floor(z + m);
		for (int bx = x0; bx <= x1; bx++) {
			for (int by = y0; by <= y1; by++) {
				for (int bz = z0; bz <= z1; bz++) {
					if (blocks.solid(bx, by, bz)) return true;
				}
			}
		}
		return false;
	}

	/**
	 * Verschiebt {@code pos} (x, y, z) um (dx, dy, dz). Ohne {@code blocks} (Fair Play aus) frei durch alles.
	 *
	 * <p>Steckt die Kamera schon in einem Block (Start an einer engen Stelle), gilt nur der Punkt selbst – so kommt sie
	 * wieder heraus; steckt sogar der Punkt fest, bewegt sie sich frei, bis sie draußen ist.
	 *
	 * @return angestoßene Achsen ({@link #HIT_X} | {@link #HIT_Y} | {@link #HIT_Z})
	 */
	public static int move(double[] pos, double dx, double dy, double dz, Blocks blocks) {
		if (blocks == null) {
			pos[0] += dx;
			pos[1] += dy;
			pos[2] += dz;
			return 0;
		}
		double m = MARGIN;
		if (blocked(blocks, pos[0], pos[1], pos[2], m)) {
			if (blocked(blocks, pos[0], pos[1], pos[2], 0)) {
				pos[0] += dx;
				pos[1] += dy;
				pos[2] += dz;
				return 0;
			}
			m = 0;
		}
		int hit = 0;
		if (axis(pos, 1, dy, blocks, m)) hit |= HIT_Y;
		if (axis(pos, 0, dx, blocks, m)) hit |= HIT_X;
		if (axis(pos, 2, dz, blocks, m)) hit |= HIT_Z;
		return hit;
	}

	/** Eine Achse in Teilschritten; true = an einer Wand angehalten. */
	private static boolean axis(double[] pos, int axis, double d, Blocks blocks, double m) {
		if (d == 0 || Double.isNaN(d)) return false;
		int steps = Math.max(1, (int) Math.ceil(Math.abs(d) / STEP));
		double s = d / steps;
		double[] test = new double[3];
		for (int i = 0; i < steps; i++) {
			double next = pos[axis] + s;
			test[0] = pos[0];
			test[1] = pos[1];
			test[2] = pos[2];
			test[axis] = next;
			if (!blocked(blocks, test[0], test[1], test[2], m)) {
				pos[axis] = next;
				continue;
			}
			// Bis an die Wand: neu berührt wird nur die Blockschicht hinter der vorderen Kante.
			if (s > 0) {
				double face = Math.floor(next + m);
				pos[axis] = Math.max(pos[axis], face - m - EPS);
			} else {
				double face = Math.floor(next - m) + 1;
				pos[axis] = Math.min(pos[axis], face + m + EPS);
			}
			return true;
		}
		return false;
	}

	/**
	 * Freien Startpunkt in der Nähe suchen (z. B. Augen 0,18 unter der Decke): kleinste Verschiebung bis
	 * {@link #SETTLE_MAX} je Achse, bei der der Würfel frei ist. Findet sich keiner, bleibt {@code pos} unverändert.
	 *
	 * @return true, wenn {@code pos} jetzt frei ist
	 */
	public static boolean settle(double[] pos, Blocks blocks) {
		if (blocks == null || !blocked(blocks, pos[0], pos[1], pos[2], MARGIN)) return true;
		int n = (int) Math.round(SETTLE_MAX / SETTLE_STEP);
		double best = Double.MAX_VALUE;
		double bx = 0, by = 0, bz = 0;
		for (int i = -n; i <= n; i++) {
			for (int j = -n; j <= n; j++) {
				for (int k = -n; k <= n; k++) {
					double dist = i * i + j * j + k * k;
					if (dist >= best) continue;
					double x = pos[0] + i * SETTLE_STEP, y = pos[1] + j * SETTLE_STEP, z = pos[2] + k * SETTLE_STEP;
					if (blocked(blocks, x, y, z, MARGIN)) continue;
					best = dist;
					bx = x;
					by = y;
					bz = z;
				}
			}
		}
		if (best == Double.MAX_VALUE) return false;
		pos[0] = bx;
		pos[1] = by;
		pos[2] = bz;
		return true;
	}

	static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}
}

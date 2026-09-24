package dev.theredstonee.trsclient.core.redstone;

/**
 * Freie Sicht von einem Punkt zu einem Block: läuft Block für Block entlang der Linie
 * (Amanatides-Woo) und fragt, ob ein voller, undurchsichtiger Block im Weg ist.
 * Start- und Zielblock zählen nicht.
 */
public final class LineOfSight {
	/** Höchstens so viele Blöcke je Linie (Radius 16 → Diagonale ≈ 28). */
	private static final int MAX_STEPS = 96;

	private LineOfSight() {
	}

	/** Ist (tx, ty, tz) von (sx, sy, sz) aus zu sehen? Zielblock = ⌊t⌋. */
	public static boolean clear(RedstoneWorld world, double sx, double sy, double sz, double tx, double ty, double tz) {
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
		for (int i = 0; i < MAX_STEPS; i++) {
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
			if (world.opaque(x, y, z)) return false;
		}
		return false;
	}

	/** Weg bis zur nächsten Blockgrenze in Schrittrichtung (in Blöcken, 0..1]. */
	private static double boundary(double v, int step) {
		double f = v - Math.floor(v);
		return step > 0 ? 1.0 - f : f;
	}

	private static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}
}

package dev.theredstonee.trsclient.core.circuit;

import java.util.Map;
import java.util.TreeMap;

/**
 * Wo und wie eine Schaltung in der Welt liegt: Ursprung (kleinste Ecke nach dem Drehen), Drehung in
 * Vierteldrehungen im Uhrzeigersinn (von oben gesehen) und Spiegelung (vor dem Drehen an der x-Achse).
 * Rechnet Felder und Richtungs-Eigenschaften der Schaltung in Welt-Koordinaten um.
 */
public final class Placement {
	public final int x;
	public final int y;
	public final int z;
	/** 0–3 Vierteldrehungen im Uhrzeigersinn. */
	public final int rotation;
	public final boolean mirror;

	public Placement(int x, int y, int z, int rotation, boolean mirror) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.rotation = ((rotation % 4) + 4) % 4;
		this.mirror = mirror;
	}

	/**
	 * Legt die Schaltung so, dass ihre Grundfläche mittig über {@code (ax, ay, az)} liegt (unterste Schicht auf
	 * Höhe ay).
	 */
	public static Placement centered(Circuit c, int ax, int ay, int az, int rotation, boolean mirror) {
		int r = ((rotation % 4) + 4) % 4;
		int w = (r % 2 == 0) ? c.sizeX : c.sizeZ;
		int d = (r % 2 == 0) ? c.sizeZ : c.sizeX;
		return new Placement(ax - (w - 1) / 2, ay, az - (d - 1) / 2, r, mirror);
	}

	/** Breite (x) nach dem Drehen. */
	public int width(Circuit c) {
		return rotation % 2 == 0 ? c.sizeX : c.sizeZ;
	}

	/** Tiefe (z) nach dem Drehen. */
	public int depth(Circuit c) {
		return rotation % 2 == 0 ? c.sizeZ : c.sizeX;
	}

	/** Welt-Koordinaten eines Feldes {x, y, z}. */
	public void toWorld(Circuit c, int lx, int ly, int lz, int[] out) {
		int px = mirror ? c.sizeX - 1 - lx : lx;
		int pz = lz;
		int sx = c.sizeX;
		int sz = c.sizeZ;
		for (int i = 0; i < rotation; i++) {
			// (x, z) → (sz-1-z, x): Norden (z=0) wandert nach Osten
			int nx = sz - 1 - pz;
			int nz = px;
			px = nx;
			pz = nz;
			int t = sx;
			sx = sz;
			sz = t;
		}
		out[0] = x + px;
		out[1] = y + ly;
		out[2] = z + pz;
	}

	/** Richtungsname nach Spiegelung + Drehung ("north" → "east" bei einer Vierteldrehung). */
	public String direction(String dir) {
		if (dir == null) return null;
		String d = dir;
		if (mirror) {
			if ("east".equals(d)) d = "west";
			else if ("west".equals(d)) d = "east";
		}
		for (int i = 0; i < rotation; i++) d = clockwise(d);
		return d;
	}

	static String clockwise(String d) {
		if ("north".equals(d)) return "east";
		if ("east".equals(d)) return "south";
		if ("south".equals(d)) return "west";
		if ("west".equals(d)) return "north";
		return d;
	}

	/** Eigenschaften eines Feldes nach Spiegelung + Drehung (nur {@code facing} ist richtungsabhängig). */
	public Map<String, String> props(BlockSpec spec) {
		Map<String, String> out = new TreeMap<String, String>(spec.props);
		String f = out.get("facing");
		if (f != null) out.put("facing", direction(f));
		return out;
	}

	public Placement moved(int nx, int ny, int nz) {
		return new Placement(nx, ny, nz, rotation, mirror);
	}

	public Placement rotated(int newRotation) {
		return new Placement(x, y, z, newRotation, mirror);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Placement)) return false;
		Placement p = (Placement) o;
		return p.x == x && p.y == y && p.z == z && p.rotation == rotation && p.mirror == mirror;
	}

	@Override
	public int hashCode() {
		return ((x * 31 + y) * 31 + z) * 8 + rotation * 2 + (mirror ? 1 : 0);
	}
}

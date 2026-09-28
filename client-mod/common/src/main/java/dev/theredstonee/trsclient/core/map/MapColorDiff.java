package dev.theredstonee.trsclient.core.map;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Selbsttest-Hilfe: Momentaufnahme der Kartenfarben rund um einen Punkt und Vergleich mit einem späteren Stand –
 * belegt, dass sich die Farben zwischen Vanilla- und Texturfarben wirklich unterscheiden. Nur Spiel-Thread.
 */
public final class MapColorDiff {
	private final Map<Long, int[]> pixels = new HashMap<Long, int[]>();
	private final double cx, cz, radius;

	private MapColorDiff(double cx, double cz, double radius) {
		this.cx = cx;
		this.cz = cz;
		this.radius = radius;
	}

	/** Kopie aller geladenen Bereiche der Ebene, die den Kreis um (cx, cz) berühren. */
	public static MapColorDiff snapshot(MapLayer layer, double cx, double cz, double radius) {
		MapColorDiff d = new MapColorDiff(cx, cz, radius);
		if (layer == null) return d;
		for (MapRegion r : layer.loaded()) {
			if (d.touches(r)) d.pixels.put(r.key(), r.pixels.clone());
		}
		return d;
	}

	private boolean touches(MapRegion r) {
		double x0 = (double) r.rx * MapRegion.SIZE, z0 = (double) r.rz * MapRegion.SIZE;
		double nx = Math.max(x0, Math.min(cx, x0 + MapRegion.SIZE)), nz = Math.max(z0, Math.min(cz, z0 + MapRegion.SIZE));
		return (nx - cx) * (nx - cx) + (nz - cz) * (nz - cz) <= radius * radius;
	}

	/**
	 * Vergleicht mit dem jetzigen Stand der Ebene (nur Pixel im Kreis, die in beiden Ständen erkundet sind).
	 *
	 * @return {verglichene Pixel, geänderte Pixel (Summe der Kanal-Unterschiede > 12), Summe aller Kanal-Unterschiede}
	 */
	public long[] compare(MapLayer layer) {
		long compared = 0, changed = 0, sum = 0;
		if (layer == null) return new long[] {0, 0, 0};
		for (MapRegion r : layer.loaded()) {
			int[] before = pixels.get(r.key());
			if (before == null) continue;
			for (int z = 0; z < MapRegion.SIZE; z++) {
				double wz = (double) r.rz * MapRegion.SIZE + z + 0.5;
				for (int x = 0; x < MapRegion.SIZE; x++) {
					double wx = (double) r.rx * MapRegion.SIZE + x + 0.5;
					if ((wx - cx) * (wx - cx) + (wz - cz) * (wz - cz) > radius * radius) continue;
					int i = z * MapRegion.SIZE + x;
					int a = before[i], b = r.pixels[i];
					if ((a & MapColors.KNOWN) == 0 || (b & MapColors.KNOWN) == 0) continue;
					int d = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
							+ Math.abs((a & 0xFF) - (b & 0xFF));
					compared++;
					sum += d;
					if (d > 12) changed++;
				}
			}
		}
		return new long[] {compared, changed, sum};
	}

	/** Lesbare Zusammenfassung von {@link #compare}. */
	public static String describe(long[] r) {
		if (r[0] == 0) return "keine vergleichbaren Pixel";
		return String.format(Locale.ROOT, "%d Pixel verglichen, %.1f %% geändert, Ø %.1f je Kanal", r[0], r[1] * 100.0 / r[0],
				r[2] / (3.0 * r[0]));
	}
}

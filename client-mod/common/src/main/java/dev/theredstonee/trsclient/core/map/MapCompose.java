package dev.theredstonee.trsclient.core.map;

/**
 * Setzt einen Bereich zum fertigen Bild zusammen (Relief mit den Nachbarhöhen – auch über Bereichsgrenzen, wenn
 * der Nachbar geladen ist) und rechnet die Übersicht (Mittel über 4×4 Blöcke).
 */
public final class MapCompose implements MapLayer.SummaryMaker {
	public static final MapCompose INSTANCE = new MapCompose();

	private final int[] buffer = new int[MapRegion.AREA];

	private MapCompose() {
	}

	/** Schattiertes ARGB-Bild (128×128) in {@code out}. */
	public static void compose(MapLayer layer, MapRegion r, int[] out) {
		int size = MapRegion.SIZE;
		MapRegion north = layer == null ? null : layer.peek(r.rx, r.rz - 1);
		MapRegion west = layer == null ? null : layer.peek(r.rx - 1, r.rz);
		int cave = layer == null ? Integer.MIN_VALUE : layer.caveRef;
		int[] px = r.pixels;
		short[] h = r.heights;
		for (int z = 0; z < size; z++) {
			int row = z * size;
			for (int x = 0; x < size; x++) {
				int i = row + x;
				int p = px[i];
				if ((p & MapColors.KNOWN) == 0) {
					out[i] = 0;
					continue;
				}
				int height = h[i];
				int hn;
				if (z > 0) {
					hn = (px[i - size] & MapColors.KNOWN) != 0 ? h[i - size] : height;
				} else if (north != null && (north.pixels[(size - 1) * size + x] & MapColors.KNOWN) != 0) {
					hn = north.heights[(size - 1) * size + x];
				} else {
					hn = height;
				}
				int hw;
				if (x > 0) {
					hw = (px[i - 1] & MapColors.KNOWN) != 0 ? h[i - 1] : height;
				} else if (west != null && (west.pixels[row + size - 1] & MapColors.KNOWN) != 0) {
					hw = west.heights[row + size - 1];
				} else {
					hw = height;
				}
				out[i] = MapColors.compose(p, height, hn, hw, cave);
			}
		}
	}

	/** Übersicht 32×32 aus einem fertigen 128×128-Bild. */
	public static int[] summaryOf(int[] argb) {
		int n = MapRegion.SUMMARY;
		int step = MapRegion.SIZE / n;
		int[] out = new int[n * n];
		for (int sz = 0; sz < n; sz++) {
			for (int sx = 0; sx < n; sx++) {
				out[sz * n + sx] = MapColors.average(argb, sz * step * MapRegion.SIZE + sx * step, MapRegion.SIZE, step, step);
			}
		}
		return out;
	}

	@Override
	public int[] summary(MapLayer layer, MapRegion region) {
		compose(layer, region, buffer);
		return summaryOf(buffer);
	}
}

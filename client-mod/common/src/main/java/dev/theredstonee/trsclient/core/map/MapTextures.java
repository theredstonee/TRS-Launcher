package dev.theredstonee.trsclient.core.map;

import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Grafikkarten-Texturen der Karte: je Bereich eine 128×128-Textur (ein Texel je Block), für die herausgezoomte
 * Weltkarte Kacheln aus 8×8 Übersichten (256×256, ein Texel je 4 Blöcke). Neu hochgeladen wird nur, was sich
 * geändert hat – mit Budget je Bild und Mindestabstand je Textur, damit nichts ruckelt. Nur Render-Thread.
 */
public final class MapTextures {
	/** Bereiche je Kachel-Kante. */
	public static final int SUPER = 8;
	public static final int SUPER_PIXELS = SUPER * MapRegion.SUMMARY;

	private static final class Tex {
		TextureRef ref;
		int version = Integer.MIN_VALUE;
		long uploaded;
		long lastUsed;
	}

	private final Map<String, Tex> regions = new HashMap<String, Tex>();
	private final Map<String, Tex> supers = new HashMap<String, Tex>();
	private final int[] buffer = new int[MapRegion.AREA];
	private final int[] superBuffer = new int[SUPER_PIXELS * SUPER_PIXELS];
	private int uploadsLeft;
	private int summariesLeft;
	private int maxRegionTextures = 192;
	/** Statistik: Hochladungen insgesamt. */
	private long uploads;

	/** Übersichten nur mit Budget neu rechnen (jede kostet ein Zusammensetzen). */
	private final MapLayer.SummaryMaker budgeted = (layer, region) -> {
		if (summariesLeft <= 0) return null;
		summariesLeft--;
		return MapCompose.INSTANCE.summary(layer, region);
	};

	/** Zu Beginn jedes Bilds: wie viele Texturen dürfen hochgeladen werden? */
	public void beginFrame(int maxUploads) {
		uploadsLeft = maxUploads;
		summariesLeft = 6;
	}

	public void setMaxRegionTextures(int max) {
		maxRegionTextures = Math.max(32, max);
	}

	public long uploads() {
		return uploads;
	}

	public int regionTextureCount() {
		return regions.size();
	}

	/**
	 * Textur eines Bereichs (null = noch nichts zu zeigen). Veraltete Texturen werden erneuert, sobald Budget da ist
	 * und {@code minIntervalMs} seit dem letzten Hochladen vergangen sind; bis dahin bleibt die alte sichtbar.
	 */
	public TextureRef region(MapLayer layer, MapRegion r, long now, long minIntervalMs) {
		String key = regionKey(layer, r.rx, r.rz);
		Tex t = regions.get(key);
		boolean stale = t == null || t.version != r.version;
		if (stale && uploadsLeft > 0 && (t == null || t.ref == null || now - t.uploaded >= minIntervalMs)
				&& Textures.store() != null) {
			MapCompose.compose(layer, r, buffer);
			r.summary = MapCompose.summaryOf(buffer);
			r.summaryVersion = r.version;
			layer.summaryChanged();
			TextureRef ref = Textures.store().upload(key, MapRegion.SIZE, MapRegion.SIZE, buffer);
			uploadsLeft--;
			uploads++;
			if (ref != null) {
				if (t == null) {
					t = new Tex();
					regions.put(key, t);
				}
				t.ref = ref;
				t.version = r.version;
				t.uploaded = now;
			}
		}
		if (t == null) return null;
		t.lastUsed = now;
		return t.ref;
	}

	/** Kachel aus 8×8 Übersichten (null = noch nichts). Neu gebaut höchstens einmal je Sekunde. */
	public TextureRef superTile(MapLayer layer, int sx, int sz, long now) {
		String key = "map/s" + layer.index + "/" + sx + "_" + sz;
		Tex t = supers.get(key);
		boolean stale = t == null || t.version != layer.generation();
		if (stale && uploadsLeft > 0 && (t == null || now - t.uploaded >= 1000) && Textures.store() != null) {
			boolean any = false;
			int n = MapRegion.SUMMARY;
			java.util.Arrays.fill(superBuffer, 0);
			for (int rz = 0; rz < SUPER; rz++) {
				for (int rx = 0; rx < SUPER; rx++) {
					int[] s = layer.summary(sx * SUPER + rx, sz * SUPER + rz, budgeted);
					if (s == null) continue;
					any = true;
					for (int y = 0; y < n; y++) {
						System.arraycopy(s, y * n, superBuffer, (rz * n + y) * SUPER_PIXELS + rx * n, n);
					}
				}
			}
			if (any || t != null) {
				TextureRef ref = Textures.store().upload(key, SUPER_PIXELS, SUPER_PIXELS, superBuffer);
				uploadsLeft--;
				uploads++;
				if (ref != null) {
					if (t == null) {
						t = new Tex();
						supers.put(key, t);
					}
					t.ref = ref;
					t.version = layer.generation();
					t.uploaded = now;
				}
			}
		}
		if (t == null) return null;
		t.lastUsed = now;
		return t.ref;
	}

	/** Lange nicht benutzte Texturen freigeben (Grafikspeicher begrenzen). */
	public void trim(long now) {
		release(regions, now, 15_000, maxRegionTextures);
		release(supers, now, 20_000, 64);
	}

	private static void release(Map<String, Tex> map, long now, long idleMs, int max) {
		if (Textures.store() == null) return;
		Iterator<Map.Entry<String, Tex>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			Tex t = it.next().getValue();
			if (now - t.lastUsed > idleMs) {
				if (t.ref != null) Textures.store().release(t.ref);
				it.remove();
			}
		}
		if (map.size() <= max) return;
		List<Map.Entry<String, Tex>> all = new ArrayList<Map.Entry<String, Tex>>(map.entrySet());
		all.sort((a, b) -> Long.compare(a.getValue().lastUsed, b.getValue().lastUsed));
		for (int i = 0; i < all.size() - max; i++) {
			Tex t = all.get(i).getValue();
			if (t.ref != null) Textures.store().release(t.ref);
			map.remove(all.get(i).getKey());
		}
	}

	/** Texturen einer Ebene freigeben (Ebene geschlossen). */
	public void releaseLayer(MapLayer layer) {
		String prefixR = "map/r" + layer.index + "/";
		String prefixS = "map/s" + layer.index + "/";
		releasePrefix(regions, prefixR);
		releasePrefix(supers, prefixS);
	}

	private static void releasePrefix(Map<String, Tex> map, String prefix) {
		Iterator<Map.Entry<String, Tex>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Tex> e = it.next();
			if (!e.getKey().startsWith(prefix)) continue;
			if (e.getValue().ref != null && Textures.store() != null) Textures.store().release(e.getValue().ref);
			it.remove();
		}
	}

	public void clear() {
		releasePrefix(regions, "");
		releasePrefix(supers, "");
	}

	static String regionKey(MapLayer layer, int rx, int rz) {
		return "map/r" + layer.index + "/" + rx + "_" + rz;
	}
}

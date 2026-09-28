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
	/** Bereiche je Kante einer Weitkachel (zweite Stufe, ein Texel je 32 Blöcke). */
	public static final int MEGA = 64;
	/** Texel je Bereich in einer Weitkachel. */
	static final int MEGA_CELL = SUPER_PIXELS / MEGA;
	/** Blöcke je Texel einer Übersichtskachel bzw. Weitkachel. */
	public static final int SUPER_BLOCKS_PER_TEXEL = MapRegion.SIZE / MapRegion.SUMMARY;
	public static final int MEGA_BLOCKS_PER_TEXEL = MapRegion.SIZE / MEGA_CELL;

	private static final class Tex {
		TextureRef ref;
		int version = Integer.MIN_VALUE;
		long stamp = Long.MIN_VALUE;
		long uploaded;
		long lastUsed;
	}

	private final Map<String, Tex> regions = new HashMap<String, Tex>();
	private final Map<String, Tex> supers = new HashMap<String, Tex>();
	private final Map<String, Tex> megas = new HashMap<String, Tex>();
	private final int[] buffer = new int[MapRegion.AREA];
	private final int[] superBuffer = new int[SUPER_PIXELS * SUPER_PIXELS];
	private int uploadsLeft;
	private int summariesLeft;
	/** Zeitgrenze des Bilds fürs Hochladen (System.nanoTime), 0 = keine. */
	private long uploadDeadline;
	private int uploadsThisFrame;
	private int maxRegionTextures = 192;
	private int maxSuperTextures = 160;
	/** Statistik: Hochladungen insgesamt. */
	private long uploads;

	/** Wie oft eine Übersicht wegen des Budgets verschoben wurde (Kachel dann später noch einmal bauen). */
	private int summaryDenied;

	/** Übersichten nur mit Budget neu rechnen (jede kostet ein Zusammensetzen). */
	private final MapLayer.SummaryMaker budgeted = (layer, region) -> {
		if (summariesLeft <= 0 || (uploadDeadline != 0 && System.nanoTime() > uploadDeadline)) {
			summaryDenied++;
			return null;
		}
		summariesLeft--;
		return MapCompose.INSTANCE.summary(layer, region);
	};

	/** Zu Beginn jedes Bilds: wie viele Texturen dürfen hochgeladen werden? */
	public void beginFrame(int maxUploads) {
		uploadsLeft = maxUploads;
		summariesLeft = 6;
		uploadDeadline = 0;
		uploadsThisFrame = 0;
	}

	/**
	 * Wie {@link #beginFrame(int)}, zusätzlich mit Zeitbudget: nach {@code budgetNanos} wird in diesem Bild nichts
	 * mehr hochgeladen (mindestens eine Textur geht immer, damit es vorangeht). Für die Weltkarte beim schnellen
	 * Schieben/Zoomen: viel Budget, aber nie ein spürbarer Hänger.
	 */
	public void beginFrame(int maxUploads, long budgetNanos) {
		beginFrame(maxUploads);
		summariesLeft = 8;
		uploadDeadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : 0;
	}

	/** Darf in diesem Bild noch hochgeladen werden? */
	private boolean canUpload() {
		if (uploadsLeft <= 0) return false;
		return uploadDeadline == 0 || uploadsThisFrame == 0 || System.nanoTime() < uploadDeadline;
	}

	private void uploaded() {
		uploadsLeft--;
		uploadsThisFrame++;
		uploads++;
	}

	public void setMaxRegionTextures(int max) {
		maxRegionTextures = Math.max(32, max);
	}

	/** Höchstzahl gleichzeitig gehaltener Übersichtskacheln (mindestens so viele, wie auf einmal sichtbar sind). */
	public void setMaxSuperTextures(int max) {
		maxSuperTextures = Math.max(64, max);
	}

	/** Hat dieser Bereich schon eine aktuelle Textur (ohne etwas hochzuladen)? */
	public boolean regionReady(MapLayer layer, MapRegion r) {
		Tex t = regions.get(regionKey(layer, r.rx, r.rz));
		return t != null && t.ref != null;
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
		if (stale && canUpload() && (t == null || t.ref == null || now - t.uploaded >= minIntervalMs)
				&& Textures.store() != null) {
			MapCompose.compose(layer, r, buffer);
			r.summary = MapCompose.summaryOf(buffer);
			r.summaryVersion = r.version;
			layer.summaryChanged(r.rx, r.rz);
			TextureRef ref = Textures.store().upload(key, MapRegion.SIZE, MapRegion.SIZE, buffer);
			uploaded();
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

	/**
	 * Kachel aus 8×8 Übersichten (null = noch nichts). Neu gebaut nur, wenn sich darin etwas geändert hat
	 * ({@link MapLayer#tileStamp}), und höchstens alle 400 ms.
	 */
	public TextureRef superTile(MapLayer layer, int sx, int sz, long now) {
		String key = "map/s" + layer.index + "/" + sx + "_" + sz;
		Tex t = supers.get(key);
		long stamp = layer.tileStamp(1, sx, sz);
		boolean stale = t == null || t.stamp != stamp;
		if (stale && canUpload() && (t == null || now - t.uploaded >= 400) && Textures.store() != null) {
			boolean any = false;
			int deniedBefore = summaryDenied;
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
				uploaded();
				if (ref != null) {
					if (t == null) {
						t = new Tex();
						supers.put(key, t);
					}
					t.ref = ref;
					t.version = layer.generation();
					// Fehlten Übersichten wegen des Budgets: veraltet lassen, dann wird die Kachel bald vervollständigt.
					t.stamp = summaryDenied == deniedBefore ? stamp : Long.MIN_VALUE;
					t.uploaded = now;
				}
			} else {
				// Noch keine Übersicht da (lädt): Platzhalter, damit nicht jedes Bild neu gesucht wird.
				t = new Tex();
				t.stamp = Long.MIN_VALUE;
				t.uploaded = now;
				supers.put(key, t);
			}
		}
		if (t == null) return null;
		t.lastUsed = now;
		return t.ref;
	}

	/**
	 * Weitkachel aus {@value #MEGA}×{@value #MEGA} Bereichen (256×256, ein Texel je 32 Blöcke) für die ganz weit
	 * herausgezoomte Weltkarte – aus denselben Übersichten gebaut, je Bereich auf 4×4 gemittelt. null = noch nichts.
	 */
	public TextureRef megaTile(MapLayer layer, int mx, int mz, long now) {
		String key = "map/m" + layer.index + "/" + mx + "_" + mz;
		Tex t = megas.get(key);
		long stamp = layer.tileStamp(2, mx, mz);
		boolean stale = t == null || t.stamp != stamp;
		if (stale && canUpload() && (t == null || now - t.uploaded >= 700) && Textures.store() != null) {
			boolean any = false;
			int deniedBefore = summaryDenied;
			java.util.Arrays.fill(superBuffer, 0);
			int bx = mx * MEGA, bz = mz * MEGA;
			for (int rz = 0; rz < MEGA; rz++) {
				for (int rx = 0; rx < MEGA; rx++) {
					if (!layer.known(MapRegion.key(bx + rx, bz + rz))) continue;
					int[] s = layer.summary(bx + rx, bz + rz, budgeted);
					if (s == null) continue;
					any = true;
					shrink(s, MapRegion.SUMMARY, MapRegion.SUMMARY / MEGA_CELL, superBuffer, (rz * MEGA_CELL) * SUPER_PIXELS + rx * MEGA_CELL,
							SUPER_PIXELS);
				}
			}
			if (any || t != null) {
				TextureRef ref = Textures.store().upload(key, SUPER_PIXELS, SUPER_PIXELS, superBuffer);
				uploaded();
				if (ref != null) {
					if (t == null) {
						t = new Tex();
						megas.put(key, t);
					}
					t.ref = ref;
					// Fehlten Übersichten wegen des Budgets: veraltet lassen, dann wird die Kachel bald vervollständigt.
					t.stamp = summaryDenied == deniedBefore ? stamp : Long.MIN_VALUE;
					t.uploaded = now;
				}
			} else {
				// Noch keine Übersicht da (lädt): Platzhalter, damit nicht jedes Bild neu gesucht wird.
				t = new Tex();
				t.stamp = Long.MIN_VALUE;
				t.uploaded = now;
				megas.put(key, t);
			}
		}
		if (t == null) return null;
		t.lastUsed = now;
		return t.ref;
	}

	/**
	 * Verkleinert ein quadratisches ARGB-Bild ({@code size}²) um den Faktor {@code f} (Mittel je f×f, durchsichtige
	 * Pixel zählen nur für die Deckkraft) nach {@code dst} ab {@code off} mit Zeilenlänge {@code stride}.
	 */
	static void shrink(int[] src, int size, int f, int[] dst, int off, int stride) {
		int n = size / f;
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				dst[off + y * stride + x] = MapExport.average(src, size, x * f, y * f, f, f);
			}
		}
	}

	/** Lange nicht benutzte Texturen freigeben (Grafikspeicher begrenzen). */
	public void trim(long now) {
		release(regions, now, 15_000, maxRegionTextures);
		release(supers, now, 20_000, maxSuperTextures);
		release(megas, now, 30_000, 48);
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
		releasePrefix(megas, "map/m" + layer.index + "/");
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
		releasePrefix(megas, "");
	}

	static String regionKey(MapLayer layer, int rx, int rz) {
		return "map/r" + layer.index + "/" + rx + "_" + rz;
	}
}

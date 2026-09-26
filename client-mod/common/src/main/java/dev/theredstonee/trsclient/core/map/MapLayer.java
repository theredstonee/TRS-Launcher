package dev.theredstonee.trsclient.core.map;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eine Kartenebene einer Welt/Dimension: die Oberfläche, ein Höhlenschnitt auf einer bestimmten Höhe oder die
 * Oberfläche unter einem Dach-Schnitt (Innenansicht, {@code roof<höhe>}).
 * Hält die Bereiche im Speicher (begrenzt, die ältesten fliegen zuerst – vorher gespeichert), lädt gespeicherte
 * Bereiche im Hintergrund nach und kennt die Übersichten aller Bereiche auf der Platte (für die herausgezoomte
 * Weltkarte). Nur Spiel-Thread.
 */
public final class MapLayer {
	/** Schattiertes Bild und Übersicht eines Bereichs berechnen (siehe {@link MapCompose}). */
	public interface SummaryMaker {
		int[] summary(MapLayer layer, MapRegion region);
	}

	public final String id;
	/** Laufende Nummer (Texturnamen). */
	public final int index;
	/** Bezugshöhe der Höhlenansicht oder {@link Integer#MIN_VALUE} (Oberfläche). */
	public final int caveRef;
	/** Dach-Schnitt: höchste abgetastete Höhe oder {@link ColumnScanner#NO_CUT} (kein Schnitt). */
	public final int roofCut;
	private final MapDisk disk;
	private final Path dir;
	private final Map<Long, MapRegion> regions = new HashMap<Long, MapRegion>();
	private final Map<Long, int[]> diskSummaries = new HashMap<Long, int[]>();
	private final Set<Long> onDisk = new HashSet<Long>();
	private final Set<Long> loading = new HashSet<Long>();
	private final Set<Long> summaryLoading = new HashSet<Long>();
	private boolean listed;
	private boolean closed;
	/** Ändert sich, sobald sich irgendeine Übersicht ändert (Weltkarten-Kacheln neu bauen). */
	private int generation;
	private int maxRegions = 256;

	/**
	 * @param dir Ordner auf der Platte oder null (nur im Speicher)
	 */
	public MapLayer(String id, int index, int caveRef, MapDisk disk, Path dir) {
		this(id, index, caveRef, ColumnScanner.NO_CUT, disk, dir);
	}

	/**
	 * @param roofCut Dach-Schnitt (Innenansicht) oder {@link ColumnScanner#NO_CUT}
	 * @param dir Ordner auf der Platte oder null (nur im Speicher)
	 */
	public MapLayer(String id, int index, int caveRef, int roofCut, MapDisk disk, Path dir) {
		this.id = id;
		this.index = index;
		this.caveRef = caveRef;
		this.roofCut = roofCut;
		this.disk = disk;
		this.dir = dir;
		if (disk != null && dir != null) {
			disk.listRegions(dir, keys -> {
				if (closed) return;
				for (long k : keys) onDisk.add(k);
				listed = true;
				generation++;
			});
		} else {
			listed = true;
		}
	}

	public boolean cave() {
		return caveRef != Integer.MIN_VALUE;
	}

	/** Innenansicht unter einem Dach-Schnitt? */
	public boolean roof() {
		return roofCut != ColumnScanner.NO_CUT;
	}

	public Path dir() {
		return dir;
	}

	public boolean listed() {
		return listed;
	}

	public int generation() {
		return generation;
	}

	public void setMaxRegions(int max) {
		maxRegions = Math.max(16, max);
	}

	public int loadedCount() {
		return regions.size();
	}

	public Collection<MapRegion> loaded() {
		return regions.values();
	}

	/** Alle bekannten Bereiche (geladen oder auf der Platte). */
	public Set<Long> knownKeys() {
		Set<Long> all = new HashSet<Long>(onDisk);
		all.addAll(regions.keySet());
		return all;
	}

	/** Geladener Bereich ohne Nachladen (Nachbarn beim Schattieren). */
	public MapRegion peek(int rx, int rz) {
		return regions.get(MapRegion.key(rx, rz));
	}

	/** Bereich zum Anzeigen: geladen oder null (dann wird er – falls gespeichert – im Hintergrund geladen). */
	public MapRegion get(int rx, int rz, long now) {
		long key = MapRegion.key(rx, rz);
		MapRegion r = regions.get(key);
		if (r != null) {
			r.lastUsed = now;
			return r;
		}
		if (onDisk.contains(key)) requestLoad(rx, rz, key);
		return null;
	}

	/** Bereich zum Beschreiben (neu angelegt, falls nötig; gespeicherte Daten werden später dazugemischt). */
	public MapRegion forWrite(int rx, int rz, long now) {
		long key = MapRegion.key(rx, rz);
		MapRegion r = regions.get(key);
		if (r == null) {
			r = new MapRegion(rx, rz);
			regions.put(key, r);
			if (onDisk.contains(key)) requestLoad(rx, rz, key);
		}
		r.lastUsed = now;
		return r;
	}

	/** Liegt dieser Bereich (noch) auf der Platte und wird gerade geladen? */
	public boolean isLoading(int rx, int rz) {
		return loading.contains(MapRegion.key(rx, rz));
	}

	/**
	 * Übersicht (32×32 ARGB) eines Bereichs: aus dem Speicher, sonst von der Platte (im Hintergrund).
	 * null = (noch) nicht verfügbar.
	 */
	public int[] summary(int rx, int rz, SummaryMaker maker) {
		long key = MapRegion.key(rx, rz);
		MapRegion r = regions.get(key);
		if (r != null) {
			if (r.summaryVersion != r.version && maker != null) {
				int[] s = maker.summary(this, r);
				if (s != null) {
					r.summary = s;
					r.summaryVersion = r.version;
				}
			}
			if (r.summary != null) return r.summary;
		}
		int[] s = diskSummaries.get(key);
		if (s != null) return s;
		if (onDisk.contains(key) && disk != null && dir != null && !summaryLoading.contains(key)) {
			summaryLoading.add(key);
			disk.loadSummary(MapDisk.regionFile(dir, rx, rz), summary -> {
				summaryLoading.remove(key);
				if (closed || summary == null) return;
				diskSummaries.put(key, summary);
				generation++;
			});
		}
		return null;
	}

	/** Eine Übersicht hat sich geändert (Textur neu schattiert). */
	public void summaryChanged() {
		generation++;
	}

	private void requestLoad(int rx, int rz, final long key) {
		if (disk == null || dir == null || loading.contains(key) || closed) return;
		loading.add(key);
		disk.loadRegion(MapDisk.regionFile(dir, rx, rz), decoded -> {
			loading.remove(key);
			if (closed) return;
			if (decoded == null) {
				onDisk.remove(key);
				return;
			}
			long now = System.currentTimeMillis();
			MapRegion r = regions.get(key);
			if (r == null) {
				r = new MapRegion(decoded.rx, decoded.rz);
				System.arraycopy(decoded.pixels, 0, r.pixels, 0, MapRegion.AREA);
				System.arraycopy(decoded.heights, 0, r.heights, 0, MapRegion.AREA);
				r.version = 1;
				r.savedVersion = 1;
				r.lastUsed = now;
				if (decoded.summary != null) {
					r.summary = decoded.summary;
					r.summaryVersion = r.version;
				}
				regions.put(key, r);
			} else if (r.mergeFrom(decoded.pixels, decoded.heights, now)) {
				if (r.dirtySince == 0) r.dirtySince = now;
			}
			diskSummaries.remove(key);
			generation++;
		});
	}

	/**
	 * Regelmäßig (etwa jede Sekunde): Bereiche speichern, die seit {@code saveAfterMs} ungespeichert sind, und
	 * die ältesten entladen, wenn mehr als erlaubt im Speicher liegen.
	 */
	public void maintain(long now, long saveAfterMs, SummaryMaker maker, int maxSaves) {
		int saves = 0;
		if (dir != null) {
			for (MapRegion r : regions.values()) {
				if (saves >= maxSaves) break;
				if (r.dirty() && r.dirtySince != 0 && now - r.dirtySince >= saveAfterMs) {
					save(r, maker);
					saves++;
				}
			}
		}
		if (regions.size() <= maxRegions) return;
		List<MapRegion> all = new ArrayList<MapRegion>(regions.values());
		all.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
		int remove = regions.size() - maxRegions;
		for (int i = 0; i < all.size() && remove > 0; i++) {
			MapRegion r = all.get(i);
			if (loading.contains(r.key())) continue;
			if (r.dirty() && dir != null) save(r, maker);
			if (r.summary != null && dir != null) diskSummaries.put(r.key(), r.summary);
			regions.remove(r.key());
			remove--;
		}
	}

	private void save(MapRegion r, SummaryMaker maker) {
		if (disk == null || dir == null) return;
		if (!r.anyKnown()) {
			r.savedVersion = r.version;
			r.dirtySince = 0;
			return;
		}
		if (r.summaryVersion != r.version && maker != null) {
			int[] s = maker.summary(this, r);
			if (s != null) {
				r.summary = s;
				r.summaryVersion = r.version;
			}
		}
		disk.save(MapDisk.regionFile(dir, r.rx, r.rz), r.rx, r.rz, r.pixels.clone(), r.heights.clone(),
				r.summary == null ? null : r.summary.clone());
		r.savedVersion = r.version;
		r.dirtySince = 0;
		onDisk.add(r.key());
	}

	/** Alles Ungespeicherte speichern (Weltwechsel/Beenden). */
	public void saveAll(SummaryMaker maker) {
		for (MapRegion r : regions.values()) {
			if (r.dirty()) save(r, maker);
		}
	}

	/** Ebene schließen: speichern und vergessen. */
	public void close(SummaryMaker maker) {
		saveAll(maker);
		closed = true;
		regions.clear();
		diskSummaries.clear();
	}

	public boolean closed() {
		return closed;
	}
}

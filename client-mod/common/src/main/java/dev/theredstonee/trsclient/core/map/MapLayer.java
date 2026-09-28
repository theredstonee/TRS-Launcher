package dev.theredstonee.trsclient.core.map;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
	/** Stand für alle Kacheln (Auflistung fertig, unbekannte Änderung). */
	private int globalStamp;
	/** Stand je Übersichtskachel: Stufe 1 = {@link MapTextures#SUPER}² Bereiche, Stufe 2 = {@link MapTextures#MEGA}² Bereiche. */
	private final Map<Long, Integer> tileStamps1 = new HashMap<Long, Integer>();
	private final Map<Long, Integer> tileStamps2 = new HashMap<Long, Integer>();
	/**
	 * Wann ein wartender Ladeauftrag zuletzt im Bild gebraucht wurde (ms) – vom Kartenthread gelesen, damit Aufträge
	 * für längst weggeschobene Bereiche nicht mehr gelesen werden.
	 */
	private final ConcurrentHashMap<Long, Long> wantedAt = new ConcurrentHashMap<Long, Long>();
	/** So lange (ms) gilt ein Ladeauftrag ohne erneute Anfrage noch als gebraucht. */
	static final long WANTED_MS = 1500;

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
				touchAllTiles();
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

	/** Gibt es diesen Bereich (geladen oder auf der Platte)? Ohne Kopie – für jedes Bild. */
	public boolean known(long key) {
		return regions.containsKey(key) || onDisk.contains(key);
	}

	/**
	 * Stand einer Übersichtskachel ({@code level} 1 oder 2) – ändert sich, sobald sich eine Übersicht darin ändert
	 * (Bereich geladen, Übersicht gelesen, Bereich neu abgetastet) oder die ganze Ebene (Auflistung fertig).
	 */
	public long tileStamp(int level, int tx, int tz) {
		Integer n = (level == 2 ? tileStamps2 : tileStamps1).get(MapRegion.key(tx, tz));
		return ((long) globalStamp << 32) | ((n == null ? 0 : n) & 0xFFFFFFFFL);
	}

	private void touchTiles(int rx, int rz) {
		long k1 = MapRegion.key(Math.floorDiv(rx, MapTextures.SUPER), Math.floorDiv(rz, MapTextures.SUPER));
		long k2 = MapRegion.key(Math.floorDiv(rx, MapTextures.MEGA), Math.floorDiv(rz, MapTextures.MEGA));
		Integer a = tileStamps1.get(k1);
		tileStamps1.put(k1, a == null ? 1 : a + 1);
		Integer b = tileStamps2.get(k2);
		tileStamps2.put(k2, b == null ? 1 : b + 1);
	}

	private void touchAllTiles() {
		globalStamp++;
		tileStamps1.clear();
		tileStamps2.clear();
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
		if (onDisk.contains(key)) {
			if (loading.contains(key)) wantedAt.put(key, now);
			else requestLoad(rx, rz, key, true);
		}
		return null;
	}

	/** Bereich zum Beschreiben (neu angelegt, falls nötig; gespeicherte Daten werden später dazugemischt). */
	public MapRegion forWrite(int rx, int rz, long now) {
		long key = MapRegion.key(rx, rz);
		MapRegion r = regions.get(key);
		if (r == null) {
			r = new MapRegion(rx, rz);
			regions.put(key, r);
			if (onDisk.contains(key)) requestLoad(rx, rz, key, false);
		}
		r.lastUsed = now;
		// Neu abgetastet: die Übersichtskacheln darüber sind veraltet.
		touchTiles(rx, rz);
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
		if (onDisk.contains(key) && disk != null && dir != null) {
			// Eigener Schlüsselraum für Übersichten (bitweise invertiert), damit sie Bereichs-Ladeaufträge nicht stören.
			final long summaryKey = ~key;
			wantedAt.put(summaryKey, System.currentTimeMillis());
			if (summaryLoading.contains(key)) return null;
			summaryLoading.add(key);
			final int trx = rx, trz = rz;
			disk.loadSummary(MapDisk.regionFile(dir, rx, rz), () -> stillWanted(summaryKey), summary -> {
				summaryLoading.remove(key);
				wantedAt.remove(summaryKey);
				if (closed || summary == null) return;
				diskSummaries.put(key, summary);
				generation++;
				touchTiles(trx, trz);
			}, () -> {
				// Nicht mehr im Bild: Kachel als veraltet markieren – sobald sie wieder sichtbar ist, fragt sie neu an.
				summaryLoading.remove(key);
				wantedAt.remove(summaryKey);
				touchTiles(trx, trz);
			});
		}
		return null;
	}

	/** Eine Übersicht hat sich geändert (Textur neu schattiert). */
	public void summaryChanged() {
		generation++;
		touchAllTiles();
	}

	/** Die Übersicht dieses Bereichs hat sich geändert (Textur neu schattiert) – nur seine Kacheln neu bauen. */
	public void summaryChanged(int rx, int rz) {
		generation++;
		touchTiles(rx, rz);
	}

	private boolean stillWanted(long key) {
		Long t = wantedAt.get(key);
		return t != null && System.currentTimeMillis() - t <= WANTED_MS;
	}

	/**
	 * @param onlyIfWanted true = Anzeige (fällt weg, wenn der Bereich eine Weile nicht mehr gebraucht wurde); false =
	 *                     Zusammenführen mit neu Abgetastetem (muss immer geladen werden)
	 */
	private void requestLoad(final int rx, final int rz, final long key, boolean onlyIfWanted) {
		if (disk == null || dir == null || loading.contains(key) || closed) return;
		loading.add(key);
		if (onlyIfWanted) wantedAt.put(key, System.currentTimeMillis());
		else wantedAt.remove(key);
		disk.loadRegion(MapDisk.regionFile(dir, rx, rz), onlyIfWanted ? () -> stillWanted(key) : null, decoded -> {
			loading.remove(key);
			wantedAt.remove(key);
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
			touchTiles(rx, rz);
		}, () -> {
			// Übersprungen (aus dem Bild geschoben): beim nächsten Anzeigen neu anfragen.
			loading.remove(key);
			wantedAt.remove(key);
			// Wurde der Bereich inzwischen beschrieben, muss er doch geladen werden (Zusammenführen).
			if (regions.containsKey(key) && !closed) requestLoad(rx, rz, key, false);
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
		wantedAt.clear();
	}

	public boolean closed() {
		return closed;
	}
}

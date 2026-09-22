package dev.theredstonee.trsclient.core.minimap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zwischenspeicher der Minimap: je Chunk 16×16 Farben (Kartenfarbe des obersten Blocks)
 * und Höhen. Chunks werden nur häppchenweise (Budget pro Tick) neu gelesen, damit das
 * Zeichnen der Karte selbst nichts kostet – gezeichnet wird nur aus diesem Speicher.
 */
public final class MinimapCache {
	/** Liefert die Daten eines Chunks. */
	public interface Source {
		/** Ist der Chunk geladen? (Nur geladene Chunks werden gelesen – keine Server-Anfragen.) */
		boolean isLoaded(int chunkX, int chunkZ);

		/**
		 * Füllt {@code colors} (0xRRGGBB, 0 = unbekannt) und {@code heights} (je 256 Werte,
		 * Index = z * 16 + x).
		 *
		 * @return false, wenn der Chunk nicht gelesen werden konnte
		 */
		boolean fill(int chunkX, int chunkZ, int[] colors, int[] heights);
	}

	/** Ein gespeicherter Chunk. */
	public static final class Entry {
		public final int[] colors = new int[256];
		public final int[] heights = new int[256];
		public long updated;
	}

	/** Mehr Chunks als das werden (am weitesten weg) vergessen. */
	public static final int MAX_CHUNKS = 1024;

	private final Map<Long, Entry> chunks = new HashMap<>();
	private final int[] tmpColors = new int[256];
	private final int[] tmpHeights = new int[256];
	private int lastUpdates;

	private static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}

	/**
	 * Liest fehlende und veraltete Chunks rund um die Kamera nach – höchstens {@code budget} Stück.
	 * Fehlende zuerst (von innen nach außen), danach die ältesten.
	 *
	 * @return Anzahl neu gelesener Chunks
	 */
	public int update(Source source, int centerChunkX, int centerChunkZ, int radius, long now, long maxAgeMs, int budget) {
		int done = 0;
		Entry oldest = null;
		int oldestX = 0;
		int oldestZ = 0;
		for (int ring = 0; ring <= radius && done < budget; ring++) {
			for (int dz = -ring; dz <= ring && done < budget; dz++) {
				for (int dx = -ring; dx <= ring && done < budget; dx++) {
					// Nur der Rand des Rings (innere Felder kamen schon dran).
					if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
					int cx = centerChunkX + dx;
					int cz = centerChunkZ + dz;
					Entry entry = chunks.get(key(cx, cz));
					if (entry == null) {
						if (read(source, cx, cz, now)) done++;
					} else if (maxAgeMs > 0 && now - entry.updated > maxAgeMs
							&& (oldest == null || entry.updated < oldest.updated)) {
						oldest = entry;
						oldestX = cx;
						oldestZ = cz;
					}
				}
			}
		}
		// Pro Aufruf höchstens einen veralteten Chunk auffrischen.
		if (done < budget && oldest != null && read(source, oldestX, oldestZ, now)) done++;
		trim(centerChunkX, centerChunkZ);
		lastUpdates = done;
		return done;
	}

	private boolean read(Source source, int cx, int cz, long now) {
		if (!source.isLoaded(cx, cz)) return false;
		if (!source.fill(cx, cz, tmpColors, tmpHeights)) return false;
		Entry entry = chunks.get(key(cx, cz));
		if (entry == null) {
			entry = new Entry();
			chunks.put(key(cx, cz), entry);
		}
		System.arraycopy(tmpColors, 0, entry.colors, 0, 256);
		System.arraycopy(tmpHeights, 0, entry.heights, 0, 256);
		entry.updated = now;
		return true;
	}

	/** Wirft die am weitesten entfernten Chunks weg, wenn es zu viele werden. */
	private void trim(int centerChunkX, int centerChunkZ) {
		if (chunks.size() <= MAX_CHUNKS) return;
		List<Long> keys = new ArrayList<>(chunks.keySet());
		int remove = chunks.size() - MAX_CHUNKS;
		for (int i = 0; i < remove; i++) {
			long worst = 0;
			long worstDist = -1;
			for (Long k : keys) {
				if (!chunks.containsKey(k)) continue;
				int cx = (int) (k >> 32);
				int cz = (int) (long) k;
				long dx = cx - centerChunkX;
				long dz = cz - centerChunkZ;
				long dist = dx * dx + dz * dz;
				if (dist > worstDist) {
					worstDist = dist;
					worst = k;
				}
			}
			chunks.remove(worst);
		}
	}

	/** Kartenfarbe an dieser Blockposition (0 = unbekannt). */
	public int color(int blockX, int blockZ) {
		Entry e = chunks.get(key(blockX >> 4, blockZ >> 4));
		return e == null ? 0 : e.colors[(blockZ & 15) * 16 + (blockX & 15)];
	}

	/** Höhe an dieser Blockposition (nur sinnvoll, wenn {@link #color} ≠ 0). */
	public int height(int blockX, int blockZ) {
		Entry e = chunks.get(key(blockX >> 4, blockZ >> 4));
		return e == null ? 0 : e.heights[(blockZ & 15) * 16 + (blockX & 15)];
	}

	public boolean hasChunk(int chunkX, int chunkZ) {
		return chunks.containsKey(key(chunkX, chunkZ));
	}

	public int size() {
		return chunks.size();
	}

	public int lastUpdates() {
		return lastUpdates;
	}

	/** Weltwechsel: alles vergessen. */
	public void clear() {
		chunks.clear();
	}
}

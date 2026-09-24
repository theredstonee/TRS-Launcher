package dev.theredstonee.trsclient.core.redstone;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Komparator-Ausgabe von Behältern, deren Inhalt der Client gesehen hat: Den Inhalt einer Truhe
 * kennt der Client nur, solange sie geöffnet ist. Beim Öffnen wird hier die Ausgabe gemerkt
 * (höchstens {@link #MAX} Behälter, der älteste fällt heraus). Nichts davon verlässt den Client.
 */
public final class ContainerMemory {
	public static final int MAX = 256;
	/** So lange (Ticks) gilt ein Wert als "gerade offen". */
	private static final long OPEN_TICKS = 3;

	private static final class Seen {
		int signal;
		long tick;
	}

	private final Map<Long, Seen> entries = new LinkedHashMap<Long, Seen>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Seen> eldest) {
			return size() > MAX;
		}
	};

	/** Merkt die Ausgabe des Behälters an (x, y, z) im Tick {@code tick}. */
	public void put(int x, int y, int z, int signal, long tick) {
		Long key = Long.valueOf(Dir.pack(x, y, z));
		Seen e = entries.get(key);
		if (e == null) {
			e = new Seen();
			entries.put(key, e);
		}
		e.signal = Math.max(0, Math.min(15, signal));
		e.tick = tick;
	}

	/** Gemerkte Ausgabe oder -1. */
	public int get(int x, int y, int z) {
		Seen e = entries.get(Long.valueOf(Dir.pack(x, y, z)));
		return e == null ? -1 : e.signal;
	}

	/** Ist der Behälter gerade geöffnet (Wert ist aktuell)? */
	public boolean current(int x, int y, int z, long now) {
		Seen e = entries.get(Long.valueOf(Dir.pack(x, y, z)));
		return e != null && now - e.tick <= OPEN_TICKS;
	}

	public int size() {
		return entries.size();
	}

	public void clear() {
		entries.clear();
	}
}

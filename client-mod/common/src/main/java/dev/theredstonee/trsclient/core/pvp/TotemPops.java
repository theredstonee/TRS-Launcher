package dev.theredstonee.trsclient.core.pvp;

import java.util.ArrayList;
import java.util.List;

/**
 * Totem-Pop-Zähler je Gegner (ab Minecraft 1.11): der Server meldet jedes ausgelöste Totem als Entity-Ereignis 35 an
 * alle Clients in der Nähe. Gezählt wird je Spielername; die Liste zeigt die zuletzt aktiven Gegner.
 */
public final class TotemPops {
	/** Nach so langer Zeit ohne neuen Pop verschwindet ein Gegner aus der Liste. */
	public static final long FORGET_MS = 90_000;
	public static final int MAX_SHOWN = 4;

	/** Ein Gegner mit seiner Pop-Zahl. */
	public static final class Entry {
		public final String name;
		public int pops;
		public long last;

		Entry(String name) {
			this.name = name;
		}
	}

	private final List<Entry> entries = new ArrayList<Entry>();

	/** Ein Totem wurde ausgelöst. {@code self} = der eigene Spieler (wird nicht gelistet). */
	public void onPop(String name, boolean self, long now) {
		if (self || name == null || name.isEmpty()) return;
		Entry e = find(name);
		if (e == null) {
			e = new Entry(name);
			entries.add(e);
		}
		e.pops++;
		e.last = now;
	}

	/** Gegner gestorben: aus der Liste nehmen (die Runde gegen ihn ist vorbei). */
	public void onDeath(String name) {
		Entry e = find(name);
		if (e != null) entries.remove(e);
	}

	/** Neueste zuerst, alte ausgeblendet; höchstens {@link #MAX_SHOWN}. */
	public List<Entry> recent(long now) {
		for (int i = entries.size() - 1; i >= 0; i--) {
			if (now - entries.get(i).last > FORGET_MS) entries.remove(i);
		}
		List<Entry> out = new ArrayList<Entry>(entries);
		java.util.Collections.sort(out, new java.util.Comparator<Entry>() {
			@Override
			public int compare(Entry a, Entry b) {
				return a.last == b.last ? 0 : (a.last > b.last ? -1 : 1);
			}
		});
		while (out.size() > MAX_SHOWN) out.remove(out.size() - 1);
		return out;
	}

	public int pops(String name) {
		Entry e = find(name);
		return e == null ? 0 : e.pops;
	}

	public void reset() {
		entries.clear();
	}

	private Entry find(String name) {
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).name.equalsIgnoreCase(name)) return entries.get(i);
		}
		return null;
	}
}

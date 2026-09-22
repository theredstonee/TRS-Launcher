package dev.theredstonee.trsclient.core.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Logik des Resourcepack-Menüs: Filtern/Suchen und Aktivieren/Deaktivieren mit korrekter
 * Reihenfolge. Die Reihenfolge der aktiven IDs entspricht Minecraft (letztes = höchste Priorität).
 */
public final class PackList {
	/** Anzeige-Filter. */
	public enum Filter {
		ALL("Alle"), ENABLED("Aktiv"), AVAILABLE("Verfügbar");

		public final String label;

		Filter(String label) {
			this.label = label;
		}

		public Filter next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	/** Ein Pack in der Liste (Titel/Beschreibung bereits als Klartext). */
	public record Entry(String id, String title, String description, boolean required, boolean compatible) {
	}

	private PackList() {
	}

	/**
	 * Sichtbare Einträge: aktive zuerst (höchste Priorität oben), danach verfügbare alphabetisch.
	 * Suche: jedes Wort der Anfrage muss in Titel, ID oder Beschreibung vorkommen (ohne Groß/klein).
	 */
	public static List<Entry> visible(List<Entry> all, List<String> enabledIds, Filter filter, String query) {
		String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		List<Entry> enabled = new ArrayList<>();
		List<Entry> available = new ArrayList<>();
		for (Entry e : all) {
			if (!matches(e, words)) continue;
			if (enabledIds.contains(e.id())) enabled.add(e);
			else available.add(e);
		}
		enabled.sort((a, b) -> Integer.compare(enabledIds.indexOf(b.id()), enabledIds.indexOf(a.id())));
		available.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));
		List<Entry> out = new ArrayList<>();
		if (filter != Filter.AVAILABLE) out.addAll(enabled);
		if (filter != Filter.ENABLED) out.addAll(available);
		return out;
	}

	private static boolean matches(Entry e, String[] words) {
		String hay = (e.title() + " " + e.id() + " " + e.description()).toLowerCase(Locale.ROOT);
		for (String w : words) {
			if (!w.isEmpty() && !hay.contains(w)) return false;
		}
		return true;
	}

	/**
	 * Schaltet ein Pack um und liefert die neue Liste aktiver IDs. Pflicht-Packs (z. B. "vanilla")
	 * bleiben aktiv; neu aktivierte Packs bekommen die höchste Priorität.
	 */
	public static List<String> toggle(List<String> enabledIds, Entry pack) {
		List<String> out = new ArrayList<>(enabledIds);
		if (out.contains(pack.id())) {
			if (!pack.required()) out.remove(pack.id());
		} else {
			out.add(pack.id());
		}
		return Collections.unmodifiableList(out);
	}

	/** Verschiebt ein aktives Pack um eine Priorität nach oben (+1) oder unten (-1). */
	public static List<String> move(List<String> enabledIds, String id, int direction, List<String> fixedIds) {
		List<String> out = new ArrayList<>(enabledIds);
		int i = out.indexOf(id);
		int j = i + Integer.signum(direction);
		if (i < 0 || j < 0 || j >= out.size() || fixedIds.contains(id) || fixedIds.contains(out.get(j))) return out;
		Collections.swap(out, i, j);
		return out;
	}
}

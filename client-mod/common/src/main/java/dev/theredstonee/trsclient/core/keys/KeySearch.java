package dev.theredstonee.trsclient.core.keys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suche in der Minecraft-Tastenbelegung (Steuerung → Tastenbelegung). Die Bäume liefern je Aktion ein {@link Entry}
 * (Name, Kategorie, Taste), hier wird die Eingabe zerlegt und geprüft – ohne Minecraft-Klassen, damit es in allen
 * Versionen gleich funktioniert.
 *
 * <p>Suchsprache (Wörter mit Leerzeichen trennen, alles muss passen):
 * <ul>
 * <li>freier Text – im Namen, in der Kategorie oder im Tastennamen</li>
 * <li>{@code key:2}, {@code key:r}, {@code key:space}, {@code key:maus4} – Aktionen auf dieser Taste</li>
 * <li>{@code mouse} – nur Maustasten</li>
 * <li>{@code mod:sodium} – Mod bzw. Kategorie</li>
 * <li>{@code conflict} – doppelt belegte Tasten, {@code unbound} – nicht belegte Aktionen</li>
 * </ul>
 * Deutsche und spanische Schreibweisen (taste:, maus, konflikt, unbelegt, tecla:, ratón, conflicto, libre …) gehen auch.
 */
public final class KeySearch {
	private KeySearch() {
	}

	private static volatile dev.theredstonee.trsclient.core.module.Module module;

	/** Vom Modul-Register gesetzt (Komfort-Paket). */
	public static void bind(dev.theredstonee.trsclient.core.module.Module m) {
		module = m;
	}

	/** Modul „Suche in der Tastenbelegung“ an (Standard: an, auch bevor die Module geladen sind). */
	public static boolean enabled() {
		dev.theredstonee.trsclient.core.module.Module m = module;
		return m == null || m.isEnabled();
	}

	/** Eine Aktion der Tastenbelegung, wie sie der Baum sieht. */
	public static final class Entry {
		/** Übersetzter Name der Aktion („Springen“). */
		public final String name;
		/** Übersetzungsschlüssel der Aktion („key.jump“, „key.sodium.menu“). */
		public final String nameKey;
		/** Übersetzte Kategorie („Bewegung“, „Sodium“). */
		public final String category;
		/** Schlüssel bzw. ID der Kategorie („key.categories.movement“, „sodium:main“). */
		public final String categoryKey;
		/** Gespeicherte Taste wie in options.txt: „key.keyboard.2“, „key.mouse.4“, „key.keyboard.unknown“. */
		public final String keyCode;
		/** Angezeigter Tastenname („2“, „Maustaste 4“, „Linke Umschalttaste“). */
		public final String keyLabel;
		/** Baum-eigenes Objekt (Listeneintrag o. Ä.), unverändert durchgereicht. */
		public final Object handle;
		boolean conflict;

		public Entry(String name, String nameKey, String category, String categoryKey, String keyCode, String keyLabel, Object handle) {
			this.name = name == null ? "" : name;
			this.nameKey = nameKey == null ? "" : nameKey;
			this.category = category == null ? "" : category;
			this.categoryKey = categoryKey == null ? "" : categoryKey;
			this.keyCode = keyCode == null ? "" : keyCode;
			this.keyLabel = keyLabel == null ? "" : keyLabel;
			this.handle = handle;
		}

		public boolean unbound() {
			return keyCode.isEmpty() || keyCode.endsWith(".unknown") || keyCode.equals("key.keyboard.none");
		}

		public boolean mouse() {
			return keyCode.startsWith("key.mouse.");
		}

		public boolean conflict() {
			return conflict;
		}
	}

	/** Zerlegte Eingabe. */
	public static final class Query {
		final List<String> words = new ArrayList<String>();
		final List<String> keys = new ArrayList<String>();
		final List<String> mods = new ArrayList<String>();
		boolean mouse;
		boolean conflict;
		boolean unbound;

		public boolean empty() {
			return words.isEmpty() && keys.isEmpty() && mods.isEmpty() && !mouse && !conflict && !unbound;
		}
	}

	private static final Pattern MOUSE_ALIAS = Pattern.compile("^(?:mouse|maus|maustaste|m|button|btn|raton|ratón|boton|botón)[ _-]?(\\d{1,2})$");
	private static final Map<String, String> MOUSE_NAMES = new HashMap<String, String>();

	static {
		MOUSE_NAMES.put("left", "1");
		MOUSE_NAMES.put("right", "2");
		MOUSE_NAMES.put("middle", "3");
	}

	public static Query parse(String text) {
		Query q = new Query();
		if (text == null) return q;
		String t = text.trim().toLowerCase(Locale.ROOT);
		if (t.length() > 200) t = t.substring(0, 200);
		for (String raw : t.split("\\s+")) {
			if (raw.isEmpty()) continue;
			int colon = raw.indexOf(':');
			String prefix = colon > 0 ? raw.substring(0, colon) : "";
			String value = colon > 0 ? raw.substring(colon + 1) : raw;
			if (isOne(prefix, "key", "taste", "tecla", "k")) {
				if (value.isEmpty()) continue;
				if (isOne(value, "mouse", "maus", "raton", "ratón")) q.mouse = true;
				else q.keys.add(normalizeKey(value));
			} else if (isOne(prefix, "mod", "cat", "category", "kategorie", "kat", "categoria", "categoría")) {
				if (!value.isEmpty()) q.mods.add(value);
			} else if (isOne(prefix, "is", "ist", "es")) {
				applyFlag(q, value);
			} else if (!applyFlag(q, raw)) {
				q.words.add(raw);
			}
		}
		return q;
	}

	private static boolean applyFlag(Query q, String w) {
		if (isOne(w, "mouse", "maus", "raton", "ratón")) {
			q.mouse = true;
		} else if (isOne(w, "conflict", "conflicts", "konflikt", "konflikte", "doppelt", "conflicto", "conflictos", "duplicate")) {
			q.conflict = true;
		} else if (isOne(w, "unbound", "unbelegt", "frei", "free", "unassigned", "libre", "libres", "none")) {
			q.unbound = true;
		} else {
			return false;
		}
		return true;
	}

	private static boolean isOne(String s, String... options) {
		for (String o : options) if (o.equals(s)) return true;
		return false;
	}

	/** „maus4“, „mouse 4“, „m4“ → „mouse4“; sonst die Eingabe ohne Leerzeichen. */
	static String normalizeKey(String value) {
		String v = value.trim().toLowerCase(Locale.ROOT);
		Matcher m = MOUSE_ALIAS.matcher(v);
		if (m.matches()) return "mouse" + Integer.parseInt(m.group(1));
		return v;
	}

	/** Suchbare Namen einer Taste: Code-Ende („2“, „left.shift“, „mouse4“), ohne Punkte, angezeigter Name. */
	static List<String> keyNames(Entry e) {
		List<String> out = new ArrayList<String>();
		if (e.unbound()) return out;
		String code = e.keyCode.toLowerCase(Locale.ROOT);
		if (code.startsWith("key.mouse.")) {
			String n = code.substring("key.mouse.".length());
			String mapped = MOUSE_NAMES.get(n);
			out.add("mouse" + (mapped != null ? mapped : n));
			out.add(n);
		} else if (code.startsWith("key.keyboard.")) {
			String n = code.substring("key.keyboard.".length());
			out.add(n);
			out.add(n.replace(".", ""));
			if (n.startsWith("keypad.")) out.add("num" + n.substring("keypad.".length()));
		} else {
			out.add(code);
		}
		String label = e.keyLabel.trim().toLowerCase(Locale.ROOT);
		if (!label.isEmpty()) {
			out.add(label);
			out.add(label.replace(" ", ""));
		}
		return out;
	}

	/** Suchbegriff für eine gedrückte Taste („key:2“, „key:mouse4“) – für „Taste drücken zum Suchen“. */
	public static String queryFor(String keyCode) {
		Entry probe = new Entry("", "", "", "", keyCode, "", null);
		List<String> names = keyNames(probe);
		return names.isEmpty() ? "" : "key:" + names.get(0);
	}

	/**
	 * F3-Kombinationen (ab 1.21.9 als eigene Aktionen „key.debug.*“ in der Kategorie Debug) wirken nur zusammen mit F3 –
	 * sie sind keine Doppelbelegung mit normalen Tasten.
	 */
	static boolean debugCombo(Entry e) {
		return e.nameKey.startsWith("key.debug.") || e.categoryKey.equals("key.categories.debug") || e.categoryKey.equals("minecraft:debug");
	}

	/** Doppelt belegte Tasten markieren (gleiche gespeicherte Taste, beide belegt, keine F3-Kombination). */
	public static void markConflicts(List<Entry> entries) {
		Map<String, Integer> count = new HashMap<String, Integer>();
		for (Entry e : entries) {
			if (e.unbound() || debugCombo(e)) continue;
			Integer c = count.get(e.keyCode);
			count.put(e.keyCode, c == null ? 1 : c + 1);
		}
		for (Entry e : entries) {
			Integer c = count.get(e.keyCode);
			e.conflict = !e.unbound() && !debugCombo(e) && c != null && c > 1;
		}
	}

	public static boolean matches(Entry e, Query q) {
		if (q.empty()) return true;
		if (q.mouse && !e.mouse()) return false;
		if (q.unbound && !e.unbound()) return false;
		if (q.conflict && !e.conflict) return false;
		if (!q.keys.isEmpty()) {
			List<String> names = keyNames(e);
			for (String k : q.keys) if (!names.contains(k)) return false;
		}
		if (!q.mods.isEmpty()) {
			String hay = (e.categoryKey + "\n" + e.nameKey + "\n" + e.category).toLowerCase(Locale.ROOT);
			for (String m : q.mods) if (!hay.contains(m)) return false;
		}
		if (!q.words.isEmpty()) {
			String hay = (e.name + "\n" + e.category + "\n" + e.keyLabel + "\n" + e.nameKey).toLowerCase(Locale.ROOT);
			for (String w : q.words) if (!hay.contains(w)) return false;
		}
		return true;
	}

	/** Passende Einträge in der ursprünglichen Reihenfolge (markiert vorher Konflikte). */
	public static List<Entry> filter(List<Entry> entries, String text) {
		markConflicts(entries);
		Query q = parse(text);
		if (q.empty()) return Collections.unmodifiableList(entries);
		List<Entry> out = new ArrayList<Entry>();
		for (Entry e : entries) if (matches(e, q)) out.add(e);
		return out;
	}
}

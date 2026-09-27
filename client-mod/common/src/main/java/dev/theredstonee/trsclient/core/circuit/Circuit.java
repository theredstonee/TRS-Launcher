package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.module.NewSince;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Eine Schaltung der Bibliothek (vom Server bzw. aus dem Cache, Format siehe {@code docs/circuit-format.md}):
 * <pre>
 * {"format": 1, "id": "not_gate", "category": "basics", "difficulty": 1, "server": "ok",
 *  "texts": {"en": {"name": "…", "desc": "…"}, "de": {…}},
 *  "palette": {"#": "solid", "A": "lever[face=floor]@A", ...},
 *  "layers": [["A-#tL"]],          // layers[y][z] = Zeile in x-Richtung (Osten), '.'/' ' = Luft
 *  "tests": [...]}                  // Simulation, siehe sim.CircuitSimTest
 * </pre>
 * Koordinaten: x = Osten, y = oben, z = Süden; Richtungen in den Eigenschaften wie in Minecraft.
 */
public final class Circuit {
	public static final int MAX_SIZE = 16;
	/** Grenzen für Schaltungen vom Server bzw. aus Einreichungen. */
	public static final int MAX_PALETTE = 64;
	public static final int MAX_NAME = 64;
	public static final int MAX_DESC = 1200;
	public static final int MAX_NOTE = 300;
	public static final int MAX_LANGUAGES = 12;
	public static final int MAX_TESTS = 16;
	/** Aktuelle Formatversion. */
	public static final int FORMAT = 1;

	private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");
	private static final Pattern LANG = Pattern.compile("[a-z]{2}(-[A-Z]{2})?");
	private static final Pattern VERSION = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){1,3}");

	/** Kategorien in der Reihenfolge der Bibliothek. */
	public enum Category {
		BASICS("basics"),
		CLOCKS("clocks"),
		MEMORY("memory"),
		PULSE("pulse"),
		DOORS("doors"),
		FARMS("farms"),
		DISPLAYS("displays");

		public final String id;

		Category(String id) {
			this.id = id;
		}

		public static Category of(String id) {
			for (Category c : values()) if (c.id.equals(id)) return c;
			return null;
		}
	}

	/** Ein belegtes Feld. */
	public static final class Cell {
		public final int x;
		public final int y;
		public final int z;
		public final BlockSpec spec;

		Cell(int x, int y, int z, BlockSpec spec) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.spec = spec;
		}
	}

	/** Eine Zeile der Materialliste. */
	public static final class Material {
		public final BlockDef def;
		public final int count;

		Material(BlockDef def, int count) {
			this.def = def;
			this.count = count;
		}
	}

	public final String id;
	public final Category category;
	/** 1 = einfach, 2 = mittel, 3 = knifflig. */
	public final int difficulty;
	/** Ab welcher Minecraft-Version die Schaltung läuft (höchster Wert aus Blöcken, Angabe und Server-Index). */
	public final String since;
	/** Bis zu welcher Minecraft-Version (einschließlich) oder null = ohne Grenze. */
	public final String until;
	/** Läuft auf Java-Servern ohne Besonderheiten? false = Hinweis im Text {@code note}. */
	public final boolean serverOk;
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	public final List<Cell> cells;
	/** Tests für die Simulation (roh, siehe {@code sim.CircuitSimTest}). */
	public final JsonArray tests;
	/** Texte je Sprache: Sprache → (name/desc/note → Text). */
	private final Map<String, Map<String, String>> texts;
	private final List<Material> materials;
	private final Map<String, Cell> markers;
	private final int checked;
	/** Tests in der Simulation bestanden (vom Lader gesetzt). */
	private volatile boolean simulated;

	private Circuit(String id, Category category, int difficulty, String since, String until, boolean serverOk, int sx,
			int sy, int sz, List<Cell> cells, JsonArray tests, Map<String, Map<String, String>> texts) {
		this.id = id;
		this.category = category;
		this.difficulty = difficulty;
		this.since = since;
		this.until = until;
		this.serverOk = serverOk;
		this.sizeX = sx;
		this.sizeY = sy;
		this.sizeZ = sz;
		this.cells = Collections.unmodifiableList(cells);
		this.tests = tests;
		this.texts = texts;
		Map<BlockDef, Integer> counts = new LinkedHashMap<BlockDef, Integer>();
		Map<String, Cell> marks = new HashMap<String, Cell>();
		int n = 0;
		for (Cell c : cells) {
			if (c.spec.marker != null) marks.put(c.spec.marker, c);
			if (c.spec.optional) continue;
			n++;
			Integer old = counts.get(c.spec.def);
			counts.put(c.spec.def, old == null ? 1 : old + 1);
		}
		List<Material> list = new ArrayList<Material>();
		for (Map.Entry<BlockDef, Integer> e : counts.entrySet()) list.add(new Material(e.getKey(), e.getValue()));
		Collections.sort(list, new java.util.Comparator<Material>() {
			@Override
			public int compare(Material a, Material b) {
				return b.count != a.count ? b.count - a.count : a.def.key.compareTo(b.def.key);
			}
		});
		this.materials = Collections.unmodifiableList(list);
		this.markers = marks;
		this.checked = n;
	}

	/**
	 * Liest und prüft eine Schaltung; Fehler → IllegalArgumentException mit Grund. Streng: nur bekannte Blöcke und
	 * Eigenschaften, Größe 1–16 je Achse, Textlängen, Versionen nur aus Ziffern.
	 */
	public static Circuit parse(JsonObject o, BlockCatalog catalog) {
		String id = str(o, "id", null);
		if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("ungültige id: " + id);
		if (o.has("format") && o.get("format").getAsInt() > FORMAT) throw new IllegalArgumentException(id + ": neueres Format");
		Category cat = Category.of(str(o, "category", ""));
		if (cat == null) throw new IllegalArgumentException(id + ": unbekannte Kategorie");
		int difficulty = o.has("difficulty") ? o.get("difficulty").getAsInt() : 1;
		if (difficulty < 1 || difficulty > 3) throw new IllegalArgumentException(id + ": difficulty 1–3");
		String server = str(o, "server", "ok");
		if (!"ok".equals(server) && !"note".equals(server)) throw new IllegalArgumentException(id + ": server ok|note");
		JsonElement pal = o.get("palette");
		if (pal == null || !pal.isJsonObject() || pal.getAsJsonObject().entrySet().size() > MAX_PALETTE) {
			throw new IllegalArgumentException(id + ": Palette fehlt/zu groß");
		}
		Map<Character, BlockSpec> palette = new HashMap<Character, BlockSpec>();
		for (Map.Entry<String, JsonElement> e : pal.getAsJsonObject().entrySet()) {
			if (e.getKey().length() != 1) throw new IllegalArgumentException(id + ": Palette-Schlüssel muss ein Zeichen sein");
			char ch = e.getKey().charAt(0);
			if (ch == '.' || ch <= ' ' || ch > '~') throw new IllegalArgumentException(id + ": ungültiges Palette-Zeichen");
			try {
				BlockSpec spec = BlockSpec.parse(e.getValue().getAsString(), catalog);
				validateProps(spec);
				palette.put(ch, spec);
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException(id + ": " + ex.getMessage());
			}
		}
		JsonElement layersJson = o.get("layers");
		if (layersJson == null || !layersJson.isJsonArray()) throw new IllegalArgumentException(id + ": layers fehlt");
		JsonArray layers = layersJson.getAsJsonArray();
		if (layers.size() == 0 || layers.size() > MAX_SIZE) throw new IllegalArgumentException(id + ": 1–16 Schichten");
		int sy = layers.size();
		int sz = 0;
		int sx = 0;
		List<Cell> cells = new ArrayList<Cell>();
		for (int y = 0; y < sy; y++) {
			JsonArray rows = layers.get(y).getAsJsonArray();
			if (rows.size() > MAX_SIZE) throw new IllegalArgumentException(id + ": mehr als 16 Zeilen");
			sz = Math.max(sz, rows.size());
			for (int z = 0; z < rows.size(); z++) {
				String row = rows.get(z).getAsString();
				if (row.length() > MAX_SIZE) throw new IllegalArgumentException(id + ": Zeile länger als 16");
				sx = Math.max(sx, row.length());
				for (int x = 0; x < row.length(); x++) {
					char ch = row.charAt(x);
					if (ch == '.' || ch == ' ') continue;
					BlockSpec spec = palette.get(ch);
					if (spec == null) throw new IllegalArgumentException(id + ": Zeichen '" + ch + "' fehlt in der Palette");
					cells.add(new Cell(x, y, z, spec));
				}
			}
		}
		if (sx == 0 || sy == 0 || sz == 0) throw new IllegalArgumentException(id + ": leer");
		if (cells.isEmpty()) throw new IllegalArgumentException(id + ": keine Blöcke");
		String since = str(o, "since", "1.8");
		if (!VERSION.matcher(since).matches()) throw new IllegalArgumentException(id + ": since ungültig");
		for (Cell c : cells) {
			if (NewSince.compare(c.spec.def.since, since) > 0) since = c.spec.def.since;
		}
		String until = str(o, "until", null);
		if (until != null && !VERSION.matcher(until).matches()) throw new IllegalArgumentException(id + ": until ungültig");
		JsonArray tests = new JsonArray();
		JsonElement testsJson = o.get("tests");
		if (testsJson != null && testsJson.isJsonArray()) tests = testsJson.getAsJsonArray();
		if (tests.size() > MAX_TESTS) throw new IllegalArgumentException(id + ": zu viele Tests");
		Map<String, Map<String, String>> texts = new LinkedHashMap<String, Map<String, String>>();
		JsonElement textsJson = o.get("texts");
		if (textsJson != null && textsJson.isJsonObject()) {
			JsonObject t = textsJson.getAsJsonObject();
			if (t.entrySet().size() > MAX_LANGUAGES) throw new IllegalArgumentException(id + ": zu viele Sprachen");
			for (Map.Entry<String, JsonElement> e : t.entrySet()) {
				if (!LANG.matcher(e.getKey()).matches()) throw new IllegalArgumentException(id + ": Sprache " + e.getKey());
				if (!e.getValue().isJsonObject()) throw new IllegalArgumentException(id + ": Texte " + e.getKey());
				Map<String, String> m = new HashMap<String, String>();
				JsonObject lt = e.getValue().getAsJsonObject();
				text(lt, "name", MAX_NAME, m, id);
				text(lt, "desc", MAX_DESC, m, id);
				text(lt, "note", MAX_NOTE, m, id);
				texts.put(e.getKey(), m);
			}
		}
		return new Circuit(id, cat, difficulty, since, until, "ok".equals(server), sx, sy, sz, cells, tests, texts);
	}

	private static String str(JsonObject o, String key, String fallback) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? fallback : e.getAsString();
	}

	private static void text(JsonObject o, String key, int max, Map<String, String> out, String id) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) return;
		String v = e.getAsString().trim();
		if (v.length() > max) throw new IllegalArgumentException(id + ": " + key + " zu lang");
		for (int i = 0; i < v.length(); i++) {
			char c = v.charAt(i);
			if (c < 32 && c != '\n') throw new IllegalArgumentException(id + ": Steuerzeichen in " + key);
			if (c == '§') throw new IllegalArgumentException(id + ": Formatcode in " + key);
		}
		if (!v.isEmpty()) out.put(key, v);
	}

	/** Nur bekannte Eigenschaften mit erlaubten Werten (keine NBT, keine freien Zeichenketten). */
	static void validateProps(BlockSpec spec) {
		for (Map.Entry<String, String> e : spec.props.entrySet()) {
			String k = e.getKey();
			String v = e.getValue();
			boolean ok;
			if ("facing".equals(k)) ok = v.matches("north|south|east|west|up|down");
			else if ("delay".equals(k)) ok = v.matches("[1-4]");
			else if ("mode".equals(k)) ok = v.matches("compare|subtract");
			else if ("face".equals(k)) ok = v.matches("floor|wall|ceiling");
			else if ("inverted".equals(k)) ok = v.matches("true|false");
			else ok = false;
			if (!ok) throw new IllegalArgumentException("Eigenschaft " + k + "=" + v + " nicht erlaubt");
		}
		if (spec.marker != null && !spec.marker.matches("[A-Za-z0-9]{1,8}")) throw new IllegalArgumentException("Anschluss " + spec.marker);
	}

	/** Text in {@code lang} (sonst Grundsprache, Englisch, erste Sprache) oder null. */
	public String text(String lang, String key) {
		Map<String, String> m = lang == null ? null : texts.get(lang);
		String v = m == null ? null : m.get(key);
		if (v == null && lang != null && lang.indexOf('-') > 0) {
			m = texts.get(lang.substring(0, lang.indexOf('-')));
			v = m == null ? null : m.get(key);
		}
		if (v == null) {
			m = texts.get("en");
			v = m == null ? null : m.get(key);
		}
		if (v == null) {
			for (Map<String, String> other : texts.values()) {
				v = other.get(key);
				if (v != null) break;
			}
		}
		return v;
	}

	/** Gibt es den Text genau in dieser Sprache? */
	public boolean hasText(String lang, String key) {
		Map<String, String> m = texts.get(lang);
		return m != null && m.get(key) != null;
	}

	/** Sprachen mit eigenen Texten. */
	public Set<String> languages() {
		return Collections.unmodifiableSet(texts.keySet());
	}

	/** Tests in der Simulation bestanden (nur dann zeigt die Bibliothek „geprüft“). */
	public boolean simulated() {
		return simulated;
	}

	void setSimulated(boolean ok) {
		simulated = ok;
	}

	/** Mit Versionsgrenzen aus dem Server-Index (die engere gewinnt). */
	Circuit withVersions(String min, String max) {
		String s = since;
		if (min != null && VERSION.matcher(min).matches() && NewSince.compare(min, s) > 0) s = min;
		String u = until;
		if (max != null && VERSION.matcher(max).matches() && (u == null || NewSince.compare(max, u) < 0)) u = max;
		if (s.equals(since) && (u == null ? until == null : u.equals(until))) return this;
		Circuit c = new Circuit(id, category, difficulty, s, u, serverOk, sizeX, sizeY, sizeZ, new ArrayList<Cell>(cells), tests,
				texts);
		c.simulated = simulated;
		return c;
	}

	/** Materialliste (ohne {@code ?}-Felder), meiste zuerst. */
	public List<Material> materials() {
		return materials;
	}

	/** Zahl der Blöcke, die gesetzt und geprüft werden. */
	public int blockCount() {
		return checked;
	}

	/** Feld mit Anschluss {@code name} oder null. */
	public Cell marker(String name) {
		return markers.get(name);
	}

	/** Läuft in dieser Minecraft-Version (z. B. "1.8.9", "1.21.11", "26.3")? */
	public boolean runsIn(String minecraftVersion) {
		if (minecraftVersion == null) return true;
		if (NewSince.compare(minecraftVersion, since) < 0) return false;
		return until == null || NewSince.compare(minecraftVersion, until) <= 0;
	}
}

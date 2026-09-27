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

/**
 * Eine Schaltung der Bibliothek (aus {@code circuits/<id>.json}). Aufbau:
 * <pre>
 * {"id": "not_gate", "category": "basics", "difficulty": 1, "server": "ok",
 *  "palette": {"#": "solid", "A": "lever[face=floor]@A", ...},
 *  "layers": [["A-#tL"]],          // layers[y][z] = Zeile in x-Richtung (Osten), '.'/' ' = Luft
 *  "tests": [...]}                  // Simulation, siehe sim.CircuitSimTest
 * </pre>
 * Koordinaten: x = Osten, y = oben, z = Süden; Richtungen in den Eigenschaften wie in Minecraft.
 */
public final class Circuit {
	public static final int MAX_SIZE = 16;

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
	/** Ab welcher Minecraft-Version die Schaltung läuft (höchster Wert aus Blöcken und Angabe). */
	public final String since;
	/** Läuft auf Java-Servern ohne Besonderheiten? false = Hinweis in den Texten ({@code <id>.note}). */
	public final boolean serverOk;
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	public final List<Cell> cells;
	/** Tests für die Simulation (roh, siehe {@code sim.CircuitSimTest}). */
	public final JsonArray tests;
	private final List<Material> materials;
	private final Map<String, Cell> markers;
	private final int checked;

	private Circuit(String id, Category category, int difficulty, String since, boolean serverOk, int sx, int sy, int sz,
			List<Cell> cells, JsonArray tests) {
		this.id = id;
		this.category = category;
		this.difficulty = difficulty;
		this.since = since;
		this.serverOk = serverOk;
		this.sizeX = sx;
		this.sizeY = sy;
		this.sizeZ = sz;
		this.cells = Collections.unmodifiableList(cells);
		this.tests = tests;
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

	/** Liest eine Schaltung; Fehler im Aufbau → IllegalArgumentException mit Grund. */
	public static Circuit parse(JsonObject o, BlockCatalog catalog) {
		String id = o.get("id").getAsString();
		Category cat = Category.of(o.get("category").getAsString());
		if (cat == null) throw new IllegalArgumentException(id + ": unbekannte Kategorie");
		int difficulty = o.has("difficulty") ? o.get("difficulty").getAsInt() : 1;
		boolean serverOk = !o.has("server") || "ok".equals(o.get("server").getAsString());
		Map<Character, BlockSpec> palette = new HashMap<Character, BlockSpec>();
		for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("palette").entrySet()) {
			if (e.getKey().length() != 1) throw new IllegalArgumentException(id + ": Palette-Schlüssel muss ein Zeichen sein");
			char ch = e.getKey().charAt(0);
			if (ch == '.' || ch == ' ') throw new IllegalArgumentException(id + ": '.' und ' ' sind Luft");
			try {
				palette.put(ch, BlockSpec.parse(e.getValue().getAsString(), catalog));
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException(id + ": " + ex.getMessage());
			}
		}
		JsonArray layers = o.getAsJsonArray("layers");
		int sy = layers.size();
		int sz = 0;
		int sx = 0;
		List<Cell> cells = new ArrayList<Cell>();
		for (int y = 0; y < sy; y++) {
			JsonArray rows = layers.get(y).getAsJsonArray();
			sz = Math.max(sz, rows.size());
			for (int z = 0; z < rows.size(); z++) {
				String row = rows.get(z).getAsString();
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
		if (sx == 0 || sy == 0 || sz == 0 || sx > MAX_SIZE || sy > MAX_SIZE || sz > MAX_SIZE) {
			throw new IllegalArgumentException(id + ": Größe " + sx + "×" + sy + "×" + sz + " (erlaubt 1–16)");
		}
		String since = o.has("since") ? o.get("since").getAsString() : "1.8";
		for (Cell c : cells) {
			if (NewSince.compare(c.spec.def.since, since) > 0) since = c.spec.def.since;
		}
		JsonArray tests = o.has("tests") ? o.getAsJsonArray("tests") : new JsonArray();
		return new Circuit(id, cat, difficulty, since, serverOk, sx, sy, sz, cells, tests);
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
		return minecraftVersion == null || NewSince.compare(minecraftVersion, since) >= 0;
	}
}

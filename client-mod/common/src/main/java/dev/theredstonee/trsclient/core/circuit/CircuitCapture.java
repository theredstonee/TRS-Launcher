package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Liest einen markierten Bereich der Welt (zwei Eckpunkte, höchstens 16×16×16) und wandelt ihn in das
 * Schaltungs-Format um – nur Blöcke und die geprüften Zustände (Richtung, Verzögerung, Modus, Anbringung),
 * keine Kisteninhalte, keine NBT. Unbekannte volle Blöcke werden „beliebiger fester Block“, andere unbekannte
 * Blöcke (Dekoration, Pflanzen …) verhindern das Einreichen und werden genannt.
 */
public final class CircuitCapture {
	/** Palette-Zeichen in dieser Reihenfolge ({@code #} = fest, {@code -} = Staub bleiben lesbar). */
	private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+*=%&$!?<>";

	/** Ergebnis. */
	public static final class Result {
		/** Schaltung im Format (ohne Texte/Kategorie – die kommen aus dem Formular) oder null. */
		public JsonObject circuit;
		/** Geprüft gelesen oder null. */
		public Circuit parsed;
		/** Fehlercode: {@code too_big}, {@code empty}, {@code unsupported}, {@code not_loaded}, {@code too_many_kinds}. */
		public String error;
		/** Nicht unterstützte Blöcke (Registry-Namen). */
		public final List<String> unsupported = new ArrayList<String>();
	}

	private CircuitCapture() {
	}

	/** Größe des Bereichs zwischen zwei Ecken (je Achse). */
	public static int[] size(int x1, int y1, int z1, int x2, int y2, int z2) {
		return new int[] {Math.abs(x2 - x1) + 1, Math.abs(y2 - y1) + 1, Math.abs(z2 - z1) + 1};
	}

	public static Result capture(CircuitWorld w, BlockCatalog catalog, int x1, int y1, int z1, int x2, int y2, int z2, String id) {
		Result r = new Result();
		int minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
		int[] s = size(x1, y1, z1, x2, y2, z2);
		if (s[0] > Circuit.MAX_SIZE || s[1] > Circuit.MAX_SIZE || s[2] > Circuit.MAX_SIZE) {
			r.error = "too_big";
			return r;
		}
		String[][][] spec = new String[s[1]][s[2]][s[0]];
		TreeSet<String> unsupported = new TreeSet<String>();
		Map<String, String> props = new HashMap<String, String>();
		BlockCatalog.Normalized n = new BlockCatalog.Normalized();
		int lx = Integer.MAX_VALUE, ly = Integer.MAX_VALUE, lz = Integer.MAX_VALUE, hx = -1, hy = -1, hz = -1;
		for (int y = 0; y < s[1]; y++) {
			for (int z = 0; z < s[2]; z++) {
				for (int x = 0; x < s[0]; x++) {
					props.clear();
					String raw = w.block(minX + x, minY + y, minZ + z, props);
					if (raw == null) {
						r.error = "not_loaded";
						return r;
					}
					catalog.normalize(raw, props, w.legacy(), n);
					if (n.isAir() || "piston_head".equals(n.key) || "moving_piston".equals(n.key) || "piston_extension".equals(n.key)) continue;
					String text;
					BlockDef def = catalog.get(n.key);
					if (def != null && !def.anySolid()) {
						text = specText(def, n.props);
					} else if (w.conductor(minX + x, minY + y, minZ + z)) {
						text = "solid";
					} else {
						unsupported.add(raw.startsWith("minecraft:") ? raw.substring(10) : raw);
						continue;
					}
					spec[y][z][x] = text;
					lx = Math.min(lx, x);
					ly = Math.min(ly, y);
					lz = Math.min(lz, z);
					hx = Math.max(hx, x);
					hy = Math.max(hy, y);
					hz = Math.max(hz, z);
				}
			}
		}
		if (!unsupported.isEmpty()) {
			r.error = "unsupported";
			r.unsupported.addAll(unsupported);
			return r;
		}
		if (hx < 0) {
			r.error = "empty";
			return r;
		}
		Map<String, Character> palette = new LinkedHashMap<String, Character>();
		int next = 0;
		JsonArray layers = new JsonArray();
		for (int y = ly; y <= hy; y++) {
			JsonArray rows = new JsonArray();
			for (int z = lz; z <= hz; z++) {
				StringBuilder row = new StringBuilder();
				for (int x = lx; x <= hx; x++) {
					String t = spec[y][z][x];
					if (t == null) {
						row.append('.');
						continue;
					}
					Character ch = palette.get(t);
					if (ch == null) {
						if ("solid".equals(t) && !palette.containsValue('#')) ch = '#';
						else if ("redstone_wire".equals(t) && !palette.containsValue('-')) ch = '-';
						else {
							if (next >= CHARS.length() || palette.size() >= Circuit.MAX_PALETTE) {
								r.error = "too_many_kinds";
								return r;
							}
							ch = CHARS.charAt(next++);
						}
						palette.put(t, ch);
					}
					row.append(ch.charValue());
				}
				rows.add(new JsonPrimitive(trimRight(row)));
			}
			layers.add(rows);
		}
		JsonObject o = new JsonObject();
		o.addProperty("format", Circuit.FORMAT);
		o.addProperty("id", id == null || id.isEmpty() ? "submission" : id);
		o.addProperty("category", "basics");
		o.addProperty("difficulty", 1);
		o.addProperty("server", "ok");
		JsonObject pal = new JsonObject();
		for (Map.Entry<String, Character> e : palette.entrySet()) pal.addProperty(String.valueOf(e.getValue()), e.getKey());
		o.add("palette", pal);
		o.add("layers", layers);
		try {
			r.parsed = Circuit.parse(o, catalog);
			r.circuit = o;
		} catch (IllegalArgumentException e) {
			r.error = "invalid";
		}
		return r;
	}

	private static String trimRight(StringBuilder row) {
		int end = row.length();
		while (end > 1 && row.charAt(end - 1) == '.') end--;
		return row.substring(0, end);
	}

	/** Palette-Text eines Blocks: nur die geprüften Eigenschaften (+ Richtung bei Wand-Hebel/-Knopf). */
	static String specText(BlockDef def, Map<String, String> worldProps) {
		Map<String, String> p = new TreeMap<String, String>();
		for (String key : def.check) {
			String v = worldProps.get(key);
			if (v != null) p.put(key, v.toLowerCase(Locale.ROOT));
		}
		if ("attach".equals(def.rule) && "wall".equals(p.get("face"))) {
			String f = worldProps.get("facing");
			if (f != null) p.put("facing", f);
		}
		StringBuilder b = new StringBuilder(def.key);
		if (!p.isEmpty()) {
			b.append('[');
			boolean first = true;
			for (Map.Entry<String, String> e : p.entrySet()) {
				if (!first) b.append(',');
				b.append(e.getKey()).append('=').append(e.getValue());
				first = false;
			}
			b.append(']');
		}
		return b.toString();
	}

	/** ID aus einem Namen: klein, a–z/0–9/_ (höchstens 40 Zeichen). */
	public static String slug(String name) {
		String s = name == null ? "" : name.toLowerCase(Locale.ROOT);
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < s.length() && b.length() < 40; i++) {
			char c = s.charAt(i);
			if (c == 'ä') b.append("ae");
			else if (c == 'ö') b.append("oe");
			else if (c == 'ü') b.append("ue");
			else if (c == 'ß') b.append("ss");
			else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
			else if (b.length() > 0 && b.charAt(b.length() - 1) != '_') b.append('_');
		}
		while (b.length() > 0 && b.charAt(b.length() - 1) == '_') b.setLength(b.length() - 1);
		return b.length() == 0 ? "submission" : b.toString();
	}
}

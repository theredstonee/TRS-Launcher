package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Alle Blöcke der Schaltungs-Bibliothek und die Übersetzung „Block in der Welt → Bibliotheks-Block“:
 * moderne IDs samt Varianten (Kupfer-Birne gewachst/oxidiert …) und die Namen aus 1.8.9–1.12.2
 * ({@code unpowered_repeater}, {@code reeds}, Fackel an der Wand über {@code facing} …).
 */
public final class BlockCatalog {
	private final Map<String, BlockDef> defs = new LinkedHashMap<String, BlockDef>();
	/** Moderne Zweit-IDs → Schlüssel. */
	private final Map<String, String> aliases = new HashMap<String, String>();
	/** Alte ID → Schlüssel + mitgelieferte Eigenschaften. */
	private final Map<String, String> legacyKeys = new HashMap<String, String>();
	private final Map<String, Map<String, String>> legacyProps = new HashMap<String, Map<String, String>>();

	/** Ergebnis von {@link #normalize}: Bibliotheks-Schlüssel (oder die rohe ID, wenn unbekannt) + Eigenschaften. */
	public static final class Normalized {
		public String key;
		public final Map<String, String> props = new HashMap<String, String>();

		public boolean isAir() {
			return key == null || "air".equals(key) || "cave_air".equals(key) || "void_air".equals(key);
		}
	}

	static BlockCatalog parse(JsonObject root) {
		BlockCatalog c = new BlockCatalog();
		JsonObject blocks = root.getAsJsonObject("blocks");
		for (Map.Entry<String, JsonElement> e : blocks.entrySet()) {
			JsonObject o = e.getValue().getAsJsonObject();
			String key = e.getKey();
			List<String> check = strings(o.get("check"));
			BlockDef def = new BlockDef(key, str(o, "item"), str(o, "legacyItem"), str(o, "since"), str(o, "sim"), check,
					str(o, "rule"));
			c.defs.put(key, def);
			for (String a : strings(o.get("aliases"))) c.aliases.put(a, key);
			JsonElement legacy = o.get("legacy");
			if (legacy != null && legacy.isJsonObject()) {
				for (Map.Entry<String, JsonElement> l : legacy.getAsJsonObject().entrySet()) {
					c.legacyKeys.put(l.getKey(), key);
					Map<String, String> props = new HashMap<String, String>();
					for (Map.Entry<String, JsonElement> p : l.getValue().getAsJsonObject().entrySet()) {
						props.put(p.getKey(), p.getValue().getAsString());
					}
					c.legacyProps.put(l.getKey(), props);
				}
			}
		}
		return c;
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static List<String> strings(JsonElement e) {
		List<String> out = new ArrayList<String>();
		if (e != null && e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			for (JsonElement x : a) out.add(x.getAsString());
		}
		return out;
	}

	public BlockDef get(String key) {
		return defs.get(key);
	}

	public Collection<BlockDef> all() {
		return Collections.unmodifiableCollection(defs.values());
	}

	/**
	 * Übersetzt einen Block aus der Welt in die Sprache der Bibliothek.
	 *
	 * @param rawId  Registry-Name ({@code minecraft:repeater}, in alten Versionen {@code minecraft:unpowered_repeater})
	 * @param props  Blockzustand (Name → Wert, Groß-/Kleinschreibung egal)
	 * @param legacy Minecraft 1.8.9–1.12.2 (alte Namen und Eigenschaften)
	 */
	public Normalized normalize(String rawId, Map<String, String> props, boolean legacy, Normalized out) {
		Normalized n = out == null ? new Normalized() : out;
		n.props.clear();
		if (rawId == null) {
			n.key = null;
			return n;
		}
		String id = rawId.toLowerCase(Locale.ROOT);
		if (id.startsWith("minecraft:")) id = id.substring(10);
		if (props != null) {
			for (Map.Entry<String, String> e : props.entrySet()) {
				if (e.getKey() == null || e.getValue() == null) continue;
				n.props.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().toLowerCase(Locale.ROOT));
			}
		}
		String key = null;
		if (legacy) {
			key = legacyKeys.get(id);
			if (key != null) n.props.putAll(legacyProps.get(id));
		}
		if (key == null) key = defs.containsKey(id) ? id : aliases.get(id);
		if (key == null) {
			n.key = id;
			return n;
		}
		BlockDef def = defs.get(key);
		if (legacy && def != null && "torch".equals(def.rule)) {
			String facing = n.props.get("facing");
			if (facing == null || "up".equals(facing)) {
				key = "redstone_torch";
				n.props.remove("facing");
			} else {
				key = "redstone_wall_torch";
			}
		} else if (legacy && def != null && "attach".equals(def.rule)) {
			String facing = n.props.remove("facing");
			if (facing == null) facing = "";
			if (facing.startsWith("up") || facing.equals("up")) {
				n.props.put("face", "floor");
			} else if (facing.startsWith("down") || facing.equals("down")) {
				n.props.put("face", "ceiling");
			} else {
				n.props.put("face", "wall");
				n.props.put("facing", facing);
			}
		}
		n.key = key;
		return n;
	}
}

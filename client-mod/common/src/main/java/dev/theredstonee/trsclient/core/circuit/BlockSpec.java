package dev.theredstonee.trsclient.core.circuit;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ein Feld einer Schaltung: Block + Zustand, wie es in der JSON-Palette steht –
 * {@code repeater[facing=west,delay=2]~@A}: {@code ~} = beweglich (Kolben verschieben ihn, ein Kolbenkopf an der
 * Stelle zählt als richtig), {@code ?} = nur für die Simulation/Anzeige (nicht prüfen, nicht in der Materialliste),
 * {@code @A} = Anschluss „A“ für die Simulation.
 */
public final class BlockSpec {
	public final BlockDef def;
	/** Eigenschaften (sortiert, klein geschrieben). */
	public final Map<String, String> props;
	public final boolean movable;
	public final boolean optional;
	/** Anschluss-Name für Tests (Eingang/Ausgang) oder null. */
	public final String marker;

	BlockSpec(BlockDef def, Map<String, String> props, boolean movable, boolean optional, String marker) {
		this.def = def;
		this.props = Collections.unmodifiableMap(new TreeMap<String, String>(props));
		this.movable = movable;
		this.optional = optional;
		this.marker = marker;
	}

	/** Liest {@code id[k=v,…]~?@M}; unbekannte Blöcke → IllegalArgumentException. */
	static BlockSpec parse(String text, BlockCatalog catalog) {
		String s = text.trim();
		String marker = null;
		int at = s.indexOf('@');
		if (at >= 0) {
			marker = s.substring(at + 1).trim();
			s = s.substring(0, at);
		}
		boolean movable = false;
		boolean optional = false;
		while (s.endsWith("~") || s.endsWith("?")) {
			if (s.endsWith("~")) movable = true;
			else optional = true;
			s = s.substring(0, s.length() - 1);
		}
		Map<String, String> props = new TreeMap<String, String>();
		int br = s.indexOf('[');
		String id = s;
		if (br >= 0) {
			if (!s.endsWith("]")) throw new IllegalArgumentException("Klammer fehlt: " + text);
			id = s.substring(0, br);
			String inner = s.substring(br + 1, s.length() - 1);
			for (String part : inner.split(",")) {
				if (part.trim().isEmpty()) continue;
				int eq = part.indexOf('=');
				if (eq <= 0) throw new IllegalArgumentException("Eigenschaft ohne Wert: " + text);
				props.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
			}
		}
		id = id.trim();
		if (id.startsWith("minecraft:")) id = id.substring(10);
		BlockDef def = catalog.get(id);
		if (def == null) throw new IllegalArgumentException("Unbekannter Block: " + id);
		return new BlockSpec(def, props, movable, optional, marker);
	}

	public String prop(String key) {
		return props.get(key);
	}

	/** Dieselbe Angabe mit anderen Eigenschaften (Drehung). */
	BlockSpec with(Map<String, String> newProps) {
		return new BlockSpec(def, newProps, movable, optional, marker);
	}

	@Override
	public String toString() {
		return def.key + (props.isEmpty() ? "" : props.toString());
	}
}

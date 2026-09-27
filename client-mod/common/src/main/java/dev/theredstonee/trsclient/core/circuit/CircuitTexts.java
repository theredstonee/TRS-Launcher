package dev.theredstonee.trsclient.core.circuit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.i18n.I18n;

import java.util.HashMap;
import java.util.Map;

/**
 * Texte der Schaltungs-Bibliothek: Blocknamen, Kategorien und Zusätze aus {@code circuits/lang/<sprache>.json}
 * (Englisch, Deutsch, Spanisch vollständig; Beta-Sprachen fallen je Schlüssel auf Englisch zurück). Name,
 * Erklärung und Server-Hinweis einer Schaltung stehen in der Schaltung selbst (vom Server, {@code texts}).
 */
public final class CircuitTexts {
	private static CircuitTexts cached;

	private final String language;
	private final Map<String, String> table;
	private final Map<String, String> fallback;

	private CircuitTexts(String language, Map<String, String> table, Map<String, String> fallback) {
		this.language = language;
		this.table = table;
		this.fallback = fallback;
	}

	/** Texte in der aktiven Sprache (bei Sprachwechsel neu geladen). */
	public static synchronized CircuitTexts get() {
		String code = I18n.code();
		if (cached == null || !cached.language.equals(code)) cached = load(code);
		return cached;
	}

	/** Texte einer bestimmten Sprache (Tests). */
	public static CircuitTexts load(String code) {
		Map<String, String> en = read("en");
		Map<String, String> own = "en".equals(code) ? en : read(code);
		return new CircuitTexts(code, own, en);
	}

	private static Map<String, String> read(String code) {
		Map<String, String> out = new HashMap<String, String>();
		JsonObject o = CircuitLibrary.read("/assets/trsclient/circuits/lang/" + code + ".json");
		if (o == null) return out;
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			if (e.getKey().startsWith("_")) continue;
			if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
		}
		return out;
	}

	/** Eigene Sprache, sonst Englisch, sonst der Schlüssel. */
	public String text(String key) {
		String v = table.get(key);
		if (v == null || v.isEmpty()) v = fallback.get(key);
		return v == null ? key : v;
	}

	/** Gibt es den Schlüssel in genau dieser Sprache (ohne Rückfall)? */
	public boolean hasOwn(String key) {
		String v = table.get(key);
		return v != null && !v.isEmpty();
	}

	/** Name der Schaltung (Texte kommen mit der Schaltung vom Server; Rückfall Englisch, sonst die ID). */
	public String name(Circuit c) {
		String v = c.text(language, "name");
		return v == null ? c.id.replace('_', ' ') : v;
	}

	public String desc(Circuit c) {
		String v = c.text(language, "desc");
		return v == null ? "" : v;
	}

	/** Server-Hinweis (nur wenn {@link Circuit#serverOk} false ist). */
	public String note(Circuit c) {
		String v = c.text(language, "note");
		return v == null ? "" : v;
	}

	public String block(BlockDef def) {
		return text(def.labelKey());
	}

	public String category(Circuit.Category category) {
		return text("category." + category.id);
	}

	public String language() {
		return language;
	}
}

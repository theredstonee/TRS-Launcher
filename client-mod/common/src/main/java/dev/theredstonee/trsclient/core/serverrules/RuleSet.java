package dev.theredstonee.trsclient.core.serverrules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ein kompletter Regelsatz des Servers (Kanal {@code trsclient:rules} bzw. {@code TRS|Rules}, UTF-8-JSON ohne
 * Längenpräfix):
 *
 * <pre>{"v":1,
 *  "modules":{"minimap":{"enabled":false},
 *             "zoom":{"settings":{"factor":{"max":4.0}}},
 *             "cinematic":{"settings":{"freecamFairPlay":{"value":true}}}},
 *  "message":"optional"}</pre>
 *
 * Unbekannte Felder werden still übergangen; {@code v} &gt; 1 ändert daran nichts (nur bekannte Felder zählen).
 */
public final class RuleSet {
	/** Leerer Regelsatz (nichts gesperrt). */
	public static final RuleSet EMPTY = new RuleSet(1, Collections.<String, ModuleLock>emptyMap(), null);
	/** Höchstens so viele Module/Einstellungen je Modul (Schutz vor riesigen Nachrichten). */
	static final int MAX_MODULES = 512;
	static final int MAX_SETTINGS = 128;
	static final int MAX_ID = 64;
	/** Längster Hinweistext (Zeichen) – wie eine Chat-Nachricht. */
	public static final int MAX_MESSAGE = 256;

	/** Protokollversion des Servers ({@code v}; fehlt = 1). */
	public final int version;
	/** Regeln je Modul-ID (Reihenfolge wie vom Server). */
	public final Map<String, ModuleLock> modules;
	/** Hinweistext für den Spieler (bereinigt, ohne §-Codes); null = keiner. */
	public final String message;

	RuleSet(int version, Map<String, ModuleLock> modules, String message) {
		this.version = version;
		this.modules = Collections.unmodifiableMap(new LinkedHashMap<String, ModuleLock>(modules));
		this.message = message;
	}

	/** Regeln für ein Modul (null = keine). */
	public ModuleLock module(String id) {
		return modules.get(id);
	}

	/** Gibt es keine einzige Regel und keinen Hinweis? */
	public boolean isEmpty() {
		return modules.isEmpty() && message == null;
	}

	/**
	 * Liest die rohen Bytes einer {@code rules}-Nachricht.
	 *
	 * @return der Regelsatz oder null, wenn die Nachricht zu groß bzw. kein JSON-Objekt ist
	 */
	public static RuleSet parse(byte[] data) {
		if (data == null || data.length > ServerRules.MAX_BYTES) return null;
		return parse(new String(data, StandardCharsets.UTF_8));
	}

	/** Wie {@link #parse(byte[])}, aus Text. */
	public static RuleSet parse(String json) {
		if (json == null) return null;
		JsonObject root;
		try {
			JsonElement e = new JsonParser().parse(json);
			if (e == null || !e.isJsonObject()) return null;
			root = e.getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
		int version = 1;
		JsonPrimitive v = primitive(root.get("v"));
		if (v != null && v.isNumber()) {
			double d = v.getAsDouble();
			if (d >= 1 && d <= Integer.MAX_VALUE) version = (int) d;
		}
		Map<String, ModuleLock> modules = new LinkedHashMap<String, ModuleLock>();
		JsonElement mods = root.get("modules");
		if (mods != null && mods.isJsonObject()) {
			for (Map.Entry<String, JsonElement> m : mods.getAsJsonObject().entrySet()) {
				if (modules.size() >= MAX_MODULES) break;
				String id = m.getKey();
				if (!validId(id) || m.getValue() == null || !m.getValue().isJsonObject()) continue;
				ModuleLock lock = module(id, m.getValue().getAsJsonObject());
				if (!lock.empty()) modules.put(id, lock);
			}
		}
		String message = null;
		JsonPrimitive msg = primitive(root.get("message"));
		if (msg != null && msg.isString()) message = cleanMessage(msg.getAsString());
		return new RuleSet(version, modules, message);
	}

	private static ModuleLock module(String id, JsonObject o) {
		Boolean enabled = null;
		JsonPrimitive en = primitive(o.get("enabled"));
		if (en != null && en.isBoolean()) enabled = Boolean.valueOf(en.getAsBoolean());
		Map<String, SettingLock> settings = new LinkedHashMap<String, SettingLock>();
		JsonElement s = o.get("settings");
		if (s != null && s.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : s.getAsJsonObject().entrySet()) {
				if (settings.size() >= MAX_SETTINGS) break;
				if (!validId(e.getKey())) continue;
				SettingLock lock = setting(e.getValue());
				if (lock != null && !lock.empty()) settings.put(e.getKey(), lock);
			}
		}
		return new ModuleLock(id, enabled, settings);
	}

	private static SettingLock setting(JsonElement e) {
		if (e == null || !e.isJsonObject()) return null;
		JsonObject o = e.getAsJsonObject();
		Object value = null;
		JsonPrimitive v = primitive(o.get("value"));
		if (v != null) {
			if (v.isBoolean()) {
				value = Boolean.valueOf(v.getAsBoolean());
			} else if (v.isNumber()) {
				double d = v.getAsDouble();
				if (!Double.isNaN(d) && !Double.isInfinite(d)) value = Double.valueOf(d);
			} else if (v.isString()) {
				String str = v.getAsString().trim();
				if (!str.isEmpty() && str.length() <= MAX_ID) value = str;
			}
		}
		double min = number(o.get("min"));
		double max = number(o.get("max"));
		if (!Double.isNaN(min) && !Double.isNaN(max) && min > max) {
			// Unsinnige Grenzen: nicht raten.
			min = Double.NaN;
			max = Double.NaN;
		}
		return new SettingLock(value, min, max);
	}

	private static double number(JsonElement e) {
		JsonPrimitive p = primitive(e);
		if (p == null || !p.isNumber()) return Double.NaN;
		double d = p.getAsDouble();
		return Double.isInfinite(d) ? Double.NaN : d;
	}

	private static JsonPrimitive primitive(JsonElement e) {
		return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
	}

	/** Modul-/Einstellungs-IDs: kurz, nur Buchstaben, Ziffern, '_', '-', '.'. */
	static boolean validId(String id) {
		if (id == null || id.isEmpty() || id.length() > MAX_ID) return false;
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
			if (!ok) return false;
		}
		return true;
	}

	/** Hinweistext säubern: §-Codes und Steuerzeichen weg, Leerraum zusammenfassen, kürzen; leer = null. */
	static String cleanMessage(String text) {
		if (text == null) return null;
		StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_MESSAGE));
		boolean space = false;
		for (int i = 0; i < text.length() && sb.length() < MAX_MESSAGE; i++) {
			char c = text.charAt(i);
			if (c == (char) 0xA7) {
				i++; // Formatcode samt Folgezeichen
				continue;
			}
			if (Character.isWhitespace(c) || Character.isISOControl(c)) {
				space = sb.length() > 0;
				continue;
			}
			if (Character.getType(c) == Character.FORMAT) continue;
			if (space) {
				sb.append(' ');
				space = false;
				if (sb.length() >= MAX_MESSAGE) break;
			}
			sb.append(c);
		}
		String out = sb.toString().trim();
		return out.isEmpty() ? null : out;
	}

	@Override
	public String toString() {
		return "RuleSet{v=" + version + ", modules=" + modules.values() + ", message=" + message + "}";
	}
}

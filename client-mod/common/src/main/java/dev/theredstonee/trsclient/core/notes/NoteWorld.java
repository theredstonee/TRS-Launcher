package dev.theredstonee.trsclient.core.notes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;

import java.util.Locale;

/**
 * Welt eines Notizbuchs – dieselbe Kennung wie bei geteilten Wegpunkten (API.md §18.10): Server über die Adresse
 * (klein, ohne Standardport), Einzelspielerwelten über die ersten 16 Hex von SHA-256 des Wegpunkt-Weltschlüssels
 * {@code sp:<weltordner>}. So finden sich die Notizen auf jedem PC wieder, auf dem es dieselbe Welt/denselben Server
 * gibt.
 *
 * <p>Buch-Schlüssel: {@code server:<adresse>} bzw. {@code world:<16 hex>}.
 */
public final class NoteWorld {
	public static final String SERVER = "server";
	public static final String WORLD = "world";
	/** Längste Server-Adresse / längster Weltname, die gespeichert werden. */
	public static final int MAX_ADDRESS = 255;
	public static final int MAX_NAME = 64;

	/** {@link #SERVER} oder {@link #WORLD}. */
	public final String type;
	/** Server-Adresse bzw. Welt-Kennung (16 Hex). */
	public final String id;
	/** Anzeigename (Adresse bzw. Weltordner), nie null. */
	public final String name;

	private NoteWorld(String type, String id, String name) {
		this.type = type;
		this.id = id;
		this.name = name == null ? "" : name;
	}

	/**
	 * Aus dem Wegpunkt-Weltschlüssel des Spiels ({@code mp:<adresse>} bzw. {@code sp:<weltordner>}); null ohne Welt.
	 */
	public static NoteWorld fromWaypointKey(String key) {
		if (key == null || key.length() < 4) return null;
		if (key.startsWith("mp:")) {
			String addr = WaypointShare.normalizeServer(key.substring(3));
			if (addr.isEmpty() || addr.equals("?") || addr.length() > MAX_ADDRESS) return null;
			return new NoteWorld(SERVER, addr, addr);
		}
		if (key.startsWith("sp:")) {
			String level = key.substring(3);
			if (level.isEmpty() || level.equals("?")) return null;
			return new NoteWorld(WORLD, WaypointShare.worldId(key), Note.clean(level, MAX_NAME, false));
		}
		return null;
	}

	/** Aus einem Buch-Schlüssel (+ gespeichertem Namen). */
	public static NoteWorld fromKey(String key, String name) {
		if (key == null) return null;
		if (key.startsWith(SERVER + ":")) {
			String addr = key.substring(SERVER.length() + 1);
			if (addr.isEmpty() || addr.length() > MAX_ADDRESS) return null;
			return new NoteWorld(SERVER, addr, addr);
		}
		if (key.startsWith(WORLD + ":")) {
			String id = key.substring(WORLD.length() + 1);
			if (!hex16(id)) return null;
			return new NoteWorld(WORLD, id, name);
		}
		return null;
	}

	/** Buch-Schlüssel. */
	public String key() {
		return type + ":" + id;
	}

	public boolean server() {
		return SERVER.equals(type);
	}

	/** Anzeigename; Welten ohne bekannten Namen (nur vom Konto) zeigen den Anfang der Kennung. */
	public String label() {
		if (!name.isEmpty()) return name;
		return "#" + id.substring(0, Math.min(6, id.length()));
	}

	/** Sync-Form: {@code {type:"server", address}} bzw. {@code {type:"world", id, name?}}. */
	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		if (server()) {
			o.addProperty("address", id);
		} else {
			o.addProperty("id", id);
			if (!name.isEmpty()) o.addProperty("name", name);
		}
		return o;
	}

	/** Aus der Sync-Form; null, wenn ungültig. */
	public static NoteWorld fromJson(JsonElement e) {
		if (e == null || !e.isJsonObject()) return null;
		JsonObject o = e.getAsJsonObject();
		String type = str(o, "type");
		if (SERVER.equals(type)) {
			String addr = WaypointShare.normalizeServer(str(o, "address"));
			if (addr.isEmpty() || addr.length() > MAX_ADDRESS || !plainAddress(addr)) return null;
			return new NoteWorld(SERVER, addr, addr);
		}
		if (WORLD.equals(type)) {
			String id = str(o, "id");
			if (id == null || !hex16(id.toLowerCase(Locale.ROOT))) return null;
			return new NoteWorld(WORLD, id.toLowerCase(Locale.ROOT), Note.clean(str(o, "name"), MAX_NAME, false));
		}
		return null;
	}

	private static boolean plainAddress(String a) {
		for (int i = 0; i < a.length(); i++) {
			char c = a.charAt(i);
			if (c <= ' ' || c == '/' || c == '\\' || c == 0x7F) return false;
		}
		return true;
	}

	static boolean hex16(String s) {
		return Note.validId(s);
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	/** Dateiname des Buchs: erste 16 Hex von SHA-256 des Schlüssels. */
	public String fileName() {
		return WaypointShare.worldId(key()) + ".json";
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof NoteWorld && ((NoteWorld) o).key().equals(key());
	}

	@Override
	public int hashCode() {
		return key().hashCode();
	}

	@Override
	public String toString() {
		return key();
	}
}

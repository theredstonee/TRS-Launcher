package dev.theredstonee.trsclient.core.waypoint.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.SafeText;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON des Live-Teilens (API.md §41) – tolerant: unbekannte Felder werden ignoriert, fehlende/falsch getypte Felder
 * ergeben Standardwerte, kaputte Einträge fallen einzeln weg. Ohne Spiel, getestet.
 */
public final class LiveJson {
	/** Höchstens so viele geteilte Wegpunkte je Welt (Server-Grenze). */
	public static final int MAX_SHARED = 100;
	/** Höchstens so viele Freunde/Freigaben. */
	static final int MAX_OWNERS = 200;
	private static final java.util.regex.Pattern DIMENSION = java.util.regex.Pattern.compile("[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}");

	private LiveJson() {
	}

	// --- Lesen ---

	/** Objekt aus Text oder null. */
	public static JsonObject object(String text) {
		if (text == null || text.isEmpty()) return null;
		try {
			JsonElement e = new JsonParser().parse(text);
			return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	static JsonArray arr(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	static String str(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		if (e == null || !e.isJsonPrimitive()) return null;
		JsonPrimitive p = e.getAsJsonPrimitive();
		return p.isString() ? p.getAsString() : null;
	}

	/** Zahl oder NaN. */
	static double num(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		if (e == null || !e.isJsonPrimitive()) return Double.NaN;
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (!p.isNumber()) return Double.NaN;
		try {
			double d = p.getAsDouble();
			return Double.isInfinite(d) ? Double.NaN : d;
		} catch (NumberFormatException ex) {
			return Double.NaN;
		}
	}

	static boolean bool(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	/** Liste von UUIDs (normalisiert, ohne Doppelte, höchstens {@link #MAX_OWNERS}). */
	static Set<String> uuids(JsonArray a) {
		Set<String> out = new LinkedHashSet<String>();
		if (a == null) return out;
		for (JsonElement e : a) {
			if (out.size() >= MAX_OWNERS) break;
			String u = e != null && e.isJsonPrimitive() ? Uuids.normalize(e.getAsString()) : null;
			if (u == null && e != null && e.isJsonObject()) u = Uuids.normalize(str(e.getAsJsonObject(), "uuid"));
			if (u != null) out.add(u);
		}
		return out;
	}

	/** Farbe: Zahl 0xRRGGBB oder "#rrggbb"; sonst Standard. */
	static int color(JsonObject o, String key, int fallback) {
		double d = num(o, key);
		if (!Double.isNaN(d)) return ((int) (long) d) & 0xFFFFFF;
		String s = str(o, key);
		if (s != null && s.matches("#?[0-9a-fA-F]{6}")) return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16);
		return fallback;
	}

	/** Dimension als Namensraum-Kennung (auch {@code dim-1} usw.) oder null. */
	static String dimension(String raw) {
		String d = WaypointShare.canonicalDimension(raw);
		return d != null && DIMENSION.matcher(d).matches() ? d : null;
	}

	/** Ein geteilter Wegpunkt eines Freundes; null = unbrauchbar. */
	static FriendWaypoint waypoint(String owner, JsonElement e) {
		if (e == null || !e.isJsonObject()) return null;
		JsonObject o = e.getAsJsonObject();
		String id = str(o, "id");
		if (!Waypoint.validId(id)) return null;
		String name = str(o, "name");
		name = name == null ? null : SafeText.line(name, 32);
		if (name == null || name.trim().isEmpty()) return null;
		double x = num(o, "x"), y = num(o, "y"), z = num(o, "z");
		if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return null;
		if (Math.abs(x) > Chat.Waypoint.MAX_XZ || Math.abs(z) > Chat.Waypoint.MAX_XZ || y < Chat.Waypoint.MIN_Y || y > Chat.Waypoint.MAX_Y) {
			return null;
		}
		String dim = dimension(str(o, "dimension"));
		if (dim == null) return null;
		return new FriendWaypoint(owner, id, name.trim(), (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), dim,
				color(o, "color", 0x3D7BFF), bool(o, "death"));
	}

	/** Position ({@code position} von GET /friends bzw. das Ereignis selbst); null = unbrauchbar. */
	static FriendPosition position(JsonObject o, long now) {
		if (o == null) return null;
		double x = num(o, "x"), y = num(o, "y"), z = num(o, "z");
		if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return null;
		if (Math.abs(x) > Chat.Waypoint.MAX_XZ || Math.abs(z) > Chat.Waypoint.MAX_XZ || Math.abs(y) > 100_000) return null;
		String dim = dimension(str(o, "dimension"));
		if (dim == null) return null;
		double yaw = num(o, "yaw");
		if (Double.isNaN(yaw)) yaw = 0;
		return new FriendPosition(x, y, z, dim, (float) (((yaw % 360) + 360) % 360), now);
	}

	/** {@code GET /friends} → Besitzer (nur die, die mir freigegeben haben). */
	public static List<Owner> owners(String text, long now) {
		List<Owner> out = new ArrayList<Owner>();
		JsonArray list = arr(object(text), "owners");
		if (list == null) return out;
		Set<String> seen = new LinkedHashSet<String>();
		for (JsonElement e : list) {
			if (out.size() >= MAX_OWNERS) break;
			if (e == null || !e.isJsonObject()) continue;
			JsonObject o = e.getAsJsonObject();
			String uuid = Uuids.normalize(str(o, "uuid"));
			if (uuid == null || !seen.add(uuid)) continue;
			String name = str(o, "name");
			Owner owner = new Owner(uuid, name != null && name.matches("[A-Za-z0-9_]{1,16}") ? name : null);
			double rev = num(o, "rev");
			owner.rev = Double.isNaN(rev) ? -1 : (long) rev;
			List<FriendWaypoint> wps = new ArrayList<FriendWaypoint>();
			JsonArray arr = arr(o, "waypoints");
			if (arr != null) {
				Set<String> ids = new LinkedHashSet<String>();
				for (JsonElement w : arr) {
					if (wps.size() >= MAX_SHARED) break;
					FriendWaypoint fw = waypoint(uuid, w);
					if (fw != null && ids.add(fw.id)) wps.add(fw);
				}
			}
			owner.waypoints = Owner.freeze(wps);
			owner.position = position(obj(o, "position"), now);
			out.add(owner);
		}
		return out;
	}

	/** Peers aus {@code PUT /session} ({@code {peers:{send,receive}}}) oder dem Ereignis {@code wpshare_peers} ({@code {send,receive}}). */
	public static final class Peers {
		public final int send;
		public final Set<String> receive;

		Peers(int send, Set<String> receive) {
			this.send = send;
			this.receive = receive;
		}
	}

	public static Peers peers(JsonObject o) {
		if (o == null) return null;
		JsonObject p = obj(o, "peers");
		if (p == null) p = o;
		double send = num(p, "send");
		JsonArray receive = arr(p, "receive");
		if (Double.isNaN(send) && receive == null) return null;
		return new Peers(Double.isNaN(send) ? 0 : (int) Math.max(0, Math.min(10_000, send)), uuids(receive));
	}

	/** Freigaben ({@code GET /grants}). */
	public static final class Grants {
		public final Set<String> grants;
		public final Set<String> grantedToMe;

		Grants(Set<String> grants, Set<String> grantedToMe) {
			this.grants = grants;
			this.grantedToMe = grantedToMe;
		}
	}

	public static Grants grants(String text) {
		JsonObject o = object(text);
		if (o == null) return null;
		return new Grants(uuids(arr(o, "grants")), uuids(arr(o, "grantedToMe")));
	}

	/** {@code rev} einer Antwort (PUT/GET /waypoints) oder -1. */
	public static long rev(String text) {
		double d = num(object(text), "rev");
		return Double.isNaN(d) ? -1 : (long) d;
	}

	/** Aktueller {@code rev} aus einem 409-Fehler ({@code {"error":{"code":"rev_mismatch","rev":n}}}) oder -1. */
	public static long errorRev(String text) {
		JsonObject err = obj(object(text), "error");
		double d = num(err, "rev");
		if (Double.isNaN(d)) d = num(obj(err, "details"), "rev");
		return Double.isNaN(d) || d < 0 ? -1 : (long) d;
	}

	// --- Schreiben ---

	/** Ein eigener Wegpunkt in der Form der API (bereinigt); null = so nicht teilbar. */
	static JsonObject entry(Waypoint w, String fallbackDim) {
		if (w == null || w.temporary || !Waypoint.validId(w.id)) return null;
		String name = SafeText.line(w.name == null ? "" : w.name, 32);
		if (name == null || name.trim().isEmpty()) return null;
		if (Math.abs((long) w.x) > Chat.Waypoint.MAX_XZ || Math.abs((long) w.z) > Chat.Waypoint.MAX_XZ
				|| w.y < Chat.Waypoint.MIN_Y || w.y > Chat.Waypoint.MAX_Y) {
			return null;
		}
		String dim = w.dimension == null || w.dimension.isEmpty() ? fallbackDim : dimension(w.dimension);
		if (dim == null) return null;
		JsonObject o = new JsonObject();
		o.addProperty("id", w.id);
		o.addProperty("name", name.trim());
		o.addProperty("x", w.x);
		o.addProperty("y", w.y);
		o.addProperty("z", w.z);
		o.addProperty("dimension", dim);
		o.addProperty("color", w.color & 0xFFFFFF);
		if (w.death) o.addProperty("death", true);
		return o;
	}

	/**
	 * Die geteilte Liste einer Welt: markierte Wegpunkte, dazu der Todespunkt, wenn {@code shareDeath} an ist (höchstens
	 * einer, steht vorn). Höchstens {@link #MAX_SHARED}; Wegpunkte ohne Dimension gelten als Oberwelt.
	 */
	public static JsonArray sharedList(List<Waypoint> all, boolean shareDeath) {
		JsonArray out = new JsonArray();
		if (all == null) return out;
		String fallback = "minecraft:overworld";
		// Todespunkt zuerst (der jüngste), dann die anderen in Listen-Reihenfolge.
		Waypoint death = null;
		for (Waypoint w : all) {
			if (w != null && w.death && !w.temporary && (shareDeath || w.shared)) death = w;
		}
		if (death != null) {
			JsonObject e = entry(death, fallback);
			if (e != null) out.add(e);
		}
		for (Waypoint w : all) {
			if (out.size() >= MAX_SHARED) break;
			if (w == null || w.death || !w.shared) continue;
			JsonObject e = entry(w, fallback);
			if (e != null) out.add(e);
		}
		return out;
	}

	/** Körper für {@code PUT /waypoints}. {@code rev < 0} = ohne (erste Fassung bzw. unbekannt). */
	public static String putBody(LiveWorld world, long rev, JsonArray list) {
		JsonObject o = new JsonObject();
		o.add("world", world.toJson());
		if (rev >= 0) o.addProperty("rev", rev);
		o.add("waypoints", list);
		return o.toString();
	}

	/** Körper für {@code PUT /session}. */
	public static String sessionBody(LiveWorld world, String dimension) {
		JsonObject o = new JsonObject();
		o.add("world", world.toJson());
		o.addProperty("dimension", dimension);
		return o.toString();
	}

	/** Körper für {@code POST /position} (auf 1/10 Block bzw. ganze Grad gerundet). */
	public static String positionBody(double x, double y, double z, String dimension, float yaw) {
		JsonObject o = new JsonObject();
		o.addProperty("x", Math.round(x * 10) / 10.0);
		o.addProperty("y", Math.round(y * 10) / 10.0);
		o.addProperty("z", Math.round(z * 10) / 10.0);
		o.addProperty("dimension", dimension);
		o.addProperty("yaw", (double) Math.round((((yaw % 360) + 360) % 360)));
		return o.toString();
	}

	/** Eigene Liste vom Server ({@code GET /waypoints}) als Wegpunkte zum Wiederherstellen (alle als geteilt markiert). */
	public static List<Waypoint> ownList(String text) {
		List<Waypoint> out = new ArrayList<Waypoint>();
		JsonArray list = arr(object(text), "waypoints");
		if (list == null) return out;
		for (JsonElement e : list) {
			if (out.size() >= MAX_SHARED) break;
			FriendWaypoint fw = waypoint("self", e);
			if (fw == null) continue;
			Waypoint w = new Waypoint(fw.name, fw.x, fw.y, fw.z, fw.dimension, fw.color);
			w.id = fw.id;
			w.shared = true;
			w.death = fw.death;
			out.add(w);
		}
		return out;
	}
}

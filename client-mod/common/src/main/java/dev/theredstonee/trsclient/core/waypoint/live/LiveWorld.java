package dev.theredstonee.trsclient.core.waypoint.live;

import com.google.gson.JsonObject;
import dev.theredstonee.trsclient.core.social.SafeText;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;

import java.util.Locale;

/**
 * Welt fürs Live-Teilen (API.md §41): ein Server ({@code {type:"server", address}}, Adresse wie §18.10 normalisiert)
 * oder eine gehostete TRS-Welt ({@code {type:"world", id}}, 16 Hex – Host und Gäste rechnen denselben Wert aus dem
 * Host und dem Weltnamen des Raums). Einzelspielerwelten ohne Hosting und LAN/eigene Rechner gibt es hier nicht.
 */
public final class LiveWorld {
	public final String type;
	/** Nur Server: normalisierte Adresse. */
	public final String address;
	/** Nur gehostete Welt: 16 Hex. */
	public final String id;

	private LiveWorld(String type, String address, String id) {
		this.type = type;
		this.address = address;
		this.id = id;
	}

	/** Server; null, wenn die Adresse unbrauchbar ist oder auf einen lokalen/privaten Rechner zeigt. */
	public static LiveWorld server(String rawAddress) {
		String a = SafeText.serverAddress(WaypointShare.normalizeServer(rawAddress));
		if (a == null || local(a)) return null;
		return new LiveWorld("server", a, null);
	}

	/**
	 * Gehostete Welt: Kennung aus Host-UUID und Weltnamen des Raums (beide Seiten kennen beides; der Name reist nie
	 * zur API, nur der Hash). null, wenn etwas fehlt.
	 */
	public static LiveWorld hosted(String hostUuid, String worldName) {
		if (hostUuid == null || !hostUuid.matches("[0-9a-f]{32}") || worldName == null || worldName.trim().isEmpty()) return null;
		String id = WaypointShare.worldId("hosted:" + hostUuid + ":" + worldName.trim().toLowerCase(Locale.ROOT));
		return new LiveWorld("world", null, id);
	}

	/** Aus dem Weltschlüssel des Wegpunkt-Speichers ({@code mp:<adresse>}); Einzelspieler → null. */
	public static LiveWorld fromStoreKey(String key) {
		if (key == null || !key.startsWith("mp:")) return null;
		return server(key.substring(3));
	}

	/** Lokale/private Adressen (LAN, eigener PC): bei verschiedenen Freunden verschiedene Welten → nie teilen. */
	static boolean local(String address) {
		String host = address;
		int colon = host.lastIndexOf(':');
		if (colon > 0) host = host.substring(0, colon);
		if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".localhost")) return true;
		if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
		String[] p = host.split("\\.");
		int a = Integer.parseInt(p[0]);
		int b = Integer.parseInt(p[1]);
		return a == 127 || a == 10 || a == 0 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31)
				|| (a == 169 && b == 254) || (a == 100 && b >= 64 && b <= 127);
	}

	/** JSON-Objekt für die API. */
	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		if (address != null) o.addProperty("address", address);
		if (id != null) o.addProperty("id", id);
		return o;
	}

	/** Kompakter Text (für die Abfrage {@code ?world=}). */
	public String json() {
		return toJson().toString();
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof LiveWorld)) return false;
		LiveWorld w = (LiveWorld) o;
		return type.equals(w.type) && eq(address, w.address) && eq(id, w.id);
	}

	@Override
	public int hashCode() {
		return json().hashCode();
	}

	@Override
	public String toString() {
		return json();
	}

	private static boolean eq(String a, String b) {
		return a == null ? b == null : a.equals(b);
	}
}

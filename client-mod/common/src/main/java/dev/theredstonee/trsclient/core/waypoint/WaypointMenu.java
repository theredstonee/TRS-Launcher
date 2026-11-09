package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.live.LiveShare;

import java.util.ArrayList;
import java.util.List;

/**
 * Aktionen an einem Wegpunkt bzw. einer Stelle – dieselben auf der Weltkarte und in der Wegpunkt-Liste. Kennungen sind
 * zugleich die Sprachschlüssel {@code map.menu.<id>}.
 */
public final class WaypointMenu {
	public static final String EDIT = "edit";
	public static final String HIDE = "hide";
	public static final String SHOW = "show";
	public static final String DELETE = "delete";
	public static final String TELEPORT = "teleport";
	public static final String TARGET = "target";
	public static final String UNTARGET = "untarget";
	public static final String COPY = "copy";
	public static final String CHAT = "chat";
	public static final String SHARE = "share";
	public static final String SHARE_FRIENDS = "shareFriends";
	public static final String UNSHARE_FRIENDS = "unshareFriends";

	private WaypointMenu() {
	}

	/** Einträge für einen eigenen Wegpunkt. */
	public static List<String> forWaypoint(MapEngine e, Waypoint w) {
		List<String> items = new ArrayList<String>();
		MapPlatform p = e.platform();
		WaypointStore store = p == null ? null : p.waypoints();
		String key = p == null ? "" : p.waypointWorldKey();
		items.add(EDIT);
		items.add(w.visible ? HIDE : SHOW);
		if (WaypointTeleport.possible(e.modules(), p, w.dimension)) items.add(TELEPORT);
		items.add(store != null && store.target(key) == w ? UNTARGET : TARGET);
		if (p != null && p.chatBridge()) {
			items.add(COPY);
			items.add(CHAT);
		}
		items.add(SHARE);
		if (LiveShare.ui() != null) items.add(w.shared ? UNSHARE_FRIENDS : SHARE_FRIENDS);
		items.add(DELETE);
		return items;
	}

	/**
	 * Führt eine Aktion aus (nicht {@link #EDIT} und {@link #SHARE}: Dialog bzw. Teilen-Fenster sind Sache der Oberfläche).
	 *
	 * @return Text für einen Hinweis oder null
	 */
	public static String run(String id, MapEngine e, Waypoint w) {
		MapPlatform p = e.platform();
		if (p == null) return null;
		WaypointStore store = p.waypoints();
		String key = p.waypointWorldKey();
		if (HIDE.equals(id) || SHOW.equals(id)) {
			w.visible = SHOW.equals(id);
			e.waypointEdited();
		} else if (DELETE.equals(id)) {
			e.removeWaypoint(w);
		} else if (TELEPORT.equals(id)) {
			if (!WaypointTeleport.send(e.modules(), p, w.x, w.y, w.z, w.dimension)) return I18n.tr("map.teleport.denied");
		} else if (TARGET.equals(id) || UNTARGET.equals(id)) {
			if (store != null) {
				store.setTarget(key, TARGET.equals(id) ? w : null, false);
				e.waypointEdited();
			}
			return TARGET.equals(id) ? I18n.tr("map.target.set", w.name) : null;
		} else if (COPY.equals(id)) {
			String text = coordinates(w.x, w.y, w.z);
			p.copyToClipboard(text);
			return I18n.tr("waypoint.card.copied", text);
		} else if (CHAT.equals(id)) {
			p.sendChat(chatLine(w.name, w.x, w.y, w.z, w.dimension, p.dimension()));
			return I18n.tr("map.chat.sent");
		} else if (SHARE_FRIENDS.equals(id) || UNSHARE_FRIENDS.equals(id)) {
			w.shared = SHARE_FRIENDS.equals(id);
			e.waypointEdited();
			return I18n.tr(w.shared ? "map.friend.sharedNow" : "map.friend.unsharedNow", w.name);
		}
		return null;
	}

	/** Koordinaten zum Kopieren. */
	public static String coordinates(int x, int y, int z) {
		return x + " " + y + " " + z;
	}

	/**
	 * Zeile für den Spiel-Chat: Name und Koordinaten, in einer anderen Dimension mit deren Namen – die Koordinaten kann
	 * jeder TRS Client anklicken. Keine Formatierungszeichen, keine Zeilenumbrüche.
	 */
	public static String chatLine(String name, int x, int y, int z, String dim, String currentDim) {
		String clean = name == null ? "" : name.replace((char) 0xA7, ' ').replaceAll("[\\p{Cntrl}]", " ").trim();
		// Ein führender Schrägstrich würde aus der Nachricht einen Befehl machen.
		while (clean.startsWith("/")) clean = clean.substring(1).trim();
		String line = (clean.isEmpty() ? "" : clean + ": ") + coordinates(x, y, z);
		if (dim != null && !dim.isEmpty() && !dim.equals(currentDim)) line += " (" + WorldMapUi.dimensionName(dim) + ")";
		return line;
	}

	/** Modul-Hilfe: Sortierung für neue Listen. */
	public static WaypointSort sort(TrsModules m) {
		return m == null ? WaypointSort.DISTANCE : m.waypointSort.get();
	}
}

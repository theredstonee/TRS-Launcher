package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.i18n.I18n;

/** Eine Gruppe von Wegpunkten in einer Welt („Basis“, „Farmen“), als Ganzes ein-/ausblendbar (Gson-DTO). */
public final class WaypointSet {
	public String id;
	/** Eigener Name; die beiden festen Sets haben keinen (kommt aus der Sprachdatei). */
	public String name = "";
	public boolean visible = true;

	/** Für Gson. */
	public WaypointSet() {
	}

	public WaypointSet(String id, String name) {
		this.id = id;
		this.name = name == null ? "" : name;
	}

	/** Eines der festen Sets, die sich weder umbenennen noch löschen lassen? */
	public boolean builtIn() {
		return WaypointStore.SET_DEFAULT.equals(id) || WaypointStore.SET_DEATHS.equals(id);
	}

	/** Name zum Anzeigen. */
	public String label() {
		if (WaypointStore.SET_DEFAULT.equals(id)) return I18n.tr("waypoints.set.default");
		if (WaypointStore.SET_DEATHS.equals(id)) return I18n.tr("waypoints.set.deaths");
		return name == null || name.isEmpty() ? id : name;
	}
}

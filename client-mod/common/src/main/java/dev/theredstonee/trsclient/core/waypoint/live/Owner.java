package dev.theredstonee.trsclient.core.waypoint.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ein Freund, der mir in der aktuellen Welt freigegeben hat: seine geteilten Wegpunkte und seine Live-Position. */
public final class Owner {
	public final String uuid;
	String name;
	long rev = -1;
	List<FriendWaypoint> waypoints = Collections.emptyList();
	FriendPosition position;
	/** Lokal ausgeblendet (Weltkarte, Reiter „Freunde“). */
	boolean hidden;

	Owner(String uuid, String name) {
		this.uuid = uuid;
		this.name = name;
	}

	public String name() {
		return name == null || name.isEmpty() ? uuid.substring(0, 8) : name;
	}

	public long rev() {
		return rev;
	}

	public List<FriendWaypoint> waypoints() {
		return waypoints;
	}

	public FriendPosition position() {
		return position;
	}

	public boolean hidden() {
		return hidden;
	}

	/** Kopie für die Anzeige (Liste ist unveränderlich). */
	static List<FriendWaypoint> freeze(List<FriendWaypoint> list) {
		return list == null || list.isEmpty() ? Collections.<FriendWaypoint>emptyList()
				: Collections.unmodifiableList(new ArrayList<FriendWaypoint>(list));
	}
}

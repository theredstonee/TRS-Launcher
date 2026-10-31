package dev.theredstonee.trsclient.core.waypoint.live;

import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.Rooms;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.online.Uuids;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;

/**
 * Spielzugriff des Live-Teilens über die Karte ({@link MapPlatform} – gibt es in jedem Loader, auch ohne sichtbare
 * Karte) und das Welt-Hosting (gehostete Welten als Host oder Gast).
 */
final class GameEnv implements LiveShare.Env {
	private static MapPlatform p() {
		MapEngine e = MapEngine.get();
		return e == null ? null : e.platform();
	}

	@Override
	public boolean inWorld() {
		MapPlatform p = p();
		return p != null && p.inWorld();
	}

	@Override
	public String storeKey() {
		MapPlatform p = p();
		String k = p == null ? null : p.waypointWorldKey();
		return k == null ? "" : k;
	}

	@Override
	public String dimension() {
		MapPlatform p = p();
		return p == null ? null : p.dimension();
	}

	@Override
	public double x() {
		MapPlatform p = p();
		return p == null ? 0 : p.x();
	}

	@Override
	public double y() {
		MapPlatform p = p();
		return p == null ? 0 : p.y();
	}

	@Override
	public double z() {
		MapPlatform p = p();
		return p == null ? 0 : p.z();
	}

	@Override
	public float yaw() {
		MapPlatform p = p();
		return p == null ? 0f : p.yaw();
	}

	@Override
	public WaypointStore store() {
		MapPlatform p = p();
		return p == null ? null : p.waypoints();
	}

	@Override
	public void waypointsChanged() {
		MapPlatform p = p();
		if (p != null) p.waypointsChanged();
	}

	@Override
	public LiveWorld hostedWorld(String self) {
		Hosting h = Hosting.current();
		if (h == null) return null;
		try {
			// Gast in einer TRS-Welt: Host + Weltname des Raums (die Adresse ist nur ein lokaler Tunnel).
			Rooms.Room guest = h.guestRoom();
			if (h.guestState() == Hosting.GuestState.CONNECTED && guest != null) {
				return LiveWorld.hosted(Uuids.normalize(guest.hostUuid), guest.name);
			}
			// Host einer geöffneten Welt (nur solange man selbst in der Einzelspielerwelt ist).
			Rooms.Room room = h.room();
			if (h.hostState() == Hosting.HostState.OPEN && room != null && storeKey().startsWith("sp:")) {
				String host = Uuids.normalize(room.hostUuid);
				return LiveWorld.hosted(host != null ? host : self, room.name);
			}
		} catch (RuntimeException ignored) {
			// Hosting darf das Teilen nie stören.
		}
		return null;
	}
}

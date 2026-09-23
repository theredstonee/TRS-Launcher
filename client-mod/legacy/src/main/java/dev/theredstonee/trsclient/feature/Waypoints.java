package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import net.minecraft.client.entity.EntityPlayerSP;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Verwaltet die Wegpunkte: Welt erkennen, anlegen, Todespunkt setzen, speichern.
 * Die Datei liegt neben der Config ({@code config/trsclient-waypoints.json}).
 */
public final class Waypoints {
	private final TrsModules modules;
	private final WaypointStore store;
	/** Welt/Server, zu der die aktuell sichtbaren Wegpunkte gehören. */
	private String worldKey = "";
	private boolean wasDead;

	public Waypoints(TrsModules modules, Path file) {
		this.modules = modules;
		this.store = new WaypointStore(file);
		this.store.load();
	}

	public WaypointStore store() {
		return store;
	}

	/** Schlüssel der aktuellen Welt ("" = keine). */
	public String worldKey() {
		return worldKey;
	}

	/** Wegpunkte der aktuellen Welt (alle, auch unsichtbare). */
	public List<Waypoint> all() {
		return worldKey.isEmpty() ? Collections.<Waypoint>emptyList() : store.all(worldKey);
	}

	/** Sichtbare Wegpunkte der aktuellen Welt und Dimension. */
	public List<Waypoint> visible() {
		return worldKey.isEmpty() ? Collections.<Waypoint>emptyList() : store.visible(worldKey, Mc.dimensionId());
	}

	/** Einmal je Client-Tick: Welt erkennen und Todespunkt setzen. */
	public void tick() {
		EntityPlayerSP player = Mc.player();
		if (player == null || Mc.world() == null) {
			worldKey = "";
			wasDead = false;
			return;
		}
		if (worldKey.isEmpty()) worldKey = currentWorldKey();

		boolean dead = player.getHealth() <= 0;
		if (dead && !wasDead && modules.waypoints.isEnabled() && modules.waypointDeath.get()) {
			store.setDeath(worldKey, floor(player.posX), floor(player.posY), floor(player.posZ),
					Mc.dimensionId(), 0xE0281E);
			save();
			Mc.actionBar(I18n.tr("waypoint.deathSet"));
		}
		wasDead = dead;
	}

	/** Legt einen Wegpunkt an der Spielerposition an. */
	public Waypoint create(String name, int color) {
		EntityPlayerSP player = Mc.player();
		if (player == null) return null;
		if (worldKey.isEmpty()) worldKey = currentWorldKey();
		Waypoint waypoint = new Waypoint(name, floor(player.posX), floor(player.posY), floor(player.posZ),
				Mc.dimensionId(), color);
		store.add(worldKey, waypoint);
		save();
		return waypoint;
	}

	/** Legt einen Wegpunkt an einer festen Position an (Selbsttest, Import). */
	public Waypoint createAt(String name, int x, int y, int z, int color) {
		if (worldKey.isEmpty()) worldKey = currentWorldKey();
		Waypoint waypoint = new Waypoint(name, x, y, z, Mc.dimensionId(), color);
		store.add(worldKey, waypoint);
		save();
		return waypoint;
	}

	public void remove(Waypoint waypoint) {
		store.remove(worldKey, waypoint);
		save();
	}

	/** Nach Änderungen an Name/Farbe/Sichtbarkeit. */
	public void save() {
		store.touch();
		try {
			store.saveIfDirty();
		} catch (IOException e) {
			TrsClient.LOGGER.error("Wegpunkte konnten nicht gespeichert werden: " + store.file(), e);
		}
	}

	/** Welt verlassen: beim nächsten Betreten neu bestimmen. */
	public void onDisconnect() {
		worldKey = "";
		wasDead = false;
	}

	private static String currentWorldKey() {
		String address = Mc.serverAddress();
		if (address != null) return WaypointStore.serverKey(address);
		String level = Mc.levelName();
		return WaypointStore.singleplayerKey(level == null ? "?" : level);
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}
}

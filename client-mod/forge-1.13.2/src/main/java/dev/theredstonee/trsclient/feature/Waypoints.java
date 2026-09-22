package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.integrated.IntegratedServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Verwaltet die Wegpunkte: Welt erkennen, anlegen, Todespunkt setzen, speichern.
 * Die Datei liegt neben der Config ({@code config/trsclient-waypoints.json}).
 *
 * <p>1.13.2: die Dimension kommt über {@code World#getDimension().getType().getId()}
 * (bis 1.12.2 war das {@code world.provider.getDimension()}).
 */
public final class Waypoints {
	private final Minecraft mc = Minecraft.getInstance();
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

	/** Kennung der Dimension ("dim0", "dim-1" …) – Wegpunkte gelten nur in ihrer Dimension. */
	public String dimensionId() {
		if (mc.world == null) return "";
		return "dim" + mc.world.getDimension().getType().getId();
	}

	/** Wegpunkte der aktuellen Welt (alle, auch unsichtbare). */
	public List<Waypoint> all() {
		return worldKey.isEmpty() ? Collections.<Waypoint>emptyList() : store.all(worldKey);
	}

	/** Sichtbare Wegpunkte der aktuellen Welt und Dimension. */
	public List<Waypoint> visible() {
		return worldKey.isEmpty() ? Collections.<Waypoint>emptyList() : store.visible(worldKey, dimensionId());
	}

	/** Einmal je Client-Tick: Welt erkennen und Todespunkt setzen. */
	public void tick() {
		EntityPlayerSP player = mc.player;
		if (player == null || mc.world == null) {
			worldKey = "";
			wasDead = false;
			return;
		}
		if (worldKey.isEmpty()) worldKey = currentWorldKey();

		boolean dead = player.getHealth() <= 0;
		if (dead && !wasDead && modules.waypoints.isEnabled() && modules.waypointDeath.get()) {
			store.setDeath(worldKey, floor(player.posX), floor(player.posY), floor(player.posZ),
					dimensionId(), 0xE0281E);
			save();
			if (mc.ingameGUI != null) mc.ingameGUI.setOverlayMessage("Todespunkt gesetzt", false);
		}
		wasDead = dead;
	}

	/** Legt einen Wegpunkt an der Spielerposition an. */
	public Waypoint create(String name, int color) {
		EntityPlayerSP player = mc.player;
		if (player == null) return null;
		if (worldKey.isEmpty()) worldKey = currentWorldKey();
		Waypoint waypoint = new Waypoint(name, floor(player.posX), floor(player.posY), floor(player.posZ),
				dimensionId(), color);
		store.add(worldKey, waypoint);
		save();
		return waypoint;
	}

	/** Legt einen Wegpunkt an einer festen Position an (Selbsttest, Import). */
	public Waypoint createAt(String name, int x, int y, int z, int color) {
		if (worldKey.isEmpty()) worldKey = currentWorldKey();
		Waypoint waypoint = new Waypoint(name, x, y, z, dimensionId(), color);
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

	private String currentWorldKey() {
		ServerData data = mc.getCurrentServerData();
		if (data != null && data.serverIP != null) return WaypointStore.serverKey(data.serverIP);
		IntegratedServer server = mc.getIntegratedServer();
		String level = server == null ? null : server.getFolderName();
		return WaypointStore.singleplayerKey(level == null ? "?" : level);
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}
}

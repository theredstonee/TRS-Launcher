package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

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
	private final dev.theredstonee.trsclient.core.waypoint.WaypointGuide guide = new dev.theredstonee.trsclient.core.waypoint.WaypointGuide();

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
	/** Sichtbare Wegpunkte, je Tick einmal gefiltert (HUD und Minimap fragen je Bild). */
	private List<Waypoint> visibleCache = Collections.<Waypoint>emptyList();
	private boolean visibleStale = true;

	public List<Waypoint> visible() {
		if (worldKey.isEmpty()) return Collections.<Waypoint>emptyList();
		if (visibleStale) {
			visibleCache = store.visible(worldKey, Mc.dimensionId());
			visibleStale = false;
		}
		return visibleCache;
	}

	/** Einmal je Client-Tick: Welt erkennen und Todespunkt setzen. */
	public void tick(Minecraft mc) {
		visibleStale = true;
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			worldKey = "";
			guide.reset();
			return;
		}
		if (worldKey.isEmpty()) worldKey = currentWorldKey();

		boolean record = modules.waypointDeath.get()
				&& (modules.waypoints.isEnabled() || modules.minimap.isEnabled() || modules.worldMap.isEnabled());
		dev.theredstonee.trsclient.core.waypoint.WaypointGuide.Event event = guide.tick(store, worldKey, Mc.x(player), Mc.y(player), Mc.z(player), Mc.dimensionId(),
				player.getHealth() <= 0, record, modules.waypointDeathKeep.getInt(), modules.waypointDeathGuide.get(), modules.waypointDeathRemove.get());
		if (event.changed) save();
		if (event.message != null) notice(event.message);
	}

	/** Legt einen Wegpunkt an der Spielerposition an. */
	public Waypoint create(String name, int color) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) return null;
		if (worldKey.isEmpty()) worldKey = currentWorldKey();
		Waypoint waypoint = new Waypoint(name, floor(Mc.x(player)), floor(Mc.y(player)), floor(Mc.z(player)),
				Mc.dimensionId(), color);
		store.add(worldKey, waypoint);
		visibleStale = true;
		save();
		return waypoint;
	}

	/** Schnell-Wegpunkt am Standort, ohne Dialog (Name und Farbe laufen durch, Set wie zuletzt gewählt). */
	public Waypoint createQuick() {
		int n = all().size();
		Waypoint waypoint = create(I18n.tr("waypoint.suggest", n + 1), dev.theredstonee.trsclient.core.waypoint.WaypointDialog.COLORS[n % dev.theredstonee.trsclient.core.waypoint.WaypointDialog.COLORS.length]);
		if (waypoint == null) return null;
		waypoint.set = WaypointStore.fieldValue(store.lastSet(worldKey));
		save();
		notice(I18n.tr("waypoint.quickSet", waypoint.name));
		return waypoint;
	}

	/** Das aktuelle Ziel dieser Welt oder null. */
	public Waypoint target() {
		return worldKey.isEmpty() ? null : store.target(worldKey);
	}

	private static void notice(String text) {
		Mc.actionBar(Mc.text(text));
	}

	public void remove(Waypoint waypoint) {
		store.remove(worldKey, waypoint);
		visibleStale = true;
		save();
	}

	/** Nach Änderungen an Name/Farbe/Sichtbarkeit. */
	public void save() {
		visibleStale = true;
		store.touch();
		try {
			store.saveIfDirty();
		} catch (IOException e) {
			TrsClient.LOGGER.error("Wegpunkte konnten nicht gespeichert werden: {}", store.file(), e);
		}
	}

	/** Welt verlassen: beim nächsten Betreten neu bestimmen. */
	public void onDisconnect() {
		worldKey = "";
		guide.reset();
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

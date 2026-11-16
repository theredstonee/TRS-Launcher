package dev.theredstonee.trsclient.feature;

import dev.theredstonee.trsclient.core.i18n.I18n;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import dev.theredstonee.trsclient.core.waypoint.ProxyWorlds;
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
	private final Path proxyFile;
	private ProxyWorlds proxyWorlds;
	/** Welt/Server, zu der die aktuell sichtbaren Wegpunkte gehören. */
	private String worldKey = "";
	private boolean proxyWarningShown;
	private final dev.theredstonee.trsclient.core.waypoint.WaypointGuide guide = new dev.theredstonee.trsclient.core.waypoint.WaypointGuide();

	public Waypoints(TrsModules modules, Path file) {
		this.modules = modules;
		this.store = new WaypointStore(file);
		this.store.load();
		this.proxyFile = file.resolveSibling("trsclient-proxy-worlds.json");
	}

	public ProxyWorlds proxyWorlds() {
		return proxyWorlds;
	}

	public String proxyServerAddress() {
		return Mc.world() == null || Mc.player() == null || Mc.levelName() != null ? null : Mc.serverAddress();
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
	public void tick() {
		if (proxyWorlds == null) proxyWorlds = ProxyWorlds.open(proxyFile);
		visibleStale = true;
		EntityPlayerSP player = Mc.player();
		if (player == null || Mc.world() == null) {
			onDisconnect();
			return;
		}
		if (!proxyWarningShown && !proxyWorlds.writable() && proxyServerAddress() != null) {
			proxyWarningShown = true;
			notice(I18n.tr("proxy.error"));
		}
		updateWorldKey();

		boolean record = modules.waypointDeath.get()
				&& (modules.waypoints.isEnabled() || modules.minimap.isEnabled() || modules.worldMap.isEnabled());
		dev.theredstonee.trsclient.core.waypoint.WaypointGuide.Event event = guide.tick(store, worldKey, player.posX, player.posY, player.posZ, Mc.dimensionId(),
				player.getHealth() <= 0, record, modules.waypointDeathKeep.getInt(), modules.waypointDeathGuide.get(), modules.waypointDeathRemove.get());
		if (event.changed) save();
		if (event.message != null) notice(event.message);
	}

	/** Legt einen Wegpunkt an der Spielerposition an. */
	public Waypoint create(String name, int color) {
		EntityPlayerSP player = Mc.player();
		if (player == null) return null;
		updateWorldKey();
		Waypoint waypoint = new Waypoint(name, floor(player.posX), floor(player.posY), floor(player.posZ),
				Mc.dimensionId(), color);
		store.add(worldKey, waypoint);
		visibleStale = true;
		save();
		return waypoint;
	}

	/** Legt einen Wegpunkt an einer festen Position an (Selbsttest, Import). */
	public Waypoint createAt(String name, int x, int y, int z, int color) {
		updateWorldKey();
		Waypoint waypoint = new Waypoint(name, x, y, z, Mc.dimensionId(), color);
		store.add(worldKey, waypoint);
		save();
		return waypoint;
	}

	/** Schnell-Wegpunkt am Standort, ohne Dialog (Name und Farbe laufen durch, Set wie zuletzt gewählt). */
	public Waypoint createQuick() {
		if (Mc.player() != null && Mc.world() != null) updateWorldKey();
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
		Mc.actionBar(text);
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
			TrsClient.LOGGER.error("Wegpunkte konnten nicht gespeichert werden: " + store.file(), e);
		}
	}

	/** Welt verlassen: beim nächsten Betreten neu bestimmen. */
	public void onDisconnect() {
		if (!worldKey.isEmpty()) {
			try {
				store.saveIfDirty();
			} catch (IOException e) {
				TrsClient.LOGGER.error("Wegpunkte konnten nicht gespeichert werden: " + store.file(), e);
			}
		}
		worldKey = "";
		visibleCache = Collections.<Waypoint>emptyList();
		visibleStale = true;
		guide.reset();
	}

	private void updateWorldKey() {
		if (proxyWorlds == null) proxyWorlds = ProxyWorlds.open(proxyFile);
		String next = currentWorldKey();
		if (next.equals(worldKey)) return;
		try {
			store.saveIfDirty();
		} catch (IOException e) {
			TrsClient.LOGGER.error("Wegpunkte konnten nicht gespeichert werden: " + store.file(), e);
		}
		worldKey = next;
		visibleCache = Collections.<Waypoint>emptyList();
		visibleStale = true;
		guide.reset();
	}

	private String currentWorldKey() {
		String address = proxyServerAddress();
		if (address != null) return proxyWorlds.resolve(address, ServerRules.backendId());
		String level = Mc.levelName();
		return WaypointStore.singleplayerKey(level == null ? "?" : level);
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}
}

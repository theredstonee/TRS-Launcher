package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.module.TrsModules;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Selbsttest der neuen Wegpunkte im Spiel ({@code -PtrsAutotestOnly=waypoints}, auch Teil von {@code maps}), für alle
 * Versionen gleich: legt Sets, Symbole und Todespunkte an, zeigt Ziel mit Lichtstrahl, Beschriftung beim Draufschauen,
 * Rand-Pfeil, Karte, Rechtsklick-Menüs, Dialog und Liste, teleportiert wirklich per Befehl (auch über Dimensionen) und
 * prüft Schnell-Taste und Weg zurück. Prüfungen stehen als „OK“/„FEHLER“ im Protokoll. Nur für Autotests.
 */
public final class WaypointSelfTest {
	/** Was der Baum für den Test tun kann (Spiel-Thread). */
	public interface Host {
		void shot(String name);

		/** Befehl über den eingebauten Server (Testaufbau, nicht der Weg des Teleports). */
		void command(String command);

		void openWorldMap();

		WorldMapUi worldMap();

		void openList();

		WaypointsUi list();

		void closeScreen();

		/** Wiederbeleben nach dem Tod. */
		void respawn();

		/** Schnell-Wegpunkt wie mit der Taste. */
		void quickWaypoint();
	}

	private final Host host;
	private final Consumer<String> log;
	private int phase;
	private int failures;
	private int startX, startY, startZ;
	private Waypoint bed, chest, letters, far, wheat, portal, hidden;
	private WaypointSet setBase, setFarms;
	private boolean behindDone;

	public WaypointSelfTest(Host host, Consumer<String> log) {
		this.host = host;
		this.log = log;
	}

	private void check(String what, boolean ok) {
		log.accept((ok ? "OK: " : "FEHLER: ") + what);
		if (!ok) failures++;
	}

	private static Waypoint add(WaypointStore s, String key, String name, int x, int y, int z, String dim, int color, WaypointSet set, String symbol, String mark) {
		Waypoint w = new Waypoint(name, x, y, z, dim, color);
		w.symbol = symbol;
		w.mark = mark;
		w.set = set == null ? "" : set.id;
		return s.add(key, w);
	}

	private static String netherOf(String dim) {
		return dim.startsWith("dim") ? "dim-1" : "minecraft:the_nether";
	}

	/** Ein Schritt; Rückgabe = Ticks bis zum nächsten, -1 = fertig. */
	public int step(int bx, int by, int bz) {
		MapEngine e = MapEngine.get();
		MapPlatform p = e.platform();
		TrsModules m = e.modules();
		WaypointStore s = p.waypoints();
		String key = p.waypointWorldKey();
		String dim = p.dimension();
		switch (phase++) {
			case 0: {
				m.waypoints.setEnabled(true);
				m.minimap.setEnabled(true);
				m.worldMap.setEnabled(true);
				m.waypointLabels.set(WaypointLabels.LOOK);
				m.waypointBeam.set(true);
				m.waypointDistance.set(true);
				m.waypointRange.set(0);
				startX = bx;
				startY = by;
				startZ = bz;
				// Die Testwelt wird wiederverwendet: alles von vorher weg.
				for (Waypoint old : new ArrayList<Waypoint>(s.all(key))) s.remove(key, old);
				for (WaypointSet old : s.sets(key)) {
					if (!old.builtIn()) s.removeSet(key, old.id);
					else s.setSetVisible(key, old.id, true);
				}
				setBase = s.addSet(key, "Basis");
				setFarms = s.addSet(key, "Farmen");
				bed = add(s, key, "Bett", bx - 8, by + 2, bz + 30, dim, 0xE0281E, setBase, "bed", "");
				chest = add(s, key, "Truhe", bx + 10, by + 2, bz + 42, dim, 0xFFB84D, setBase, "chest", "");
				add(s, key, "Basis", bx + 26, by + 4, bz + 60, dim, 0x3DDC84, setBase, "home", "");
				letters = add(s, key, "Lager Nord", bx - 24, by + 1, bz + 70, dim, 0x7A3DFF, null, "", "LN");
				add(s, key, "Schwert", bx + 2, by + 2, bz + 18, dim, 0x4DD8E0, null, "sword", "");
				far = add(s, key, "Fernes Dorf", bx + 40, by, bz + 400, dim, 0xFF6FB5, null, "", "");
				wheat = add(s, key, "Weizen", bx - 40, by + 1, bz + 55, dim, 0xF2E14C, setFarms, "farm", "");
				hidden = add(s, key, "Versteckt", bx + 1, by + 1, bz + 25, dim, 0xFFFFFF, null, "key", "");
				hidden.visible = false;
				portal = add(s, key, "Nether-Tor", bx / 8 + 3, 70, bz / 8 + 3, netherOf(dim), 0xB07CFF, null, "portal", "");
				s.addDeath(key, bx + 14, by + 1, bz + 22, dim, 0xE0281E, 5);
				s.setTarget(key, chest, false);
				e.waypointEdited();
				check("Alte Wegpunkte ohne Set: Standard-Set vorhanden", WaypointStore.SET_DEFAULT.equals(s.sets(key).get(0).id));
				// Über dem Boden, Blick nach Süden: Die Wegpunkte liegen vor der Kamera.
				host.command("tp @p " + bx + " " + (by + 8) + " " + bz + " 0 10");
				return 60;
			}
			case 1:
				check("Versteckter Wegpunkt zählt nicht als sichtbar", !s.visible(key, dim).contains(hidden));
				host.shot("wp-world-target");
				// Auf das Bett schauen: Name und Entfernung erscheinen.
				host.command("tp @p " + startX + " " + (startY + 8) + " " + startZ + " "
						+ yawTo(startX, startZ, bed.x, bed.z) + " " + pitchTo(p, bed));
				return 30;
			case 2:
				host.shot("wp-world-look");
				// Vom Ziel wegdrehen: Pfeil am Rand (einmal seitlich, einmal im Rücken).
				host.command("tp @p " + startX + " " + (startY + 8) + " " + startZ + " 100 0");
				return 25;
			case 3:
				host.shot("wp-world-edge");
				host.command("tp @p " + startX + " " + (startY + 8) + " " + startZ + " 180 0");
				phase = 22;
				return 25;
			case 22:
				if (behindDone) {
					// Nach der Live-Prüfung (Fall 21): Liste auf der Karte zeigen.
					host.worldMap().testSidebar(true, "");
					phase = 24;
					return 20;
				}
				behindDone = true;
				host.shot("wp-world-behind");
				host.command("tp @p " + startX + " " + (startY + 8) + " " + startZ + " 0 10");
				host.openWorldMap();
				phase = 4;
				return 30;
			case 4: {
				WorldMapUi map = host.worldMap();
				map.setScaleNow(4f);
				map.centerOn(startX + 4, startZ + 40);
				map.testSidebar(true, "");
				return 20;
			}
			case 5: {
				WorldMapUi map = host.worldMap();
				host.shot("wp-map-list");
				map.testSidebar(false, "");
				map.waypointMenu(chest, 90, 40);
				List<String> items = map.testMenuItems();
				check("Menü Wegpunkt: Bearbeiten, Ausblenden, Löschen, Ziel, Koordinaten, Teilen " + items,
						items.contains(WaypointMenu.EDIT) && items.contains(WaypointMenu.HIDE) && items.contains(WaypointMenu.DELETE)
								&& items.contains(WaypointMenu.UNTARGET) && items.contains(WaypointMenu.COPY) && items.contains(WaypointMenu.CHAT));
				check("Menü Wegpunkt: Teleport nur mit Rechten (Zugriff " + p.commandAccess() + ")",
						items.contains(WaypointMenu.TELEPORT) == ((p.commandAccess() & WaypointTeleport.TP) != 0));
				return 10;
			}
			case 6: {
				WorldMapUi map = host.worldMap();
				host.shot("wp-menu-waypoint");
				map.testSpotMenu(90, 40, startX + 6, startZ + 12);
				List<String> items = map.testMenuItems();
				check("Menü freie Stelle: Erstellen, Koordinaten, Chat " + items,
						items.contains("create") && items.contains(WaypointMenu.COPY) && items.contains(WaypointMenu.CHAT));
				boolean rights = (p.commandAccess() & WaypointTeleport.TP) != 0;
				check("Menü freie Stelle: Teleport nur mit Rechten und bekannter Höhe (Zugriff " + p.commandAccess() + ")", items.contains("teleportHere") == rights);
				return 10;
			}
			case 7: {
				WorldMapUi map = host.worldMap();
				host.shot("wp-menu-spot");
				map.testMenuAction("x");
				map.testEditDialog(bed);
				return 10;
			}
			case 8:
				host.shot("wp-dialog");
				host.worldMap().testColorPicker();
				phase = 25;
				return 6;
			case 25:
				host.shot("wp-dialog-color");
				host.closeScreen();
				host.openList();
				phase = 9;
				return 20;
			case 9: {
				WaypointsUi list = host.list();
				host.shot("wp-list");
				check("Liste zeigt alle Wegpunkte (" + list.testRows().size() + ")", list.testRows().size() == s.all(key).size());
				list.testFilter("", setBase.id, WaypointSort.NAME);
				return 10;
			}
			case 10: {
				WaypointsUi list = host.list();
				host.shot("wp-list-set");
				check("Filter Set + Sortierung Name: " + list.testRows(), list.testRows().size() == 3 && "Basis".equals(list.testRows().get(0)));
				list.testRowMenu(chest, 150, 60);
				return 10;
			}
			case 11: {
				WaypointsUi list = host.list();
				host.shot("wp-list-menu");
				list.testFilter("", dev.theredstonee.trsclient.core.map.WaypointFilter.ALL, WaypointSort.DISTANCE);
				list.testEdit(letters);
				return 10;
			}
			case 12: {
				host.shot("wp-list-dialog");
				s.setSetVisible(key, setFarms.id, false);
				check("Set ausblenden versteckt seine Wegpunkte", !s.visible(key, dim).contains(wheat) && !e.waypointShown(wheat));
				s.setSetVisible(key, setFarms.id, true);
				host.closeScreen();
				host.openWorldMap();
				return 20;
			}
			case 13: {
				// Teleport auf eine freie Stelle – nur der normale Befehl, das Ergebnis kommt vom Server.
				WorldMapUi map = host.worldMap();
				map.testSpotMenu(90, 40, startX + 6, startZ + 12);
				if ((p.commandAccess() & WaypointTeleport.TP) == 0) {
					// Ohne Rechte (Einzelspieler ohne Cheats): kein Eintrag, und auch der Weg daran vorbei sendet nichts.
					check("Ohne Rechte: kein Eintrag, nichts gesendet", !map.testMenuItems().contains("teleportHere")
							&& !WaypointTeleport.send(m, p, startX + 6, startY, startZ + 12, dim));
					host.closeScreen();
					phase = 17;
					return 5;
				}
				map.testMenuAction("teleportHere");
				return 40;
			}
			case 14: {
				check(String.format("Teleport auf freie Stelle: bei %.1f/%.1f/%.1f, Ziel x=%d z=%d", p.x(), p.y(), p.z(), startX + 6, startZ + 12),
						Math.abs(p.x() - (startX + 6.5)) < 1.5 && Math.abs(p.z() - (startZ + 12.5)) < 1.5);
				host.closeScreen();
				boolean canIn = (p.commandAccess() & WaypointTeleport.EXECUTE_IN) != 0;
				check("Teleport-Eintrag in die andere Dimension passt zur Version", WaypointTeleport.possible(m, p, portal.dimension) == canIn);
				if (!canIn) {
					phase = 17;
					return 5;
				}
				check("Befehl gesendet", WaypointTeleport.send(m, p, portal.x, portal.y, portal.z, portal.dimension));
				return 80;
			}
			case 15:
				check("In den Nether teleportiert: " + p.dimension(), p.dimension().equals(portal.dimension));
				host.shot("wp-teleported-nether");
				check("Zurück", WaypointTeleport.send(m, p, startX, startY + 8, startZ, dim.contains("nether") ? "minecraft:overworld" : dim));
				return 80;
			case 16:
				check("Zurück in der Oberwelt: " + p.dimension(), !p.dimension().contains("nether"));
				return 5;
			case 17: {
				int before = s.all(key).size();
				host.quickWaypoint();
				List<Waypoint> now = s.all(key);
				check("Schnell-Taste legt am Standort an", now.size() == before + 1
						&& now.get(now.size() - 1).distanceTo(p.x(), p.y(), p.z()) < 2);
				return 4;
			}
			case 18:
				host.shot("wp-quick");
				host.command("kill @p");
				return 30;
			case 19:
				host.respawn();
				return 40;
			case 20: {
				Waypoint death = s.lastDeath(key);
				int deaths = 0;
				for (Waypoint w : s.all(key)) if (w.death) deaths++;
				check("Tod: zwei Todespunkte behalten (" + deaths + ")", deaths == 2);
				check("Weg zurück: Todespunkt ist das Ziel", death != null && s.target(key) == death);
				host.shot("wp-death-guide");
				log.accept(failures == 0 ? "Wegpunkte-Selbsttest: alles OK" : "Wegpunkte-Selbsttest: " + failures + " FEHLER");
				// Nur gegen eine lokale Attrappe: So verhält sich das Live-Teilen auf einem teilbaren Server.
				if (!dev.theredstonee.trsclient.core.waypoint.live.LiveSelfTest.available()) return -1;
				dev.theredstonee.trsclient.core.waypoint.live.LiveShare.testWorld(dev.theredstonee.trsclient.core.waypoint.live.LiveWorld.server("silent.test.example"));
				return 400;
			}
			case 21: {
				dev.theredstonee.trsclient.core.waypoint.live.LiveShare live = dev.theredstonee.trsclient.core.waypoint.live.LiveShare.get();
				if (live != null) {
					log.accept("Live-Teilen: API fehlt=" + live.apiMissing() + ", Oberfläche=" + (dev.theredstonee.trsclient.core.waypoint.live.LiveShare.ui() != null)
							+ ", Hinweis=" + live.takeNotice() + ", Stream nötig=" + live.wantsStream());
				}
				host.openWorldMap();
				return 20;
			}
			case 24:
				host.shot("wp-live-silent");
				host.closeScreen();
				dev.theredstonee.trsclient.core.waypoint.live.LiveShare.testWorld(null);
				return -1;
			default:
				return -1;
		}
	}

	private static int pitchTo(MapPlatform p, Waypoint w) {
		double dx = w.x + 0.5 - p.x(), dz = w.z + 0.5 - p.z(), dy = w.y + 0.5 - (p.y() + 1.62);
		return (int) Math.round(Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
	}

	private static int yawTo(int fromX, int fromZ, int toX, int toZ) {
		return (int) Math.round(Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ)));
	}
}

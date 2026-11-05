package dev.theredstonee.trsclient.core.waypoint.live;

import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Gemeinsamer Teil der Selbsttest-Phase „Live-Wegpunkte“ im Karten-Test ({@code -PtrsAutotestOnly=maps} gegen die
 * Attrappe {@code client-mod/scripts/mock-wpshare-api.mjs}): Testwelt gilt als Server, Freund „Notch“ teilt Wegpunkte
 * und läuft im Kreis. Bilder: Minimap, HUD, Weltkarte, Reiter „Freunde“, Kontextmenü, Übernehmen, Freunde-Liste mit
 * Freigabe-Schalter. Nur für Autotests.
 */
public final class LiveSelfTest {
	public static final String FRIEND = "069a79f444e94726a5befca90e38aaf5";
	static final String TEST_SERVER = "wpshare.test.example";

	/** Was der Baum für den Test tun kann (Spiel-Thread). */
	public interface Host {
		void shot(String name);

		void command(String command);

		void openWorldMap();

		/** Offene Weltkarte oder null. */
		WorldMapUi worldMap();

		/** Sozial-Bildschirm im Reiter „Freunde“ öffnen (false = geht nicht). */
		boolean openFriends();

		void closeScreen();
	}

	private final Host host;
	private final Consumer<String> log;
	private int phase;
	private int polls;
	private FriendWaypoint turm;

	public LiveSelfTest(Host host, Consumer<String> log) {
		this.host = host;
		this.log = log;
	}

	/** Läuft das Spiel gegen eine lokale Attrappe (sonst wird die Phase übersprungen)? */
	public static boolean available() {
		String api = System.getProperty("trsclient.api.url", "");
		return api.startsWith("http://127.0.0.1") || api.startsWith("http://localhost");
	}

	/**
	 * Ein Schritt; Rückgabe = Ticks bis zum nächsten, -1 = fertig. {@code bx/by/bz} = Startpunkt des Karten-Tests.
	 */
	public int step(int bx, int by, int bz) {
		MapEngine e = MapEngine.get();
		TrsModules m = e.modules();
		LiveShare live = LiveShare.get();
		switch (phase) {
			case 0: {
				if (!available() || live == null) {
					log.accept("übersprungen (keine Attrappe -PtrsApi=http://127.0.0.1:<port> bzw. kein TRS-Online)");
					return -1;
				}
				LiveShare.testWorld(LiveWorld.server(TEST_SERVER));
				m.waypoints.setEnabled(true);
				m.waypointShareLive.set(true);
				m.waypointSharePosition.set(true);
				m.waypointShareDeath.set(true);
				m.waypointFriendWaypoints.set(true);
				m.waypointFriendPositions.set(true);
				m.minimapWaypoints.set(true);
				m.minimapEdgeWaypoints.set(true);
				m.worldMapWaypoints.set(true);
				m.minimapZoom.set(TrsModules.MinimapZoom.FAR);
				m.minimapShape.set(TrsModules.MapShape.ROUND);
				m.minimapRotate.set(false);
				for (Waypoint w : e.waypoints()) {
					if ("Turm".equals(w.name) || "Wachturm".equals(w.name)) e.removeWaypoint(w);
				}
				for (Waypoint w : e.waypoints()) {
					if ("Basis".equals(w.name)) {
						w.shared = true;
						e.waypointEdited();
					}
				}
				host.closeScreen();
				host.command("tp @p " + bx + " " + (by + 20) + " " + bz + " 0 30");
				phase++;
				return 20;
			}
			case 1: {
				Owner o = live.owner(FRIEND);
				boolean ready = live.active() && o != null && o.waypoints().size() >= 4 && o.position() != null;
				if (!ready && polls++ < 90) return 10;
				polls = 0;
				TrsOnline online = TrsOnline.current();
				log.accept(String.format(Locale.ROOT, "Sitzung %s, Freunde %d, Wegpunkte des Freundes %d, Live-Position %s, Status %s, peers send %d",
						live.active() ? "aktiv" : "AUS", live.owners().size(), o == null ? 0 : o.waypoints().size(),
						o != null && o.position() != null ? "ja" : "nein", online == null ? "?" : online.status(), live.peersSend()));
				if (!ready) log.accept("FEHLER: Daten des Freundes kamen nicht an");
				for (FriendWaypoint w : o == null ? java.util.Collections.<FriendWaypoint>emptyList() : o.waypoints()) {
					if ("Turm".equals(w.name)) turm = w;
				}
				// Eigener Wegpunkt direkt neben dem Turm: die Beschriftungen müssen einander ausweichen.
				if (turm != null) e.addWaypoint("Wachturm", turm.x + 2, turm.y, turm.z + 1, 0xF2E14C);
				phase++;
				return 40;
			}
			case 2: {
				host.shot("wplive-minimap");
				if (turm != null) {
					double dx = turm.x + 0.5 - e.playerX(), dz = turm.z + 0.5 - e.playerZ(), dy = turm.y + 0.5 - (e.playerY() + 1.62);
					float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
					float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
					// Etwas nach links, damit Turm und der Kopf des Freundes ins Bild passen.
					host.command(String.format(Locale.ROOT, "tp @p ~ ~ ~ %.1f %.1f", yaw - 12f, pitch - 5f));
				}
				phase++;
				return 30;
			}
			case 3:
				host.shot("wplive-hud");
				host.openWorldMap();
				phase++;
				return 30;
			case 4: {
				WorldMapUi ui = host.worldMap();
				if (ui == null) {
					log.accept("FEHLER: Weltkarte ging nicht auf");
					phase = 9;
					return 1;
				}
				ui.setScreenPxNow(3f);
				phase++;
				return 30;
			}
			case 5: {
				host.shot("wplive-worldmap");
				WorldMapUi ui = host.worldMap();
				if (ui != null) ui.testSidebarFriends(true);
				phase++;
				return 20;
			}
			case 6: {
				WorldMapUi ui = host.worldMap();
				host.shot("wplive-sidebar-friends");
				if (ui != null) {
					log.accept("Reiter Freunde: " + ui.testFriendRows() + " Zeilen (1 Freund + seine Wegpunkte)");
					if (turm != null) ui.testFriendMenu(turm);
				}
				phase++;
				return 10;
			}
			case 7: {
				WorldMapUi ui = host.worldMap();
				host.shot("wplive-menu");
				if (ui != null && turm != null) ui.adoptFriend(turm);
				boolean adopted = false;
				for (Waypoint w : e.waypoints()) adopted |= "Turm".equals(w.name) && turm != null && w.x == turm.x && w.z == turm.z;
				log.accept("Übernehmen „Turm“: " + (adopted ? "OK" : "FEHLER"));
				if (ui != null) ui.testSidebarFriends(false);
				phase++;
				return 10;
			}
			case 8:
				host.shot("wplive-adopted");
				if (!host.openFriends()) {
					log.accept("Freunde-Bildschirm nicht verfügbar");
					phase = 10;
					return 1;
				}
				phase++;
				return 40;
			case 9: {
				if (!live.grantsLoaded() && polls++ < 200) return 10;
				log.accept("Freigaben geladen: " + live.grantsLoaded() + ", teilt mit mir: " + live.grantedToMe(FRIEND));
				live.setGrant(FRIEND, true);
				phase++;
				return 30;
			}
			case 10: {
				host.shot("wplive-friends");
				int[] n = live.testCounters();
				log.accept(String.format(Locale.ROOT, "Freigabe für Notch: %s; gesendet: %d Positionen, %d Listen, %d Abrufe GET /friends",
						live.granted(FRIEND) ? "an" : "aus", n[0], n[1], n[2]));
				host.closeScreen();
				for (Waypoint w : e.waypoints()) {
					if ("Turm".equals(w.name) || "Wachturm".equals(w.name)) e.removeWaypoint(w);
				}
				LiveShare.testWorld(null);
				m.minimapZoom.set(TrsModules.MinimapZoom.NORMAL);
				m.minimapRotate.set(true);
				phase++;
				return 20;
			}
			default:
				return -1;
		}
	}
}

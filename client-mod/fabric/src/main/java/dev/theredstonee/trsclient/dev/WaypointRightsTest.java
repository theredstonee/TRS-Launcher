package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPlatform;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointMenu;
import dev.theredstonee.trsclient.core.waypoint.WaypointTeleport;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.screen.WorldMapScreen;
import net.minecraft.client.Minecraft;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Rechte des Teleports auf einem echten Server ({@code -PtrsAutotestOnly=waypoint-rights -PtrsAutotestServer=127.0.0.1:<port>},
 * RCON-Port und Passwort über {@code -Dtrsclient.autotest.rcon=<port>:<passwort>}): ohne OP fehlt der Eintrag und es wird
 * nichts gesendet, mit OP erscheint er und der Befehl wirkt, nach dem Entzug ist er wieder weg. Nur für Autotests.
 */
public final class WaypointRightsTest {
	private int phase;
	private int wait;
	private int tries;
	private int failures;
	private String name;
	private Waypoint marker;
	private double startX, startZ;

	public static void install() {
		WaypointRightsTest test = new WaypointRightsTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Wegpunkt-Rechte: {}", text);
	}

	private void check(String what, boolean ok) {
		log((ok ? "OK: " : "FEHLER: ") + what);
		if (!ok) failures++;
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException | IOException ex) {
			TrsClient.LOGGER.error("[Autotest] Wegpunkt-Rechte: Fehler in Phase {}", phase, ex);
			mc.stop();
			phase = 999;
		}
	}

	private static WorldMapUi map() {
		return (WorldMapUi) ((TrsUiScreen) Mc.screen()).ui();
	}

	private void step(Minecraft mc) throws IOException {
		MapEngine e = MapEngine.get();
		MapPlatform p = e.platform();
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || Mc.screen() == null) return;
				mc.options.pauseOnLostFocus = false;
				TrsClient.get().modules().worldMap.setEnabled(true);
				TrsClient.get().modules().waypoints.setEnabled(true);
				join(mc);
				phase++;
				wait = 40;
				return;
			case 1:
				if (mc.level == null || mc.player == null) {
					if (tries++ > 200) throw new IOException("kein Beitritt");
					wait = 10;
					return;
				}
				tries = 0;
				name = mc.player.getName().getString();
				rcon("deop " + name);
				rcon("gamemode creative " + name);
				rcon("tp " + name + " 10 80 10");
				phase++;
				wait = 60;
				return;
			case 2:
				check("Ohne OP: kein tp im Befehlsbaum (Zugriff " + p.commandAccess() + ")", p.commandAccess() == 0);
				marker = e.addWaypoint("Marke", 40, 80, 40, 0x3DDC84);
				Mc.setScreen(WorldMapScreen.create());
				phase++;
				wait = 20;
				return;
			case 3:
				map().waypointMenu(marker, 90, 40);
				check("Ohne OP: Eintrag fehlt am Wegpunkt " + map().testMenuItems(), !map().testMenuItems().contains(WaypointMenu.TELEPORT));
				map().testSpotMenu(90, 40, 12, 12);
				check("Ohne OP: Eintrag fehlt an freier Stelle " + map().testMenuItems(), !map().testMenuItems().contains("teleportHere"));
				check("Ohne OP: nichts wird gesendet", !WaypointTeleport.send(e.modules(), p, 40, 80, 40, ""));
				AutoTest.shot(mc, "trsclient-wp-rights-noop");
				startX = p.x();
				startZ = p.z();
				phase++;
				wait = 10;
				return;
			case 4:
				rcon("op " + name);
				phase++;
				wait = 40;
				return;
			case 5:
				check("Mit OP: tp und execute im Befehlsbaum (Zugriff " + p.commandAccess() + ")",
						p.commandAccess() == (WaypointTeleport.TP | WaypointTeleport.EXECUTE_IN));
				map().waypointMenu(marker, 90, 40);
				check("Mit OP: Eintrag am Wegpunkt " + map().testMenuItems(), map().testMenuItems().contains(WaypointMenu.TELEPORT));
				AutoTest.shot(mc, "trsclient-wp-rights-op");
				phase++;
				wait = 10;
				return;
			case 6:
				map().testMenuAction(WaypointMenu.TELEPORT);
				phase++;
				wait = 40;
				return;
			case 7:
				check(String.format("Mit OP: Teleport wirkt (bei %.1f %.1f %.1f)", p.x(), p.y(), p.z()),
						Math.abs(p.x() - 40.5) < 1.5 && Math.abs(p.z() - 40.5) < 1.5);
				check("Mit OP: in den Nether gesendet", WaypointTeleport.send(e.modules(), p, 5, 70, 5, "minecraft:the_nether"));
				phase++;
				wait = 60;
				return;
			case 8:
				check("Mit OP: im Nether " + p.dimension(), p.dimension().contains("nether"));
				WaypointTeleport.send(e.modules(), p, 10, 90, 10, "minecraft:overworld");
				rcon("deop " + name);
				phase++;
				wait = 60;
				return;
			case 9:
				check("Nach dem Entzug: wieder ohne Eintrag (Zugriff " + p.commandAccess() + ")", p.commandAccess() == 0);
				e.removeWaypoint(marker);
				log(failures == 0 ? "alles OK" : failures + " FEHLER");
				AutoTest.disconnect(mc);
				phase++;
				wait = 40;
				return;
			case 10:
				mc.stop();
				phase++;
				return;
			default:
		}
	}

	/** Beitritt zum Test-Server (nur in den Versionen, in denen der Test läuft). */
	private static void join(Minecraft mc) throws IOException {
		String server = System.getProperty("trsclient.autotest.server");
		if (server == null || server.isEmpty()) throw new IOException("kein Test-Server angegeben");
		//? if >=1.21.9 && <26.1 {
		/*net.minecraft.client.multiplayer.ServerData data = new net.minecraft.client.multiplayer.ServerData("TRS Rechte-Test", server,
				net.minecraft.client.multiplayer.ServerData.Type.OTHER);
		net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new dev.theredstonee.trsclient.screen.TrsTitleScreen(), mc,
				net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(server), data, false, null);
		*///?} else {
		throw new IOException("Beitritt nur in 1.21.9–1.21.11 umgesetzt");
		//?}
	}

	/** Kleiner RCON-Aufruf (nur Testserver auf 127.0.0.1). */
	private static void rcon(String command) throws IOException {
		String[] cfg = System.getProperty("trsclient.autotest.rcon", "25598:test").split(":", 2);
		try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(cfg[0]))) {
			socket.setSoTimeout(5000);
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());
			send(out, 1, 3, cfg[1]);
			read(in);
			send(out, 2, 2, command);
			log("RCON " + command + " → " + read(in));
		}
	}

	private static void send(DataOutputStream out, int id, int type, String text) throws IOException {
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		ByteBuffer b = ByteBuffer.allocate(14 + body.length).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(10 + body.length).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
		out.write(b.array());
		out.flush();
	}

	private static String read(DataInputStream in) throws IOException {
		byte[] head = new byte[4];
		in.readFully(head);
		int len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
		byte[] rest = new byte[len];
		in.readFully(rest);
		return new String(rest, 8, Math.max(0, len - 10), StandardCharsets.UTF_8);
	}
}

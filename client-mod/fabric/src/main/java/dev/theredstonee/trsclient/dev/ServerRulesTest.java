package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
//? if >=1.21.9 && <26.1 {
/*import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
*///?}

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selbsttest „TRS Server Rules“ ({@code -PtrsAutotestOnly=serverrules}, 1.21.9–1.21.11) gegen einen echten Server mit
 * dem Plugin (Paper + trs-server-rules-bukkit). Erwartete Server-Config: {@code minimap: false}, {@code fullbright: true},
 * {@code zoom.factor {max: 4}}, {@code cinematic.freecamFairPlay: true}, Welt {@code world_nether} mit {@code zoom: false},
 * {@code message: "TRS Rules test"}.
 * <ol>
 *   <li>Eigene Werte absichtlich gegenteilig setzen (Minimap an, Gamma aus, Zoom ×8, Fair Play aus).</li>
 *   <li>Beitreten ({@code -Dtrsclient.autotest.server}), auf die Regeln warten, Sperren prüfen, Hinweis genau einmal.</li>
 *   <li>TRS-Menü (Minimap, Gamma, Zoom, Kamera-Fahrten) fotografieren – Schloss + „Vom Server“.</li>
 *   <li>Per RCON ({@code -Dtrsclient.autotest.rcon=port:passwort}) in den Nether teleportieren → neue Regeln, Zoom gesperrt,
 *       Hinweis nicht noch einmal.</li>
 *   <li>Trennen → keine Regeln mehr, eigene Werte gelten wieder.</li>
 * </ol>
 * Ergebnisse als Logzeilen „[Autotest] ServerRules: PASS/FAIL …“, am Ende eine Summe; Bilder trsclient-&lt;mc&gt;-serverrules-*.png.
 */
public final class ServerRulesTest {
	private static final String MESSAGE = "TRS Rules test";
	private static final boolean PROXY = Boolean.getBoolean("trsclient.autotest.sr.proxy");
	private int phase;
	private int wait = 60;
	private int waited;
	private int menuStep;
	private int receivedBefore;
	private final List<String> failures = new ArrayList<String>();
	private int passes;

	public static void install() {
		final ServerRulesTest test = new ServerRulesTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] ServerRules: {}", text);
	}

	private void check(boolean ok, String what) {
		if (ok) {
			passes++;
			log("PASS " + what);
		} else {
			failures.add(what);
			log("FAIL " + what);
		}
	}

	private boolean waitFor(boolean done, int maxTicks) {
		if (done) {
			waited = 0;
			return true;
		}
		if (++waited > maxTicks) {
			log("Zeitüberschreitung in Phase " + phase);
			waited = 0;
			return true;
		}
		return false;
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc, TrsClient.get().modules());
		} catch (RuntimeException | LinkageError e) {
			TrsClient.LOGGER.error("[Autotest] ServerRules: Fehler in Phase {}", phase, e);
			failures.add("Ausnahme in Phase " + phase + ": " + e);
			phase = 99;
		}
	}

	private static boolean inGame(Minecraft mc) {
		return mc.level != null && mc.player != null && Mc.screen() == null && Mc.overlay() == null;
	}

	private void step(Minecraft mc, TrsModules m) {
		ServerRules rules = ServerRules.get();
		switch (phase) {
			case 0:
				if (!waitFor(Mc.overlay() == null, 600)) return;
				mc.options.pauseOnLostFocus = false;
				// Eigene Werte gegenteilig zur Server-Config – so sieht man, dass der Server sie überstimmt.
				m.minimap.setEnabled(true);
				m.fullbright.setEnabled(false);
				m.zoom.setEnabled(true);
				m.zoomFactor.set(8);
				m.cinematic.cinematic.setEnabled(true);
				m.cinematic.freecamFairPlay.set(false);
				check(rules != null, "ServerRules initialisiert");
				if (rules == null) {
					phase = 99;
					return;
				}
				log("hello = " + rules.helloJson());
				if (!connect(mc)) {
					phase = 99;
					return;
				}
				phase = PROXY ? 20 : 1;
				wait = 20;
				return;
			case 1:
				if (!waitFor(inGame(mc) && rules.received(), 1200)) return;
				check(rules.received(), "Regeln vom Server empfangen");
				phase++;
				wait = 40; // Hinweis im Chat ankommen lassen
				return;
			case 2:
				log("Regeln (Oberwelt): " + rules.rules().modules.keySet() + ", Hinweis=" + rules.rules().message);
				check(!m.minimap.isEnabled() && m.minimap.serverLocked() && m.minimap.ownEnabled(),
						"Minimap gesperrt aus (eigener Wert an bleibt gespeichert)");
				check(m.fullbright.isEnabled() && m.fullbright.serverLocked() && TrsClient.get().fullbright(),
						"Gamma vom Server erzwungen an");
				check(m.zoomFactor.get() <= 4.0 && m.zoomFactor.serverLocked() && m.zoomFactor.own() == 8.0,
						String.format(Locale.ROOT, "Zoom-Faktor geklemmt (wirksam %.1f, eigener %.1f, Grenze %.1f)",
								m.zoomFactor.get(), m.zoomFactor.own(), m.zoomFactor.serverMax()));
				check(m.zoom.isEnabled() && !m.zoom.serverLocked(), "Zoom in der Oberwelt erlaubt");
				check(m.cinematic.freecamFairPlay.get() && m.cinematic.freecamFairPlay.serverLocked(),
						"Freecam Fair Play erzwungen");
				check(chatCount(MESSAGE) == 1, "Hinweis genau einmal im Chat (" + chatCount(MESSAGE) + ")");
				AutoTest.shot(mc, "trsclient-serverrules-ingame-overworld");
				menuStep = 0;
				phase++;
				wait = 200; // Vanilla-/TRS-Toasts (Chat-Hinweis, Sozial) abklingen lassen – sie verdecken sonst das Schloss
				return;
			case 3: {
				// TRS-Menü: Schloss + „Vom Server“ an den gesperrten Modulen.
				String[] names = {"grid-world", "minimap", "fullbright", "zoom", "cinematic"};
				if (menuStep > 0) AutoTest.shot(mc, "trsclient-serverrules-menu-" + names[menuStep - 1]);
				if (menuStep >= names.length) {
					Mc.setScreen(null);
					phase++;
					wait = 10;
					return;
				}
				if (menuStep == 0) {
					// Kartenansicht: Schloss neben dem Schalter (Minimap/Gamma gesperrt)
					Mc.setScreen(new TrsMenuScreen(null).showCategory(m.minimap.category()));
				} else if ("cinematic".equals(names[menuStep])) {
					// bis zur Zeile „Fair Play“ scrollen
					Mc.setScreen(new TrsMenuScreen(null).select(m.cinematic.cinematic).scrollSettings(215));
				} else {
					Mc.setScreen(new TrsMenuScreen(null).select(m.registry.byId(names[menuStep])));
				}
				menuStep++;
				wait = 25;
				return;
			}
			case 4: {
				// In den Nether (Welt mit eigener Regel zoom: false) – per RCON wie die Server-Konsole.
				receivedBefore = rules.receivedCount();
				String name = mc.player.getName().getString();
				String answer = Rcon.command("execute in minecraft:the_nether run tp " + name + " 0 100 0");
				log("RCON-Antwort: " + answer);
				phase++;
				wait = 20;
				return;
			}
			case 5:
				if (!waitFor(inGame(mc) && rules.rules().modules.containsKey("zoom") && isNether(mc), 600)) return;
				phase++;
				wait = 40;
				return;
			case 6:
				log("Regeln (Nether): " + rules.rules().modules.keySet() + ", Dimension " + dimension(mc));
				check(isNether(mc), "im Nether angekommen");
				check(rules.receivedCount() > receivedBefore, "Regeln nach Weltwechsel neu gesendet (" + receivedBefore + " -> "
						+ rules.receivedCount() + ")");
				check(!m.zoom.isEnabled() && m.zoom.serverLocked(), "Zoom im Nether gesperrt (neue Regeln nach Weltwechsel)");
				check(!m.minimap.isEnabled() && m.fullbright.isEnabled() && m.zoomFactor.get() <= 4.0,
						"globale Regeln gelten im Nether weiter");
				check(chatCount(MESSAGE) == 1, "Hinweis nach Weltwechsel nicht wiederholt (" + chatCount(MESSAGE) + ")");
				AutoTest.shot(mc, "trsclient-serverrules-ingame-nether");
				Mc.setScreen(new TrsMenuScreen(null).select(m.zoom));
				phase++;
				wait = 140; // Fortschritts-Toast „Nether“ abklingen lassen
				return;
			case 7:
				AutoTest.shot(mc, "trsclient-serverrules-menu-zoom-nether");
				Mc.setScreen(null);
				AutoTest.disconnect(mc);
				Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 40;
				return;
			case 8:
				if (!waitFor(mc.level == null, 400)) return;
				check(!rules.received() && rules.rules().modules.isEmpty(), "nach dem Trennen keine Regeln mehr");
				check(m.minimap.isEnabled() && !m.minimap.serverLocked(), "Minimap wieder nach eigenem Wert (an)");
				check(!m.fullbright.isEnabled() && !m.fullbright.serverLocked(), "Gamma wieder nach eigenem Wert (aus)");
				check(m.zoom.isEnabled() && m.zoomFactor.get() == 8.0, "Zoom wieder frei (Faktor 8)");
				check(!m.cinematic.freecamFairPlay.serverLocked(), "Fair-Play-Sperre aufgehoben");
				// Eigene Werte zurück auf Standard (die Test-Config bleibt sauber).
				m.minimap.setEnabled(false);
				m.zoomFactor.set(4);
				m.cinematic.freecamFairPlay.set(true);
				phase = 99;
				return;
			// --- Proxy (Velocity/BungeeCord mit Plugin, -Dtrsclient.autotest.sr.proxy=true): Unterserver lobby (ohne Regeln)
			// und rules (Backend-Plugin + Proxy-Regel freelook: false) ---
			case 20:
				if (!waitFor(inGame(mc) && rules.received(), 1200)) return;
				log("Proxy/lobby: Regeln " + rules.rules().modules.keySet());
				check(rules.received() && !m.minimap.serverLocked() && !m.freelook.serverLocked(),
						"Proxy: auf lobby Regeln empfangen, nichts gesperrt");
				switchServer("rules");
				return;
			case 21:
				if (!waitFor(inGame(mc) && rules.rules().modules.containsKey("freelook")
						&& rules.rules().modules.containsKey("minimap"), 1200)) return;
				phase++;
				wait = 40;
				return;
			case 22:
				log("Proxy/rules: Regeln " + rules.rules().modules.keySet() + ", Hinweis=" + rules.rules().message);
				check(!m.freelook.isEnabled() && m.freelook.serverLocked(), "Proxy: Freelook vom Proxy gesperrt (servers.rules)");
				check(!m.minimap.isEnabled() && m.minimap.serverLocked(), "Proxy: Minimap vom Backend-Plugin gesperrt (zusammengeführt)");
				check(m.fullbright.isEnabled() && m.zoomFactor.get() <= 4.0 && m.cinematic.freecamFairPlay.get(),
						"Proxy: übrige Backend-Regeln wirksam");
				check(chatCount(MESSAGE) >= 1, "Proxy: Hinweis des Backends angezeigt (" + chatCount(MESSAGE) + ")");
				AutoTest.shot(mc, "trsclient-serverrules-proxy-rules");
				switchServer("lobby");
				return;
			case 23:
				if (!waitFor(inGame(mc) && !m.minimap.serverLocked() && !m.freelook.serverLocked() && rules.receivedCount() > 0, 1200)) return;
				phase++;
				wait = 40;
				return;
			case 24:
				log("Proxy/lobby: Regeln " + rules.rules().modules.keySet());
				check(!m.minimap.serverLocked() && !m.freelook.serverLocked() && !m.fullbright.serverLocked(),
						"Proxy: zurück auf lobby – Regeln von rules aufgehoben");
				switchServer("rules");
				return;
			case 25:
				if (!waitFor(inGame(mc) && m.freelook.serverLocked() && m.minimap.serverLocked(), 1200)) return;
				check(m.freelook.serverLocked() && m.minimap.serverLocked(), "Proxy: erneuter Wechsel auf rules – Regeln wieder da");
				phase++;
				wait = 100; // länger als nötig bleiben: kein Kick wegen „fehlendem TRS Client“
				return;
			case 26:
				check(inGame(mc), "Proxy: nach 5 s auf rules weiter verbunden");
				AutoTest.disconnect(mc);
				Mc.setScreen(new TrsTitleScreen());
				phase = 8;
				wait = 40;
				return;
			case 99:
				log(String.format(Locale.ROOT, "ERGEBNIS: %d bestanden, %d fehlgeschlagen %s", passes, failures.size(),
						failures.isEmpty() ? "" : failures.toString()));
				log("fertig");
				phase = 100;
				mc.stop();
				return;
			default:
		}
	}

	private static String dimension(Minecraft mc) {
		return mc.level == null ? "-" : String.valueOf(Mc.dimensionId());
	}

	private static boolean isNether(Minecraft mc) {
		return dimension(mc).contains("the_nether");
	}

	/** {@code /server <name>} über den Proxy, danach auf die neuen Regeln warten (nächste Phase). */
	private void switchServer(String name) {
		Mc.sendChat("/server " + name);
		log("wechsle auf " + name);
		phase++;
		wait = 20;
	}

	/** Wie oft {@code text} in den Chat-Zeilen steht. */
	private static int chatCount(String text) {
		int n = 0;
		for (Object line : ((dev.theredstonee.trsclient.mixin.ChatComponentAccessor) ChatLines.chat()).trsclient$allMessages()) {
			if (String.valueOf(line).contains(text)) n++;
		}
		return n;
	}

	private boolean connect(Minecraft mc) {
		String server = System.getProperty("trsclient.autotest.server");
		if (server == null || server.isEmpty()) {
			failures.add("kein Test-Server (-PtrsAutotestServer)");
			return false;
		}
		//? if >=1.21.9 && <26.1 {
		/*ServerData data = new ServerData("TRS Server-Rules-Test", server, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(new TrsTitleScreen(), mc, ServerAddress.parseString(server), data, false, null);
		log("verbinde mit " + server);
		return true;
		*///?} else {
		failures.add("Beitritt nur in 1.21.9–1.21.11 umgesetzt");
		return false;
		//?}
	}

	/** Winziger RCON-Client (Befehle wie an der Server-Konsole). */
	static final class Rcon {
		private Rcon() {
		}

		static String command(String command) {
			String spec = System.getProperty("trsclient.autotest.rcon", "");
			int colon = spec.indexOf(':');
			if (colon < 0) return "(kein -Dtrsclient.autotest.rcon=port:passwort)";
			try (java.net.Socket s = new java.net.Socket("127.0.0.1", Integer.parseInt(spec.substring(0, colon)))) {
				s.setSoTimeout(5000);
				java.io.DataInputStream in = new java.io.DataInputStream(s.getInputStream());
				java.io.OutputStream out = s.getOutputStream();
				send(out, 1, 3, spec.substring(colon + 1));
				if (read(in)[0].equals("-1")) return "(RCON: Anmeldung abgelehnt)";
				send(out, 2, 2, command);
				return read(in)[1];
			} catch (java.io.IOException | RuntimeException e) {
				return "(RCON-Fehler: " + e + ")";
			}
		}

		private static void send(java.io.OutputStream out, int id, int type, String body) throws java.io.IOException {
			byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(14 + b.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			buf.putInt(10 + b.length).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
			out.write(buf.array());
			out.flush();
		}

		/** {id, body} */
		private static String[] read(java.io.DataInputStream in) throws java.io.IOException {
			byte[] head = new byte[4];
			in.readFully(head);
			int len = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
			byte[] rest = new byte[len];
			in.readFully(rest);
			java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(rest).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			int id = b.getInt();
			b.getInt();
			return new String[] {String.valueOf(id), new String(rest, 8, Math.max(0, len - 10), java.nio.charset.StandardCharsets.UTF_8)};
		}
	}
}

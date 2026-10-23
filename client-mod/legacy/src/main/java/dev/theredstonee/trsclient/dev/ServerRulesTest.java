package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.serverrules.ServerRules;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selbsttest „TRS Server Rules“ ({@code -PtrsAutotestOnly=serverrules}, 1.8.9–1.12.2) gegen einen echten Server mit dem
 * Plugin (z. B. Paper 1.8.8 + trs-server-rules-bukkit, Kanal {@code TRS|Rules}). Erwartete Server-Config wie im
 * Fabric-Test: {@code minimap: false}, {@code fullbright: true}, {@code zoom.factor {max: 4}},
 * {@code cinematic.freecamFairPlay: true}, {@code message: "TRS Rules test"}.
 * <ol>
 *   <li>Eigene Werte gegenteilig setzen, beitreten ({@code -Dtrsclient.autotest.server}), Sperren prüfen.</li>
 *   <li>TRS-Menü fotografieren (Schloss + „Vom Server“).</li>
 *   <li>{@code /trsrules reload} per RCON ({@code -Dtrsclient.autotest.rcon=port:passwort}) → Regeln neu, Hinweis nicht
 *       noch einmal.</li>
 *   <li>Trennen → keine Regeln mehr.</li>
 * </ol>
 * Ergebnisse als Logzeilen „[Autotest] ServerRules: PASS/FAIL …“; Bilder trsclient-&lt;mc&gt;-serverrules-*.png.
 */
public final class ServerRulesTest {
	private static final String MESSAGE = "TRS Rules test";
	private int phase;
	private int wait = 40;
	private int waited;
	private int menuStep;
	private int receivedBefore;
	private final List<String> failures = new ArrayList<String>();
	private int passes;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new ServerRulesTest());
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] ServerRules: " + text);
	}

	private static void shot(Minecraft mc, String name) {
		String file = "trsclient-" + Mc.version() + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		log("Bild " + file);
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

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc, TrsClient.get().modules());
		} catch (RuntimeException | LinkageError e) {
			TrsClient.LOGGER.error("[Autotest] ServerRules: Fehler in Phase " + phase, e);
			failures.add("Ausnahme in Phase " + phase + ": " + e);
			phase = 99;
		}
	}

	private static boolean inGame(Minecraft mc) {
		return Mc.world() != null && Mc.player() != null && mc.currentScreen == null;
	}

	private void step(Minecraft mc, TrsModules m) {
		ServerRules rules = ServerRules.get();
		switch (phase) {
			case 0: {
				mc.gameSettings.pauseOnLostFocus = false;
				m.minimap.setEnabled(true);
				m.fullbright.setEnabled(false);
				m.zoom.setEnabled(true);
				m.zoomFactor.set(8);
				m.cinematic.cinematic.setEnabled(true);
				m.cinematic.freecamFairPlay.set(false);
				check(rules != null, "ServerRules initialisiert");
				String server = System.getProperty("trsclient.autotest.server", "");
				if (rules == null || server.isEmpty()) {
					failures.add("kein Test-Server (-PtrsAutotestServer)");
					phase = 99;
					return;
				}
				log("hello = " + rules.helloJson());
				mc.displayGuiScreen(new GuiConnecting(new GuiMultiplayer(new TrsTitleScreen()), mc,
						new ServerData("TRS Server-Rules-Test", server, false)));
				log("verbinde mit " + server);
				phase++;
				wait = 20;
				return;
			}
			case 1:
				if (!waitFor(inGame(mc) && rules.received(), 1200)) return;
				check(rules.received(), "Regeln vom Server empfangen");
				phase++;
				wait = 40;
				return;
			case 2:
				log("Regeln: " + rules.rules().modules.keySet() + ", Hinweis=" + rules.rules().message);
				check(!m.minimap.isEnabled() && m.minimap.serverLocked() && m.minimap.ownEnabled(),
						"Minimap gesperrt aus (eigener Wert an bleibt gespeichert)");
				check(m.fullbright.isEnabled() && m.fullbright.serverLocked(), "Gamma vom Server erzwungen an");
				check(m.zoomFactor.get() <= 4.0 && m.zoomFactor.serverLocked() && m.zoomFactor.own() == 8.0,
						String.format(Locale.ROOT, "Zoom-Faktor geklemmt (wirksam %.1f, eigener %.1f)", m.zoomFactor.get(),
								m.zoomFactor.own()));
				check(m.zoom.isEnabled() && !m.zoom.serverLocked(), "Zoom erlaubt (nur Faktor begrenzt)");
				check(m.cinematic.freecamFairPlay.get() && m.cinematic.freecamFairPlay.serverLocked(), "Freecam Fair Play erzwungen");
				check(chatCount(mc) == 1, "Hinweis genau einmal im Chat (" + chatCount(mc) + ")");
				shot(mc, "serverrules-ingame");
				menuStep = 0;
				phase++;
				wait = 200; // Vanilla-/TRS-Toasts (Chat-Hinweis, Sozial) abklingen lassen – sie verdecken sonst das Schloss
				return;
			case 3: {
				String[] names = {"grid-world", "minimap", "fullbright", "zoom", "cinematic"};
				if (menuStep > 0) shot(mc, "serverrules-menu-" + names[menuStep - 1]);
				if (menuStep >= names.length) {
					mc.displayGuiScreen(null);
					phase++;
					wait = 10;
					return;
				}
				if (menuStep == 0) {
					mc.displayGuiScreen(new TrsMenuScreen(null).showCategory(m.minimap.category()));
				} else if ("cinematic".equals(names[menuStep])) {
					mc.displayGuiScreen(new TrsMenuScreen(null).select(m.cinematic.cinematic).scrollSettings(215));
				} else {
					Module mod = m.registry.byId(names[menuStep]);
					mc.displayGuiScreen(new TrsMenuScreen(null).select(mod));
				}
				menuStep++;
				wait = 25;
				return;
			}
			case 4:
				receivedBefore = rules.receivedCount();
				log("RCON-Antwort: " + Rcon.command("trsrules reload"));
				phase++;
				wait = 10;
				return;
			case 5:
				if (!waitFor(rules.receivedCount() > receivedBefore, 400)) return;
				check(rules.receivedCount() > receivedBefore, "Regeln nach /trsrules reload neu gesendet (" + receivedBefore + " -> "
						+ rules.receivedCount() + ")");
				phase++;
				wait = 20;
				return;
			case 6:
				check(!m.minimap.isEnabled() && m.fullbright.isEnabled() && m.zoomFactor.get() <= 4.0, "Sperren nach Reload unverändert");
				check(chatCount(mc) == 1, "Hinweis nach Reload nicht wiederholt (" + chatCount(mc) + ")");
				Mc.leaveWorld();
				mc.displayGuiScreen(new TrsTitleScreen());
				phase++;
				wait = 40;
				return;
			case 7:
				if (!waitFor(Mc.world() == null, 400)) return;
				check(!rules.received() && rules.rules().modules.isEmpty(), "nach dem Trennen keine Regeln mehr");
				check(m.minimap.isEnabled() && !m.minimap.serverLocked(), "Minimap wieder nach eigenem Wert (an)");
				check(!m.fullbright.isEnabled() && !m.fullbright.serverLocked(), "Gamma wieder nach eigenem Wert (aus)");
				check(m.zoomFactor.get() == 8.0, "Zoom-Faktor wieder frei (8)");
				check(!m.cinematic.freecamFairPlay.serverLocked(), "Fair-Play-Sperre aufgehoben");
				m.minimap.setEnabled(false);
				m.zoomFactor.set(4);
				m.cinematic.freecamFairPlay.set(true);
				phase = 99;
				return;
			case 99:
				log(String.format(Locale.ROOT, "ERGEBNIS: %d bestanden, %d fehlgeschlagen %s", passes, failures.size(),
						failures.isEmpty() ? "" : failures.toString()));
				log("fertig");
				phase = 100;
				mc.shutdown();
				return;
			default:
		}
	}

	/** Wie oft der Hinweistext in den Chat-Zeilen steht (Feld chatLines von GuiNewChat). */
	@SuppressWarnings("unchecked")
	private static int chatCount(Minecraft mc) {
		if (mc.ingameGUI == null) return -1;
		GuiNewChat chat = mc.ingameGUI.getChatGUI();
		for (String name : new String[] {"chatLines", "field_146252_h"}) {
			try {
				java.lang.reflect.Field f = GuiNewChat.class.getDeclaredField(name);
				f.setAccessible(true);
				int n = 0;
				for (ChatLine line : (List<ChatLine>) f.get(chat)) {
					if (String.valueOf(line.getChatComponent()).contains(MESSAGE)) n++;
				}
				return n;
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// nächster Name
			}
		}
		return -1;
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

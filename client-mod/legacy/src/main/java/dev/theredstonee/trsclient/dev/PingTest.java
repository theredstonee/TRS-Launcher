package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.net.NetBoost;
import dev.theredstonee.trsclient.core.net.NetTiming;
import dev.theredstonee.trsclient.core.net.PingMeter;
import dev.theredstonee.trsclient.core.net.ServerPingTest;
import dev.theredstonee.trsclient.core.net.StatusPing;
import dev.theredstonee.trsclient.core.perf.FrameStats;
import dev.theredstonee.trsclient.core.perf.LowLatency;
import dev.theredstonee.trsclient.menus.LegacyMenus;
import dev.theredstonee.trsclient.perf.LegacyLatency;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Locale;

/**
 * Selbsttest „Ping &amp; Latenz“ für Forge 1.8.9 ({@code -PtrsAutotestOnly=ping}): Ping-Test der Serverliste, Ping-HUD auf
 * einem lokalen Server (Wert der Spielerliste + Keepalive-Jitter + TPS), Entpacken im Spiel vorher (Vanilla) und nachher
 * (TRS) mit derselben Mess-Sonde, niedrige Eingabeverzögerung, Modulseiten. Logzeilen „[Autotest] Ping: …“.
 */
public final class PingTest {
	private final FrameStats frames = new FrameStats();
	private final NetTiming timing = new NetTiming();
	private int phase;
	private int wait = 40;
	private int waited;
	private int latencyStep;
	private boolean vanillaRun;
	/** Beitritt 1 = TRS (Aufwärmen), 2 = Vanilla, 3 = TRS (warm, zum Vergleich). */
	private int joinRound;

	public static void install() {
		System.setProperty("trsclient.latency.measure", "true");
		MinecraftForge.EVENT_BUS.register(new PingTest());
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Ping: " + text);
	}

	private static void shot(Minecraft mc, String name) {
		String file = "trsclient-" + Mc.version() + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		log("Bild " + file);
	}

	@SubscribeEvent
	public void onRender(TickEvent.RenderTickEvent event) {
		if (event.phase == TickEvent.Phase.START) frames.frame(System.nanoTime());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		NetHandlerPlayClient net = Mc.connection();
		if (net != null && net.getNetworkManager() != null) timing.attach(net.getNetworkManager().channel());
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc, TrsClient.get().modules());
		} catch (RuntimeException | LinkageError e) {
			TrsClient.LOGGER.error("[Autotest] Ping: Fehler in Phase " + phase, e);
			phase = 99;
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

	private void step(Minecraft mc, TrsModules m) {
		GuiScreen screen = mc.currentScreen;
		switch (phase) {
			case 0:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				m.ping.setEnabled(true);
				m.pingJitter.set(true);
				m.pingGraph.set(true);
				m.pingDetails.set(true);
				m.pingSpikeWarning.set(false);
				m.netOptimize.setEnabled(true);
				m.lowLatency.setEnabled(false);
				mc.displayGuiScreen(new GuiMultiplayer(new TrsTitleScreen()));
				phase++;
				wait = 60;
				return;
			case 1:
				shot(mc, "ping-list");
				// Zum Vergleich: was Vanillas eigene Serverliste gemessen hat.
				if (screen instanceof GuiMultiplayer) {
					final ServerList list = ((GuiMultiplayer) screen).getServerList();
					for (int i = 0; i < list.countServers(); i++) {
						final ServerData data = list.getServerData(i);
						log("Vanilla-Liste: " + data.serverIP + " → " + data.serverMOTD + " (" + data.pingToServer + " ms)");
					}
				}
				if (LegacyMenus.current() != null && screen instanceof GuiMultiplayer) {
					LegacyMenus.current().startPingTest((GuiMultiplayer) screen);
				}
				phase++;
				wait = 10;
				return;
			case 2:
				if (!waitFor(!ServerPingTest.shared().running(), 400)) return;
				phase++;
				wait = 20;
				return;
			case 3: {
				shot(mc, "ping-list-sorted");
				if (screen instanceof GuiMultiplayer) {
					ServerList servers = ((GuiMultiplayer) screen).getServerList();
					StringBuilder sb = new StringBuilder();
					for (int i = 0; i < servers.countServers(); i++) {
						ServerPingTest.Entry e = ServerPingTest.shared().entry(servers.getServerData(i).serverIP);
						StatusPing.Result r = e == null ? null : e.result;
						sb.append(i).append(". ").append(servers.getServerData(i).serverName).append(" = ").append(r).append("; ");
					}
					log("Reihenfolge nach Test: " + sb);
				}
				phase++;
				return;
			}
			case 4:
				if (!join(mc)) return;
				log((vanillaRun ? "Vanilla" : "TRS") + ": im Spiel – messe");
				phase++;
				wait = joinRound == 1 ? 240 : 100;
				return;
			case 5: {
				PingMeter.Snapshot s = NetBoost.ping().snapshot(null, NetBoost.platform().millis(), System.nanoTime());
				log(String.format(Locale.ROOT, "Quelle=%s aktuell=%d ms Ø=%.1f ms Jitter(Keepalive)=%.1f ms TPS=%.2f Verlauf=%d",
						s.source, s.current, s.average, s.jitter, s.tps, s.historyCount));
				log("Beitritt " + joinRound + (vanillaRun ? " – Entpacken Vanilla: " : " – Entpacken TRS: ") + timing.summary());
				log("Netz: " + NetBoost.STATS.summary() + ", Verbindung erkannt: " + (NetBoost.channel() != null));
				if (joinRound == 1) shot(mc, "ping-hud");
				phase = joinRound >= 3 ? 7 : 6;
				return;
			}
			case 6:
				// Weitere Beitritte: 2 ohne Netzwerk-Optimierung (Vanillas Entpacker), 3 wieder mit – dieselbe Sonde.
				Mc.leaveWorld();
				vanillaRun = joinRound == 1;
				m.netOptimize.setEnabled(!vanillaRun);
				mc.displayGuiScreen(new GuiMultiplayer(new TrsTitleScreen()));
				phase = 4;
				wait = 40;
				return;
			case 7:
				if (!latency(mc, m)) return;
				m.netOptimize.setEnabled(true);
				phase++;
				return;
			case 8:
				mc.displayGuiScreen(new TrsMenuScreen(null).select(m.ping).scrollSettings(170));
				phase++;
				wait = 30;
				return;
			case 9:
				shot(mc, "ping-settings");
				mc.displayGuiScreen(new TrsMenuScreen(null).select(m.netOptimize));
				phase++;
				wait = 30;
				return;
			case 10:
				shot(mc, "ping-netoptimize");
				mc.displayGuiScreen(new TrsMenuScreen(null).select(m.lowLatency));
				phase++;
				wait = 80;
				return;
			case 11:
				shot(mc, "ping-lowlatency");
				mc.displayGuiScreen(null);
				log("fertig");
				phase = 99;
				wait = 10;
				return;
			default:
				if (phase == 99) {
					phase = 100;
					Mc.leaveWorld();
					mc.gameSettings.limitFramerate = 260;
					mc.displayGuiScreen(new TrsTitleScreen());
					mc.shutdown();
				}
		}
	}

	private boolean join(Minecraft mc) {
		String server = System.getProperty("trsclient.autotest.server");
		if (server == null || server.isEmpty()) {
			log("kein Test-Server angegeben – Spielteil übersprungen");
			phase = 7;
			return false;
		}
		if (latencyStep == 0) {
			latencyStep = -1;
			joinRound++;
			timing.reset();
			mc.displayGuiScreen(new GuiConnecting(new GuiMultiplayer(new TrsTitleScreen()), mc,
					new ServerData("TRS Ping-Test", server, false)));
			log("verbinde mit " + server);
			wait = 20;
			return false;
		}
		if (!waitFor(Mc.world() != null && Mc.player() != null && mc.currentScreen == null, 800)) return false;
		latencyStep = 0;
		return true;
	}

	/** Je 6 s: aus (nur messen), ausgewogen, maximal. */
	private boolean latency(Minecraft mc, TrsModules m) {
		LowLatency ll = LegacyLatency.get();
		if (ll == null || !ll.available()) {
			log("Niedrige Eingabeverzögerung: nicht verfügbar");
			return true;
		}
		String[] names = {"aus", "ausgewogen", "maximal"};
		if (latencyStep == 0) {
			// Mit FPS-Grenze (60): Vanilla schläft nach dem Lesen der Eingaben (Display.sync) – genau da hilft spätes Lesen.
			mc.gameSettings.limitFramerate = 60;
			mc.gameSettings.enableVsync = false;
		}
		if (latencyStep > 0) {
			frames.stop(System.nanoTime());
			LowLatency.Stats st = ll.stats();
			log(String.format(Locale.ROOT, "Eingabe-Latenz %s (FPS-Grenze 60): %.0f FPS, Bildzeit-Streuung %.2f ms, 1%%-Low %.0f FPS, GPU-Warteschlange Ø %.2f Bilder, gewartet Ø %.2f ms, Eingabe-Alter Ø %.2f ms",
					names[latencyStep - 1], frames.averageFps(), frames.stdDevMillis(), frames.lowFps(0.01), st.avgQueued, st.avgWaitMillis,
					st.avgInputAgeMillis));
		}
		if (latencyStep == names.length) {
			m.lowLatency.setEnabled(false);
			latencyStep = 0;
			return true;
		}
		m.lowLatency.setEnabled(latencyStep > 0);
		if (latencyStep == 1) m.lowLatencyMode.set(LowLatency.Mode.BALANCED);
		if (latencyStep == 2) m.lowLatencyMode.set(LowLatency.Mode.MAXIMUM);
		m.lowLatencyLatePoll.set(true);
		ll.resetStats();
		frames.start(System.nanoTime());
		latencyStep++;
		wait = 120;
		return false;
	}
}

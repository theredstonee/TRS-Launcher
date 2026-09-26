package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.net.NetBoost;
import dev.theredstonee.trsclient.core.net.PingMeter;
import dev.theredstonee.trsclient.core.net.ServerPingTest;
import dev.theredstonee.trsclient.core.net.StatusPing;
import dev.theredstonee.trsclient.core.perf.LowLatency;
import dev.theredstonee.trsclient.menus.VanillaMenus;
import dev.theredstonee.trsclient.perf.LatencyHooks;
import dev.theredstonee.trsclient.perf.PerfHooks;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerList;
//? if >=1.21.9 && <26.1 {
/*import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
*///?}

import java.util.Locale;

/**
 * Selbsttest „Ping &amp; Latenz“ ({@code -PtrsAutotestOnly=ping}, gedacht für 1.21.11): Ping-Test der Serverliste (Sortierung),
 * Beitritt zu einem lokalen Server (mit {@code -Dtrsclient.autotest.server}, gern über einen Verzögerungs-Proxy),
 * Ping-HUD mit Verlauf/Jitter/Spitze, Messung der niedrigen Eingabeverzögerung (aus / ausgewogen / maximal) und die
 * Modulseiten. Ergebnisse als Logzeilen „[Autotest] Ping: …“, Bilder trsclient-&lt;mc&gt;-ping-*.png.
 */
public final class PingTest {
	private int phase;
	private int wait = 60;
	private int waited;
	private int latencyStep;
	private long spikeDeadline;

	public static void install() {
		final PingTest test = new PingTest();
		System.setProperty("trsclient.latency.measure", "true");
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Ping: {}", text);
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
		TrsModules m = TrsClient.get().modules();
		try {
			step(mc, m);
		} catch (RuntimeException | LinkageError e) {
			TrsClient.LOGGER.error("[Autotest] Ping: Fehler in Phase {}", phase, e);
			phase = 99;
		}
	}

	private void step(Minecraft mc, TrsModules m) {
		switch (phase) {
			case 0:
				if (!waitFor(Mc.overlay() == null, 600)) return;
				m.ping.setEnabled(true);
				m.pingJitter.set(true);
				m.pingGraph.set(true);
				m.pingDetails.set(true);
				m.pingSpikeWarning.set(true);
				m.pingSpikeThreshold.set(150);
				m.pingInterval.set(1);
				m.netOptimize.setEnabled(true);
				m.lowLatency.setEnabled(false);
				Mc.setScreen(new JoinMultiplayerScreen(new TrsTitleScreen()));
				phase++;
				wait = 60;
				return;
			case 1:
				AutoTest.shot(mc, "trsclient-ping-list");
				VanillaMenus.startPingTest();
				log("Ping-Test gestartet");
				phase++;
				wait = 10;
				return;
			case 2:
				if (!waitFor(!ServerPingTest.shared().running(), 400)) return;
				phase++;
				wait = 20;
				return;
			case 3: {
				AutoTest.shot(mc, "trsclient-ping-list-sorted");
				if (Mc.screen() instanceof JoinMultiplayerScreen) {
					ServerList servers = ((JoinMultiplayerScreen) Mc.screen()).getServers();
					StringBuilder sb = new StringBuilder();
					for (int i = 0; i < servers.size(); i++) {
						ServerPingTest.Entry e = ServerPingTest.shared().entry(servers.get(i).ip);
						StatusPing.Result r = e == null ? null : e.result;
						sb.append(i).append(". ").append(servers.get(i).name).append(" = ").append(r).append("; ");
					}
					log("Reihenfolge nach Test: " + sb);
				}
				phase++;
				return;
			}
			case 4:
				if (!join(mc)) return;
				log("im Spiel – messe Ping");
				phase++;
				wait = 240; // 12 s messen (1 Anfrage je Sekunde)
				return;
			case 5: {
				PingMeter.Snapshot s = NetBoost.ping().snapshot(null, NetBoost.platform().millis(), System.nanoTime());
				log(String.format(Locale.ROOT, "Quelle=%s aktuell=%d ms Ø=%.1f ms Jitter=%.1f ms min=%d max=%d Zeitüberschr.=%.0f%% TPS=%.2f Verlauf=%d",
						s.source, s.current, s.average, s.jitter, s.min, s.max, s.timeoutPercent, s.tps, s.historyCount));
				log("Netz: " + NetBoost.STATS.summary() + ", Verbindung erkannt: " + (NetBoost.channel() != null));
				AutoTest.shot(mc, "trsclient-ping-hud");
				spikeDeadline = System.currentTimeMillis() + 35_000;
				phase++;
				return;
			}
			case 6: {
				// Der Test-Proxy baut regelmäßig eine Spitze ein – warten, bis die Warnung erscheint.
				PingMeter.Snapshot s = NetBoost.ping().snapshot(null, NetBoost.platform().millis(), System.nanoTime());
				if (s.spike) {
					log("Spitze erkannt: " + s.spikeValue + " ms");
					phase = 12; // erst ein paar Bilder später fotografieren (HUD liest die Werte alle 100 ms)
					wait = 10;
				} else if (System.currentTimeMillis() > spikeDeadline) {
					log("keine Spitze innerhalb von 35 s (Proxy ohne Spitzen?)");
					phase++;
				}
				return;
			}
			case 7:
				if (!latency(mc, m)) return;
				phase++;
				return;
			case 12:
				AutoTest.shot(mc, "trsclient-ping-spike");
				phase = 7;
				return;
			case 8:
				Mc.setScreen(new TrsMenuScreen(null).select(m.ping).scrollSettings(170));
				phase++;
				wait = 30;
				return;
			case 9:
				AutoTest.shot(mc, "trsclient-ping-settings");
				log("Modulseite Netz: Verbindung erkannt = " + (NetBoost.channel() != null));
				Mc.setScreen(new TrsMenuScreen(null).select(m.netOptimize));
				phase++;
				wait = 30;
				return;
			case 10:
				AutoTest.shot(mc, "trsclient-ping-netoptimize");
				Mc.setScreen(new TrsMenuScreen(null).select(m.lowLatency));
				phase++;
				wait = 80; // Modulseite misst ein paar Sekunden
				return;
			case 11:
				AutoTest.shot(mc, "trsclient-ping-lowlatency");
				Mc.setScreen(null);
				log("Netz am Ende: " + NetBoost.STATS.summary());
				log("fertig");
				phase = 99;
				wait = 10;
				return;
			default:
				if (phase == 99) {
					phase = 100;
					mc.stop();
				}
		}
	}

	/** Beitritt zum Test-Server (-Dtrsclient.autotest.server). true = im Spiel. */
	private boolean join(Minecraft mc) {
		String server = System.getProperty("trsclient.autotest.server");
		if (server == null || server.isEmpty()) {
			log("kein Test-Server angegeben – Spielteil übersprungen");
			phase = 7;
			return false;
		}
		//? if >=1.21.9 && <26.1 {
		/*if (latencyStep == 0) {
			latencyStep = -1;
			ServerData data = new ServerData("TRS Ping-Test", server, ServerData.Type.OTHER);
			ConnectScreen.startConnecting(new TrsTitleScreen(), mc, ServerAddress.parseString(server), data, false, null);
			log("verbinde mit " + server);
			wait = 20;
			return false;
		}
		if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null && Mc.overlay() == null, 800)) return false;
		latencyStep = 0;
		return true;
		*///?} else {
		log("Beitritt nur in 1.21.9–1.21.11 umgesetzt");
		phase = 7;
		return false;
		//?}
	}

	/** Je 6 s: aus (nur messen), ausgewogen, maximal – FPS, Streuung der Bildzeiten, GPU-Warteschlange, Eingabe-Alter. */
	private boolean latency(Minecraft mc, TrsModules m) {
		LowLatency ll = LatencyHooks.get();
		if (ll == null || !ll.available()) {
			log("Niedrige Eingabeverzögerung: nicht verfügbar");
			return true;
		}
		String[] names = {"aus", "ausgewogen", "maximal"};
		if (latencyStep == 0) {
			//? if >=1.19 {
			mc.options.framerateLimit().set(260);
			mc.options.enableVsync().set(false);
			//?}
		}
		if (latencyStep > 0) {
			PerfHooks.FRAME_STATS.stop(System.nanoTime());
			LowLatency.Stats st = ll.stats();
			log(String.format(Locale.ROOT, "Eingabe-Latenz %s: %.0f FPS, Bildzeit-Streuung %.2f ms, 1%%-Low %.0f FPS, GPU-Warteschlange Ø %.2f Bilder, gewartet Ø %.2f ms, Eingabe-Alter Ø %.2f ms",
					names[latencyStep - 1], PerfHooks.FRAME_STATS.averageFps(), PerfHooks.FRAME_STATS.stdDevMillis(),
					PerfHooks.FRAME_STATS.lowFps(0.01), st.avgQueued, st.avgWaitMillis, st.avgInputAgeMillis));
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
		// Vanillas AFK-Bremse (ab 1.21.2) würde nach einer Minute ohne Eingabe auf 30 FPS gehen.
		//? if >=1.21.2 {
		/*mc.getFramerateLimitTracker().onInputReceived();
		*///?}
		ll.resetStats();
		PerfHooks.FRAME_STATS.start(System.nanoTime());
		latencyStep++;
		wait = 120;
		return false;
	}
}

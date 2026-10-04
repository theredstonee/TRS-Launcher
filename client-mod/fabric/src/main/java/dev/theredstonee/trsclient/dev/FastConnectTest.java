package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.connect.FastConnect;
import dev.theredstonee.trsclient.core.connect.FastSwitch;
import dev.theredstonee.trsclient.core.connect.ServerPacks;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
//? if >=1.21.9 && <26.1 {
/*import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
*///?}

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Selbsttest „Schnell verbinden“ ({@code -PtrsAutotestOnly=fastconnect}, 1.21.9–1.21.11) gegen lokale Server:
 * <ol>
 *   <li>Adressen-Rennen ({@code -Dtrsclient.autotest.fc.broken=host:port}): Host mit toter erster Adresse (über
 *   {@code connect-hints.json}) – Zeit bis zur Verbindung.</li>
 *   <li>Ressourcenpaket ({@code -Dtrsclient.autotest.fc.pack=host:port}): Beitritt ohne Paket im Speicher, dann mit
 *   vorgeladenem Paket – Zeit bis im Spiel.</li>
 *   <li>Serverwechsel ({@code -Dtrsclient.autotest.server=proxy}, {@code fc.servers=a,b}, {@code fc.switches=N}):
 *   N Wechsel mit „Schneller Serverwechsel“ aus, dann an – Dauer je Wechsel.</li>
 * </ol>
 * Ergebnisse als Logzeilen „[Autotest] FastConnect: …“.
 */
public final class FastConnectTest {
	private int phase;
	private int wait = 60;
	private int waited;
	private long t0;
	private int step;
	private long lastSwitch = -1;
	private final List<Long> off = new ArrayList<Long>();
	private final List<Long> on = new ArrayList<Long>();
	private static final int WARMUP = 2;
	private boolean afkWaited;

	public static void install() {
		final FastConnectTest test = new FastConnectTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] FastConnect: {}", text);
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
			TrsClient.LOGGER.error("[Autotest] FastConnect: Fehler in Phase {}", phase, e);
			phase = 99;
		}
	}

	private static boolean inGame(Minecraft mc) {
		return mc.level != null && mc.player != null && Mc.screen() == null && Mc.overlay() == null;
	}

	private void step(Minecraft mc, TrsModules m) {
		String broken = System.getProperty("trsclient.autotest.fc.broken", "");
		String pack = System.getProperty("trsclient.autotest.fc.pack", "");
		String proxy = System.getProperty("trsclient.autotest.server", "");
		switch (phase) {
			case 0:
				if (!waitFor(Mc.overlay() == null, 600)) return;
				m.fastConnect.setEnabled(true);
				m.fastConnectPreResolve.set(true);
				m.fastConnectSwitch.set(true);
				m.fastConnectPacks.set(true);
				phase = broken.isEmpty() ? 10 : 1;
				return;
			// --- 1. Adressen-Rennen ---
			case 1:
				t0 = System.nanoTime();
				connect(mc, broken, false);
				log("verbinde mit " + broken + " (erste Adresse tot)");
				phase++;
				wait = 2;
				return;
			case 2:
				if (!waitFor(inGame(mc), 1200)) return;
				log(String.format(Locale.ROOT, "Rennen: im Spiel nach %d ms; Verbindung: %s über %s in %d ms, %d von %d Adressen versucht",
						ms(t0), FastConnect.LAST.host, FastConnect.LAST.ip, FastConnect.LAST.ms, FastConnect.LAST.attempts, FastConnect.LAST.addresses));
				AutoTest.disconnect(mc);
				Mc.setScreen(new TrsTitleScreen());
				phase = 10;
				wait = 40;
				return;
			// --- 2. Ressourcenpaket vorladen ---
			case 10:
				if (pack.isEmpty()) {
					phase = 20;
					return;
				}
				deleteDownloads(mc);
				t0 = System.nanoTime();
				connect(mc, pack, true);
				log("Paket-Beitritt ohne Speicher (kalt) zu " + pack);
				phase++;
				wait = 2;
				return;
			case 11:
				if (!waitFor(inGame(mc), 2400)) return;
				log(String.format(Locale.ROOT, "Paket: Beitritt kalt (Download beim Beitritt) %d ms", ms(t0)));
				AutoTest.disconnect(mc);
				Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 40;
				return;
			case 12:
				deleteDownloads(mc);
				t0 = System.nanoTime();
				ServerPacks.preload(pack, false);
				log("Paket vorladen (wie beim Auswählen in der Liste) …");
				phase++;
				return;
			case 13: {
				boolean done = preloaded(mc);
				if (!waitFor(done, 2400)) return;
				log(String.format(Locale.ROOT, "Paket: vorgeladen in %d ms (%s)", ms(t0), done ? "ok" : "fehlt"));
				t0 = System.nanoTime();
				connect(mc, pack, true);
				phase++;
				wait = 2;
				return;
			}
			case 14:
				if (!waitFor(inGame(mc), 2400)) return;
				log(String.format(Locale.ROOT, "Paket: Beitritt mit vorgeladenem Paket %d ms", ms(t0)));
				AutoTest.disconnect(mc);
				Mc.setScreen(new TrsTitleScreen());
				phase = 20;
				wait = 40;
				return;
			// --- 3. Serverwechsel im Proxy-Netzwerk ---
			case 20:
				if (proxy.isEmpty()) {
					phase = 99;
					return;
				}
				connect(mc, proxy, true);
				log("verbinde mit Proxy " + proxy);
				phase++;
				wait = 2;
				return;
			case 21:
				if (!waitFor(inGame(mc), 2400)) return;
				step = 0;
				phase++;
				wait = 60;
				return;
			case 22: {
				// 2 Aufwärm-Wechsel (JIT, Caches) zählen nicht; danach abwechselnd aus/an, damit Drift beide gleich trifft.
				int n = Integer.getInteger("trsclient.autotest.fc.switches", 5);
				String[] servers = System.getProperty("trsclient.autotest.fc.servers", "b,a").split(",");
				if (step >= WARMUP + 2 * n) {
					log("Wechsel aus: " + stats(off) + " | an: " + stats(on));
					phase = 99;
					return;
				}
				m.fastConnectSwitch.set(step < WARMUP || (step - WARMUP) % 2 == 1);
				lastSwitch = FastSwitch.count();
				// fc.afk: wie ein Spieler, der in der Lobby wartet – über eine Minute ohne Eingabe (Vanilla drosselt dann ab 1.21.2).
				boolean afk = Boolean.getBoolean("trsclient.autotest.fc.afk");
				if (afk && !afkWaited) {
					afkWaited = true;
					wait = 65 * 20;
					return;
				}
				afkWaited = false;
				//? if >=1.21.2 {
				/*if (!afk) mc.getFramerateLimitTracker().onInputReceived();
				*///?}
				Mc.sendChat("/server " + servers[step % servers.length].trim());
				t0 = System.nanoTime();
				phase++;
				wait = 2;
				return;
			}
			case 23: {
				boolean switched = FastSwitch.count() != lastSwitch && inGame(mc);
				if (waited == 899) {
					log("warte noch: Wechsel " + FastSwitch.count() + "/" + lastSwitch + ", Bildschirm " + Mc.screen() + ", Overlay "
							+ Mc.overlay() + ", Welt " + (mc.level != null) + ", Spieler " + (mc.player != null));
				}
				if (!waitFor(switched, 900)) return;
				long ms = FastSwitch.lastMs();
				if (step >= WARMUP) (m.fastConnectSwitch.get() ? on : off).add(ms);
				log(String.format(Locale.ROOT, "Wechsel %d (%s): %d ms (gemessen ab Senden %d ms)", step + 1,
						step < WARMUP ? "Aufwärmen" : m.fastConnectSwitch.get() ? "schnell an" : "schnell aus", ms, ms(t0)));
				step++;
				phase = 22;
				wait = 60;
				return;
			}
			case 99:
				log("fertig");
				phase = 100;
				mc.stop();
				return;
			default:
		}
	}

	private static long ms(long t0) {
		return (System.nanoTime() - t0) / 1_000_000L;
	}

	private static String stats(List<Long> l) {
		if (l.isEmpty()) return "-";
		long min = Long.MAX_VALUE;
		long max = 0;
		long sum = 0;
		for (long v : l) {
			min = Math.min(min, v);
			max = Math.max(max, v);
			sum += v;
		}
		return String.format(Locale.ROOT, "Ø %d ms (min %d, max %d, n=%d) %s", sum / l.size(), min, max, l.size(), l);
	}

	private static void connect(Minecraft mc, String address, boolean packsEnabled) {
		//? if >=1.21.9 && <26.1 {
		/*ServerData data = new ServerData("TRS FastConnect-Test", address, ServerData.Type.OTHER);
		if (packsEnabled) data.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
		ConnectScreen.startConnecting(new TrsTitleScreen(), mc, ServerAddress.parseString(address), data, false, null);
		*///?} else
		log("Beitritt nur in 1.21.9–1.21.11 umgesetzt");
	}

	/** Vanillas Paket-Speicher leeren (damit „kalt“ wirklich herunterlädt). */
	private static void deleteDownloads(Minecraft mc) {
		Path dir = mc.gameDirectory.toPath().resolve("downloads");
		try {
			if (Files.isDirectory(dir)) {
				try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
					s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
				}
			}
		} catch (java.io.IOException | RuntimeException e) {
			log("downloads/ nicht gelöscht: " + e);
		}
	}

	/** Liegt mindestens ein Paket in downloads/&lt;id&gt;/&lt;sha1&gt;? */
	private static boolean preloaded(Minecraft mc) {
		Path dir = mc.gameDirectory.toPath().resolve("downloads");
		try (java.util.stream.Stream<Path> s = Files.walk(dir, 2)) {
			return s.anyMatch(p -> Files.isRegularFile(p) && p.getFileName().toString().length() == 40);
		} catch (java.io.IOException | RuntimeException e) {
			return false;
		}
	}
}

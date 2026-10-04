package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.connect.DnsCache;
import dev.theredstonee.trsclient.core.connect.FastConnect;
import dev.theredstonee.trsclient.core.connect.FastSwitch;
import dev.theredstonee.trsclient.core.connect.ServerPacks;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selbsttest „Schnell verbinden“ für Forge 1.8.9 ({@code -PtrsAutotestOnly=fastconnect}) gegen lokale Server:
 * schnellste Adresse vorab (Java-Adress-Speicher), Ressourcenpaket kalt/vorgeladen, Serverwechsel über BungeeCord mit
 * gleichem Paket (aus/an). Logzeilen „[Autotest] FastConnect: …“.
 */
public final class FastConnectTest {
	private static final int WARMUP = 2;
	private int phase;
	private int wait = 40;
	private int waited;
	private long t0;
	private int step;
	private long lastSwitch = -1;
	private final List<Long> off = new ArrayList<Long>();
	private final List<Long> on = new ArrayList<Long>();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new FastConnectTest());
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] FastConnect: " + text);
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
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(Minecraft.getMinecraft(), TrsClient.get().modules());
		} catch (RuntimeException | LinkageError e) {
			TrsClient.LOGGER.error("[Autotest] FastConnect: Fehler in Phase " + phase, e);
			phase = 99;
		}
	}

	private static boolean inGame(Minecraft mc) {
		return Mc.world() != null && Mc.player() != null && mc.currentScreen == null;
	}

	private void step(Minecraft mc, TrsModules m) {
		String broken = System.getProperty("trsclient.autotest.fc.broken", "");
		String pack = System.getProperty("trsclient.autotest.fc.pack", "");
		String proxy = System.getProperty("trsclient.autotest.server", "");
		switch (phase) {
			case 0:
				m.fastConnect.setEnabled(true);
				m.fastConnectPreResolve.set(true);
				m.fastConnectSwitch.set(true);
				m.fastConnectPacks.set(true);
				phase = broken.isEmpty() ? 10 : 1;
				return;
			// --- 1. schnellste Adresse vorab (wie beim Auswählen in der Liste) ---
			case 1:
				t0 = System.nanoTime();
				FastConnect.prefetch(broken);
				phase++;
				return;
			case 2: {
				String host = broken.substring(0, broken.lastIndexOf(':'));
				if (!waitFor(FastConnect.CACHE.winner(host) != null, 200)) return;
				log(String.format(Locale.ROOT, "vorab aufgelöst + schnellste Adresse gemessen in %d ms", ms(t0)));
				FastConnect.primeJvm(broken);
				t0 = System.nanoTime();
				connect(mc, broken, true);
				phase++;
				wait = 1;
				return;
			}
			case 3:
				if (!waitFor(inGame(mc), 800)) return;
				log(String.format(Locale.ROOT, "Beitritt (erste DNS-Adresse tot): im Spiel nach %d ms", ms(t0)));
				leave(mc);
				phase = 10;
				wait = 40;
				return;
			// --- 2. Ressourcenpaket ---
			case 10:
				if (pack.isEmpty()) {
					phase = 20;
					return;
				}
				deletePacks(mc);
				t0 = System.nanoTime();
				connect(mc, pack, true);
				log("Paket-Beitritt ohne Speicher (kalt) zu " + pack);
				phase++;
				wait = 1;
				return;
			case 11:
				if (!waitFor(inGame(mc) && packLoaded(mc), 1600)) return;
				log(String.format(Locale.ROOT, "Paket: Beitritt kalt (Download beim Beitritt) %d ms", ms(t0)));
				leave(mc);
				phase++;
				wait = 40;
				return;
			case 12:
				deletePacks(mc);
				t0 = System.nanoTime();
				ServerPacks.preload(pack, false);
				phase++;
				return;
			case 13:
				if (!waitFor(countPacks(mc) > 0, 1600)) return;
				log(String.format(Locale.ROOT, "Paket: vorgeladen in %d ms", ms(t0)));
				t0 = System.nanoTime();
				connect(mc, pack, true);
				phase++;
				wait = 1;
				return;
			case 14:
				if (!waitFor(inGame(mc) && packLoaded(mc), 1600)) return;
				log(String.format(Locale.ROOT, "Paket: Beitritt mit vorgeladenem Paket %d ms", ms(t0)));
				leave(mc);
				phase = 20;
				wait = 40;
				return;
			// --- 3. Serverwechsel ---
			case 20:
				if (proxy.isEmpty()) {
					phase = 99;
					return;
				}
				connect(mc, proxy, true);
				phase++;
				wait = 1;
				return;
			case 21:
				if (!waitFor(inGame(mc), 1600)) return;
				step = 0;
				phase++;
				wait = 60;
				return;
			case 22: {
				int n = Integer.getInteger("trsclient.autotest.fc.switches", 5);
				String[] servers = System.getProperty("trsclient.autotest.fc.servers", "b,a").split(",");
				if (step >= WARMUP + 2 * n) {
					log("Wechsel aus: " + stats(off) + " | an: " + stats(on));
					phase = 99;
					return;
				}
				m.fastConnectSwitch.set(step < WARMUP || (step - WARMUP) % 2 == 1);
				lastSwitch = FastSwitch.count();
				Mc.player().sendChatMessage("/server " + servers[step % servers.length].trim());
				t0 = System.nanoTime();
				phase++;
				wait = 1;
				return;
			}
			case 23: {
				boolean switched = FastSwitch.count() != lastSwitch && inGame(mc);
				if (!waitFor(switched, 900)) return;
				long fromSend = ms(t0);
				if (step >= WARMUP) (m.fastConnectSwitch.get() ? on : off).add(fromSend);
				log(String.format(Locale.ROOT, "Wechsel %d (%s): %d ms ab Senden (Wechsel selbst %d ms)", step + 1,
						step < WARMUP ? "Aufwärmen" : m.fastConnectSwitch.get() ? "schnell an" : "schnell aus", fromSend, FastSwitch.lastMs()));
				step++;
				phase = 22;
				wait = 60;
				return;
			}
			case 99:
				log("fertig");
				phase = 100;
				leave(mc);
				mc.shutdown();
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
		ServerData data = new ServerData("TRS FastConnect-Test", address, false);
		if (packsEnabled) data.setResourceMode(ServerData.ServerResourceMode.ENABLED);
		mc.displayGuiScreen(new GuiConnecting(new GuiMultiplayer(new TrsTitleScreen()), mc, data));
		log("verbinde mit " + address);
	}

	private static void leave(Minecraft mc) {
		Mc.leaveWorld();
		mc.displayGuiScreen(new TrsTitleScreen());
	}

	private static File packDir(Minecraft mc) {
		return new File(Mc.gameDir(), "server-resource-packs");
	}

	private static void deletePacks(Minecraft mc) {
		File[] files = packDir(mc).listFiles();
		if (files == null) return;
		for (File f : files) if (f.getName().length() == 40 && !f.delete()) log("nicht gelöscht: " + f);
	}

	private static int countPacks(Minecraft mc) {
		File[] files = packDir(mc).listFiles();
		int n = 0;
		if (files != null) for (File f : files) if (f.getName().length() == 40) n++;
		return n;
	}

	/** Server-Paket aktiv und die Ressourcen neu geladen (das Test-Paket bringt die Domäne „trs“ mit)? */
	private static boolean packLoaded(Minecraft mc) {
		return mc.getResourceManager().getResourceDomains().contains("trs");
	}

	static String key(String host) {
		return DnsCache.key(host);
	}
}

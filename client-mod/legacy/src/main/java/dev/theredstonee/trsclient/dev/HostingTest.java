package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.PlayerRights;
import dev.theredstonee.trsclient.core.hosting.Rooms;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.hosting.HostingUi;
import dev.theredstonee.trsclient.core.ui.hosting.JoinUi;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Welt-Hosting mit ZWEI Spielen unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=hosting}, Rolle über
 * {@code -Dtrsclient.hosting.test.role=host|guest}, Absprache über Dateien in {@code -Dtrsclient.hosting.test.dir}):
 * Host öffnet die Testwelt, Gast tritt per Code bei (Anfrage → Host nimmt an). Screenshots:
 * trsclient-&lt;mc&gt;-hosting-&lt;rolle&gt;-*.png. Beendet das Spiel danach.
 */
public final class HostingTest {
	private final String role = System.getProperty("trsclient.hosting.test.role", "host");
	private final Path dir = Paths.get(System.getProperty("trsclient.hosting.test.dir", "hosting-test"));
	private final String mcVersion = Mc.version();
	private final String world = "trs-autotest-" + Mc.version();
	private int phase;
	private int wait;
	private int waited;
	private String guestUuid;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new HostingTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (wait > 0) {
			wait--;
			return;
		}
		int current = phase;
		try {
			if (role.equals("guest")) guest(mc);
			else host(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Hosting: Fehler in Phase {} – weiter", current, e);
			if (phase == current) phase++;
			wait = 5;
		}
		if (phase > 100) {
			phase = -1000;
			log("fertig");
			mc.shutdown();
		}
	}

	private void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Hosting/{}: {}", role, text);
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-hosting-" + role + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		log("Bild " + name);
	}

	private boolean waitFor(boolean ready, int max, String what) {
		if (!ready && waited++ < max) {
			wait = 5;
			return false;
		}
		if (!ready) log("ZEITÜBERSCHREITUNG: " + what);
		waited = 0;
		return true;
	}

	private void write(String file, String text) {
		try {
			Files.createDirectories(dir);
			Files.write(dir.resolve(file), text.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			log("Datei " + file + ": " + e);
		}
	}

	private String read(String file) {
		try {
			Path p = dir.resolve(file);
			return Files.isRegularFile(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim() : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static boolean signedIn() {
		TrsOnline o = TrsOnline.current();
		Hosting h = Hosting.current();
		return o != null && o.online() && h != null && h.signedIn();
	}

	private boolean ready(Minecraft mc) {
		GuiScreen s = Mc.screen();
		if (!(s instanceof TrsTitleScreen) && !(s instanceof GuiMainMenu)) return false;
		mc.gameSettings.pauseOnLostFocus = false;
		mc.gameSettings.renderDistanceChunks = 2;
		return true;
	}

	private static HostingUi hostingUi(GuiScreen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof HostingUi ? (HostingUi) ((TrsUiScreen) s).ui() : null;
	}

	private static JoinUi joinUi(GuiScreen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof JoinUi ? (JoinUi) ((TrsUiScreen) s).ui() : null;
	}

	private void host(Minecraft mc) {
		Hosting h = Hosting.current();
		switch (phase) {
			case 0:
				if (!ready(mc)) return;
				phase++;
				wait = 20;
				return;
			case 1:
				if (!waitFor(signedIn(), 600, "Anmeldung")) return;
				if (mc.getSaveLoader().canLoadWorld(world)) mc.launchIntegratedServer(world, world, null);
				else mc.launchIntegratedServer(world, world, Mc.creativeWorld(System.nanoTime()));
				phase++;
				return;
			case 2:
				if (!waitFor(Mc.world() != null && Mc.player() != null && Mc.screen() == null, 600, "Welt")) return;
				wait = 40;
				phase++;
				return;
			case 3:
				Mc.setScreen(new GuiIngameMenu());
				wait = 15;
				phase++;
				return;
			case 4:
				shot(mc, "pause");
				Mc.setScreen(MenuScreens.hosting(null));
				wait = 20;
				phase++;
				return;
			case 5: {
				shot(mc, "setup");
				HostingUi ui = hostingUi(Mc.screen());
				if (ui != null) ui.testHost(true);
				phase++;
				return;
			}
			case 6:
				if (!waitFor(h.hostState() == Hosting.HostState.OPEN, 600, "Welt offen")) return;
				wait = 20;
				phase++;
				return;
			case 7: {
				shot(mc, "open");
				Rooms.Room r = h.room();
				log("Raum " + (r == null ? "?" : r.prettyCode()) + ", Relay-Problem: " + h.relayProblem());
				if (r != null) write("code.txt", r.code);
				HostingUi ui = hostingUi(Mc.screen());
				if (ui != null) ui.testTab(1);
				phase++;
				return;
			}
			case 8: {
				Rooms.Room r = h.room();
				boolean requested = r != null && !r.members("requested").isEmpty();
				if (!waitFor(requested || h.guests().size() > 1, 1200, "Anfrage")) return;
				if (requested) {
					shot(mc, "request");
					guestUuid = r.members("requested").get(0).uuid;
					h.accept(guestUuid);
				}
				phase++;
				return;
			}
			case 9: {
				List<Hosting.Guest> g = h.guests();
				if (!waitFor(g.size() > 1, 1200, "Gast in der Welt")) return;
				for (Hosting.Guest x : g) {
					if (!x.host) {
						guestUuid = x.uuid;
						log("Gast " + x.name + " über " + x.path);
						write("host-sees.txt", x.name + " " + x.path);
					}
				}
				HostingUi ui = hostingUi(Mc.screen());
				if (ui != null) ui.testTab(0);
				wait = 20;
				phase++;
				return;
			}
			case 10:
				shot(mc, "players");
				if (guestUuid != null) h.setRights(guestUuid, PlayerRights.DEFAULT.withBuild(false));
				phase = 20;
				return;
			case 20:
				if (!waitFor(read("guest-done.txt") != null, 1200, "Gast fertig")) return;
				h.stopHosting("closed");
				wait = 40;
				phase++;
				return;
			case 21:
				log("Status nach Beenden: " + h.hostState());
				write("host-done.txt", "ok");
				phase = 101;
				return;
			default:
		}
	}

	private void guest(Minecraft mc) {
		Hosting h = Hosting.current();
		switch (phase) {
			case 0:
				if (!ready(mc)) return;
				phase++;
				wait = 20;
				return;
			case 1:
				if (!waitFor(signedIn(), 600, "Anmeldung")) return;
				phase++;
				return;
			case 2: {
				String code = read("code.txt");
				if (!waitFor(code != null, 2400, "Code vom Host")) return;
				Mc.setScreen(MenuScreens.join(new TrsTitleScreen()));
				wait = 20;
				phase++;
				return;
			}
			case 3: {
				shot(mc, "join");
				JoinUi ui = joinUi(Mc.screen());
				if (ui != null) ui.testCode(read("code.txt"));
				phase = 20;
				return;
			}
			case 20:
				if (!waitFor(Mc.world() != null && Mc.player() != null, 2400, "in der Welt des Hosts")) return;
				wait = 80;
				phase++;
				return;
			case 21:
				shot(mc, "joined");
				log("Weg: " + h.guestPath() + ", Zustand " + h.guestState());
				write("guest-path.txt", String.valueOf(h.guestPath()));
				wait = 200;
				phase++;
				return;
			case 22:
				shot(mc, "later");
				Mc.leaveWorld();
				Mc.setScreen(new GuiMainMenu());
				write("guest-done.txt", "ok");
				wait = 40;
				phase = 101;
				return;
			default:
		}
	}
}

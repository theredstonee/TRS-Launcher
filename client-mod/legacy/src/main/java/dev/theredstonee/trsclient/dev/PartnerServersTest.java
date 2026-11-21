package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.intro.IntroGate;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.partner.PartnerServer;
import dev.theredstonee.trsclient.core.partner.PartnerServers;
import dev.theredstonee.trsclient.core.ui.menus.PartnerSection;
import dev.theredstonee.trsclient.menus.LegacyPartners;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

/**
 * Selbsttest Partner-Server ({@code -PtrsAutotestOnly=partnerservers}, mit {@code scripts/mock-partner-api.mjs} als
 * {@code -PtrsApi}) unter 1.8.9–1.12.2: Abschnitt, Logo und Ping, Hover, Beitritt ohne servers.dat-Eintrag,
 * „ausblenden ✕“ und wieder an. Screenshots trsclient-&lt;mc&gt;-partnerservers-*.png.
 */
public final class PartnerServersTest {
	static final String OWN_SERVER = "127.0.0.1:25603";
	private final String mcVersion = Mc.version();
	private int phase;
	private int wait;
	private int waited;
	private int failures;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new PartnerServersTest());
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Partner: {}", text);
	}

	private void check(boolean ok, String what) {
		if (ok) {
			log("OK " + what);
		} else {
			failures++;
			TrsClient.LOGGER.error("[Autotest] Partner: FEHLER {}", what);
		}
	}

	private boolean waitFor(boolean ready, int maxTries) {
		if (!ready && waited++ < maxTries) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-partnerservers" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		log("Bild " + file);
	}

	private static int count(Minecraft mc, String ip) {
		ServerList list = new ServerList(mc);
		list.loadServerList();
		int n = 0;
		for (int i = 0; i < list.countServers(); i++) if (ip.equals(list.getServerData(i).serverIP)) n++;
		return n;
	}

	private static void ownServer(Minecraft mc) {
		if (count(mc, OWN_SERVER) > 0) return;
		ServerList list = new ServerList(mc);
		list.loadServerList();
		list.addServerData(new ServerData("Mein SMP", OWN_SERVER, false));
		list.saveServerList();
	}

	private static void setShown(boolean on) {
		TrsModules m = IntroGate.modules();
		m.showPartnerServers.set(on);
		IntroGate.save(m);
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END || phase >= 999) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Partner: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void step(Minecraft mc) {
		GuiScreen screen = mc.currentScreen;
		switch (phase) {
			case 0:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				ownServer(mc);
				setShown(true);
				mc.displayGuiScreen(new GuiMultiplayer(new TrsTitleScreen()));
				phase++;
				wait = 10;
				return;
			case 1: {
				List<PartnerServer> shown = LegacyPartners.shown();
				if (!waitFor(!shown.isEmpty(), 120)) return;
				check(!shown.isEmpty(), "Partner-Server geladen: " + shown.size());
				for (PartnerServer s : shown) log("  " + s.name + " (" + s.address + ", " + s.minVersion + "–" + s.maxVersion + ")");
				phase++;
				return;
			}
			case 2: {
				boolean ready = true;
				for (PartnerServer s : LegacyPartners.shown()) {
					ready &= PartnerServers.get().pings().result(s.address, System.currentTimeMillis()) != null;
					ready &= s.icon == null || PartnerServers.get().logo(s, System.currentTimeMillis()) != null;
				}
				if (!waitFor(ready, 60)) return;
				check(ready, "Logos und Ping da");
				int[] at = LegacyPartners.lastDrawn();
				check(at != null, "Abschnitt gezeichnet" + (at == null ? "" : " bei y=" + at[1] + ", Höhe "
						+ PartnerSection.height(LegacyPartners.shown().size())));
				wait = 10;
				phase++;
				return;
			}
			case 3: {
				shot(mc, "");
				int[] at = LegacyPartners.lastDrawn();
				if (at != null) LegacyPartners.testMouse(at[0] + 60, PartnerSection.rowTop(at[1], 0) + 16);
				wait = 10;
				phase++;
				return;
			}
			case 4:
				shot(mc, "-hover");
				LegacyPartners.testMouse(-1, -1);
				IntroGate.modules().menuMultiplayer.set(false);
				wait = 10;
				phase = 40;
				return;
			case 40: {
				shot(mc, "-classic");
				IntroGate.modules().menuMultiplayer.set(true);
				int[] p = LegacyPartners.lastDrawn();
				PartnerServer first = LegacyPartners.shown().get(0);
				check(LegacyPartners.click(p[0] + 60, PartnerSection.rowTop(p[1], 0) + 16, 0), "Klick auf " + first.name);
				wait = 40;
				phase = 5;
				return;
			}
			case 5:
				check(!(screen instanceof GuiMultiplayer), "Klick verbindet (Bildschirm " + (screen == null ? "-" : screen.getClass().getSimpleName()) + ")");
				check(count(mc, LegacyPartners.shown().get(0).address) == 0, "nicht in servers.dat gespeichert");
				mc.displayGuiScreen(new GuiMultiplayer(new TrsTitleScreen()));
				wait = 30;
				phase++;
				return;
			case 6: {
				int[] p = LegacyPartners.lastDrawn();
				check(p != null, "Abschnitt nach Rückkehr wieder da");
				if (p == null) {
					phase = 9;
					return;
				}
				check(LegacyPartners.click(p[0] + p[2] - 4, p[1] + 5, 0), "Klick auf „ausblenden ✕“");
				wait = 10;
				phase++;
				return;
			}
			case 7:
				check(!IntroGate.modules().showPartnerServers.get(), "Einstellung aus");
				check(LegacyPartners.shown().isEmpty() && LegacyPartners.lastDrawn() == null, "Abschnitt weg");
				shot(mc, "-hidden");
				setShown(true);
				wait = 10;
				phase++;
				return;
			case 8:
				check(!LegacyPartners.shown().isEmpty(), "Einstellung an → Abschnitt wieder da");
				phase++;
				return;
			default:
				log(failures == 0 ? "alles OK" : failures + " FEHLER");
				phase = 999;
				mc.shutdown();
		}
	}
}

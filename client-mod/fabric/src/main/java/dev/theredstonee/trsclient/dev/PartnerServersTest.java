package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.intro.IntroGate;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.partner.PartnerServer;
import dev.theredstonee.trsclient.core.partner.PartnerServers;
import dev.theredstonee.trsclient.core.ui.menus.PartnerSection;
import dev.theredstonee.trsclient.menus.PartnerList;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.util.List;

/**
 * Selbsttest Partner-Server ({@code -PtrsAutotestOnly=partnerservers}, mit {@code scripts/mock-partner-api.mjs} als
 * {@code -PtrsApi}): Abschnitt über den eigenen Servern, Logo und Ping, Hover, Beitritt ohne servers.dat-Eintrag,
 * „ausblenden ✕“ und wieder an. Screenshots trsclient-&lt;mc&gt;-partnerservers-*.png, Ergebnis als „OK“/„FEHLER“ im Log.
 */
public final class PartnerServersTest {
	static final String OWN_SERVER = "127.0.0.1:25603";
	private int phase;
	private int wait;
	private int waited;
	private int failures;

	public static void install() {
		PartnerServersTest test = new PartnerServersTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
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

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Partner: Fehler in Phase {}", phase, e);
			mc.stop();
			phase = 999;
		}
	}

	private static ServerData data(String name, String ip) {
		//? if >=1.20.2 {
		return new ServerData(name, ip, ServerData.Type.OTHER);
		//?} else
		/*return new ServerData(name, ip, false);*/
	}

	private static int count(Minecraft mc, String ip) {
		ServerList list = new ServerList(mc);
		list.load();
		int n = 0;
		for (int i = 0; i < list.size(); i++) if (ip.equals(list.get(i).ip)) n++;
		return n;
	}

	private static void ownServer(Minecraft mc) {
		if (count(mc, OWN_SERVER) > 0) return;
		ServerList list = new ServerList(mc);
		list.load();
		//? if >=1.19 {
		list.add(data("Mein SMP", OWN_SERVER), false);
		//?} else
		/*list.add(data("Mein SMP", OWN_SERVER));*/
		list.save();
	}

	private static void setShown(boolean on) {
		TrsModules m = IntroGate.modules();
		m.showPartnerServers.set(on);
		IntroGate.save(m);
	}

	private void step(Minecraft mc) {
		Screen screen = Mc.screen();
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || screen == null) return;
				mc.options.pauseOnLostFocus = false;
				ownServer(mc);
				setShown(true);
				Mc.setScreen(new JoinMultiplayerScreen(new TrsTitleScreen()));
				phase++;
				wait = 10;
				return;
			case 1: {
				List<PartnerServer> shown = PartnerList.shown();
				if (!waitFor(!shown.isEmpty(), 120)) return;
				check(!shown.isEmpty(), "Partner-Server geladen: " + shown.size());
				for (PartnerServer s : shown) log("  " + s.name + " (" + s.address + ", " + s.minVersion + "–" + s.maxVersion + ")");
				phase++;
				return;
			}
			case 2: {
				// Logo und Ping abwarten
				boolean ready = true;
				for (PartnerServer s : PartnerList.shown()) {
					ready &= PartnerServers.get().pings().result(s.address, System.currentTimeMillis()) != null;
					ready &= s.icon == null || PartnerServers.get().logo(s, System.currentTimeMillis()) != null;
				}
				if (!waitFor(ready, 60)) return;
				check(ready, "Logos und Ping da");
				int[] at = PartnerList.lastDrawn();
				check(at != null, "Abschnitt gezeichnet" + (at == null ? "" : " bei y=" + at[1] + ", Höhe "
						+ PartnerSection.height(PartnerList.shown().size())));
				wait = 10;
				phase++;
				return;
			}
			case 3:
				AutoTest.shot(mc, "trsclient-partnerservers");
				int[] at = PartnerList.lastDrawn();
				if (at != null) AutoTest.moveMouse(mc, at[0] + 60, PartnerSection.rowTop(at[1], 0) + 16);
				wait = 10;
				phase++;
				return;
			case 4:
				AutoTest.shot(mc, "trsclient-partnerservers-hover");
				AutoTest.moveMouse(mc, 0, 0);
				IntroGate.modules().menuMultiplayer.set(false);
				wait = 10;
				phase = 40;
				return;
			case 40: {
				AutoTest.shot(mc, "trsclient-partnerservers-classic");
				IntroGate.modules().menuMultiplayer.set(true);
				int[] p = PartnerList.lastDrawn();
				PartnerServer first = PartnerList.shown().get(0);
				AutoTest.click(mc, p[0] + 60, PartnerSection.rowTop(p[1], 0) + 16);
				log("Klick auf " + first.name);
				wait = 20;
				phase = 5;
				return;
			}
			case 5:
				check(!(screen instanceof JoinMultiplayerScreen), "Klick verbindet (Bildschirm " + (screen == null ? "-" : screen.getClass().getSimpleName()) + ")");
				check(count(mc, PartnerList.shown().isEmpty() ? "-" : PartnerList.shown().get(0).address) == 0, "nicht in servers.dat gespeichert");
				Mc.setScreen(new JoinMultiplayerScreen(new TrsTitleScreen()));
				wait = 30;
				phase++;
				return;
			case 6: {
				int[] p = PartnerList.lastDrawn();
				check(p != null, "Abschnitt nach Rückkehr wieder da");
				if (p == null) {
					phase = 9;
					return;
				}
				AutoTest.click(mc, p[0] + p[2] - 4, p[1] + 5);
				wait = 10;
				phase++;
				return;
			}
			case 7:
				check(!IntroGate.modules().showPartnerServers.get(), "„ausblenden ✕“ schaltet die Einstellung aus");
				check(PartnerList.shown().isEmpty() && PartnerList.lastDrawn() == null, "Abschnitt weg");
				AutoTest.shot(mc, "trsclient-partnerservers-hidden");
				setShown(true);
				wait = 10;
				phase++;
				return;
			case 8:
				check(!PartnerList.shown().isEmpty(), "Einstellung an → Abschnitt wieder da");
				phase++;
				return;
			default:
				log(failures == 0 ? "alles OK" : failures + " FEHLER");
				mc.stop();
				phase = 999;
		}
	}
}

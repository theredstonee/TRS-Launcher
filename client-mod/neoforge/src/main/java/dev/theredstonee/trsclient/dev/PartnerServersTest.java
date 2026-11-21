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
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.util.List;

/**
 * Selbsttest Partner-Server ({@code -PtrsAutotestOnly=partnerservers}, mit {@code scripts/mock-partner-api.mjs} als
 * {@code -PtrsApi}): Abschnitt, Logo und Ping, Hover, Beitritt ohne servers.dat-Eintrag, „ausblenden ✕“ und wieder an.
 * Ohne echte Maus: Hover über {@link PartnerList#testMouse}, Klicks direkt über {@link PartnerList#click}.
 */
public final class PartnerServersTest {
	static final String OWN_SERVER = "127.0.0.1:25603";
	private final String prefix;
	private int phase;
	private int wait;
	private int waited;
	private int failures;

	PartnerServersTest(String prefix) {
		this.prefix = prefix;
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
		Screenshot.grab(mc.gameDirectory, prefix + "partnerservers" + name + ".png", Mc.mainRenderTarget(),
				//? if >=1.21.6
				//1,
				msg -> TrsClient.LOGGER.info("[Autotest] {}", msg.getString()));
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
		list.add(data("Mein SMP", OWN_SERVER), false);
		list.save();
	}

	private static void setShown(boolean on) {
		TrsModules m = IntroGate.modules();
		m.showPartnerServers.set(on);
		IntroGate.save(m);
	}

	private static Object serverList(Screen s) {
		return s instanceof JoinMultiplayerScreen ? ((dev.theredstonee.trsclient.mixin.JoinMultiplayerAccessor) s).trsclient$serverList() : null;
	}

	/** Je Client-Tick aus dem Autotest. */
	public void tick(Minecraft mc) {
		if (phase >= 999) return;
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Partner: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.stop();
		}
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
				phase++;
				return;
			}
			case 2: {
				boolean ready = true;
				for (PartnerServer s : PartnerList.shown()) {
					ready &= PartnerServers.get().pings().result(s.address, System.currentTimeMillis()) != null;
					ready &= s.icon == null || PartnerServers.get().logo(s, System.currentTimeMillis()) != null;
				}
				if (!waitFor(ready, 60)) return;
				check(ready, "Logos und Ping da");
				int[] at = PartnerList.lastDrawn();
				check(at != null, "Abschnitt gezeichnet" + (at == null ? "" : " bei y=" + at[1]));
				if (at != null) PartnerList.testMouse(at[0] + 60, PartnerSection.rowTop(at[1], 0) + 16);
				wait = 10;
				phase++;
				return;
			}
			case 3: {
				shot(mc, "-hover");
				PartnerList.testMouse(-1, -1);
				int[] p = PartnerList.lastDrawn();
				PartnerServer first = PartnerList.shown().get(0);
				check(PartnerList.click(serverList(screen), p[0] + 60, PartnerSection.rowTop(p[1], 0) + 16, 0), "Klick auf " + first.name);
				wait = 40;
				phase++;
				return;
			}
			case 4:
				check(!(screen instanceof JoinMultiplayerScreen), "Klick verbindet (Bildschirm " + (screen == null ? "-" : screen.getClass().getSimpleName()) + ")");
				check(count(mc, PartnerList.shown().get(0).address) == 0, "nicht in servers.dat gespeichert");
				Mc.setScreen(new JoinMultiplayerScreen(new TrsTitleScreen()));
				wait = 30;
				phase++;
				return;
			case 5: {
				int[] p = PartnerList.lastDrawn();
				check(p != null, "Abschnitt nach Rückkehr wieder da");
				if (p == null) {
					phase = 8;
					return;
				}
				check(PartnerList.click(serverList(screen), p[0] + p[2] - 4, p[1] + 5, 0), "Klick auf „ausblenden ✕“");
				wait = 10;
				phase++;
				return;
			}
			case 6:
				check(!IntroGate.modules().showPartnerServers.get(), "Einstellung aus");
				check(PartnerList.shown().isEmpty() && PartnerList.lastDrawn() == null, "Abschnitt weg");
				setShown(true);
				wait = 10;
				phase++;
				return;
			case 7:
				check(!PartnerList.shown().isEmpty(), "Einstellung an → Abschnitt wieder da");
				phase++;
				return;
			default:
				log(failures == 0 ? "alles OK" : failures + " FEHLER");
				phase = 999;
				mc.stop();
		}
	}
}

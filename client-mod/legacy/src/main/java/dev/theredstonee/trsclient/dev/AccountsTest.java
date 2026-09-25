package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.account.AccountManager;
import dev.theredstonee.trsclient.core.account.GameAccount;
import dev.theredstonee.trsclient.screen.AccountsScreen;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest Kontowechsel ({@code -PtrsAutotestOnly=accounts}): Kontobildschirm auf dem Titelbildschirm, Wechsel zum
 * zweiten Konto der Launcher-Attrappe ({@code TRS_CLIENT_LINK}), Screenshots, danach Spielende.
 */
public final class AccountsTest {
	private int phase;
	private int wait;
	private int waited;
	private final String mcVersion = Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new AccountsTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END) tick(Minecraft.getMinecraft());
	}

	/** Gesichter geladen (oder endgültig ohne)? */
	private static boolean facesSettled(AccountManager m) {
		dev.theredstonee.trsclient.core.account.FaceCache faces = dev.theredstonee.trsclient.core.account.FaceCache.shared(m.userAgent());
		for (GameAccount a : m.state().accounts) {
			if (!faces.settled(a.uuid)) return false;
		}
		return true;
	}

	private static void log(String what) {
		AccountManager m = AccountManager.get();
		AccountManager.State s = m == null ? null : m.state();
		StringBuilder names = new StringBuilder();
		if (s != null) {
			for (GameAccount a : s.accounts) names.append(a.name).append('/').append(a.source).append(' ');
		}
		TrsClient.LOGGER.info("[Autotest] Konten {}: modus={} aufgabe={} aktuell={} meldung={} liste=[{}] mc-user={}", what,
				s == null ? null : s.mode, s == null ? null : s.task, s == null ? null : s.current,
				s == null ? null : s.message, names.toString().trim(), Minecraft.getMinecraft().getSession().getUsername());
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Screenshot {}", file);
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		AccountManager m = AccountManager.get();
		switch (phase) {
			case 0:
				if (!(mc.currentScreen instanceof TrsTitleScreen) && !(mc.currentScreen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				phase++;
				wait = 20;
				return;
			case 1:
				mc.displayGuiScreen(AccountsScreen.create(mc.currentScreen));
				log("geöffnet");
				phase++;
				wait = 40;
				return;
			case 2:
				if ((m.state().mode != AccountManager.Mode.LOCAL && m.state().accounts.size() < 2 || !facesSettled(m)) && waited++ < 30) {
					wait = 10;
					return;
				}
				log("Liste");
				shot(mc, "accounts");
				phase++;
				return;
			case 3: {
				GameAccount target = null;
				for (GameAccount a : m.state().accounts) {
					if (!a.uuid.equals(m.state().current)) target = a;
				}
				if (target == null || m.state().mode != AccountManager.Mode.LAUNCHER) {
					TrsClient.LOGGER.info("[Autotest] Konten: kein zweites Launcher-Konto – Wechsel übersprungen");
					phase = 5;
					return;
				}
				TrsClient.LOGGER.info("[Autotest] Konten: wechsle zu {}", target.name);
				m.switchTo(target.uuid);
				waited = 0;
				phase++;
				wait = 10;
				return;
			}
			case 4:
				if (m.state().task != AccountManager.Task.NONE && waited++ < 60) {
					wait = 5;
					return;
				}
				log("gewechselt");
				shot(mc, "accounts-switched");
				phase++;
				return;
			case 5:
				mc.displayGuiScreen(new TrsMenuScreen(new TrsTitleScreen()));
				phase++;
				wait = 30;
				return;
			case 6:
				shot(mc, "menu-accounts");
				TrsClient.LOGGER.info("[Autotest] Konten fertig");
				phase++;
				wait = 20;
				return;
			case 7:
				phase++;
				mc.shutdown();
				return;
			default:
		}
	}
}

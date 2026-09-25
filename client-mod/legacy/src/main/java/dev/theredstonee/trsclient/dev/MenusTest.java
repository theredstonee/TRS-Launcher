package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import dev.theredstonee.trsclient.core.ui.friends.FriendsUi;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest Menüs ({@code -PtrsAutotestOnly=menus}) unter 1.8.9–1.12.2: Serverliste, Einstellungen, Grafik,
 * Weltenliste, Freunde (API-Attrappe), Clips &amp; Bilder, Pausenmenü mit TRS-Knöpfen, Server-Info. Beendet das
 * Spiel direkt nach den Bildern.
 */
public final class MenusTest {
	private int phase;
	private int wait;
	private int waited;
	private final String mcVersion = Mc.version();
	private final String world = "trs-autotest-" + Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new MenusTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		try {
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Menüs: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-menus-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Menüs: Bild {} (Hintergrund Ø {} µs)", name, Math.round(MenuSkin.averageMicros()));
	}

	private boolean waitFor(boolean ready, int max) {
		if (!ready && waited++ < max) {
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
		GuiScreen screen = mc.currentScreen;
		switch (phase) {
			case 0:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				mc.gameSettings.renderDistanceChunks = 2;
				phase++;
				wait = 10;
				return;
			case 1:
				mc.displayGuiScreen(new GuiMultiplayer(new TrsTitleScreen()));
				phase++;
				wait = 40;
				return;
			case 2:
				shot(mc, "multiplayer");
				mc.displayGuiScreen(new GuiOptions(new TrsTitleScreen(), mc.gameSettings));
				phase++;
				wait = 10;
				return;
			case 3:
				shot(mc, "options");
				mc.displayGuiScreen(new GuiVideoSettings(screen, mc.gameSettings));
				phase++;
				wait = 10;
				return;
			case 4:
				shot(mc, "options-video");
				mc.displayGuiScreen(Mc.worldSelectScreen(new TrsTitleScreen()));
				phase++;
				wait = 20;
				return;
			case 5:
				shot(mc, "worlds");
				mc.displayGuiScreen(MenuScreens.friends(new TrsTitleScreen()));
				phase++;
				wait = 10;
				return;
			case 6: {
				TrsOnline online = TrsOnline.current();
				Friends.Snapshot s = online == null ? null : online.friends().snapshot();
				if (!waitFor(s != null && s.view != null, 120)) return;
				phase++;
				wait = 40;
				return;
			}
			case 7:
				shot(mc, "friends");
				if (screen instanceof TrsUiScreen && ((TrsUiScreen) screen).ui() instanceof FriendsUi) {
					((FriendsUi) ((TrsUiScreen) screen).ui()).showTab(1);
				}
				phase++;
				wait = 10;
				return;
			case 8:
				shot(mc, "friends-requests");
				mc.displayGuiScreen(MenuScreens.clips(new TrsTitleScreen()));
				phase++;
				wait = 10;
				return;
			case 9: {
				ClipsUi ui = screen instanceof TrsUiScreen && ((TrsUiScreen) screen).ui() instanceof ClipsUi
						? (ClipsUi) ((TrsUiScreen) screen).ui() : null;
				if (!waitFor(ui != null && ui.settled(), 100)) return;
				phase++;
				wait = 10;
				return;
			}
			case 10:
				shot(mc, "clips");
				if (mc.getSaveLoader().canLoadWorld(world)) {
					mc.launchIntegratedServer(world, world, null);
				} else {
					mc.launchIntegratedServer(world, world, Mc.creativeWorld(System.nanoTime()));
				}
				phase++;
				return;
			case 11:
				if (!waitFor(Mc.world() != null && Mc.player() != null && mc.currentScreen == null, 600)) return;
				phase++;
				wait = 40;
				return;
			case 12:
				mc.displayGuiScreen(new GuiIngameMenu());
				phase++;
				wait = 15;
				return;
			case 13:
				if (!(screen instanceof GuiIngameMenu) && waited++ < 5) {
					// Etwas hat das Pausenmenü geschlossen (z. B. Fokuswechsel) – erneut öffnen.
					mc.displayGuiScreen(new GuiIngameMenu());
					wait = 15;
					return;
				}
				waited = 0;
				shot(mc, "pause");
				mc.displayGuiScreen(MenuScreens.serverInfo(screen));
				phase++;
				wait = 15;
				return;
			case 14:
				shot(mc, "serverinfo");
				mc.displayGuiScreen(new GuiOptions(new GuiIngameMenu(), mc.gameSettings));
				phase++;
				wait = 15;
				return;
			case 15:
				shot(mc, "options-ingame");
				TrsClient.LOGGER.info("[Autotest] Menüs: fertig");
				phase++;
				mc.shutdown();
				return;
			default:
		}
	}
}

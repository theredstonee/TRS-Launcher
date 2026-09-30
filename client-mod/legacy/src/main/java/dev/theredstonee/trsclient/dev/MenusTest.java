package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.online.Friends;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import dev.theredstonee.trsclient.core.ui.social.SocialUi;
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
	/** Unterschritte in Phase 2 (Direkt verbinden, Server hinzufügen, Löschen-Dialog). */
	private int mpStep;
	private final String mcVersion = Mc.version();
	private final String world = "trs-autotest-" + Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new MenusTest());
	}

	/**
	 * {@code -PtrsAutotestOnly=addserver}: „Server hinzufügen“ und „Direkt verbinden“ mit breiter GUI (GUI-Größe 1) und
	 * einem fremden Knopf oben rechts (wie ihn andere Mods per InitGuiEvent setzen), dazu GUI-Größe 2. Die Formular-Fläche
	 * muss kompakt um das Formular bleiben. Screenshots trsclient-&lt;mc&gt;-menus-*-wide/-narrow.png.
	 */
	public static void installAddServer() {
		MenusTest test = new MenusTest();
		test.phase = 100;
		MinecraftForge.EVENT_BUS.register(test);
	}

	/** Nur im Formular-Test: fremder Knopf in der Ecke oben rechts (wie „Set version“ von ViaFabricPlus). */
	@SubscribeEvent
	public void onInit(net.minecraftforge.client.event.GuiScreenEvent.InitGuiEvent.Post event) {
		if (phase < 100) return;
		GuiScreen s = Mc.eventGui(event);
		if (!(s instanceof net.minecraft.client.gui.GuiScreenAddServer) && !(s instanceof net.minecraft.client.gui.GuiScreenServerList)) return;
		//? if >=1.9 {
		/*java.util.List<net.minecraft.client.gui.GuiButton> list = event.getButtonList();
		*///?} else
		java.util.List<net.minecraft.client.gui.GuiButton> list = event.buttonList;
		list.add(new net.minecraft.client.gui.GuiButton(4711, s.width - 98 - 5, 5, 98, 20, "Set version"));
	}

	private void addServerTick(Minecraft mc) {
		GuiScreen screen = mc.currentScreen;
		switch (phase) {
			case 100:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				mc.gameSettings.guiScale = 1;
				mc.displayGuiScreen(new net.minecraft.client.gui.GuiScreenAddServer(new GuiMultiplayer(new TrsTitleScreen()), testServer()));
				phase++;
				wait = 15;
				return;
			case 101:
				TrsClient.LOGGER.info("[Autotest] Menüs: Fenster {}×{}, GUI {}×{}", mc.displayWidth, mc.displayHeight, screen.width, screen.height);
				shot(mc, "add-server-wide");
				mc.displayGuiScreen(new net.minecraft.client.gui.GuiScreenServerList(new GuiMultiplayer(new TrsTitleScreen()), testServer()));
				phase++;
				wait = 15;
				return;
			case 102:
				shot(mc, "direct-connect-wide");
				mc.gameSettings.guiScale = 2;
				mc.displayGuiScreen(new net.minecraft.client.gui.GuiScreenAddServer(new GuiMultiplayer(new TrsTitleScreen()), testServer()));
				phase++;
				wait = 15;
				return;
			case 103:
				TrsClient.LOGGER.info("[Autotest] Menüs: GUI schmal {}×{}", screen.width, screen.height);
				shot(mc, "add-server-narrow");
				mc.gameSettings.guiScale = 0;
				TrsClient.LOGGER.info("[Autotest] Menüs: Formular-Test fertig");
				phase = 999;
				mc.shutdown();
				return;
			default:
		}
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		try {
			if (phase >= 100 && phase < 999) {
				if (wait > 0) wait--;
				else addServerTick(mc);
				return;
			}
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

	private static net.minecraft.client.multiplayer.ServerData testServer() {
		return new net.minecraft.client.multiplayer.ServerData("TRS Testserver", "play.trs-test.net", false);
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
				// Serverliste → Direkt verbinden → Server hinzufügen → „Server löschen?“ (Formulare, Textfelder, Dialog).
				if (mpStep == 0) {
					shot(mc, "multiplayer");
					mc.displayGuiScreen(new net.minecraft.client.gui.GuiScreenServerList(screen, testServer()));
					mpStep = 1;
					wait = 10;
					return;
				}
				if (mpStep == 1) {
					shot(mc, "direct-connect");
					mc.displayGuiScreen(new net.minecraft.client.gui.GuiScreenAddServer(screen, testServer()));
					mpStep = 2;
					wait = 10;
					return;
				}
				if (mpStep == 2) {
					shot(mc, "add-server");
					String q = net.minecraft.client.resources.I18n.format("selectServer.deleteQuestion");
					String w = "'TRS Testserver' " + net.minecraft.client.resources.I18n.format("selectServer.deleteWarning");
					mc.displayGuiScreen(new net.minecraft.client.gui.GuiYesNo(new net.minecraft.client.gui.GuiYesNoCallback() {
						@Override
						public void confirmClicked(boolean result, int id) {
							// nur fürs Bild
						}
					}, q, w, net.minecraft.client.resources.I18n.format("selectServer.deleteButton"),
							net.minecraft.client.resources.I18n.format("gui.cancel"), 0));
					mpStep = 3;
					wait = 10;
					return;
				}
				shot(mc, "delete-server");
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
				// Sozial-Bildschirm: Reiter „Freunde“ (Liste).
				if (screen instanceof TrsUiScreen && ((TrsUiScreen) screen).ui() instanceof SocialUi) {
					((SocialUi) ((TrsUiScreen) screen).ui()).showTab(1);
				}
				phase++;
				wait = 40;
				return;
			}
			case 7:
				shot(mc, "friends");
				if (screen instanceof TrsUiScreen && ((TrsUiScreen) screen).ui() instanceof SocialUi) {
					((SocialUi) ((TrsUiScreen) screen).ui()).showFriendsTab(1);
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

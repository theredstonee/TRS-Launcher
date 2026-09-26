package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.social.Toasts;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.core.ui.social.QuickReplyUi;
import dev.theredstonee.trsclient.core.ui.social.SocialUi;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest Sozial ({@code -PtrsAutotestOnly=social}) unter 1.8.9–1.12.2 gegen eine API-Attrappe: Liste, Chat,
 * Kontextmenü, Bildauswahl, Melden, Bild groß, Gruppe, Freunde; danach in einer kleinen Testwelt Toasts und die
 * Schnellantwort. Beendet das Spiel direkt nach den Bildern.
 */
public final class SocialTest {
	private int phase;
	private int wait;
	private int waited;
	private final String mcVersion = Mc.version();
	private final String world = "trs-autotest-" + Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new SocialTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		try {
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Sozial: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Sozial: Bild {}", name);
	}

	private boolean waitFor(boolean ready, int max) {
		if (!ready && waited++ < max) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private static UiScreen ui(GuiScreen screen) {
		return screen instanceof TrsUiScreen ? ((TrsUiScreen) screen).ui() : null;
	}

	private static SocialUi social(GuiScreen screen) {
		UiScreen ui = ui(screen);
		return ui instanceof SocialUi ? (SocialUi) ui : null;
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		GuiScreen screen = mc.currentScreen;
		SocialUi s = social(screen);
		switch (phase) {
			case 0:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				mc.gameSettings.renderDistanceChunks = 2;
				phase++;
				wait = 10;
				return;
			case 1:
				mc.displayGuiScreen(MenuScreens.social(new TrsTitleScreen()));
				phase++;
				wait = 10;
				return;
			case 2:
				if (!waitFor(s != null && s.ready(), 160)) return;
				phase++;
				wait = 40;
				return;
			case 3:
				shot(mc, "social-list");
				if (s != null) s.openConversation(0);
				phase++;
				wait = 60;
				return;
			case 4:
				shot(mc, "social-chat");
				if (s != null) s.testContextMenu();
				phase++;
				wait = 10;
				return;
			case 5:
				shot(mc, "social-menu");
				if (s != null) {
					s.testCloseOverlays();
					s.testPicker(true);
				}
				phase++;
				wait = 40;
				return;
			case 6:
				shot(mc, "social-picker");
				if (s != null) {
					s.testPicker(false);
					s.testReport();
				}
				phase++;
				wait = 10;
				return;
			case 7:
				shot(mc, "social-report");
				if (s != null) {
					s.testCloseOverlays();
					s.testLightbox();
				}
				phase++;
				wait = 40;
				return;
			case 8:
				shot(mc, "social-lightbox");
				if (s != null) {
					s.testCloseOverlays();
					s.testGroupDialog();
				}
				phase++;
				wait = 10;
				return;
			case 9:
				shot(mc, "social-group");
				if (s != null) {
					s.testCloseOverlays();
					s.showTab(1);
				}
				phase++;
				wait = 40;
				return;
			case 10:
				shot(mc, "social-friends");
				mc.displayGuiScreen(null);
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
				SocialOverlay.testToast(Toasts.Kind.ONLINE, "jeb_", "ist online", null, null, null);
				SocialOverlay.testToast(Toasts.Kind.INVITE, "Bob", "lädt dich ein: Survival", null, "c00000000000000000002",
						new Chat.Invite("play.example.net", "Survival"));
				SocialOverlay.testToast(Toasts.Kind.MESSAGE, "Bob", "Kommst du auf den Server?", null, "c00000000000000000001",
						null);
				phase++;
				wait = 20;
				return;
			case 13:
				shot(mc, "social-toasts");
				if (mc.currentScreen == null) {
					SocialOverlay.QuickAction action = SocialOverlay.takeQuickAction();
					if (action == null) action = SocialOverlay.QuickAction.reply("c00000000000000000001", "Bob");
					mc.displayGuiScreen(MenuScreens.socialAction(action, null));
				}
				phase++;
				wait = 10;
				return;
			case 14: {
				UiScreen ui = ui(mc.currentScreen);
				if (ui instanceof QuickReplyUi) ((QuickReplyUi) ui).testType("Bin gleich da");
				phase++;
				wait = 10;
				return;
			}
			case 15:
				shot(mc, "social-quickreply");
				TrsClient.LOGGER.info("[Autotest] Sozial: fertig");
				phase++;
				mc.shutdown();
				return;
			default:
		}
	}
}

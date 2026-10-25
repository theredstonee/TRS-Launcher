package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.fox.FoxScene;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.screen.TrsMenuHost;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Selbsttest Design „Fuchsbau“ unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=fox}): Startbildschirm in der Dämmerung
 * (Dunkel, goldene Stunde, OLED), Einführung, TRS-Menü (Design-Wahl), Serverliste, Ladebildschirm („Gelände wird
 * geladen“ in der Welt) und Pausenmenü. Lage des schlafenden Fuchses steht im Log. Danach gilt wieder die
 * Launcher-Datei; das Spiel endet. Screenshots: trsclient-&lt;mc&gt;-fox-*.png.
 */
public final class FoxDenTest {
	private int phase;
	private int wait;
	private int waited;
	private final String mcVersion = Mc.version();
	private final String world = "trs-autotest-" + Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new FoxDenTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Fuchsbau: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-fox-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Fuchsbau: Bild {} (Szene Ø {} µs, Menü Ø {} µs)", name,
				Math.round(FoxScene.shared().averageMicros()), Math.round(MenuSkin.averageMicros()));
	}

	private boolean waitFor(boolean ready, int max) {
		if (!ready && waited++ < max) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	/** Ladebildschirm mit festem Fortschritt (62 %) – zeigt den glimmenden Ast mit Flamme an der Glutkante. */
	private static dev.theredstonee.trsclient.core.ui.UiScreen loadingPreview() {
		return new dev.theredstonee.trsclient.core.ui.UiScreen() {
			@Override
			protected void draw(dev.theredstonee.trsclient.core.ui.Canvas c, int width, int height, int mouseX, int mouseY, float dt) {
				MenuSkin.loading(c, width, height, "Preparing the world", "62%", 0.62f, false);
			}

			@Override
			protected void onClosed() {
			}
		};
	}

	private static void fox(String theme) {
		Theme.set(Theme.of(theme, "fox", null, Theme.FOX));
	}

	/** „Gelände wird geladen“ über Reflexion (Konstruktor ohne bzw. mit NetHandlerPlayClient je Version). */
	private static GuiScreen terrainScreen(Minecraft mc) {
		try {
			Constructor<?> ctor = net.minecraft.client.gui.GuiDownloadTerrain.class.getConstructors()[0];
			if (ctor.getParameterTypes().length == 0) return (GuiScreen) ctor.newInstance();
			Object handler = null;
			for (Method m : Minecraft.class.getMethods()) {
				if (m.getParameterTypes().length == 0 && m.getReturnType() == ctor.getParameterTypes()[0]) {
					handler = m.invoke(mc);
					break;
				}
			}
			return (GuiScreen) ctor.newInstance(handler);
		} catch (ReflectiveOperationException | RuntimeException e) {
			TrsClient.LOGGER.warn("[Autotest] Fuchsbau: Ladebildschirm nicht erzeugbar: {}", e.toString());
			return null;
		}
	}

	private void tick(Minecraft mc) {
		GuiScreen screen = mc.currentScreen;
		TrsModules modules = TrsClient.get().modules();
		switch (phase) {
			case 0:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				mc.gameSettings.renderDistanceChunks = 2;
				fox("dark");
				mc.displayGuiScreen(new TrsTitleScreen());
				phase++;
				wait = 40;
				return;
			case 1: {
				shot(mc, "title");
				int[] b = screen == null ? null : FoxScene.shared().foxBounds(screen.width, screen.height);
				if (b != null) {
					TrsClient.LOGGER.info("[Autotest] Fuchsbau: Fuchs bei x={} y={} w={} h={} (GUI {}x{})", b[0], b[1], b[2], b[3], screen.width,
							screen.height);
				} else {
					TrsClient.LOGGER.info("[Autotest] Fuchsbau: kein Fuchs");
				}
				fox("light");
				phase++;
				wait = 20;
				return;
			}
			case 2:
				shot(mc, "title-light");
				fox("oled");
				phase++;
				wait = 10;
				return;
			case 3:
				shot(mc, "title-oled");
				fox("dark");
				new TrsMenuHost(new TrsTitleScreen()).openIntro();
				phase++;
				wait = 25;
				return;
			case 4:
				shot(mc, "intro");
				mc.displayGuiScreen(new TrsMenuScreen(new TrsTitleScreen()).select(modules.menuStyle));
				phase++;
				wait = 25;
				return;
			case 5:
				shot(mc, "trsmenu");
				mc.displayGuiScreen(new dev.theredstonee.trsclient.screen.TrsUiScreen("loading", loadingPreview()));
				phase = 50;
				wait = 20;
				return;
			case 50:
				shot(mc, "loading-bar");
				mc.displayGuiScreen(new GuiMultiplayer(new TrsTitleScreen()));
				phase = 6;
				wait = 40;
				return;
			case 6:
				shot(mc, "multiplayer");
				if (mc.getSaveLoader().canLoadWorld(world)) {
					mc.launchIntegratedServer(world, world, null);
				} else {
					mc.launchIntegratedServer(world, world, Mc.creativeWorld(System.nanoTime()));
				}
				phase++;
				return;
			case 7:
				if (!waitFor(Mc.world() != null && Mc.player() != null && mc.currentScreen == null, 600)) return;
				phase++;
				wait = 40;
				return;
			case 8: {
				GuiScreen terrain = terrainScreen(mc);
				if (terrain != null) mc.displayGuiScreen(terrain);
				phase++;
				wait = 10;
				return;
			}
			case 9:
				if (screen != null && screen.getClass().getSimpleName().contains("DownloadTerrain")) shot(mc, "loading");
				mc.displayGuiScreen(new GuiIngameMenu());
				phase++;
				wait = 15;
				return;
			case 10:
				if (!(screen instanceof GuiIngameMenu) && waited++ < 5) {
					mc.displayGuiScreen(new GuiIngameMenu());
					wait = 15;
					return;
				}
				waited = 0;
				shot(mc, "pause");
				mc.displayGuiScreen(new TrsMenuScreen(screen).select(modules.titleScreen));
				phase++;
				wait = 25;
				return;
			case 11:
				shot(mc, "trsmenu-ingame");
				Theme.set(Theme.of("dark", "redstone", null));
				Theme.loadFrom(new java.io.File(Mc.gameDir(), "config").toPath());
				TrsClient.LOGGER.info("[Autotest] Fuchsbau: fertig");
				phase++;
				mc.shutdown();
				return;
			default:
		}
	}
}

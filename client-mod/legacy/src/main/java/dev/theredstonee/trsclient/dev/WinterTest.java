package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.core.ui.winter.Penguin;
import dev.theredstonee.trsclient.core.ui.winter.WinterScene;
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
 * Selbsttest Design „Winternacht“ unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=winter}): Startbildschirm in der Nacht,
 * mit Polarlicht, mit dem Pinguin draußen und am hellen Wintertag, TRS-Menü (Design-Wahl), Serverliste, Ladebildschirm
 * („Gelände wird geladen“ in der Welt) und Pausenmenü. Danach gilt wieder die Launcher-Datei; das Spiel endet.
 * Screenshots: trsclient-&lt;mc&gt;-winter-*.png.
 */
public final class WinterTest {
	private int phase;
	private int wait;
	private int waited;
	private final String mcVersion = Mc.version();
	private final String world = "trs-autotest-" + Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new WinterTest());
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
			TrsClient.LOGGER.error("[Autotest] Winter: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-winter-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Winter: Bild {} (Szene Ø {} µs, Menü Ø {} µs)", name,
				Math.round(WinterScene.shared().averageMicros()), Math.round(MenuSkin.averageMicros()));
	}

	private boolean waitFor(boolean ready, int max) {
		if (!ready && waited++ < max) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private static void winter(String theme) {
		Theme.set(Theme.of(theme, "ice", null, Theme.WINTER));
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
			TrsClient.LOGGER.warn("[Autotest] Winter: Ladebildschirm nicht erzeugbar: {}", e.toString());
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
				winter("dark");
				mc.displayGuiScreen(new TrsTitleScreen());
				phase++;
				wait = 40;
				return;
			case 1:
				shot(mc, "title");
				WinterScene.shared().setSeconds(20);
				phase++;
				wait = 10;
				return;
			case 2:
				shot(mc, "title-aurora");
				WinterScene.shared().penguinOut();
				phase++;
				return;
			case 3:
				if (!waitFor(WinterScene.shared().penguinPhase() == Penguin.WAVE, 120)) return;
				shot(mc, "title-penguin");
				winter("light");
				phase++;
				wait = 20;
				return;
			case 4:
				shot(mc, "title-day");
				winter("dark");
				mc.displayGuiScreen(new TrsMenuScreen(new TrsTitleScreen()).select(modules.menuStyle));
				phase++;
				wait = 25;
				return;
			case 5:
				shot(mc, "trsmenu");
				mc.displayGuiScreen(new GuiMultiplayer(new TrsTitleScreen()));
				phase++;
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
				TrsClient.LOGGER.info("[Autotest] Winter: fertig");
				phase++;
				mc.shutdown();
				return;
			default:
		}
	}
}

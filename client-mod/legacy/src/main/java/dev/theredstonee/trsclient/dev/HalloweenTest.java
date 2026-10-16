package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.halloween.Halloween;
import dev.theredstonee.trsclient.core.halloween.HalloweenService;
import dev.theredstonee.trsclient.core.halloween.Jumpscares;
import dev.theredstonee.trsclient.core.halloween.SpookyFx;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Selbsttest Halloween 2026 unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=halloween}): Event erzwungen, Kürbis-Suche
 * als Beispiel-Zustand (7/13). Bilder trsclient-&lt;mc&gt;-halloween-*.png: Startbildschirm (mit und ohne Hinweis),
 * TRS-Menü Seite Halloween, Ladebildschirm (Mondlauf) und der Lade-Schreck, in der Welt mit Fledermäusen und
 * Geister-Spur, „Hinter dir!“ und ein Jumpscare. Stumm, danach endet das Spiel.
 */
public final class HalloweenTest {
	private int phase;
	private int wait;
	private int waited;
	private final String mcVersion = Mc.version();
	private final String world = "trs-autotest-" + Mc.version();

	public static void install() {
		Halloween.force(Boolean.TRUE);
		HalloweenService.get().demoState();
		MinecraftForge.EVENT_BUS.register(new HalloweenTest());
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
			TrsClient.LOGGER.error("[Autotest] Halloween: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-halloween-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Halloween: Bild {} (Menü Ø {} µs; {})", name, Math.round(MenuSkin.averageMicros()),
				Jumpscares.get().info(System.currentTimeMillis()));
	}

	private boolean waitFor(boolean ready, int max) {
		if (!ready && waited++ < max) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	/** TRS-Menü auf der Seite „Halloween“ (ModMenu#halloweenPage, sonst die Modulseite). */
	private static GuiScreen halloweenMenu(GuiScreen parent, TrsModules modules) {
		TrsMenuScreen screen = new TrsMenuScreen(parent);
		try {
			Object ui = screen.ui();
			ui.getClass().getMethod("showHalloween").invoke(ui);
		} catch (ReflectiveOperationException | RuntimeException e) {
			TrsClient.LOGGER.info("[Autotest] Halloween: halloweenPage() fehlt – Modulseite ({})", e.toString());
			screen.select(modules.halloween.halloween);
		}
		return screen;
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
			TrsClient.LOGGER.warn("[Autotest] Halloween: Ladebildschirm nicht erzeugbar: {}", e.toString());
			return null;
		}
	}

	/** Teilchen vor die Kamera: Fledermäuse aus einem „Ziel“ 4 Blöcke voraus und eine Geister-Spur quer davor. */
	private static void spawnFx(EntityPlayer p) {
		double yaw = Math.toRadians(p.rotationYaw);
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double x = p.posX + fx * 4;
		double z = p.posZ + fz * 4;
		SpookyFx s = SpookyFx.get();
		s.hit(x, p.posY + 1.2, z);
		s.hit(x + fz * 0.8, p.posY + 1.0, z - fx * 0.8);
		long now = System.currentTimeMillis();
		for (int i = 0; i < 8; i++) {
			double side = -2.5 + i * 0.6;
			s.trail("autotest", p.posX + fx * 6 + fz * side, p.posY, p.posZ + fz * 6 - fx * side, p.rotationYaw + 90, now + i * 80L);
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
				mc.displayGuiScreen(new TrsTitleScreen());
				phase++;
				wait = 40;
				return;
			case 1:
				shot(mc, "title");
				modules.halloween.local.markFlickerHintShown();
				// Kürbis am Titel „finden“ (gegen die Attrappe: Toast „Kürbis gefunden!“).
				dev.theredstonee.trsclient.core.halloween.HalloweenService.get().findPumpkin("client-title");
				phase++;
				wait = 10;
				return;
			case 2:
				shot(mc, "title-clean");
				mc.displayGuiScreen(halloweenMenu(new TrsTitleScreen(), modules));
				phase++;
				wait = 25;
				return;
			case 3:
				shot(mc, "trsmenu-halloween");
				if (mc.getSaveLoader().canLoadWorld(world)) {
					mc.launchIntegratedServer(world, world, null);
				} else {
					mc.launchIntegratedServer(world, world, Mc.creativeWorld(System.nanoTime()));
				}
				phase++;
				return;
			case 4:
				if (!waitFor(Mc.world() != null && Mc.player() != null && mc.currentScreen == null, 600)) return;
				phase++;
				wait = 40;
				return;
			case 5: {
				GuiScreen terrain = terrainScreen(mc);
				if (terrain != null) mc.displayGuiScreen(terrain);
				phase++;
				wait = 10;
				return;
			}
			case 6:
				if (screen != null && screen.getClass().getSimpleName().contains("DownloadTerrain")) shot(mc, "loading");
				// Lade-Schreck: bleibt bei 99 % stehen, Licht aus, Augen auf.
				Jumpscares.get().forceForTest(Jumpscares.Type.LOADING, System.currentTimeMillis());
				phase++;
				wait = 70;
				return;
			case 7:
				if (screen != null && screen.getClass().getSimpleName().contains("DownloadTerrain")) shot(mc, "loading-scare");
				mc.displayGuiScreen(null);
				phase++;
				wait = 30;
				return;
			case 8:
				spawnFx(Mc.player());
				phase++;
				wait = 4;
				return;
			case 9:
				shot(mc, "ingame-fx");
				mc.gameSettings.thirdPersonView = 2;
				phase++;
				wait = 10;
				return;
			case 10:
				Jumpscares.get().forceForTest(Jumpscares.Type.BEHIND_YOU, System.currentTimeMillis());
				phase++;
				wait = 3;
				return;
			case 11:
				shot(mc, "behind-you");
				// Flügel (Rücken): Ansicht von hinten, Zustände Ruhe / Luft / Schleichen.
				mc.gameSettings.thirdPersonView = 1;
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = dev.theredstonee.trsclient.core.cosmetic.v2.WingState.IDLE;
				phase = 100;
				wait = 20;
				return;
			case 100:
				shot(mc, "wings-idle");
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = dev.theredstonee.trsclient.core.cosmetic.v2.WingState.AIR;
				phase++;
				wait = 20;
				return;
			case 101:
				shot(mc, "wings-air");
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = dev.theredstonee.trsclient.core.cosmetic.v2.WingState.SNEAK;
				phase++;
				wait = 20;
				return;
			case 102:
				shot(mc, "wings-sneak");
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = -1;
				mc.gameSettings.thirdPersonView = 0;
				phase = 12;
				wait = 10;
				return;
			case 12:
				Jumpscares.get().forceForTest(Jumpscares.Type.CHEST, System.currentTimeMillis());
				phase++;
				wait = 8;
				return;
			case 13:
				shot(mc, "jumpscare");
				phase++;
				wait = 40;
				return;
			case 14:
				Halloween.force(null);
				TrsClient.LOGGER.info("[Autotest] Halloween: fertig");
				phase++;
				mc.shutdown();
				return;
			default:
		}
	}
}

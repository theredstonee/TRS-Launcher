package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Entwickler-Selbsttest, nur aktiv mit {@code -Dtrsclient.autotest=true}
 * ({@code ./gradlew runClient -PtrsAutotest}): Menü im Hauptmenü, Testwelt laden,
 * Screenshots von HUD, Zoom, Nacht ohne/mit Fullbright, Menü und HUD-Editor, dann Spiel beenden.
 * Screenshots landen in {@code run/screenshots/trsclient-1.8.9-*.png}.
 */
public final class AutoTest {
	private static final String WORLD = "trs-autotest-1.8.9";
	private static final String PREFIX = "trsclient-1.8.9-";

	private int step;
	private int wait;
	private boolean fullbrightBefore;
	/** Bildschirm, den der Test gerade erwartet (null = Spiel ohne Menü). */
	private GuiScreen expected;

	private AutoTest() {
	}

	public static void installIfRequested() {
		if (!Boolean.getBoolean("trsclient.autotest")) return;
		TrsClient.LOGGER.info("[Autotest] aktiv");
		MinecraftForge.EVENT_BUS.register(new AutoTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END) tick(Minecraft.getMinecraft());
	}

	private void tick(Minecraft mc) {
		// Das Spielfenster bekommt den Fokus – Tastendrücke landen im Spiel (Esc → Pausenmenü,
		// Rechte Umschalttaste → TRS-Menü …). Für saubere Screenshots fremde Menüs schließen.
		if (step >= 3 && step < 9 && mc.theWorld != null && mc.currentScreen != expected) {
			TrsClient.LOGGER.info("[Autotest] fremdes Menü geschlossen: {}", mc.currentScreen);
			mc.displayGuiScreen(expected);
			KeyBinding.unPressAllKeys();
			// Das zuletzt gezeichnete Bild zeigt noch das fremde Menü – vor dem Screenshot neu zeichnen lassen.
			if (wait < 5) wait = 5;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		TrsModules modules = TrsClient.get().modules();
		switch (step) {
			case 0:
				if (!(mc.currentScreen instanceof GuiMainMenu)) return; // Ressourcen laden noch
				mc.gameSettings.pauseOnLostFocus = false;
				TrsClient.LOGGER.info("[Autotest] Hauptmenü erreicht, öffne TRS-Menü");
				mc.displayGuiScreen(new TrsMenuScreen(mc.currentScreen));
				next(20);
				break;
			case 1:
				shot(mc, "menu-title");
				startWorld(mc);
				next(0);
				break;
			case 2:
				if (mc.theWorld == null || mc.thePlayer == null) return;
				if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiIngameMenu)) return; // Ladebildschirm
				TrsClient.LOGGER.info("[Autotest] Welt geladen");
				mc.displayGuiScreen(null);
				KeyBinding.unPressAllKeys();
				// Leicht nach unten schauen, damit Gelände (und damit Fullbright) im Bild ist.
				mc.thePlayer.rotationPitch = 15.0F;
				mc.thePlayer.prevRotationPitch = 15.0F;
				next(100);
				break;
			case 3:
				shot(mc, "hud");
				TrsClient.get().setForceZoom(true);
				next(30);
				break;
			case 4:
				shot(mc, "zoom");
				TrsClient.LOGGER.info("[Autotest] Zoom-Faktor beim Screenshot: {}", TrsClient.get().zoom().factor());
				TrsClient.get().setForceZoom(false);
				final IntegratedServer server = mc.getIntegratedServer();
				if (server != null) {
					server.addScheduledTask(new Runnable() {
						@Override
						public void run() {
							server.worldServers[0].setWorldTime(18000L);
						}
					});
				}
				fullbrightBefore = modules.fullbright.isEnabled();
				modules.fullbright.setEnabled(false);
				next(40);
				break;
			case 5:
				shot(mc, "night");
				modules.fullbright.setEnabled(true);
				next(20);
				break;
			case 6:
				shot(mc, "fullbright");
				TrsClient.LOGGER.info("[Autotest] Gamma-Option nach Fullbright unverändert: {}", mc.gameSettings.gammaSetting);
				modules.fullbright.setEnabled(fullbrightBefore);
				expected = new TrsMenuScreen(null);
				mc.displayGuiScreen(expected);
				next(20);
				break;
			case 7:
				shot(mc, "menu");
				expected = new HudEditorScreen(mc.currentScreen);
				mc.displayGuiScreen(expected);
				next(20);
				break;
			case 8:
				shot(mc, "hud-editor");
				expected = null;
				mc.displayGuiScreen(null);
				next(5);
				break;
			case 9:
				TrsClient.LOGGER.info("[Autotest] Hook-Aufrufe: {}", HookStats.summary());
				TrsClient.LOGGER.info("[Autotest] fertig, verlasse Welt und beende das Spiel");
				if (mc.theWorld != null) {
					mc.theWorld.sendQuittingDisconnectingPacket();
					mc.loadWorld((WorldClient) null);
				}
				mc.displayGuiScreen(new GuiMainMenu());
				next(20);
				break;
			default:
				if (step == 10) mc.shutdown();
				step = 11;
				break;
		}
	}

	private void next(int ticks) {
		step++;
		wait = ticks;
	}

	private static void startWorld(Minecraft mc) {
		if (mc.getSaveLoader().canLoadWorld(WORLD)) {
			TrsClient.LOGGER.info("[Autotest] öffne Testwelt '{}'", WORLD);
			mc.launchIntegratedServer(WORLD, WORLD, null);
		} else {
			TrsClient.LOGGER.info("[Autotest] erstelle Testwelt '{}'", WORLD);
			WorldSettings settings = new WorldSettings(System.nanoTime(), WorldSettings.GameType.CREATIVE, true, false, WorldType.DEFAULT);
			mc.launchIntegratedServer(WORLD, WORLD, settings);
		}
	}

	private static void shot(Minecraft mc, String name) {
		IChatComponent msg = ScreenShotHelper.saveScreenshot(mc.mcDataDir, PREFIX + name + ".png",
				mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] {}", msg.getUnformattedText());
	}
}

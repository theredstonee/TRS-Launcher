package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.CrosshairEditorScreen;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenDemo;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Entwickler-Selbsttest, nur aktiv mit {@code -Dtrsclient.autotest=true}
 * ({@code ./gradlew runClient -PtrsAutotest}): Menü im Hauptmenü, Testwelt laden, Rüstung/Effekte per Befehl,
 * Screenshots von HUD, Zoom, Nacht ohne/mit Fullbright, Menü, Fadenkreuz-Editor und HUD-Editor, dann beenden.
 * Screenshots landen in {@code run/forge-1.13.2/screenshots/trsclient-1.13.2-*.png}.
 */
public final class AutoTest {
	private static final String WORLD = "trs-autotest-1.13.2";
	private static final String PREFIX = "trsclient-1.13.2-";

	private int step;
	private int wait;
	private TrsConfig before;
	/** Bildschirm, den der Test gerade erwartet (null = Spiel ohne Menü). */
	private GuiScreen expected;
	/** Zoom-Mausrad-Probe: Slot vor der Simulation (-1 = noch nicht) und Zoomstufe davor. */
	private int scrollProbe = -1;
	private double scrollLevel;

	private AutoTest() {
	}

	public static void installIfRequested() {
		if (!Boolean.getBoolean("trsclient.autotest")) return;
		TrsClient.LOGGER.info("[Autotest] aktiv");
		MinecraftForge.EVENT_BUS.register(new AutoTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END) tick(Minecraft.getInstance());
	}

	private void tick(Minecraft mc) {
		// Das Spielfenster bekommt den Fokus – Tastendrücke landen im Spiel (Esc → Pausenmenü,
		// Rechte Umschalttaste → TRS-Menü …). Für saubere Screenshots fremde Menüs schließen.
		if (step >= 3 && step < 10 && mc.world != null && mc.currentScreen != expected
				&& (expected == null || mc.currentScreen == null || mc.currentScreen.getClass() != expected.getClass())) {
			TrsClient.LOGGER.info("[Autotest] fremdes Menü geschlossen: {}", mc.currentScreen);
			mc.displayGuiScreen(expected);
			KeyBinding.unPressAllKeys();
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
				// Tutorial-Hinweise (Toasts) verdecken sonst die HUD-Module rechts.
				mc.getTutorial().setStep(TutorialSteps.NONE);
				before = modules.registry.capture();
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
				if (mc.world == null || mc.player == null) return;
				// Ohne Account startet der Launcher im Demo-Modus – dessen Willkommensbildschirm zählt nicht als Ladebildschirm.
				if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiIngameMenu)
						&& !(mc.currentScreen instanceof GuiScreenDemo)) return; // Ladebildschirm
				TrsClient.LOGGER.info("[Autotest] Welt geladen");
				mc.displayGuiScreen(null);
				KeyBinding.unPressAllKeys();
				mc.player.rotationPitch = 15.0F;
				mc.player.prevRotationPitch = 15.0F;
				mc.getToastGui().clear();
				command(mc, "replaceitem entity @p armor.head minecraft:iron_helmet");
				command(mc, "replaceitem entity @p armor.chest minecraft:diamond_chestplate");
				command(mc, "replaceitem entity @p armor.feet minecraft:golden_boots{Damage:40}");
				command(mc, "replaceitem entity @p weapon.mainhand minecraft:diamond_sword");
				command(mc, "effect give @p minecraft:speed 300 1 true");
				command(mc, "effect give @p minecraft:night_vision 120 0 true");
				command(mc, "time set day");
				for (Module m : new Module[]{modules.armor, modules.effects, modules.coords, modules.clock, modules.memory,
						modules.packs, modules.toggleSprint, modules.crosshair}) {
					m.setEnabled(true);
				}
				TrsClient.get().pvp().sprint().set(true);
				modules.crosshairShape.set(Crosshair.Shape.CROSS_DOT);
				modules.crosshairColor.set(0xFFB84D);
				// Chat-Meldungen der Befehle verblassen lassen (10 s)
				next(220);
				break;
			case 3:
				shot(mc, "hud");
				TrsClient.get().setForceZoom(true);
				next(30);
				break;
			case 4:
				if (scrollProbe < 0) {
					// Zoom-Mausrad simulieren: Vanilla verschiebt beim Scrollen nach oben den Hotbar-Slot um -1.
					scrollProbe = mc.player.inventory.currentItem;
					scrollLevel = TrsClient.get().zoom().level();
					mc.player.inventory.currentItem = (scrollProbe + 8) % 9;
					wait = 3;
					return;
				}
				TrsClient.LOGGER.info("[Autotest] Zoom-Mausrad: Stufe {} -> {}, Hotbar-Slot zurückgesetzt: {}",
						scrollLevel, TrsClient.get().zoom().level(), mc.player.inventory.currentItem == scrollProbe);
				shot(mc, "zoom");
				TrsClient.LOGGER.info("[Autotest] Zoom-Faktor beim Screenshot: {}", TrsClient.get().zoom().factor());
				TrsClient.get().setForceZoom(false);
				command(mc, "effect clear @p minecraft:night_vision");
				command(mc, "time set midnight");
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
				modules.fullbright.setEnabled(false);
				expected = new TrsMenuScreen(null).select(modules.crosshair);
				mc.displayGuiScreen(expected);
				next(20);
				break;
			case 7:
				shot(mc, "menu");
				expected = new CrosshairEditorScreen(null);
				mc.displayGuiScreen(expected);
				next(20);
				break;
			case 8:
				shot(mc, "crosshair-editor");
				expected = new HudEditorScreen(null);
				mc.displayGuiScreen(expected);
				next(20);
				break;
			case 9:
				shot(mc, "hud-editor");
				expected = null;
				mc.displayGuiScreen(null);
				next(5);
				break;
			case 10:
				TrsClient.LOGGER.info("[Autotest] Hook-Aufrufe: {}", HookStats.summary());
				TrsClient.LOGGER.info("[Autotest] fertig, verlasse Welt und beende das Spiel");
				TrsClient.get().pvp().sprint().set(false);
				if (before != null) modules.registry.apply(before);
				TrsClient.get().saveConfig();
				if (mc.world != null) {
					mc.world.sendQuittingDisconnectingPacket();
					mc.loadWorld((WorldClient) null);
				}
				mc.displayGuiScreen(new GuiMainMenu());
				next(20);
				break;
			default:
				if (step == 11) mc.shutdown();
				step = 12;
				break;
		}
	}

	private void next(int ticks) {
		step++;
		wait = ticks;
	}

	/** Führt einen Befehl als Server (Berechtigungsstufe 4) auf dem Server-Thread aus. */
	private static void command(Minecraft mc, String command) {
		final MinecraftServer server = mc.getIntegratedServer();
		if (server == null) return;
		server.addScheduledTask(() -> server.getCommandManager().handleCommand(server.getCommandSource(), command));
	}

	private static void startWorld(Minecraft mc) {
		if (mc.getSaveLoader().canLoadWorld(WORLD)) {
			TrsClient.LOGGER.info("[Autotest] öffne Testwelt '{}'", WORLD);
			mc.launchIntegratedServer(WORLD, WORLD, null);
		} else {
			TrsClient.LOGGER.info("[Autotest] erstelle Testwelt '{}'", WORLD);
			WorldSettings settings = new WorldSettings(System.nanoTime(), GameType.CREATIVE, true, false, WorldType.DEFAULT);
			mc.launchIntegratedServer(WORLD, WORLD, settings);
		}
	}

	private static void shot(Minecraft mc, String name) {
		ScreenShotHelper.saveScreenshot(mc.gameDir, PREFIX + name + ".png",
				mc.mainWindow.getFramebufferWidth(), mc.mainWindow.getFramebufferHeight(), mc.getFramebuffer(),
				msg -> TrsClient.LOGGER.info("[Autotest] {}", msg.getString()));
	}
}

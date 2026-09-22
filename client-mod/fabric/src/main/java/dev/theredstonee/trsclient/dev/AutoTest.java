package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.function.Supplier;
//? if >=1.21.11 && <26.1 {
/*import net.minecraft.world.level.gamerules.GameRules;
*///?} elif <1.21.11
import net.minecraft.world.level.GameRules;

/**
 * Entwickler-Selbsttest, nur aktiv mit {@code -Dtrsclient.autotest=true}
 * ({@code ./gradlew :fabric:<version>:runClient -PtrsAutotest}): Menü auf dem Titelbildschirm, Testwelt laden,
 * Screenshots von HUD, Zoom, Nacht ohne/mit Fullbright, Menü und HUD-Editor, dann Spiel beenden.
 * Screenshots landen in {@code run/screenshots/trsclient-<minecraft>-*.png}.
 */
public final class AutoTest {
	/** Minecraft-Version für Dateinamen: Screenshots/Testwelten mehrerer Versionen kommen sich nicht in die Quere. */
	private static final String MC_VERSION = FabricLoader.getInstance().getModContainer("minecraft")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("mc");
	/** Eigene Testwelt je Version (kein Öffnen einer neueren Welt in einer älteren Version). */
	private static final String WORLD = "trs-autotest-" + MC_VERSION;

	private int step;
	private int wait;
	private boolean fullbrightBefore;
	private int reopenCount;

	private AutoTest() {
	}

	public static void installIfRequested() {
		if (!Boolean.getBoolean("trsclient.autotest")) return;
		TrsClient.LOGGER.info("[Autotest] aktiv");
		AutoTest test = new AutoTest();
		ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private void tick(Minecraft mc) {
		// Verliert das Fenster den Fokus oder drückt jemand Esc, öffnet Vanilla das Pausenmenü –
		// für saubere Screenshots wieder schließen und hängende Tasten lösen.
		if (step >= 2 && step < 9 && Mc.screen() instanceof PauseScreen) {
			Mc.setScreen(null);
			KeyMapping.releaseAll();
		}
		if (wait > 0) {
			wait--;
			return;
		}
		TrsModules modules = TrsClient.get().modules();
		switch (step) {
			case 0 -> {
				if (Mc.overlay() != null) return; // Ressourcen laden noch
				mc.options.pauseOnLostFocus = false;
				mc.options.onboardAccessibility = false;
				TrsClient.LOGGER.info("[Autotest] Titelbildschirm erreicht, öffne TRS-Menü");
				Mc.setScreen(new TrsMenuScreen(new TitleScreen()));
				next(20);
			}
			case 1 -> {
				shot(mc, "trsclient-menu-title");
				Mc.setScreen(null);
				startWorld(mc);
				next(0);
			}
			case 2 -> {
				if (mc.level == null || mc.player == null) return;
				if (Mc.screen() != null) return; // Ladebildschirm
				TrsClient.LOGGER.info("[Autotest] Welt geladen");
				KeyMapping.releaseAll();
				next(100);
			}
			case 3 -> {
				shot(mc, "trsclient-hud");
				TrsClient.get().setForceZoom(true);
				next(30);
			}
			case 4 -> {
				shot(mc, "trsclient-zoom");
				TrsClient.get().setForceZoom(false);
				MinecraftServer server = mc.getSingleplayerServer();
				// Per Befehl – funktioniert in allen Versionen gleich.
				if (server != null) server.execute(() ->
						server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set midnight"));
				fullbrightBefore = modules.fullbright.isEnabled();
				modules.fullbright.setEnabled(false);
				next(40);
			}
			case 5 -> {
				shot(mc, "trsclient-night");
				modules.fullbright.setEnabled(true);
				next(20);
			}
			case 6 -> {
				shot(mc, "trsclient-fullbright");
				modules.fullbright.setEnabled(fullbrightBefore);
				Mc.setScreen(new TrsMenuScreen(null));
				next(20);
			}
			case 7 -> {
				if (!ensureScreen(TrsMenuScreen.class, () -> new TrsMenuScreen(null))) return;
				shot(mc, "trsclient-menu");
				Mc.setScreen(new HudEditorScreen(Mc.screen()));
				next(20);
			}
			case 8 -> {
				if (!ensureScreen(HudEditorScreen.class, () -> new HudEditorScreen(null))) return;
				shot(mc, "trsclient-hud-editor");
				Mc.setScreen(null);
				next(5);
			}
			case 9 -> {
				TrsClient.LOGGER.info("[Autotest] Hook-Aufrufe: {}", HookStats.summary());
				TrsClient.LOGGER.info("[Autotest] fertig, verlasse Welt und beende das Spiel");
				disconnect(mc);
				next(20);
			}
			default -> {
				if (step == 10) mc.stop();
				step = 11;
			}
		}
	}

	/**
	 * Tastatureingaben des Benutzers können das Spielfenster erreichen und einen Bildschirm schließen.
	 * Ist der erwartete Bildschirm nicht offen, wird er (höchstens 5-mal) neu geöffnet und kurz gewartet.
	 */
	private boolean ensureScreen(Class<? extends Screen> type, Supplier<Screen> factory) {
		if (type.isInstance(Mc.screen()) || reopenCount >= 5) return true;
		reopenCount++;
		TrsClient.LOGGER.warn("[Autotest] {} wurde geschlossen (Eingabe?) – öffne erneut", type.getSimpleName());
		KeyMapping.releaseAll();
		Mc.setScreen(factory.get());
		wait = 10;
		return false;
	}

	private void next(int ticks) {
		step++;
		wait = ticks;
	}

	private static void startWorld(Minecraft mc) {
		if (mc.getLevelSource().levelExists(WORLD)) {
			TrsClient.LOGGER.info("[Autotest] öffne Testwelt '{}'", WORLD);
			//? if >=1.20.5 {
			mc.createWorldOpenFlows().openWorld(WORLD, () -> Mc.setScreen(new TitleScreen()));
			//?} elif >=1.20.3 {
			/*mc.createWorldOpenFlows().checkForBackupAndLoad(WORLD, () -> Mc.setScreen(new TitleScreen()));
			*///?} else
			/*mc.createWorldOpenFlows().loadLevel(new TitleScreen(), WORLD);*/
		} else {
			TrsClient.LOGGER.info("[Autotest] erstelle Testwelt '{}'", WORLD);
			//? if >=26.1 {
			/*LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
			*///?} elif >=1.21.2 {
			/*LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
					new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
			*///?} else {
			LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
					new GameRules(), WorldDataConfiguration.DEFAULT);
			//?}
			mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, WorldOptions.defaultWithRandomSeed(),
					//? if >=1.20.3 {
					WorldPresets::createNormalWorldDimensions, new TitleScreen());
					//?} else
					/*WorldPresets::createNormalWorldDimensions);*/
		}
	}

	private static void disconnect(Minecraft mc) {
		//? if >=1.21.6 {
		/*if (mc.level != null) mc.level.disconnect(Component.literal("TRS-Autotest"));
		mc.disconnectWithSavingScreen();
		*///?} elif >=1.20.2 {
		if (mc.level != null) mc.level.disconnect();
		mc.disconnect();
		//?} else {
		/*if (mc.level != null) mc.level.disconnect();
		mc.clearLevel();
		*///?}
	}


	private static void shot(Minecraft mc, String name) {
		// run/screenshots/trsclient-<minecraft>-<name>.png
		Screenshot.grab(mc.gameDirectory, name.replace("trsclient-", "trsclient-" + MC_VERSION + "-") + ".png", Mc.mainRenderTarget(),
				//? if >=1.21.6
				//1,
				msg -> TrsClient.LOGGER.info("[Autotest] {}", msg.getString()));
	}
}

package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Entwickler-Selbsttest, nur aktiv mit {@code -Dtrsclient.autotest=true}
 * ({@code ./gradlew runClient -PtrsAutotest}): Menü auf dem Titelbildschirm, Testwelt laden,
 * Screenshots von HUD, Zoom, Nacht ohne/mit Fullbright, Menü und HUD-Editor, dann Spiel beenden.
 * Screenshots landen in {@code run/screenshots/trsclient-*.png}.
 */
public final class AutoTest {
	private static final String WORLD = "trs-autotest";

	private int step;
	private int wait;
	private boolean fullbrightBefore;

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
		if (step >= 2 && step < 9 && mc.screen instanceof PauseScreen) {
			mc.setScreen(null);
			KeyMapping.releaseAll();
		}
		if (wait > 0) {
			wait--;
			return;
		}
		TrsModules modules = TrsClient.get().modules();
		switch (step) {
			case 0 -> {
				if (mc.getOverlay() != null) return; // Ressourcen laden noch
				mc.options.pauseOnLostFocus = false;
				mc.options.onboardAccessibility = false;
				TrsClient.LOGGER.info("[Autotest] Titelbildschirm erreicht, öffne TRS-Menü");
				mc.setScreen(new TrsMenuScreen(new TitleScreen()));
				next(20);
			}
			case 1 -> {
				shot(mc, "trsclient-menu-title");
				mc.setScreen(null);
				startWorld(mc);
				next(0);
			}
			case 2 -> {
				if (mc.level == null || mc.player == null) return;
				if (mc.screen != null) return; // Ladebildschirm
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
				if (server != null) server.execute(() -> server.overworld().setDayTime(18000L));
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
				mc.setScreen(new TrsMenuScreen(null));
				next(20);
			}
			case 7 -> {
				shot(mc, "trsclient-menu");
				mc.setScreen(new HudEditorScreen(mc.screen));
				next(20);
			}
			case 8 -> {
				shot(mc, "trsclient-hud-editor");
				mc.setScreen(null);
				next(5);
			}
			case 9 -> {
				TrsClient.LOGGER.info("[Autotest] Hook-Aufrufe: {}", HookStats.summary());
				TrsClient.LOGGER.info("[Autotest] fertig, verlasse Welt und beende das Spiel");
				if (mc.level != null) mc.level.disconnect();
				mc.disconnect();
				next(20);
			}
			default -> {
				if (step == 10) mc.stop();
				step = 11;
			}
		}
	}

	private void next(int ticks) {
		step++;
		wait = ticks;
	}

	private static void startWorld(Minecraft mc) {
		if (mc.getLevelSource().levelExists(WORLD)) {
			TrsClient.LOGGER.info("[Autotest] öffne Testwelt '{}'", WORLD);
			mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
		} else {
			TrsClient.LOGGER.info("[Autotest] erstelle Testwelt '{}'", WORLD);
			LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
					new GameRules(), WorldDataConfiguration.DEFAULT);
			mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, WorldOptions.defaultWithRandomSeed(),
					WorldPresets::createNormalWorldDimensions, new TitleScreen());
		}
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(),
				msg -> TrsClient.LOGGER.info("[Autotest] {}", msg.getString()));
	}
}

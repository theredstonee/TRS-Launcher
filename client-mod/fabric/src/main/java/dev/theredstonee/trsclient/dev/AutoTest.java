package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.CrosshairEditorScreen;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import dev.theredstonee.trsclient.screen.PackScreen;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.WaypointListScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
//? if >=1.19.3 {
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
//?} elif >=1.19 {
/*import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
*///?} elif >=1.16 {
/*import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.levelgen.WorldGenSettings;
*///?} else
/*import net.minecraft.world.level.LevelType;*/

import java.util.function.Supplier;
//? if >=1.21.11 && <26.1 {
/*import net.minecraft.world.level.gamerules.GameRules;
*///?} elif <1.21.11 && >=1.16
import net.minecraft.world.level.GameRules;

/**
 * Entwickler-Selbsttest, nur aktiv mit {@code -Dtrsclient.autotest=true}
 * ({@code ./gradlew :fabric:<version>:runClient -PtrsAutotest}): TRS-Startbildschirm und Menü, Testwelt laden,
 * Screenshots von HUD (inkl. Rüstung/Effekte/Koordinaten/…, eigenes Fadenkreuz), Zoom, Nacht ohne/mit
 * Fullbright, Treffer-Farbe, Freelook, Menü, Fadenkreuz-Editor, Resourcepacks und HUD-Editor, dann beenden.
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
	private int reopenCount;
	/** Modul-Zustand vor dem Test – wird am Ende wiederhergestellt (die Config bleibt sauber). */
	private TrsConfig before;

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
		if (step >= 3 && step < 20 && Mc.screen() instanceof PauseScreen) {
			Mc.setScreen(null);
			KeyMapping.releaseAll();
		}
		if (wait > 0) {
			wait--;
			return;
		}
		TrsModules modules = TrsClient.get().modules();
		switch (step) {
			case 0:
				if (Mc.overlay() != null || Mc.screen() == null) return; // Ressourcen laden noch
				mc.options.pauseOnLostFocus = false;
				//? if >=1.19.4
				mc.options.onboardAccessibility = false;
				before = modules.registry.capture();
				modules.titleScreen.setEnabled(true);
				mc.getTutorial().setStep(TutorialSteps.NONE);
				seedServers(mc);
				TrsClient.LOGGER.info("[Autotest] Startbildschirm erreicht: {}", Mc.screen().getClass().getSimpleName());
				if (!(Mc.screen() instanceof TrsTitleScreen)) Mc.setScreen(new TrsTitleScreen());
				next(20);
				break;
			case 1:
				if (!ensureScreen(TrsTitleScreen.class, TrsTitleScreen::new)) return;
				shot(mc, "trsclient-title");
				Mc.setScreen(new TrsMenuScreen(Mc.screen()));
				next(20);
				break;
			case 2:
				shot(mc, "trsclient-menu-title");
				Mc.setScreen(null);
				startWorld(mc);
				next(0);
				break;
			case 3:
				if (mc.level == null || mc.player == null) return;
				if (Mc.screen() != null) return; // Ladebildschirm
				TrsClient.LOGGER.info("[Autotest] Welt geladen");
				KeyMapping.releaseAll();
				// Echte Rüstung/Effekte für die neuen HUD-Module
				//? if >=1.17 {
				command(mc, "item replace entity @p armor.head with iron_helmet");
				command(mc, "item replace entity @p armor.chest with diamond_chestplate");
				command(mc, "item replace entity @p armor.feet with golden_boots");
				command(mc, "item replace entity @p weapon.mainhand with diamond_sword");
				//?} else {
				/*command(mc, "replaceitem entity @p armor.head iron_helmet");
				command(mc, "replaceitem entity @p armor.chest diamond_chestplate");
				command(mc, "replaceitem entity @p armor.feet golden_boots");
				command(mc, "replaceitem entity @p weapon.mainhand diamond_sword");
				command(mc, "difficulty peaceful");
				*///?}
				command(mc, "effect give @p speed 300 1 true");
				command(mc, "effect give @p night_vision 120 0 true");
				command(mc, "time set day");
				for (dev.theredstonee.trsclient.core.module.Module m : new dev.theredstonee.trsclient.core.module.Module[]{modules.armor, modules.effects, modules.coords,
						modules.clock, modules.memory, modules.packs, modules.toggleSprint, modules.crosshair}) {
					m.setEnabled(true);
				}
				TrsClient.get().sprintToggle().set(true);
				modules.crosshairShape.set(Crosshair.Shape.CROSS_DOT);
				modules.crosshairColor.set(0xFFB84D);
				// Chat-Meldungen und Toasts der Befehle ausblenden lassen (Chat verblasst nach 10 s)
				next(230);
				break;
			case 4:
				shot(mc, "trsclient-hud");
				TrsClient.get().setForceZoom(true);
				next(30);
				break;
			case 5:
				shot(mc, "trsclient-zoom");
				TrsClient.get().setForceZoom(false);
				command(mc, "effect clear @p night_vision");
				command(mc, "time set midnight");
				modules.fullbright.setEnabled(false);
				next(40);
				break;
			case 6:
				shot(mc, "trsclient-night");
				modules.fullbright.setEnabled(true);
				next(20);
				break;
			case 7:
				shot(mc, "trsclient-fullbright");
				modules.fullbright.setEnabled(false);
				// Treffer-Farbe: Schwein vor den Spieler setzen (friedlich → keine Monster) und treffen
				modules.hitColor.setEnabled(true);
				command(mc, "time set day");
				command(mc, "execute at @p rotated ~ 0 run summon pig ^ ^ ^3 {NoAI:1b,Invulnerable:0b}");
				next(30);
				break;
			case 8:
				//? if >=1.19.4 {
				command(mc, "damage @e[type=pig,limit=1,sort=nearest] 1");
				//?} else
				/*command(mc, "effect give @e[type=pig,limit=1,sort=nearest] instant_damage 1 0 true");*/
				next(2);
				break;
			case 9:
				shot(mc, "trsclient-hitcolor");
				TrsClient.get().pvp().forceFreelook(150F);
				next(20);
				break;
			case 10:
				shot(mc, "trsclient-freelook");
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				Mc.setScreen(new TrsMenuScreen(null).select(modules.crosshair));
				next(20);
				break;
			case 11:
				if (!ensureScreen(TrsMenuScreen.class, () -> new TrsMenuScreen(null).select(modules.crosshair))) return;
				shot(mc, "trsclient-menu");
				Mc.setScreen(new CrosshairEditorScreen(null));
				next(20);
				break;
			case 12:
				if (!ensureScreen(CrosshairEditorScreen.class, () -> new CrosshairEditorScreen(null))) return;
				shot(mc, "trsclient-crosshair-editor");
				Mc.setScreen(new PackScreen(null));
				next(20);
				break;
			case 13:
				if (!ensureScreen(PackScreen.class, () -> new PackScreen(null))) return;
				shot(mc, "trsclient-packs");
				Mc.setScreen(new HudEditorScreen(null));
				next(20);
				break;
			case 14:
				if (!ensureScreen(HudEditorScreen.class, () -> new HudEditorScreen(null))) return;
				shot(mc, "trsclient-hud-editor");
				Mc.setScreen(null);
				// Wegpunkte + Minimap + PvP-Anzeigen einschalten und einen Wegpunkt anlegen
				modules.waypoints.setEnabled(true);
				modules.minimap.setEnabled(true);
				modules.reach.setEnabled(true);
				modules.combo.setEnabled(true);
				modules.speed.setEnabled(true);
				TrsClient.get().waypoints().create("Basis", 0x3DDC84);
				// 20 Blöcke nach Norden und zurückschauen (yaw 0 = Süden) → Wegpunkt im Blick
				command(mc, "tp @p ~ ~ ~-20 0 0");
				next(40);
				break;
			case 15:
				shot(mc, "trsclient-waypoints");
				Mc.setScreen(new WaypointListScreen(null));
				next(20);
				break;
			case 16:
				if (!ensureScreen(WaypointListScreen.class, () -> new WaypointListScreen(null))) return;
				shot(mc, "trsclient-waypoint-liste");
				Mc.setScreen(null);
				// Chat: Zeitstempel an, dreimal dieselbe Nachricht → wird zusammengefasst
				modules.chat.setEnabled(true);
				modules.chatTimestamps.set(true);
				modules.chatStack.set(true);
				chat(mc, "TRS Client: Chat-Test");
				chat(mc, "Wiederholte Nachricht");
				chat(mc, "Wiederholte Nachricht");
				chat(mc, "Wiederholte Nachricht");
				next(10);
				break;
			case 17:
				shot(mc, "trsclient-chat");
				next(5);
				break;
			case 18:
				TrsClient.LOGGER.info("[Autotest] Hook-Aufrufe: {}", HookStats.summary());
				TrsClient.LOGGER.info("[Autotest] fertig, verlasse Welt und beende das Spiel");
				TrsClient.get().sprintToggle().set(false);
				if (before != null) modules.registry.apply(before);
				TrsClient.get().saveConfig();
				disconnect(mc);
				next(20);
				break;
			default:
				if (step == 19) mc.stop();
				step = 20;
				break;
		}
	}

	/** Legt für den Test zwei Server in servers.dat an, falls die Liste leer ist (Schnellbeitritt-Leiste). */
	private static void seedServers(Minecraft mc) {
		ServerList list = new ServerList(mc);
		list.load();
		if (list.size() > 0) return;
		//? if >=1.20.2 {
		list.add(new ServerData("TRS Testserver", "localhost:25565", ServerData.Type.OTHER), false);
		list.add(new ServerData("Hypixel", "mc.hypixel.net", ServerData.Type.OTHER), false);
		//?} elif >=1.19 {
		/*list.add(new ServerData("TRS Testserver", "localhost:25565", false), false);
		list.add(new ServerData("Hypixel", "mc.hypixel.net", false), false);
		*///?} else {
		/*list.add(new ServerData("TRS Testserver", "localhost:25565", false));
		list.add(new ServerData("Hypixel", "mc.hypixel.net", false));
		*///?}
		list.save();
	}

	/** Schreibt eine Nachricht in den Chat (wie eine Servernachricht – geht durch die TRS-Chat-Hooks). */
	private static void chat(Minecraft mc, String text) {
		dev.theredstonee.trsclient.compat.ChatLines.addMessage(Mc.text(text));
	}

	/** Führt einen Befehl als Server (Berechtigungsstufe 4) aus. */
	private static void command(Minecraft mc, String command) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) return;
		//? if >=1.19 {
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
		//?} else
		/*server.execute(() -> server.getCommands().performCommand(server.createCommandSourceStack(), command));*/
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
			*///?} elif >=1.19 {
			/*mc.createWorldOpenFlows().loadLevel(new TitleScreen(), WORLD);
			*///?} elif >=1.16 {
			/*mc.loadLevel(WORLD);
			*///?} else
			/*mc.selectLevel(WORLD, WORLD, null);*/
		} else {
			TrsClient.LOGGER.info("[Autotest] erstelle Testwelt '{}'", WORLD);
			//? if >=26.1 {
			/*LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
			*///?} elif >=1.21.2 {
			/*LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
					new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
			*///?} elif >=1.19.3 {
			LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
					new GameRules(), WorldDataConfiguration.DEFAULT);
			//?} elif >=1.16 {
			/*LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
					new GameRules(), DataPackConfig.DEFAULT);
			*///?}
			//? if >=1.19.3 {
			mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, WorldOptions.defaultWithRandomSeed(),
					//? if >=1.20.3 {
					WorldPresets::createNormalWorldDimensions, new TitleScreen());
					//?} else
					/*WorldPresets::createNormalWorldDimensions);*/
			//?} elif >=1.19 {
			/*RegistryAccess registries = RegistryAccess.builtinCopy().freeze();
			mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, registries, WorldPresets.createNormalWorldFromPreset(registries));
			*///?} elif >=1.18.2 {
			/*RegistryAccess registries = RegistryAccess.builtinCopy();
			mc.createLevel(WORLD, settings, registries, WorldGenSettings.makeDefault(registries));
			*///?} elif >=1.18 {
			/*RegistryAccess.RegistryHolder registries = RegistryAccess.builtin();
			mc.createLevel(WORLD, settings, registries, WorldGenSettings.makeDefault(registries));
			*///?} elif >=1.16 {
			/*RegistryAccess.RegistryHolder registries = RegistryAccess.builtin();
			mc.createLevel(WORLD, settings, registries, WorldGenSettings.makeDefault(registries.registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY),
					registries.registryOrThrow(Registry.BIOME_REGISTRY), registries.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY)));
			*///?} else {
			/*LevelSettings settings = new LevelSettings(new java.util.Random().nextLong(), GameType.CREATIVE, true, false, LevelType.NORMAL)
					.enableSinglePlayerCommands();
			mc.selectLevel(WORLD, WORLD, settings);
			*///?}
		}
	}

	private static void disconnect(Minecraft mc) {
		//? if >=1.21.6 {
		/*if (mc.level != null) mc.level.disconnect(Mc.text("TRS-Autotest"));
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
		Screenshot.grab(mc.gameDirectory, name.replace("trsclient-", "trsclient-" + MC_VERSION + "-") + ".png",
				//? if <1.17.1
				/*Mc.window().getWidth(), Mc.window().getHeight(),*/
				Mc.mainRenderTarget(),
				//? if >=1.21.6
				//1,
				msg -> TrsClient.LOGGER.info("[Autotest] {}", msg.getString()));
	}
}

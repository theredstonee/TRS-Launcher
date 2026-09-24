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
	/** Echte Eingaben (Klick, Taste): 0..2 laufen, 3 = fertig. */
	private int inputPhase;
	private boolean inputClick;
	private int titlePhase;
	private float sceneFrameMs;
	/** Modul-Zustand vor dem Test – wird am Ende wiederhergestellt (die Config bleibt sauber). */
	private TrsConfig before;
	/** Vom Test angelegter Wegpunkt – am Ende wieder entfernt. */
	private dev.theredstonee.trsclient.core.waypoint.Waypoint testWaypoint;

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
		if (step >= 3 && step < 24 && Mc.screen() instanceof PauseScreen) {
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
				// Zuerst echte Eingaben: Klick und Taste über Minecrafts Maus-/Tastatur-Verarbeitung.
				if (inputPhase < 3) {
					realInput(mc);
					return;
				}
				if (!ensureScreen(TrsTitleScreen.class, TrsTitleScreen::new)) return;
				if (titleStep(mc, (TrsTitleScreen) Mc.screen())) return;
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
				//command(mc, "effect give @e[type=pig,limit=1,sort=nearest] instant_damage 1 0 true");
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
				Mc.setScreen(new TrsMenuScreen(null).showProfiles());
				next(20);
				break;
			case 14:
				if (!ensureScreen(TrsMenuScreen.class, () -> new TrsMenuScreen(null).showProfiles())) return;
				shot(mc, "trsclient-profiles");
				Mc.setScreen(new HudEditorScreen(null).selectFirst());
				next(20);
				break;
			case 15:
				if (!ensureScreen(HudEditorScreen.class, () -> new HudEditorScreen(null).selectFirst())) return;
				shot(mc, "trsclient-hud-editor");
				Mc.setScreen(null);
				// Wegpunkte, Minimap und die PvP-Anzeigen einschalten, einen Wegpunkt anlegen
				modules.waypoints.setEnabled(true);
				modules.minimap.setEnabled(true);
				modules.reach.setEnabled(true);
				modules.combo.setEnabled(true);
				modules.speed.setEnabled(true);
				testWaypoint = TrsClient.get().waypoints().create("Basis", 0x3DDC84);
				TrsClient.LOGGER.info("[Autotest] Wegpunkt bei {}/{}/{}, Spieler bei {}/{}/{}", testWaypoint.x, testWaypoint.y,
						testWaypoint.z, (int) Mc.x(mc.player), (int) Mc.y(mc.player), (int) Mc.z(mc.player));
				// 20 Blöcke nach Norden und nach Süden schauen (yaw 0) → Wegpunkt im Blick.
				// "execute as/at @p": ~ bezieht sich sonst auf den Weltspawn, nicht auf den Spieler.
				command(mc, "execute as @p at @s run tp @s ~ ~ ~-20 0 0");
				next(40);
				break;
			case 16:
				shot(mc, "trsclient-waypoints");
				Mc.setScreen(new WaypointListScreen(null));
				next(20);
				break;
			case 17:
				if (!ensureScreen(WaypointListScreen.class, () -> new WaypointListScreen(null))) return;
				shot(mc, "trsclient-waypoint-liste");
				// Menü mit Text-Einstellungen (Auto-GG)
				Mc.setScreen(new TrsMenuScreen(null).select(modules.autoGg));
				next(20);
				break;
			case 18:
				if (!ensureScreen(TrsMenuScreen.class, () -> new TrsMenuScreen(null).select(modules.autoGg))) return;
				shot(mc, "trsclient-menu-text");
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
			case 19:
				shot(mc, "trsclient-chat");
				// Auto-GG und Text-Hotkey senden echten Chat (prüft den Sende-Weg je Version)
				modules.autoGg.setEnabled(true);
				modules.autoGgText.set("gg (TRS-Autotest)");
				modules.autoGgDelay.set(0.5);
				modules.textHotkeys.setEnabled(true);
				modules.hotkeyTexts[0].set("Text-Hotkey 1 (TRS-Autotest)");
				chat(mc, "Winner: TRS Client");
				TrsClient.get().chat().onHotkey(0);
				next(30);
				break;
			case 20:
				shot(mc, "trsclient-autogg");
				modules.autoGg.setEnabled(false);
				modules.textHotkeys.setEnabled(false);
				copyChatLine(mc);
				next(5);
				break;
			case 21:
				// TRS-Umhang, Umhang-Physik und Abzeichen (mit lokaler API-Attrappe, siehe -PtrsApi)
				CapeTest.Actions actions = new CapeTest.Actions() {
					@Override
					public void shot(String name) {
						AutoTest.shot(mc, name);
					}

					@Override
					public void command(String command) {
						AutoTest.command(mc, command);
					}
				};
				if (capeTest.step(mc, modules, actions)) return;
				// Emote-Rad und Emotes (gleiche Attrappe)
				if (emoteTest.step(mc, modules, actions)) return;
				next(5);
				break;
			case 22:
				TrsClient.LOGGER.info("[Autotest] Hook-Aufrufe: {}", HookStats.summary());
				TrsClient.LOGGER.info("[Autotest] fertig, verlasse Welt und beende das Spiel");
				TrsClient.get().sprintToggle().set(false);
				if (testWaypoint != null) TrsClient.get().waypoints().remove(testWaypoint);
				if (before != null) modules.registry.apply(before);
				TrsClient.get().saveConfig();
				disconnect(mc);
				next(20);
				break;
			default:
				if (step == 23) mc.stop();
				step = 24;
				break;
		}
	}

	private final CapeTest capeTest = new CapeTest();
	private final EmoteTest emoteTest = new EmoteTest();

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

	/**
	 * Prüft das Kopieren per Strg+Klick: klickt rechnerisch auf die unterste Chat-Zeile
	 * und schreibt das Ergebnis der Zwischenablage ins Log.
	 */
	private static void copyChatLine(Minecraft mc) {
		double scale = Mc.chatScale();
		int lineHeight = Mc.chatLineHeight();
		double mouseY = Mc.window().getGuiScaledHeight() - 40 - scale * lineHeight / 2.0;
		boolean copied = TrsClient.get().chat().onChatClick(20, mouseY, true);
		String clipboard = copied ? mc.keyboardHandler.getClipboard() : "(nicht kopiert)";
		TrsClient.LOGGER.info("[Autotest] Strg+Klick auf Chat-Zeile: {}", clipboard);
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
		//server.execute(() -> server.getCommands().performCommand(server.createCommandSourceStack(), command));
	}

	/**
	 * Tastatureingaben des Benutzers können das Spielfenster erreichen und einen Bildschirm schließen.
	 * Ist der erwartete Bildschirm nicht offen, wird er (höchstens 5-mal) neu geöffnet und kurz gewartet.
	 */
	/**
	 * Startbildschirm: Ruhe-Screenshot, eine Lampe per Tastatur-Auswahl an (wie Hover), dann die
	 * Kosten der Hintergrund-Animation messen (mit und ohne Schaltung). true = noch nicht fertig.
	 */
	private boolean titleStep(Minecraft mc, TrsTitleScreen screen) {
		dev.theredstonee.trsclient.core.ui.title.TitleUi title = screen.title();
		switch (titlePhase++) {
			case 0:
				shot(mc, "trsclient-title");
				title.focus(0);
				wait = 10;
				return true;
			case 1:
				shot(mc, "trsclient-title-hover");
				title.focus(-1);
				wait = 100;
				return true;
			case 2:
				sceneFrameMs = title.frameMillis();
				TrsClient.LOGGER.info("[Autotest] Startbildschirm: Szene {} µs, Bildschirm {} µs (CPU), Bildzeit {} ms, {} fps, sparsam={}",
						Math.round(title.sceneMicros()), Math.round(title.frameMicros()), String.format(java.util.Locale.ROOT, "%.2f", sceneFrameMs), Mc.fps(), title.sparseScene());
				title.setSceneEnabled(false);
				wait = 100;
				return true;
			case 3:
				TrsClient.LOGGER.info("[Autotest] Startbildschirm ohne Schaltung: Bildschirm {} µs (CPU), Bildzeit {} ms, {} fps",
						Math.round(title.frameMicros()), String.format(java.util.Locale.ROOT, "%.2f", title.frameMillis()), Mc.fps());
				title.setSceneEnabled(true);
				return false;
			default:
				return false;
		}
	}

	private boolean ensureScreen(Class<? extends Screen> type, Supplier<Screen> factory) {
		if (type.isInstance(Mc.screen()) || reopenCount >= 5) return true;
		reopenCount++;
		TrsClient.LOGGER.warn("[Autotest] {} wurde geschlossen (Eingabe?) – öffne erneut", type.getSimpleName());
		KeyMapping.releaseAll();
		Mc.setScreen(factory.get());
		wait = 10;
		return false;
	}

	/**
	 * Klickt "TRS-Menü" auf dem Startbildschirm und schließt das Menü mit Esc – beides über
	 * MouseHandler/KeyboardHandler, also genau den Weg echter Eingaben (Maustasten-Zählung je
	 * Version, Ereignis-Objekte ab 1.21.9, SDL ab 26.3).
	 */
	private void realInput(Minecraft mc) {
		switch (inputPhase) {
			case 0: {
				if (!(Mc.screen() instanceof TrsTitleScreen)) {
					Mc.setScreen(new TrsTitleScreen());
					wait = 10;
					return;
				}
				int[] r = ((TrsTitleScreen) Mc.screen()).spot("trsMenu");
				if (r == null) {
					wait = 2;
					return;
				}
				click(mc, r[0] + r[2] / 2.0, r[1] + r[3] / 2.0);
				inputPhase = 1;
				wait = 10;
				return;
			}
			case 1: {
				inputClick = Mc.screen() instanceof TrsMenuScreen;
				if (inputClick) {
					key(mc, dev.theredstonee.trsclient.compat.Keys.code("key.keyboard.escape"));
					inputPhase = 2;
					wait = 25;
				} else {
					report(false, false);
					inputPhase = 3;
				}
				return;
			}
			case 2: {
				report(true, Mc.screen() instanceof TrsTitleScreen);
				inputPhase = 3;
				return;
			}
			default:
				return;
		}
	}

	private void report(boolean click, boolean key) {
		String line = "[Autotest] Echte Eingabe über MouseHandler/KeyboardHandler: Klick " + (click ? "OK" : "FEHLER")
				+ ", Taste " + (key ? "OK" : "FEHLER") + " (Bildschirm: " + (Mc.screen() == null ? "-" : Mc.screen().getClass().getSimpleName()) + ")";
		if (click && key) TrsClient.LOGGER.info(line);
		else TrsClient.LOGGER.error(line);
		if (!(Mc.screen() instanceof TrsTitleScreen)) Mc.setScreen(new TrsTitleScreen());
		wait = 10;
	}

	/** Linksklick an einer GUI-Position – wie ein echter Klick des Fensters. */
	private static void click(Minecraft mc, double guiX, double guiY) {
		com.mojang.blaze3d.platform.Window w = Mc.window();
		dev.theredstonee.trsclient.mixin.MouseHandlerAccessor mouse = (dev.theredstonee.trsclient.mixin.MouseHandlerAccessor) mc.mouseHandler;
		mouse.trsclient$setXpos(guiX * w.getScreenWidth() / (double) w.getGuiScaledWidth());
		mouse.trsclient$setYpos(guiY * w.getScreenHeight() / (double) w.getGuiScaledHeight());
		int left = dev.theredstonee.trsclient.compat.Keys.MOUSE_LEFT;
		//? if >=1.21.9 {
		/*mouse.trsclient$onButton(windowHandle(), new net.minecraft.client.input.MouseButtonInfo(left, 0), dev.theredstonee.trsclient.compat.Keys.PRESS);
		mouse.trsclient$onButton(windowHandle(), new net.minecraft.client.input.MouseButtonInfo(left, 0), 0);
		*///?} else {
		mouse.trsclient$onPress(windowHandle(), left, dev.theredstonee.trsclient.compat.Keys.PRESS, 0);
		mouse.trsclient$onPress(windowHandle(), left, 0, 0);
		//?}
		// Maus danach aus dem Weg (sonst leuchtet der Knopf in späteren Screenshots als Hover).
		mouse.trsclient$setXpos(0);
		mouse.trsclient$setYpos(0);
	}

	/** Taste drücken und loslassen – wie ein echter Tastendruck des Fensters. */
	private static void key(Minecraft mc, int code) {
		dev.theredstonee.trsclient.mixin.KeyboardHandlerAccessor keyboard = (dev.theredstonee.trsclient.mixin.KeyboardHandlerAccessor) mc.keyboardHandler;
		//? if >=1.21.9 {
		/*keyboard.trsclient$keyPress(windowHandle(), dev.theredstonee.trsclient.compat.Keys.PRESS, new net.minecraft.client.input.KeyEvent(code, 0, 0));
		keyboard.trsclient$keyPress(windowHandle(), 0, new net.minecraft.client.input.KeyEvent(code, 0, 0));
		*///?} else {
		keyboard.trsclient$keyPress(windowHandle(), code, 0, dev.theredstonee.trsclient.compat.Keys.PRESS, 0);
		keyboard.trsclient$keyPress(windowHandle(), code, 0, 0, 0);
		//?}
	}

	private static long windowHandle() {
		//? if >=1.21.9 {
		/*return Mc.window().handle();
		*///?} else
		return Mc.window().getWindow();
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
					//WorldPresets::createNormalWorldDimensions);
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
				//Mc.window().getWidth(), Mc.window().getHeight(),
				Mc.mainRenderTarget(),
				//? if >=1.21.6
				//1,
				msg -> TrsClient.LOGGER.info("[Autotest] {}", msg.getString()));
	}
}

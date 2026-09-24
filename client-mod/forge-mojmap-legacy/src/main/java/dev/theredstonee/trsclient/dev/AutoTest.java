package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.feature.PvpFeatures;
import dev.theredstonee.trsclient.screen.CrosshairEditorScreen;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import dev.theredstonee.trsclient.screen.PackScreen;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.WaypointListScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Entwickler-Selbsttest, nur aktiv mit {@code -Dtrsclient.autotest=true}
 * ({@code ./gradlew :<version>:runClient -PtrsAutotest}): TRS-Startbildschirm und Menü, neue Testwelt
 * (über den Vanilla-"Welt erstellen"-Bildschirm, so bleibt es über alle Versionen gleich),
 * Screenshots von HUD, Zoom, Nacht ohne/mit Fullbright, Treffer-Farbe, Freelook, Menü,
 * Fadenkreuz-Editor, Resourcepacks, HUD-Profilen, HUD-Editor, Wegpunkten/Minimap, Wegpunkt-Liste,
 * dem Menü mit Text-Einstellungen und Chat, dann beenden.
 * Screenshots: {@code run/forge-<minecraft>/screenshots/trsclient-<minecraft>-*.png}.
 */
public final class AutoTest {
	private static final String MC_VERSION = Mc.modVersion("minecraft");

	private int step;
	private int wait;
	private int titlePhase;
	private int reopenCount;
	/** Modul-Zustand vor dem Test – wird am Ende wiederhergestellt (die Config bleibt sauber). */
	private TrsConfig before;

	private final CapeTest capeTest = new CapeTest();
	private final EmoteTest emoteTest = new EmoteTest();

	private AutoTest() {
	}

	public static void installIfRequested() {
		if (!Boolean.getBoolean("trsclient.autotest")) return;
		TrsClient.LOGGER.info("[Autotest] aktiv");
		AutoTest test = new AutoTest();
		MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END) test.tick(Minecraft.getInstance());
		});
	}

	private void tick(Minecraft mc) {
		// Verliert das Fenster den Fokus oder drückt jemand Esc, öffnet Vanilla das Pausenmenü –
		// für saubere Screenshots wieder schließen und hängende Tasten lösen.
		if (step >= 5 && step < 23 && Mc.screen() instanceof PauseScreen) {
			Mc.setScreen(null);
			KeyMapping.releaseAll();
		}
		if (wait > 0) {
			wait--;
			return;
		}
		TrsModules modules = TrsClient.get().modules();
		switch (step) {
			case 0: {
				if (mc.getOverlay() != null || Mc.screen() == null) return; // Ressourcen laden noch
				mc.options.pauseOnLostFocus = false;
				//? if >=1.19.4
				/*mc.options.onboardAccessibility = false;*/
				before = modules.registry.capture();
				modules.titleScreen.setEnabled(true);
				mc.getTutorial().setStep(TutorialSteps.NONE);
				seedServers(mc);
				deleteOldWorlds(mc);
				TrsClient.LOGGER.info("[Autotest] Startbildschirm erreicht: {}", Mc.screen().getClass().getSimpleName());
				// Neu öffnen, damit die eben angelegten Server in der Schnellbeitritt-Leiste stehen.
				Mc.setScreen(new TrsTitleScreen());
				next(20);
				break;
			}
			case 1: {
				if (!ensureScreen(TrsTitleScreen.class, TrsTitleScreen::new)) return;
				if (titleStep(mc, (TrsTitleScreen) Mc.screen())) return;
				Mc.setScreen(new TrsMenuScreen(Mc.screen()));
				next(20);
				break;
			}
			case 2: {
				shot(mc, "trsclient-menu-title");
				openCreateWorld(mc);
				next(10);
				break;
			}
			case 3: {
				// "Neue Welt erstellen" auslösen (privat, aber in allen Versionen parameterlos: onCreate()).
				if (!(Mc.screen() instanceof CreateWorldScreen)) return; // 1.19: Bildschirm kommt asynchron
				try {
					Method onCreate = CreateWorldScreen.class.getDeclaredMethod("onCreate");
					onCreate.setAccessible(true);
					onCreate.invoke(Mc.screen());
					TrsClient.LOGGER.info("[Autotest] erstelle Testwelt");
				} catch (ReflectiveOperationException e) {
					TrsClient.LOGGER.error("[Autotest] Welt konnte nicht erstellt werden", e);
					step = 21;
					return;
				}
				next(0);
				break;
			}
			case 4: {
				if (mc.level == null || mc.player == null) return;
				if (Mc.screen() != null) return; // Ladebildschirm
				TrsClient.LOGGER.info("[Autotest] Welt geladen");
				KeyMapping.releaseAll();
				command(mc, "gamemode creative @p");
				command(mc, "difficulty peaceful");
				command(mc, "gamerule doDaylightCycle false");
				// Freie Sicht: Platz um den Spieler räumen, Boden darunter, Blick waagerecht.
				command(mc, "execute at @p run fill ~-6 ~ ~-6 ~6 ~4 ~6 air");
				command(mc, "execute at @p run fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 grass_block");
				command(mc, "tp @p ~ ~ ~ ~ 0");
				// Echte Rüstung/Effekte für die HUD-Module
				//? if >=1.17 {
				/*command(mc, "item replace entity @p armor.head with iron_helmet");
				command(mc, "item replace entity @p armor.chest with diamond_chestplate");
				command(mc, "item replace entity @p armor.feet with golden_boots");
				command(mc, "item replace entity @p weapon.mainhand with diamond_sword");
				*///?} else {
				command(mc, "replaceitem entity @p armor.head iron_helmet");
				command(mc, "replaceitem entity @p armor.chest diamond_chestplate");
				command(mc, "replaceitem entity @p armor.feet golden_boots");
				command(mc, "replaceitem entity @p weapon.mainhand diamond_sword");
				//?}
				command(mc, "effect give @p speed 300 1 true");
				command(mc, "effect give @p night_vision 120 0 true");
				command(mc, "time set day");
				for (Module m : new Module[]{modules.armor, modules.effects, modules.coords,
						modules.clock, modules.memory, modules.packs, modules.toggleSprint, modules.crosshair}) {
					m.setEnabled(true);
				}
				TrsClient.get().sprintToggle().set(true);
				modules.crosshairShape.set(Crosshair.Shape.CROSS_DOT);
				modules.crosshairColor.set(0xFFB84D);
				// Chat-Meldungen der Befehle verblassen lassen (Chat verblasst nach 10 s)
				next(230);
				break;
			}
			case 5: {
				shot(mc, "trsclient-hud");
				TrsClient.get().setForceZoom(true);
				next(30);
				break;
			}
			case 6: {
				shot(mc, "trsclient-zoom");
				TrsClient.get().setForceZoom(false);
				command(mc, "effect clear @p night_vision");
				command(mc, "time set midnight");
				modules.fullbright.setEnabled(false);
				next(40);
				break;
			}
			case 7: {
				shot(mc, "trsclient-night");
				modules.fullbright.setEnabled(true);
				next(20);
				break;
			}
			case 8: {
				shot(mc, "trsclient-fullbright");
				modules.fullbright.setEnabled(false);
				// Treffer-Farbe: Schwein vor den Spieler setzen und mit Schaden-Effekt treffen
				modules.hitColor.setEnabled(true);
				command(mc, "time set day");
				command(mc, "execute at @p rotated ~ 0 run summon pig ^ ^ ^3 {NoAI:1b,Tags:[\"trsautotest\"]}");
				next(30);
				break;
			}
			case 9: {
				command(mc, "effect give @e[tag=trsautotest] instant_damage 1 0 true");
				// Befehl läuft im nächsten Server-Tick, das Treffer-Paket braucht einen weiteren – Einfärbung hält 10 Ticks.
				next(4);
				break;
			}
			case 10: {
				shot(mc, "trsclient-hitcolor");
				if (PvpFeatures.mixinFeatures()) TrsClient.get().pvp().forceFreelook(150F);
				next(20);
				break;
			}
			case 11: {
				if (PvpFeatures.mixinFeatures()) shot(mc, "trsclient-freelook");
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				Mc.setScreen(new TrsMenuScreen(null).select(modules.crosshair));
				next(20);
				break;
			}
			case 12: {
				if (!ensureScreen(TrsMenuScreen.class, () -> new TrsMenuScreen(null).select(modules.crosshair))) return;
				shot(mc, "trsclient-menu");
				Mc.setScreen(new CrosshairEditorScreen(null));
				next(20);
				break;
			}
			case 13: {
				if (!ensureScreen(CrosshairEditorScreen.class, () -> new CrosshairEditorScreen(null))) return;
				shot(mc, "trsclient-crosshair-editor");
				Mc.setScreen(new PackScreen(null));
				next(20);
				break;
			}
			case 14: {
				if (!ensureScreen(PackScreen.class, () -> new PackScreen(null))) return;
				shot(mc, "trsclient-packs");
				Mc.setScreen(new TrsMenuScreen(null).showProfiles());
				next(20);
				break;
			}
			case 15: {
				if (!ensureScreen(TrsMenuScreen.class, () -> new TrsMenuScreen(null).showProfiles())) return;
				shot(mc, "trsclient-profiles");
				Mc.setScreen(new HudEditorScreen(null).selectFirst());
				next(20);
				break;
			}
			case 16: {
				if (!ensureScreen(HudEditorScreen.class, () -> new HudEditorScreen(null).selectFirst())) return;
				shot(mc, "trsclient-hud-editor");
				Mc.setScreen(null);
				// Wegpunkte + Minimap + PvP-Anzeigen einschalten und einen Wegpunkt anlegen
				modules.waypoints.setEnabled(true);
				modules.minimap.setEnabled(true);
				modules.speed.setEnabled(true);
				if (PvpFeatures.mixinFeatures()) {
					modules.reach.setEnabled(true);
					modules.combo.setEnabled(true);
				}
				TrsClient.get().waypoints().create("Basis", 0x3DDC84);
				// 20 Blöcke nach Norden und zurückschauen (yaw 0 = Süden) → Wegpunkt im Blick
				command(mc, "tp @p ~ ~ ~-20 0 0");
				next(40);
				break;
			}
			case 17: {
				shot(mc, "trsclient-waypoints");
				Mc.setScreen(new WaypointListScreen(null));
				next(20);
				break;
			}
			case 18: {
				if (!ensureScreen(WaypointListScreen.class, () -> new WaypointListScreen(null))) return;
				shot(mc, "trsclient-waypoint-liste");
				// Menü mit Text-Einstellungen – neue Textzeilen im Einstellungs-Bereich
				Mc.setScreen(new TrsMenuScreen(null).select(textModule(modules)));
				next(20);
				break;
			}
			case 19: {
				if (!ensureScreen(TrsMenuScreen.class, () -> new TrsMenuScreen(null).select(textModule(modules)))) return;
				shot(mc, "trsclient-menu-text");
				Mc.setScreen(null);
				// Chat: Zeitstempel an, dreimal dieselbe Nachricht → wird zusammengefasst
				if (PvpFeatures.mixinFeatures()) {
					modules.chat.setEnabled(true);
					modules.chatTimestamps.set(true);
					modules.chatStack.set(true);
					chat("TRS Client: Chat-Test");
					chat("Wiederholte Nachricht");
					chat("Wiederholte Nachricht");
					chat("Wiederholte Nachricht");
				}
				next(10);
				break;
			}
			case 20: {
				shot(mc, "trsclient-chat");
				next(5);
				break;
			}
			case 21: {
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
			}
			case 22: {
				TrsClient.LOGGER.info("[Autotest] Hook-Aufrufe: {}", HookStats.summary());
				TrsClient.LOGGER.info("[Autotest] fertig, verlasse Welt und beende das Spiel");
				TrsClient.get().sprintToggle().set(false);
				if (before != null) modules.registry.apply(before);
				TrsClient.get().saveConfig();
				if (mc.level != null) mc.level.disconnect();
				mc.clearLevel();
				next(20);
				break;
			}
			default: {
				if (step == 23) mc.stop();
				step = 24;
			}
		}
	}

	/**
	 * Modul für den Screenshot der Text-Einstellungen: Auto-GG, wo es das Modul gibt;
	 * ohne Mixin (1.14.4) stattdessen die Text-Hotkeys (dort stehen ebenfalls Text- und Tastenzeilen).
	 */
	private static Module textModule(TrsModules modules) {
		return PvpFeatures.mixinFeatures() ? modules.autoGg : modules.textHotkeys;
	}

	/** Schreibt eine Nachricht in den Chat (wie eine Servernachricht – geht durch die TRS-Chat-Hooks). */
	private static void chat(String text) {
		dev.theredstonee.trsclient.compat.ChatLines.addMessage(Mc.literal(text));
	}

	/** Öffnet den Vanilla-Bildschirm "Neue Welt erstellen" (Fabrikmethode je nach Version). */
	private static void openCreateWorld(Minecraft mc) {
		//? if >=1.19 {
		/*CreateWorldScreen.openFresh(mc, null);
		*///?} elif >=1.18.2 {
		/*Mc.setScreen(CreateWorldScreen.createFresh(null));
		*///?} elif >=1.16.2 {
		Mc.setScreen(CreateWorldScreen.create(null));
		//?} else
		/*Mc.setScreen(new CreateWorldScreen(null));*/
	}

	/** Entfernt Testwelten früherer Läufe ("New World…" im Spielordner dieser Version). */
	private static void deleteOldWorlds(Minecraft mc) {
		Path saves = mc.gameDirectory.toPath().resolve("saves");
		if (!Files.isDirectory(saves)) return;
		try (Stream<Path> worlds = Files.list(saves)) {
			worlds.filter(p -> p.getFileName().toString().startsWith("New World")).forEach(AutoTest::deleteTree);
		} catch (IOException e) {
			TrsClient.LOGGER.warn("[Autotest] alte Testwelten nicht gelöscht", e);
		}
	}

	private static void deleteTree(Path root) {
		try (Stream<Path> files = Files.walk(root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		} catch (IOException e) {
			TrsClient.LOGGER.warn("[Autotest] {} nicht gelöscht", root, e);
		}
	}

	/** Legt für den Test zwei Server in servers.dat an, falls die Liste leer ist (Schnellbeitritt-Leiste). */
	private static void seedServers(Minecraft mc) {
		ServerList list = new ServerList(mc);
		list.load();
		if (list.size() > 0) return;
		//? if >=1.19 {
		/*list.add(new ServerData("TRS Testserver", "localhost:25565", false), false);
		list.add(new ServerData("Hypixel", "mc.hypixel.net", false), false);
		*///?} else {
		list.add(new ServerData("TRS Testserver", "localhost:25565", false));
		list.add(new ServerData("Hypixel", "mc.hypixel.net", false));
		//?}
		list.save();
	}

	/** Führt einen Befehl als Server (Berechtigungsstufe 4) aus. */
	private static void command(Minecraft mc, String command) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) return;
		//? if >=1.19 {
		/*server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
		*///?} else
		server.execute(() -> server.getCommands().performCommand(server.createCommandSourceStack(), command));
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
				TrsClient.LOGGER.info("[Autotest] Startbildschirm: Szene " + Math.round(title.sceneMicros()) + " µs, Bildschirm "
						+ Math.round(title.frameMicros()) + " µs (CPU), Bildzeit " + String.format(java.util.Locale.ROOT, "%.2f", title.frameMillis())
						+ " ms, " + Mc.fps() + " fps, sparsam=" + title.sparseScene());
				title.setSceneEnabled(false);
				wait = 100;
				return true;
			case 3:
				TrsClient.LOGGER.info("[Autotest] Startbildschirm ohne Schaltung: Bildschirm " + Math.round(title.frameMicros())
						+ " µs (CPU), Bildzeit " + String.format(java.util.Locale.ROOT, "%.2f", title.frameMillis()) + " ms, " + Mc.fps() + " fps");
				title.setSceneEnabled(true);
				return false;
			default:
				return false;
		}
	}

	private void next(int ticks) {
		step++;
		wait = ticks;
	}

	private static void shot(Minecraft mc, String name) {
		// run/forge-<minecraft>/screenshots/trsclient-<minecraft>-<name>.png
		String file = name.replace("trsclient-", "trsclient-" + MC_VERSION + "-") + ".png";
		//? if >=1.17 {
		/*Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(),
				msg -> TrsClient.LOGGER.info("[Autotest] {}", msg.getString()));
		*///?} else {
		Screenshot.grab(mc.gameDirectory, file, Mc.window().getWidth(), Mc.window().getHeight(), mc.getMainRenderTarget(),
				msg -> TrsClient.LOGGER.info("[Autotest] {}", msg.getString()));
		//?}
	}
}

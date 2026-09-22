package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.hud.Crosshair;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.pvp.ComboTracker;
import dev.theredstonee.trsclient.screen.CrosshairEditorScreen;
import dev.theredstonee.trsclient.screen.HudEditorScreen;
import dev.theredstonee.trsclient.screen.PackScreen;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.WaypointListScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Entwickler-Selbsttest, nur aktiv mit {@code -Dtrsclient.autotest=true}
 * ({@code ./gradlew :<minecraft>:runClient -PtrsAutotest}): TRS-Startbildschirm und Menü, Testwelt laden,
 * Screenshots von HUD (inkl. Rüstung/Effekte/Koordinaten/…, eigenes Fadenkreuz), Zoom, Nacht ohne/mit
 * Fullbright, Freelook, Menü, Fadenkreuz-Editor, Resourcepacks, HUD-Profilen, HUD-Editor sowie
 * Wegpunkte + Minimap + Chat-Zeitstempel/Zusammenfassung, Wegpunkt-Liste und dem Menü mit
 * Text-Einstellungen, dann beenden.
 * Screenshots landen in {@code run/forge-<minecraft>/screenshots/trsclient-<minecraft>-*.png}.
 */
public final class AutoTest {
	private final String mcVersion = Mc.version();
	private final String world = "trs-autotest-" + mcVersion;

	private int step;
	private int wait;
	private int reopenCount;
	/** Bildschirm, den der Test gerade erwartet (null = Spiel ohne Menü). */
	private Class<? extends GuiScreen> expected;
	private GuiScreen expectedInstance;
	/** Modul-Zustand vor dem Test – wird am Ende wiederhergestellt (die Config bleibt sauber). */
	private TrsConfig before;

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
		// Das Spielfenster bekommt den Fokus – Tastendrücke landen im Spiel (Esc → Pausenmenü …).
		// Für saubere Screenshots den erwarteten Zustand wiederherstellen (höchstens 5-mal).
		if (step >= 4 && step < 17 && Mc.world() != null && !isExpected(mc.currentScreen) && reopenCount < 5) {
			reopenCount++;
			TrsClient.LOGGER.warn("[Autotest] fremdes Menü geschlossen/ersetzt: {}", mc.currentScreen);
			mc.displayGuiScreen(expectedInstance);
			KeyBinding.unPressAllKeys();
			if (wait < 5) wait = 5;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		// Verbindung verloren (z. B. Server-Timeout bei überlasteter Maschine) → Test abbrechen statt abstürzen.
		if (step >= 4 && step < 17 && Mc.player() == null) {
			TrsClient.LOGGER.error("[Autotest] Welt/Spieler verloren in Schritt {} – Abbruch", step);
			step = 17;
		}
		TrsModules modules = TrsClient.get().modules();
		switch (step) {
			case 0:
				if (!(mc.currentScreen instanceof TrsTitleScreen) && !(mc.currentScreen instanceof GuiMainMenu)) return; // lädt noch
				mc.gameSettings.pauseOnLostFocus = false;
				//? if >=1.12 {
				/*mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
				*///?}
				before = modules.registry.capture();
				modules.titleScreen.setEnabled(true);
				seedServers(mc);
				TrsClient.LOGGER.info("[Autotest] Startbildschirm erreicht: {}", mc.currentScreen.getClass().getSimpleName());
				mc.displayGuiScreen(new TrsTitleScreen());
				next(20);
				break;
			case 1:
				shot(mc, "title");
				mc.displayGuiScreen(new TrsMenuScreen(mc.currentScreen));
				next(20);
				break;
			case 2:
				shot(mc, "menu-title");
				mc.displayGuiScreen(null);
				startWorld(mc);
				next(0);
				break;
			case 3: {
				EntityPlayerSP player = Mc.player();
				if (Mc.world() == null || player == null) return;
				if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiIngameMenu)) return; // Ladebildschirm
				TrsClient.LOGGER.info("[Autotest] Welt geladen");
				mc.displayGuiScreen(null);
				KeyBinding.unPressAllKeys();
				expect(null);
				// Leicht nach unten schauen, damit Gelände (und damit Fullbright) im Bild ist.
				player.rotationPitch = 15.0F;
				player.prevRotationPitch = 15.0F;
				String name = player.getName();
				command(mc, "replaceitem entity " + name + " slot.armor.head minecraft:iron_helmet");
				command(mc, "replaceitem entity " + name + " slot.armor.chest minecraft:diamond_chestplate");
				command(mc, "replaceitem entity " + name + " slot.armor.feet minecraft:golden_boots");
				command(mc, "replaceitem entity " + name + " slot.hotbar.0 minecraft:diamond_sword");
				command(mc, "effect " + name + " minecraft:speed 300 1 true");
				command(mc, "effect " + name + " minecraft:night_vision 120 0 true");
				command(mc, "time set 1000");
				for (Module m : new Module[]{modules.armor, modules.effects, modules.coords, modules.clock, modules.memory,
						modules.packs, modules.toggleSprint, modules.crosshair}) {
					m.setEnabled(true);
				}
				TrsClient.get().sprintToggle().set(true);
				modules.crosshairShape.set(Crosshair.Shape.CROSS_DOT);
				modules.crosshairColor.set(0xFFB84D);
				// Chat-Meldungen der Befehle verblassen lassen (Chat verblasst nach 10 s)
				next(230);
				break;
			}
			case 4:
				shot(mc, "hud");
				TrsClient.get().setForceZoom(true);
				next(30);
				break;
			case 5:
				shot(mc, "zoom");
				TrsClient.LOGGER.info("[Autotest] Zoom-Faktor beim Screenshot: {}", TrsClient.get().zoom().factor());
				TrsClient.get().setForceZoom(false);
				command(mc, "effect " + Mc.player().getName() + " clear");
				command(mc, "time set 18000");
				modules.fullbright.setEnabled(false);
				next(40);
				break;
			case 6:
				shot(mc, "night");
				modules.fullbright.setEnabled(true);
				next(20);
				break;
			case 7:
				shot(mc, "fullbright");
				TrsClient.LOGGER.info("[Autotest] Gamma-Option nach Fullbright unverändert: {}", mc.gameSettings.gammaSetting);
				modules.fullbright.setEnabled(false);
				command(mc, "time set 1000");
				TrsClient.get().pvp().forceFreelook(150F);
				next(20);
				break;
			case 8:
				shot(mc, "freelook");
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				expect(new TrsMenuScreen(null).select(modules.crosshair));
				next(20);
				break;
			case 9:
				shot(mc, "menu");
				expect(new CrosshairEditorScreen(null));
				next(20);
				break;
			case 10:
				shot(mc, "crosshair-editor");
				expect(new PackScreen(null));
				next(20);
				break;
			case 11:
				shot(mc, "packs");
				expect(new TrsMenuScreen(null).showProfiles());
				next(20);
				break;
			case 12:
				shot(mc, "profiles");
				expect(new HudEditorScreen(null).selectFirst());
				next(20);
				break;
			case 13: {
				shot(mc, "hud-editor");
				expect(null);
				// Neue Module einschalten und mit Beispielwerten füttern (reine Anzeigen).
				for (Module m : new Module[]{modules.reach, modules.combo, modules.speed, modules.minimap,
						modules.waypoints, modules.chat, modules.blockOutline, modules.hitboxes, modules.noHurtCam}) {
					m.setEnabled(true);
				}
				modules.chatTimestamps.set(true);
				long now = System.currentTimeMillis();
				TrsClient.get().pvp().reach().record(3.04, now);
				ComboTracker combo = TrsClient.get().pvp().combo();
				for (int i = 0; i < 3; i++) {
					combo.onAttack(4242, now);
					combo.onTargetHurt(4242, now);
				}
				EntityPlayerSP player = Mc.player();
				if (player != null) {
					// Wegpunkt 20 Blöcke vor dem Spieler, damit die Markierung im Bild liegt.
					double yaw = Math.toRadians(player.rotationYaw);
					int wx = (int) Math.floor(player.posX - Math.sin(yaw) * 20);
					int wz = (int) Math.floor(player.posZ + Math.cos(yaw) * 20);
					TrsClient.get().waypoints().createAt("Testpunkt", wx, (int) Math.floor(player.posY), wz, 0xFFB84D);
				}
				// Gleiche Nachricht dreimal → Zusammenfassung "(x3)" im Chat.
				for (int i = 0; i < 3; i++) command(mc, "say TRS-Selbsttest Chat");
				next(30);
				break;
			}
			case 14:
				shot(mc, "waypoints-minimap");
				expect(new WaypointListScreen(null));
				next(20);
				break;
			case 15:
				shot(mc, "waypoint-list");
				// Menü mit Text-Einstellungen (Auto-GG) – Textzeilen liegen jetzt im Einstellungs-Bereich.
				expect(new TrsMenuScreen(null).select(modules.autoGg));
				next(20);
				break;
			case 16:
				shot(mc, "menu-text");
				TrsClient.LOGGER.info("[Autotest] Wegpunkte: {}, Combo: {}, Reichweite: {}",
						TrsClient.get().waypoints().all().size(),
						TrsClient.get().pvp().combo().combo(),
						TrsClient.get().pvp().reach().distance());
				expect(null);
				next(5);
				break;
			case 17:
				TrsClient.LOGGER.info("[Autotest] Hook-Aufrufe: {}", HookStats.summary());
				TrsClient.LOGGER.info("[Autotest] fertig, verlasse Welt und beende das Spiel");
				TrsClient.get().sprintToggle().set(false);
				if (before != null) modules.registry.apply(before);
				TrsClient.get().saveConfig();
				WorldClient w = Mc.world();
				if (w != null) {
					w.sendQuittingDisconnectingPacket();
					mc.loadWorld((WorldClient) null);
				}
				mc.displayGuiScreen(new GuiMainMenu());
				next(20);
				break;
			default:
				if (step == 18) mc.shutdown();
				step = 19;
				break;
		}
	}

	/** Öffnet den erwarteten Bildschirm (null = Spiel ohne Menü). */
	private void expect(GuiScreen screen) {
		expectedInstance = screen;
		expected = screen == null ? null : screen.getClass();
		Minecraft.getMinecraft().displayGuiScreen(screen);
	}

	private boolean isExpected(GuiScreen current) {
		return expected == null ? current == null : expected.isInstance(current);
	}

	private void next(int ticks) {
		step++;
		wait = ticks;
	}

	/** Legt für den Test zwei Server in servers.dat an, falls die Liste leer ist (Schnellbeitritt-Leiste). */
	private static void seedServers(Minecraft mc) {
		ServerList list = new ServerList(mc);
		list.loadServerList();
		if (list.countServers() > 0) return;
		list.addServerData(new ServerData("TRS Testserver", "localhost:25565", false));
		list.addServerData(new ServerData("Hypixel", "mc.hypixel.net", false));
		list.saveServerList();
	}

	/** Führt einen Befehl als Server (volle Rechte) auf dem Server-Thread aus. */
	private static void command(Minecraft mc, final String command) {
		final IntegratedServer server = mc.getIntegratedServer();
		if (server == null) return;
		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				int result = server.getCommandManager().executeCommand(server, command);
				TrsClient.LOGGER.info("[Autotest] /{} → {}", command, result);
			}
		});
	}

	private void startWorld(Minecraft mc) {
		if (mc.getSaveLoader().canLoadWorld(world)) {
			TrsClient.LOGGER.info("[Autotest] öffne Testwelt '{}'", world);
			mc.launchIntegratedServer(world, world, null);
		} else {
			TrsClient.LOGGER.info("[Autotest] erstelle Testwelt '{}'", world);
			mc.launchIntegratedServer(world, world, Mc.creativeWorld(System.nanoTime()));
		}
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Screenshot {}", file);
	}
}

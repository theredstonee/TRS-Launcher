package dev.theredstonee.trsclient.dev;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
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
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import java.util.List;

/**
 * Entwickler-Selbsttest, nur aktiv mit {@code -Dtrsclient.autotest=true}
 * ({@code ./gradlew runClient -PtrsAutotest}): Menü im Hauptmenü, Testwelt laden, Rüstung/Effekte setzen,
 * Screenshots von HUD, Zoom, Nacht ohne/mit Fullbright, Menü, Fadenkreuz-Editor und HUD-Editor, dann beenden.
 * Screenshots landen in {@code run/forge-1.7.10/screenshots/trsclient-1.7.10-*.png}.
 */
public final class AutoTest {
	private static final String WORLD = "trs-autotest-1.7.10";
	private static final String PREFIX = "trsclient-1.7.10-";

	private int step;
	private int wait;
	private TrsConfig before;
	/** Bildschirm, den der Test gerade erwartet (null = Spiel ohne Menü). */
	private GuiScreen expected;

	private AutoTest() {
	}

	public static void installIfRequested() {
		if (!Boolean.getBoolean("trsclient.autotest")) return;
		TrsClient.LOGGER.info("[Autotest] aktiv");
		FMLCommonHandler.instance().bus().register(new AutoTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END) tick(Minecraft.getMinecraft());
	}

	private void tick(Minecraft mc) {
		// Das Spielfenster bekommt den Fokus – Tastendrücke landen im Spiel (Esc → Pausenmenü,
		// Rechte Umschalttaste → TRS-Menü …). Für saubere Screenshots fremde Menüs schließen.
		if (step >= 3 && step < 10 && mc.theWorld != null && mc.currentScreen != expected
				&& (expected == null || mc.currentScreen == null || mc.currentScreen.getClass() != expected.getClass())) {
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
				if (mc.theWorld == null || mc.thePlayer == null) return;
				if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiIngameMenu)) return; // Ladebildschirm
				TrsClient.LOGGER.info("[Autotest] Welt geladen");
				mc.displayGuiScreen(null);
				KeyBinding.unPressAllKeys();
				// Leicht nach unten schauen, damit Gelände (und damit Fullbright) im Bild ist.
				mc.thePlayer.rotationPitch = 15.0F;
				mc.thePlayer.prevRotationPitch = 15.0F;
				equip(mc);
				for (Module m : new Module[]{modules.armor, modules.effects, modules.coords, modules.clock, modules.memory,
						modules.packs, modules.toggleSprint, modules.crosshair}) {
					m.setEnabled(true);
				}
				TrsClient.get().pvp().sprint().set(true);
				modules.crosshairShape.set(Crosshair.Shape.CROSS_DOT);
				modules.crosshairColor.set(0xFFB84D);
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
				night(mc);
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
				TrsClient.LOGGER.info("[Autotest] Gamma-Option nach Fullbright unverändert: {}, FOV-Option: {}",
						mc.gameSettings.gammaSetting, mc.gameSettings.fovSetting);
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
				if (mc.theWorld != null) {
					mc.theWorld.sendQuittingDisconnectingPacket();
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

	/** Serverseitiger Spieler der Testwelt (1.7.10 hat keine Befehle wie /item replace, daher direkt). */
	private static EntityPlayerMP serverPlayer(Minecraft mc) {
		MinecraftServer server = mc.getIntegratedServer();
		if (server == null) return null;
		@SuppressWarnings("unchecked")
		List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
		return players.isEmpty() ? null : players.get(0);
	}

	/** Rüstung, Schwert und Effekte für die HUD-Module; Tag. */
	private static void equip(Minecraft mc) {
		EntityPlayerMP p = serverPlayer(mc);
		if (p == null) return;
		ItemStack[] armor = p.inventory.armorInventory;
		armor[3] = new ItemStack(Items.iron_helmet);
		armor[2] = new ItemStack(Items.diamond_chestplate);
		armor[0] = new ItemStack(Items.golden_boots);
		armor[0].setItemDamage(40);
		p.inventory.mainInventory[p.inventory.currentItem] = new ItemStack(Items.diamond_sword);
		// ambient = true: kaum Partikel (sonst verdecken Trank-Wirbel direkt vor der Kamera das Bild).
		p.addPotionEffect(new PotionEffect(Potion.moveSpeed.id, 6000, 1, true));
		p.addPotionEffect(new PotionEffect(Potion.nightVision.id, 2400, 0, true));
		p.worldObj.setWorldTime(1000L);
	}

	private static void night(Minecraft mc) {
		EntityPlayerMP p = serverPlayer(mc);
		if (p == null) return;
		p.removePotionEffect(Potion.nightVision.id);
		p.worldObj.setWorldTime(18000L);
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

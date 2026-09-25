package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.screen.WorldMapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Locale;

/**
 * Selbsttest Karten ({@code -PtrsAutotestOnly=maps}) unter 1.8.9–1.12.2: kleine Testwelt mit festem Seed
 * (wiederverwendet), Minimap rund/eckig/nah, Kreaturen, Wegpunkt + Todespunkt, Weltkarte (nah, weit,
 * Kontextmenü, Dialog), Höhlenansicht und Fair Play. Misst Tick- und Zeichenkosten und beendet das Spiel.
 */
public final class MapsTest {
	private static final long SEED = 20260925L;
	private int phase;
	private int wait;
	private int waited;
	private int bx, by, bz;
	private final String mcVersion = Mc.version();
	private final String world = "trs-maps-" + Mc.version();

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new MapsTest());
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
			step(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Karten: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.shutdown();
		}
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Karten: {}", text);
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-maps-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		MapEngine e = MapEngine.get();
		log(String.format(Locale.ROOT, "Bild %s – Tick Ø %.0f µs, Minimap zeichnen Ø %.0f µs, %d Chunks abgetastet, %d Texturen, %d hochgeladen",
				name, e.tickMicros(), e.drawMicros(), e.sampledTotal(), e.textures().regionTextureCount(), e.textures().uploads()));
	}

	private boolean waitFor(boolean ready, int max) {
		if (!ready && waited++ < max) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private static void cmd(Minecraft mc, final String command) {
		final IntegratedServer server = mc.getIntegratedServer();
		if (server == null) return;
		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				server.getCommandManager().executeCommand(server, command);
			}
		});
	}

	/** Kreaturen-Namen: bis 1.10 "Zombie", ab 1.11 "minecraft:zombie". */
	private String mob(String name) {
		boolean old = mcVersion.startsWith("1.8") || mcVersion.startsWith("1.9") || mcVersion.startsWith("1.10");
		return old ? name : "minecraft:" + name.toLowerCase(Locale.ROOT);
	}

	private void step(Minecraft mc) {
		TrsModules m = TrsClient.get().modules();
		MapEngine e = MapEngine.get();
		GuiScreen screen = mc.currentScreen;
		switch (phase) {
			case 0:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				mc.gameSettings.renderDistanceChunks = 6;
				m.minimap.setEnabled(true);
				m.worldMap.setEnabled(true);
				m.minimapShape.set(TrsModules.MapShape.ROUND);
				m.minimapRotate.set(true);
				m.minimapSize.set(128);
				m.minimapZoom.set(TrsModules.MinimapZoom.NORMAL);
				m.minimapHostile.set(true);
				m.minimapPassive.set(true);
				m.minimapBiome.set(true);
				m.minimapTime.set(true);
				m.minimapFairPlay.set(false);
				m.worldMapHostile.set(true);
				m.worldMapPassive.set(true);
				if (mc.getSaveLoader().canLoadWorld(world)) mc.launchIntegratedServer(world, world, null);
				else mc.launchIntegratedServer(world, world, Mc.creativeWorld(SEED));
				phase++;
				wait = 20;
				return;
			case 1:
				if (!waitFor(Mc.world() != null && Mc.player() != null && screen == null, 400)) return;
				bx = (int) Math.floor(Mc.player().posX);
				by = (int) Math.floor(Mc.player().posY);
				bz = (int) Math.floor(Mc.player().posZ);
				cmd(mc, "time set 6000");
				cmd(mc, "weather clear");
				cmd(mc, "gamerule doDaylightCycle false");
				cmd(mc, "gamerule doMobSpawning false");
				cmd(mc, "gamerule sendCommandFeedback false");
				cmd(mc, "difficulty 1");
				cmd(mc, "tp @p " + bx + " " + (by + 20) + " " + bz + " 225 30");
				cmd(mc, "summon " + mob("Zombie") + " " + (bx + 10) + " " + (by + 1) + " " + (bz - 6) + " {NoAI:1,PersistenceRequired:1}");
				cmd(mc, "summon " + mob("Skeleton") + " " + (bx - 12) + " " + (by + 1) + " " + (bz + 4) + " {NoAI:1,PersistenceRequired:1}");
				cmd(mc, "summon " + mob("Cow") + " " + (bx + 4) + " " + (by + 1) + " " + (bz + 12) + " {NoAI:1}");
				cmd(mc, "summon " + mob("Pig") + " " + (bx - 6) + " " + (by + 1) + " " + (bz - 14) + " {NoAI:1}");
				// Testwelt wird wiederverwendet: alte Test-Wegpunkte erst entfernen.
				for (dev.theredstonee.trsclient.core.waypoint.Waypoint old : e.waypoints()) {
					if ("Basis".equals(old.name) || "Mine".equals(old.name)) e.removeWaypoint(old);
				}
				e.addWaypoint("Basis", bx + 30, by, bz - 25, 0x3DDC84);
				e.addWaypoint("Mine", bx - 150, by, bz + 90, 0xFFB84D);
				TrsClient.get().waypoints().store().setDeath(TrsClient.get().waypoints().worldKey(), bx - 20, by, bz + 18,
						Mc.dimensionId(), 0xE0281E);
				phase++;
				wait = 140;
				return;
			case 2:
				shot(mc, "minimap-round");
				m.minimapShape.set(TrsModules.MapShape.SQUARE);
				phase++;
				wait = 10;
				return;
			case 3:
				shot(mc, "minimap-square-rotated");
				m.minimapRotate.set(false);
				phase++;
				wait = 20;
				return;
			case 4:
				shot(mc, "minimap-square");
				m.minimapShape.set(TrsModules.MapShape.ROUND);
				m.minimapRotate.set(true);
				m.minimapZoom.set(TrsModules.MinimapZoom.CLOSE);
				m.minimapSize.set(160);
				phase++;
				wait = 30;
				return;
			case 5:
				shot(mc, "minimap-close");
				m.minimapZoom.set(TrsModules.MinimapZoom.NORMAL);
				m.minimapSize.set(128);
				mc.displayGuiScreen(WorldMapScreen.create());
				phase++;
				wait = 40;
				return;
			case 6:
				shot(mc, "worldmap");
				worldMap(mc).setScaleNow(0.25f);
				phase++;
				wait = 60;
				return;
			case 7:
				shot(mc, "worldmap-far");
				worldMap(mc).setScaleNow(6f);
				worldMap(mc).testContextMenu(Mc.scaledResolution().getScaledWidth() / 2 + 40, Mc.scaledResolution().getScaledHeight() / 2 + 20);
				phase++;
				wait = 30;
				return;
			case 8:
				shot(mc, "worldmap-menu");
				worldMap(mc).testOpenDialog(bx + 40, bz + 20);
				phase++;
				wait = 10;
				return;
			case 9:
				shot(mc, "worldmap-dialog");
				mc.displayGuiScreen(null);
				cmd(mc, "fill " + (bx - 9) + " 20 " + (bz - 9) + " " + (bx + 9) + " 23 " + (bz + 9) + " air");
				cmd(mc, "fill " + (bx - 3) + " 20 " + (bz + 10) + " " + (bx + 3) + " 22 " + (bz + 30) + " air");
				cmd(mc, "fill " + (bx - 4) + " 19 " + (bz - 4) + " " + (bx + 4) + " 19 " + (bz + 4) + " water");
				cmd(mc, "tp @p " + bx + " 21 " + bz + " 180 20");
				phase++;
				wait = 120;
				return;
			case 10:
				log("Höhlenansicht aktiv: " + e.caveActive());
				shot(mc, "minimap-cave");
				m.minimapFairPlay.set(true);
				phase++;
				wait = 30;
				return;
			case 11:
				log("Fair Play: Höhle " + e.caveActive());
				shot(mc, "minimap-fairplay");
				m.minimapFairPlay.set(false);
				cmd(mc, "tp @p " + bx + " " + (by + 20) + " " + bz + " 225 30");
				phase++;
				wait = 160;
				return;
			case 12:
				shot(mc, "minimap-final");
				log(String.format(Locale.ROOT, "Messung: Tick Ø %.0f µs, Minimap zeichnen Ø %.0f µs", e.tickMicros(), e.drawMicros()));
				mc.displayGuiScreen(new dev.theredstonee.trsclient.screen.TrsMenuScreen(null).select(m.minimap));
				phase = 20;
				wait = 20;
				return;
			case 20:
				shot(mc, "menu-minimap");
				mc.displayGuiScreen(new dev.theredstonee.trsclient.screen.TrsMenuScreen(null).select(m.worldMap));
				phase = 21;
				wait = 20;
				return;
			case 21:
				shot(mc, "menu-worldmap");
				mc.displayGuiScreen(null);
				phase = 22;
				wait = 2;
				return;
			case 22:
				if (Mc.world() != null) Mc.world().sendQuittingDisconnectingPacket();
				mc.loadWorld((net.minecraft.client.multiplayer.WorldClient) null);
				mc.displayGuiScreen(new GuiMainMenu());
				phase = 13;
				wait = 40;
				return;
			case 13:
				mc.shutdown();
				phase++;
				return;
			default:
		}
	}

	private static WorldMapUi worldMap(Minecraft mc) {
		return (WorldMapUi) ((TrsUiScreen) mc.currentScreen).ui();
	}
}

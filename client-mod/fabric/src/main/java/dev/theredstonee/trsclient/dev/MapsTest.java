package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.screen.WorldMapScreen;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Selbsttest Karten ({@code -PtrsAutotestOnly=maps}): kleine Testwelt mit festem Seed (wiederverwendet), Minimap
 * rund/eckig/nah, Kreaturen, Wegpunkt + Todespunkt, Weltkarte (nah, weit, Kontextmenü, Wegpunkt-Dialog),
 * Höhlenansicht unter Tage und Fair Play. Misst Tick- und Zeichenkosten. Beendet das Spiel danach.
 * Screenshots: trsclient-&lt;mc&gt;-maps-*.png.
 */
public final class MapsTest {
	private static final long SEED = 20260925L;
	private int phase;
	private int wait;
	private int waited;
	private int bx, by, bz;

	public static void install() {
		MapsTest test = new MapsTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Karten: {}", text);
	}

	private void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-maps-" + name);
		MapEngine e = MapEngine.get();
		log(String.format(Locale.ROOT, "Bild %s – Tick Ø %.0f µs, Minimap zeichnen Ø %.0f µs, %d Chunks abgetastet, %d Texturen, %d hochgeladen",
				name, e.tickMicros(), e.drawMicros(), e.sampledTotal(), e.textures().regionTextureCount(), e.textures().uploads()));
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException ex) {
			TrsClient.LOGGER.error("[Autotest] Karten: Fehler in Phase {}", phase, ex);
			mc.stop();
			phase = 999;
		}
	}

	private boolean waitFor(boolean ready, int maxTries) {
		if (!ready && waited++ < maxTries) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private void cmd(Minecraft mc, String command) {
		AutoTest.command(mc, command);
	}

	private void step(Minecraft mc) {
		TrsModules m = TrsClient.get().modules();
		MapEngine e = MapEngine.get();
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || Mc.screen() == null) return;
				mc.options.pauseOnLostFocus = false;
				//? if >=1.19 {
				mc.options.renderDistance().set(6);
				//?} else
				/*mc.options.renderDistance = 6;*/
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
				AutoTest.startWorld(mc, "trs-maps-" + AutoTest.MC_VERSION, SEED);
				phase++;
				wait = 20;
				return;
			case 1:
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null, 400)) return;
				bx = (int) Math.floor(Mc.x(mc.player));
				by = (int) Math.floor(Mc.y(mc.player));
				bz = (int) Math.floor(Mc.z(mc.player));
				cmd(mc, "time set 6000");
				cmd(mc, "weather clear");
				cmd(mc, "gamerule doDaylightCycle false");
				cmd(mc, "gamerule advance_time false");
				cmd(mc, "gamerule doMobSpawning false");
				cmd(mc, "gamerule spawn_mobs false");
				cmd(mc, "gamerule sendCommandFeedback false");
				cmd(mc, "gamerule send_command_feedback false");
				cmd(mc, "difficulty easy");
				// Über dem Boden schweben und nach Nordosten schauen (Karte gedreht).
				cmd(mc, "tp @p " + bx + " " + (by + 20) + " " + bz + " 225 30");
				cmd(mc, "summon minecraft:zombie " + (bx + 10) + " " + (by + 1) + " " + (bz - 6) + " {NoAI:1b,PersistenceRequired:1b}");
				cmd(mc, "summon minecraft:skeleton " + (bx - 12) + " " + (by + 1) + " " + (bz + 4) + " {NoAI:1b,PersistenceRequired:1b}");
				cmd(mc, "summon minecraft:cow " + (bx + 4) + " " + (by + 1) + " " + (bz + 12) + " {NoAI:1b}");
				cmd(mc, "summon minecraft:pig " + (bx - 6) + " " + (by + 1) + " " + (bz - 14) + " {NoAI:1b}");
				// Testwelt wird wiederverwendet: alte Test-Wegpunkte erst entfernen.
				for (dev.theredstonee.trsclient.core.waypoint.Waypoint old : e.waypoints()) {
					if ("Basis".equals(old.name) || "Mine".equals(old.name)) e.removeWaypoint(old);
				}
				e.addWaypoint("Basis", bx + 30, by, bz - 25, 0x3DDC84);
				e.addWaypoint("Mine", bx - 150, by, bz + 90, 0xFFB84D);
				TrsClient.get().waypoints().store().setDeath(TrsClient.get().waypoints().worldKey(), bx - 20, by, bz + 18,
						Mc.dimensionId(), 0xE0281E);
				phase++;
				wait = 140; // Chunks abtasten lassen
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
				m.minimapZoom.set(TrsModules.MinimapZoom.VERY_FAR);
				phase++;
				wait = 30;
				return;
			case 6:
				shot(mc, "minimap-far");
				m.minimapZoom.set(TrsModules.MinimapZoom.NORMAL);
				m.minimapSize.set(128);
				Mc.setScreen(WorldMapScreen.create());
				phase++;
				wait = 40;
				return;
			case 7:
				shot(mc, "worldmap");
				worldMap().setScaleNow(0.25f);
				phase++;
				wait = 60;
				return;
			case 8:
				shot(mc, "worldmap-far");
				worldMap().setScaleNow(6f);
				worldMap().testContextMenu(Mc.window().getGuiScaledWidth() / 2 + 40, Mc.window().getGuiScaledHeight() / 2 + 20);
				phase++;
				wait = 30;
				return;
			case 9:
				shot(mc, "worldmap-menu");
				worldMap().testOpenDialog(bx + 40, bz + 20);
				phase++;
				wait = 10;
				return;
			case 10:
				shot(mc, "worldmap-dialog");
				Mc.setScreen(null);
				// Höhle unter dem Spieler graben, hineinstellen → Höhlenansicht.
				cmd(mc, "fill " + (bx - 9) + " 20 " + (bz - 9) + " " + (bx + 9) + " 23 " + (bz + 9) + " air");
				cmd(mc, "fill " + (bx - 3) + " 20 " + (bz + 10) + " " + (bx + 3) + " 22 " + (bz + 30) + " air");
				cmd(mc, "fill " + (bx - 4) + " 19 " + (bz - 4) + " " + (bx + 4) + " 19 " + (bz + 4) + " water");
				cmd(mc, "tp @p " + bx + " 21 " + bz + " 180 20");
				phase++;
				wait = 120;
				return;
			case 11:
				log("Höhlenansicht aktiv: " + e.caveActive());
				shot(mc, "minimap-cave");
				m.minimapFairPlay.set(true);
				phase++;
				wait = 30;
				return;
			case 12:
				log("Fair Play: Höhle " + e.caveActive());
				shot(mc, "minimap-fairplay");
				m.minimapFairPlay.set(false);
				cmd(mc, "tp @p " + bx + " " + (by + 20) + " " + bz + " 225 30");
				phase++;
				wait = 60;
				return;
			case 13:
				// Kosten messen: 5 s nichts ändern, Mittelwerte loggen.
				phase++;
				wait = 100;
				return;
			case 14:
				shot(mc, "minimap-final");
				log(String.format(Locale.ROOT, "Messung: Tick Ø %.0f µs, Minimap zeichnen Ø %.0f µs", e.tickMicros(), e.drawMicros()));
				Mc.setScreen(new dev.theredstonee.trsclient.screen.TrsMenuScreen(null).select(m.minimap));
				phase++;
				wait = 20;
				return;
			case 15:
				shot(mc, "menu-minimap");
				Mc.setScreen(new dev.theredstonee.trsclient.screen.TrsMenuScreen(null).select(m.worldMap));
				phase++;
				wait = 20;
				return;
			case 16:
				shot(mc, "menu-worldmap");
				Mc.setScreen(null);
				AutoTest.disconnect(mc);
				phase++;
				wait = 40;
				return;
			case 17:
				mc.stop();
				phase++;
				return;
			default:
		}
	}

	private static WorldMapUi worldMap() {
		return (WorldMapUi) ((TrsUiScreen) Mc.screen()).ui();
	}
}

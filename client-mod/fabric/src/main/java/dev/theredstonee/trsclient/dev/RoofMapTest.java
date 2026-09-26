package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.WorldMapScreen;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Selbsttest Dach ausblenden + Barrieren ({@code -PtrsAutotestOnly=roof}): baut in der Karten-Testwelt zwei Hallen
 * auf einer Plattform in der Luft – links eine mit Bretter-Dach (seitlich offen, damit es keine Höhle ist), rechts
 * eine mit Barriere-Decke 60 Blöcke über dem Boden – und macht Bilder der Minimap draußen, drinnen (Dach an/aus),
 * in der Barriere-Halle, der Weltkarte und mit Fair Play. Beendet das Spiel danach.
 * Screenshots: trsclient-&lt;mc&gt;-roof-*.png.
 */
public final class RoofMapTest {
	private static final long SEED = 20260925L;
	private static final int FLOOR = 150;
	private int phase;
	private int wait;
	private int waited;
	private int bx, bz;

	public static void install() {
		RoofMapTest test = new RoofMapTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Dach: {}", text);
	}

	private void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-roof-" + name);
		MapEngine e = MapEngine.get();
		log(String.format(Locale.ROOT, "Bild %s – Innenansicht %s (Schnitt %s), Höhle %s, Ebene %s, Tick Ø %.0f µs, Minimap Ø %.0f µs",
				name, e.roofActive(), e.roofActive() ? String.valueOf(e.roofCut()) : "-", e.caveActive(),
				e.viewLayer() == null ? "-" : e.viewLayer().id, e.tickMicros(), e.drawMicros()));
	}

	private void tick(Minecraft mc) {
		// Fenster auf Bildschirm 2 verliert den Fokus → Pausenmenü wieder schließen (sonst verdeckt es die Karte).
		if (((phase >= 2 && phase <= 9 && phase != 6) || phase == 11) && Mc.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) Mc.setScreen(null);
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException ex) {
			TrsClient.LOGGER.error("[Autotest] Dach: Fehler in Phase {}", phase, ex);
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

	private String box(int x0, int y0, int z0, int x1, int y1, int z1) {
		return (bx + x0) + " " + y0 + " " + (bz + z0) + " " + (bx + x1) + " " + y1 + " " + (bz + z1);
	}

	private void tp(Minecraft mc, int dx, int dz, float yaw) {
		cmd(mc, "tp @p " + (bx + dx) + ".5 " + (FLOOR + 1) + " " + (bz + dz) + ".5 " + yaw + " 10");
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
				m.minimapShape.set(TrsModules.MapShape.SQUARE);
				m.minimapRotate.set(false);
				m.minimapSize.set(192);
				m.minimapZoom.set(TrsModules.MinimapZoom.CLOSE);
				m.minimapBiome.set(true);
				m.minimapFairPlay.set(false);
				m.minimapCaveMode.set(TrsModules.CaveMode.AUTO);
				m.minimapHideRoof.set(true);
				AutoTest.startWorld(mc, "trs-maps-" + AutoTest.MC_VERSION, SEED);
				phase++;
				wait = 20;
				return;
			case 1:
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null, 400)) return;
				// Feste Stelle abseits der Karten-Szene (wiederverwendete Welt: jeder Lauf baut an derselben Stelle).
				bx = 400;
				bz = 400;
				cmd(mc, "tp @p " + bx + " " + (FLOOR + 1) + " " + (bz - 10) + " 0 10");
				cmd(mc, "forceload add " + (bx - 30) + " " + (bz - 16) + " " + (bx + 30) + " " + (bz + 16));
				phase = 11;
				wait = 40;
				return;
			case 11:
				// Warten, bis die Chunks der Szene geladen sind (sonst: „That position is not loaded“).
				if (!waitFor(mc.level.hasChunk((bx - 30) >> 4, (bz - 16) >> 4) && mc.level.hasChunk((bx + 30) >> 4, (bz + 16) >> 4), 120)) return;
				cmd(mc, "time set 6000");
				cmd(mc, "weather clear");
				cmd(mc, "gamerule doDaylightCycle false");
				cmd(mc, "gamerule advance_time false");
				cmd(mc, "gamerule doMobSpawning false");
				cmd(mc, "gamerule spawn_mobs false");
				cmd(mc, "gamerule sendCommandFeedback false");
				cmd(mc, "gamerule send_command_feedback false");
				// Plattform (die Hallen werden per „hollow“ jedes Mal frisch gebaut, auch in der wiederverwendeten Welt).
				cmd(mc, "fill " + box(-26, FLOOR, -12, 26, FLOOR, 12) + " smooth_stone");
				// Halle mit Dach (links): Bretter-Kasten, Boden 150, Dach 157, seitlich offene Fensterbänder.
				cmd(mc, "fill " + box(-22, FLOOR, -8, -4, FLOOR + 7, 8) + " oak_planks hollow");
				cmd(mc, "fill " + box(-21, FLOOR + 1, -8, -5, FLOOR + 4, -8) + " air");
				cmd(mc, "fill " + box(-21, FLOOR + 1, 8, -5, FLOOR + 4, 8) + " air");
				cmd(mc, "fill " + box(-21, FLOOR, -7, -5, FLOOR, 7) + " white_wool");
				cmd(mc, "fill " + box(-19, FLOOR, -5, -7, FLOOR, -3) + " red_wool");
				cmd(mc, "fill " + box(-19, FLOOR, 3, -7, FLOOR, 5) + " blue_wool");
				cmd(mc, "fill " + box(-14, FLOOR, -1, -12, FLOOR, 1) + " gold_block");
				// Halle mit Barriere-Decke (rechts): Quarz-Wände bis 209, Decke aus Barrieren auf 210.
				cmd(mc, "fill " + box(4, FLOOR, -8, 22, FLOOR + 60, 8) + " quartz_block hollow");
				cmd(mc, "fill " + box(5, FLOOR + 60, -7, 21, FLOOR + 60, 7) + " barrier");
				cmd(mc, "fill " + box(5, FLOOR, -7, 21, FLOOR, 7) + " lime_wool");
				cmd(mc, "fill " + box(7, FLOOR, -5, 19, FLOOR, -3) + " orange_wool");
				cmd(mc, "fill " + box(7, FLOOR, 3, 19, FLOOR, 5) + " purple_wool");
				cmd(mc, "fill " + box(12, FLOOR, -1, 14, FLOOR, 1) + " diamond_block");
				cmd(mc, "fill " + box(5, FLOOR + 1, -8, 21, FLOOR + 3, -8) + " air");
				// Zweite Barriere-Decke direkt über dem Kopf in der rechten Halle (Barriere ist kein Dach).
				cmd(mc, "fill " + box(10, FLOOR + 4, -3, 16, FLOOR + 4, 3) + " barrier");
				tp(mc, 0, -10, 0f);
				phase = 2;
				wait = 120; // Chunks abtasten lassen
				return;
			case 2:
				shot(mc, "outside");
				tp(mc, -13, 0, 0f);
				phase++;
				wait = 60;
				return;
			case 3:
				shot(mc, "inside");
				phase++;
				wait = 100; // 5 s drinnen bleiben: Kosten im Dauerbetrieb
				return;
			case 4:
				log(String.format(Locale.ROOT, "Messung drinnen: Tick Ø %.0f µs, Minimap zeichnen Ø %.0f µs", e.tickMicros(), e.drawMicros()));
				m.minimapHideRoof.set(false);
				phase++;
				wait = 20;
				return;
			case 5:
				shot(mc, "inside-off");
				m.minimapHideRoof.set(true);
				Mc.setScreen(WorldMapScreen.create());
				phase++;
				wait = 40;
				return;
			case 6:
				shot(mc, "worldmap");
				Mc.setScreen(null);
				m.minimapFairPlay.set(true);
				phase++;
				wait = 20;
				return;
			case 7:
				shot(mc, "fairplay");
				m.minimapFairPlay.set(false);
				tp(mc, 13, 0, 0f);
				phase++;
				wait = 60;
				return;
			case 8:
				shot(mc, "barrier-hall");
				// Wieder hinaus: Innenansicht geht mit Verzögerung aus.
				tp(mc, 0, -10, 0f);
				phase++;
				wait = 40;
				return;
			case 9:
				shot(mc, "outside-again");
				AutoTest.disconnect(mc);
				phase++;
				wait = 40;
				return;
			case 10:
				mc.stop();
				phase = 999;
				return;
			default:
		}
	}
}

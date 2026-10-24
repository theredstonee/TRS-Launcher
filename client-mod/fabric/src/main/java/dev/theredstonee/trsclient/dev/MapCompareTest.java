package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.map.MapEngine;
import dev.theredstonee.trsclient.core.map.MapPreview;
import dev.theredstonee.trsclient.core.map.WorldMapUi;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.screen.WorldMapScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bildvergleich der Karten ({@code -PtrsAutotestOnly=mapcompare}): feste Welt (Seed), mehrere Stellen (Berge/Klippen,
 * Meer + flache Küste, Sumpf/Dschungel/Wald, Dorf), jeweils Minimap und Weltkarte in mehreren Zoomstufen, dazu ein
 * schneller Flug über neues Gelände und eine Höhle. Misst Bildrate, Tick-/Zeichenkosten und den längsten Bild-Hänger
 * beim Öffnen der Weltkarte. Bilder: {@code trsclient-<mc>-mapcmp-<lauf>-<stelle>-<ansicht>.png}.
 *
 * <p>Schalter (System-Eigenschaften, über {@code -PtrsSys.<name>=<wert>}):
 * <ul>
 *   <li>{@code trsclient.autotest.cmp.label} – Name des Laufs im Dateinamen (z. B. {@code before}/{@code after});</li>
 *   <li>{@code trsclient.autotest.cmp.locate=true} – nur die Stellen suchen ({@code /locate}, Ergebnis im Log) und beenden;</li>
 *   <li>{@code trsclient.autotest.cmp.spots} – {@code name:x:z,name:x:z,…} statt der eingebauten Stellen;</li>
 *   <li>{@code trsclient.autotest.cmp.refKey} – Teilwörter (mit {@code +}) einer Tastenbelegung, deren Bildschirm als
 *   Vergleichskarte zusätzlich fotografiert wird (z. B. eine andere Weltkarten-Mod im lokalen Testlauf).</li>
 * </ul>
 */
public final class MapCompareTest {
	static final long SEED = 20261024L;
	/** Eingebaute Stellen für {@link #SEED} (mit {@code cmp.locate=true} ermittelt). */
	static final String DEFAULT_SPOTS = "peaks:2240:1312,coast:192:192,swamp:-224:-256,jungle:-480:224,village:-544:544,warmocean:1984:-768";
	/** Zoomstufen der Weltkarte (Bildschirmpixel je Block); 3 entspricht dem Standardzoom der Vergleichskarte. */
	private static final float[] WORLD_ZOOMS = {4f, 3f, 2f, 1f, 0.5f, 0.25f, 0.125f};

	private final String label = System.getProperty("trsclient.autotest.cmp.label", MapPreview.enabled() ? "v3" : "v2");
	private final boolean locate = Boolean.getBoolean("trsclient.autotest.cmp.locate");
	private final String refKey = System.getProperty("trsclient.autotest.cmp.refKey", "");
	private final List<String[]> spots = new ArrayList<>();
	private int phase;
	private int wait;
	private int waited;
	private int spot;
	private int sub;
	private int moved;
	private double flyX, flyY, flyZ;
	// Bildzeiten: längster Abstand zwischen zwei Ticks nach dem Öffnen der Weltkarte (grobe Hänger-Messung).
	private long lastTickNanos;
	private long worstGapNanos;
	private int fpsSum, fpsCount;

	public static void install() {
		MapCompareTest test = new MapCompareTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Kartenvergleich: {}", text);
	}

	private void shot(Minecraft mc, String view) {
		String name = spots.isEmpty() ? "x" : spots.get(spot)[0];
		AutoTest.shot(mc, "trsclient-mapcmp-" + label + "-" + name + "-" + view);
		MapEngine e = MapEngine.get();
		log(String.format(Locale.ROOT, "Bild %s/%s – %d fps, Tick Ø %.0f µs, Minimap Ø %.0f µs, %d Chunks, %d Bereichstexturen",
				name, view, Mc.fps(), e.tickMicros(), e.drawMicros(), e.sampledTotal(), e.textures().regionTextureCount()));
	}

	private void tick(Minecraft mc) {
		// Vanillas AFK-Bremse (ab 1.21.2) soll die Bildrate nicht verfälschen.
		//? if >=1.21.2 {
		/*mc.getFramerateLimitTracker().onInputReceived();
		*///?}
		long now = System.nanoTime();
		if (lastTickNanos != 0) worstGapNanos = Math.max(worstGapNanos, now - lastTickNanos);
		lastTickNanos = now;
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException ex) {
			TrsClient.LOGGER.error("[Autotest] Kartenvergleich: Fehler in Phase {}", phase, ex);
			mc.stop();
			phase = 999;
		}
	}

	private void cmd(Minecraft mc, String command) {
		AutoTest.command(mc, command);
	}

	private boolean waitFor(boolean ready, int maxTries) {
		if (!ready && waited++ < maxTries) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private void tpSpot(Minecraft mc, int y) {
		String[] s = spots.get(spot);
		// Nach Norden schauen: gedrehte und feste Karten zeigen dann dasselbe.
		cmd(mc, "tp @p " + s[1] + " " + y + " " + s[2] + " 180 60");
	}

	/** Klickt die Vergleichs-Tastenbelegung (Teilwörter aus {@code cmp.refKey}); false = keine gefunden. */
	private boolean pressRef(Minecraft mc) {
		if (refKey.isEmpty()) return false;
		String[] parts = refKey.toLowerCase(Locale.ROOT).split("\\+");
		for (KeyMapping k : mc.options.keyMappings) {
			String n = k.getName().toLowerCase(Locale.ROOT);
			boolean all = true;
			for (String p : parts) all &= n.contains(p);
			if (!all) continue;
			log("Vergleichskarte über " + k.getName() + " (" + k.saveString() + ")");
			KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(k.saveString()));
			return true;
		}
		StringBuilder names = new StringBuilder();
		for (KeyMapping k : mc.options.keyMappings) {
			if (k.getName().toLowerCase(Locale.ROOT).contains(parts[0])) names.append(' ').append(k.getName());
		}
		log("keine Tastenbelegung für " + refKey + " – vorhanden:" + names);
		return false;
	}

	private static void scroll(double amount) {
		net.minecraft.client.gui.screens.Screen s = Mc.screen();
		if (s == null) return;
		double x = Mc.window().getGuiScaledWidth() / 2.0, y = Mc.window().getGuiScaledHeight() / 2.0;
		//? if >=1.20.2 {
		s.mouseScrolled(x, y, 0, amount);
		//?} else
		/*s.mouseScrolled(x, y, amount);*/
	}

	private void step(Minecraft mc) {
		TrsModules m = TrsClient.get().modules();
		MapEngine e = MapEngine.get();
		switch (phase) {
			case 0: {
				if (Mc.overlay() != null || Mc.screen() == null) return;
				mc.options.pauseOnLostFocus = false;
				//? if >=1.19 {
				mc.options.renderDistance().set(8);
				// Bildrate offen (Messung): keine Grenze, kein VSync.
				mc.options.framerateLimit().set(260);
				mc.options.enableVsync().set(false);
				//?} else
				/*mc.options.renderDistance = 8;*/
				m.minimap.setEnabled(true);
				m.worldMap.setEnabled(true);
				// Messung: keine Bildraten-Bremse im Hintergrund (Testfenster hat keinen Fokus).
				m.dynamicFps.setEnabled(false);
				m.minimapShape.set(TrsModules.MapShape.SQUARE);
				m.minimapRotate.set(false);
				m.minimapSize.set(128);
				m.minimapZoom.set(TrsModules.MinimapZoom.NORMAL);
				m.minimapAutoZoomSpeed.set(false);
				m.minimapAutoZoomIndoor.set(false);
				m.minimapHostile.set(false);
				m.minimapPassive.set(false);
				m.minimapFairPlay.set(false);
				for (String part : System.getProperty("trsclient.autotest.cmp.spots", DEFAULT_SPOTS).split(",")) {
					String[] s = part.trim().split(":");
					if (s.length == 3) spots.add(s);
				}
				log("Lauf " + label + ", Karte v3 " + (MapPreview.enabled() ? "an" : "aus") + ", " + spots.size() + " Stellen");
				AutoTest.startWorld(mc, "trs-mapcmp-" + AutoTest.MC_VERSION, SEED);
				phase++;
				wait = 20;
				return;
			}
			case 1:
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null, 600)) return;
				// Zuschauer: schwebt ohne zu fallen über dem Gelände (gleiche Ansicht in allen Läufen).
				cmd(mc, "gamemode spectator @a");
				cmd(mc, "time set 6000");
				cmd(mc, "weather clear");
				cmd(mc, "gamerule doDaylightCycle false");
				cmd(mc, "gamerule advance_time false");
				cmd(mc, "gamerule doMobSpawning false");
				cmd(mc, "gamerule spawn_mobs false");
				cmd(mc, "gamerule sendCommandFeedback " + locate);
				cmd(mc, "gamerule send_command_feedback " + locate);
				if (locate) {
					for (String b : new String[] {"jagged_peaks", "stony_peaks", "windswept_hills", "swamp", "mangrove_swamp", "jungle",
							"dark_forest", "birch_forest", "beach", "warm_ocean", "deep_ocean", "badlands", "cherry_grove", "desert"}) {
						cmd(mc, "locate biome minecraft:" + b);
					}
					cmd(mc, "locate structure minecraft:village_plains");
					cmd(mc, "locate structure minecraft:village_savanna");
					phase = 90;
					wait = 400;
					return;
				}
				spot = 0;
				phase = 10;
				return;
			// --- je Stelle ---
			case 10:
				tpSpot(mc, 300);
				phase = 11;
				wait = 300; // Chunks laden + abtasten
				return;
			case 11:
				fpsSum += Mc.fps();
				fpsCount++;
				shot(mc, "minimap");
				m.minimapZoom.set(TrsModules.MinimapZoom.CLOSE);
				phase = 12;
				wait = 10;
				return;
			case 12:
				shot(mc, "minimap-close");
				m.minimapZoom.set(TrsModules.MinimapZoom.NORMAL);
				worstGapNanos = 0;
				Mc.setScreen(WorldMapScreen.create());
				sub = 0;
				phase = 13;
				wait = 2;
				return;
			case 13:
				if (sub == 0) {
					log(String.format(Locale.ROOT, "Weltkarte öffnen: längster Tick-Abstand %.1f ms", worstGapNanos / 1e6));
				}
				if (sub > 0) shot(mc, String.format(Locale.ROOT, "world-%s", zoomName(WORLD_ZOOMS[sub - 1])));
				if (sub < WORLD_ZOOMS.length) {
					worldMap().setScreenPxNow(WORLD_ZOOMS[sub]);
					sub++;
					worstGapNanos = 0;
					wait = 50;
					return;
				}
				log(String.format(Locale.ROOT, "Weltkarte zoomen: längster Tick-Abstand %.1f ms", worstGapNanos / 1e6));
				Mc.setScreen(null);
				phase = 14;
				wait = 10;
				return;
			case 14:
				if (!pressRef(mc)) {
					phase = 16;
					return;
				}
				sub = 0;
				phase = 15;
				wait = 60;
				return;
			case 15:
				// Vergleichskarte: Standardzoom, eine Stufe näher, dann drei Stufen weiter weg.
				if (Mc.screen() == null) {
					log("Vergleichskarte nicht offen");
					phase = 16;
					return;
				}
				shot(mc, "ref-" + sub);
				if (sub < 2) {
					if (sub == 0) {
						scroll(1);
					} else {
						for (int k = 0; k < 4; k++) scroll(-1);
					}
					sub++;
					wait = 40;
					return;
				}
				// Zoom der Vergleichskarte zurück auf den Standard (+1 −4 +3), damit jede Stelle gleich aussieht.
				for (int k = 0; k < 3; k++) scroll(1);
				Mc.setScreen(null);
				phase = 16;
				wait = 10;
				return;
			case 16:
				spot++;
				if (spot < spots.size()) {
					phase = 10;
					return;
				}
				spot = 0;
				phase = 20;
				return;
			// --- schneller Flug über neues Gelände ---
			case 20:
				flyX = Mc.x(mc.player);
				flyY = 300;
				flyZ = Mc.z(mc.player);
				cmd(mc, "tp @p " + (int) flyX + " 300 " + (int) flyZ + " -90 60");
				moved = 0;
				phase = 21;
				wait = 20;
				return;
			case 21:
				// 2 Blöcke je Tick nach Osten (40 Blöcke/s – schneller als Elytra-Gleitflug).
				flyX += 2.0;
				mc.player.setPos(flyX, flyY, flyZ);
				if (++moved < 160) return;
				spots.add(0, new String[] {"flight", "0", "0"});
				spot = 0;
				shot(mc, "minimap");
				Mc.setScreen(WorldMapScreen.create());
				phase = 22;
				wait = 3;
				return;
			case 22:
				worldMap().setScreenPxNow(1f);
				phase = 23;
				wait = 20;
				return;
			case 23:
				shot(mc, "world-1");
				Mc.setScreen(null);
				// Höhle: Luftblase unter der Oberfläche, hineinstellen → Höhlenansicht.
				int bx = (int) flyX, bz = (int) flyZ;
				cmd(mc, "fill " + (bx - 12) + " 20 " + (bz - 12) + " " + (bx + 12) + " 24 " + (bz + 12) + " air");
				cmd(mc, "fill " + (bx - 3) + " 20 " + (bz + 13) + " " + (bx + 3) + " 23 " + (bz + 40) + " air");
				cmd(mc, "fill " + (bx - 5) + " 19 " + (bz - 5) + " " + (bx + 5) + " 19 " + (bz + 5) + " water");
				cmd(mc, "tp @p " + bx + " 21 " + bz + " 180 60");
				spots.set(0, new String[] {"cave", "0", "0"});
				phase = 24;
				wait = 140;
				return;
			case 24:
				log("Höhlenansicht: " + e.caveActive());
				shot(mc, "minimap");
				phase = 25;
				wait = 10;
				return;
			case 25:
				log(String.format(Locale.ROOT, "Messung: Ø %d fps an den Stellen, Tick Ø %.0f µs, Minimap Ø %.0f µs", fpsCount == 0 ? 0 : fpsSum / fpsCount,
						e.tickMicros(), e.drawMicros()));
				AutoTest.disconnect(mc);
				phase = 91;
				wait = 60;
				return;
			case 90:
				AutoTest.disconnect(mc);
				phase = 91;
				wait = 60;
				return;
			case 91:
				mc.stop();
				phase = 999;
				return;
			default:
		}
	}

	private static String zoomName(float px) {
		return px >= 1f ? String.valueOf((int) px) : "1-" + Math.round(1f / px);
	}

	private static WorldMapUi worldMap() {
		return (WorldMapUi) ((TrsUiScreen) Mc.screen()).ui();
	}
}

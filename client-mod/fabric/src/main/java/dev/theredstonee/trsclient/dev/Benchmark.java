package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.PerfOptions;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.FrameStats;
import dev.theredstonee.trsclient.core.perf.GameOptions;
import dev.theredstonee.trsclient.perf.PerfHooks;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Stummer FPS-Benchmark ({@code -PtrsAutotest -PtrsAutotestOnly=bench}): feste Szene hoch über der Testwelt
 * (Wesen offen, hinter Wänden und mit Namensschild, Truhen, Lagerfeuer-Rauch), die Kamera dreht sich
 * gleichmäßig. Nach dem Aufwärmen werden {@link #MEASURE_TICKS} lang alle Bildzeiten aufgenommen und
 * Durchschnitt sowie 1 %-/0,1 %-Low geloggt – einmal mit den Standard-Modulen plus Leistungs-Kategorie,
 * einmal zusätzlich mit „Farben“. VSync und FPS-Grenze sind für die Messung aus.
 */
public final class Benchmark {
	private static final int WARMUP_TICKS = 300;
	private static final int MEASURE_TICKS = 800;
	private static final int COLOR_TICKS = 400;
	private static final int CX = 0, CY = 150, CZ = 0;

	private int phase;
	private int wait;
	private float yaw;
	private boolean rotate;
	private int oldVsync = GameOptions.NONE;
	private int oldLimit = -1;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		// Vanillas eigene AFK-Bremse (ab 1.21.2) soll die Messung nicht verfälschen.
		//? if >=1.21.2 {
		/*mc.getFramerateLimitTracker().onInputReceived();
		*///?}
		if (rotate) turn(mc);
		if (wait > 0) {
			wait--;
			return true;
		}
		FrameStats stats = PerfHooks.FRAME_STATS;
		switch (phase++) {
			case 0: {
				PerfOptions options = new PerfOptions();
				oldVsync = options.get(GameOptions.Opt.VSYNC);
				options.set(GameOptions.Opt.VSYNC, 0);
				oldLimit = framerateLimit(mc);
				setFramerateLimit(mc, 260);
				// Leistungs-Kategorie an (Standardwerte); Dynamische FPS aus, weil das Testfenster selten den Fokus hat.
				modules.fpsBoost.setEnabled(true);
				modules.entityCulling.setEnabled(true);
				modules.particles.setEnabled(true);
				modules.dynamicFps.setEnabled(false);
				modules.colors.setEnabled(false);
				if (PerfHooks.get() != null) PerfHooks.get().refresh();
				TrsClient.get().sprintToggle().set(false);
				TrsClient.get().sneakToggle().set(false);
				for (String rule : new String[]{"sendCommandFeedback", "send_command_feedback", "doMobSpawning", "spawn_mobs",
						"doDaylightCycle", "advance_time", "doWeatherCycle", "advance_weather"}) {
					actions.command("gamerule " + rule + " false");
				}
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("kill @e[type=!player]");
				cmd(actions, "fill %d %d %d %d %d %d air", CX - 24, CY, CZ - 24, CX + 24, CY + 12, CZ + 24);
				cmd(actions, "fill %d %d %d %d %d %d smooth_stone", CX - 24, CY, CZ - 24, CX + 24, CY, CZ + 24);
				// Zwei geschlossene Steinkästen – die Wesen darin sind von der Mitte aus nicht zu sehen.
				cmd(actions, "fill %d %d %d %d %d %d stone hollow", CX + 8, CY + 1, CZ - 7, CX + 20, CY + 7, CZ + 7);
				cmd(actions, "fill %d %d %d %d %d %d stone hollow", CX - 20, CY + 1, CZ - 7, CX - 8, CY + 7, CZ + 7);
				for (int i = 0; i < 60; i++) {
					cmd(actions, "summon pig %d %d %d " + NBT, CX + 10 + (i % 10), CY + 1, CZ - 5 + (i / 10) * 2);
				}
				for (int i = 0; i < 40; i++) {
					cmd(actions, "summon villager %d %d %d " + NBT, CX - 18 + (i % 10), CY + 1, CZ - 5 + (i / 10) * 3);
				}
				// Offen sichtbar: Kühe, Schafe mit Namensschild, Rüstungsständer
				for (int i = 0; i < 40; i++) {
					cmd(actions, "summon cow %d %d %d " + NBT, CX - 20 + (i % 20) * 2, CY + 1, CZ + 12 + (i / 20) * 4);
				}
				for (int i = 0; i < 20; i++) {
					cmd(actions, "summon sheep %d %d %d " + named("Schaf " + i), CX - 10 + i, CY + 1, CZ - 12);
				}
				for (int i = 0; i < 10; i++) {
					cmd(actions, "summon armor_stand %d %d %d {NoGravity:1b,Invulnerable:1b}", CX - 10 + i * 2, CY + 1, CZ + 20);
				}
				// Truhen (Block-Entities) und Lagerfeuer (Rauch-Partikel)
				cmd(actions, "fill %d %d %d %d %d %d chest", CX - 22, CY + 1, CZ - 22, CX + 22, CY + 1, CZ - 20);
				cmd(actions, "fill %d %d %d %d %d %d campfire", CX - 3, CY + 1, CZ + 8, CX + 3, CY + 1, CZ + 8);
				cmd(actions, "tp @p %d %d %d 0 5", CX, CY + 1, CZ);
				yaw = 0;
				rotate = true;
				TrsClient.LOGGER.info("[Benchmark] Szene gebaut, wärme {} Ticks auf", WARMUP_TICKS);
				wait = WARMUP_TICKS;
				return true;
			}
			case 1:
				stats.start(System.nanoTime());
				wait = MEASURE_TICKS;
				return true;
			case 2:
				stats.stop(System.nanoTime());
				report("Standard + Leistung", stats);
				// Zusätzlich „Farben“ (Vollbild-Farbdurchgang)
				modules.colors.setEnabled(true);
				modules.colorSaturation.set(130);
				wait = 60;
				return true;
			case 3:
				stats.start(System.nanoTime());
				wait = COLOR_TICKS;
				return true;
			case 4:
				stats.stop(System.nanoTime());
				report("mit Farben", stats);
				modules.colors.setEnabled(false);
				modules.colorSaturation.set(100);
				// Zeit in den TRS-Hooks: HUD gesammelt gezeichnet
				startProfile(stats);
				wait = PROFILE_TICKS;
				return true;
			case 5:
				stopProfile(stats, "HUD gesammelt");
				// … und wie früher Stück für Stück (1.20–1.21.1: ein Zeichenaufruf je Rechteck/Text)
				PerfHooks.forceUnbatchedHud = true;
				startProfile(stats);
				wait = PROFILE_TICKS;
				return true;
			case 6:
				stopProfile(stats, "HUD einzeln (wie früher)");
				PerfHooks.forceUnbatchedHud = false;
				PerfHooks.profile = false;
				// Zum Vergleich: alle TRS-Module aus (Restkosten der Mod ohne Funktionen)
				saved = modules.registry.capture();
				for (dev.theredstonee.trsclient.core.module.Module m : modules.registry.all()) m.setEnabled(false);
				if (PerfHooks.get() != null) PerfHooks.get().refresh();
				wait = 40;
				return true;
			case 7:
				stats.start(System.nanoTime());
				wait = PROFILE_TICKS;
				return true;
			case 8:
				stats.stop(System.nanoTime());
				report("alle TRS-Module aus", stats);
				if (saved != null) modules.registry.apply(saved);
				if (PerfHooks.get() != null) PerfHooks.get().refresh();
				rotate = false;
				// Rüstungsanzeige quer: HUD und HUD-Editor als Bild (Größe/Ankerung prüfen)
				modules.armor.setEnabled(true);
				modules.armorLayout.set(dev.theredstonee.trsclient.core.hud.ArmorLayout.Orientation.HORIZONTAL);
				wait = 20;
				return true;
			case 9:
				actions.shot("trsclient-armor-horizontal");
				dev.theredstonee.trsclient.compat.Mc.setScreen(new dev.theredstonee.trsclient.screen.HudEditorScreen(null).selectFirst());
				wait = 20;
				return true;
			case 10: {
				actions.shot("trsclient-armor-horizontal-editor");
				dev.theredstonee.trsclient.compat.Mc.setScreen(null);
				modules.armorLayout.set(dev.theredstonee.trsclient.core.hud.ArmorLayout.Orientation.VERTICAL);
				PerfOptions options = new PerfOptions();
				if (oldVsync != GameOptions.NONE) options.set(GameOptions.Opt.VSYNC, oldVsync);
				if (oldLimit > 0) setFramerateLimit(mc, oldLimit);
				options.save();
				actions.command("kill @e[type=!player]");
				wait = 20;
				return true;
			}
			default:
				return false;
		}
	}

	private static final int PROFILE_TICKS = 400;
	private dev.theredstonee.trsclient.core.config.TrsConfig saved;

	private static void startProfile(FrameStats stats) {
		PerfHooks.hudNanos = 0;
		PerfHooks.cullNanos = 0;
		PerfHooks.profile = true;
		stats.start(System.nanoTime());
	}

	private void stopProfile(FrameStats stats, String label) {
		stats.stop(System.nanoTime());
		int frames = Math.max(1, stats.frames());
		report(label, stats);
		TrsClient.LOGGER.info(String.format(Locale.ROOT, "[Benchmark] %s: HUD %.1f µs/Bild, Entity-Culling %.1f µs/Bild", label,
				PerfHooks.hudNanos / 1000.0 / frames, PerfHooks.cullNanos / 1000.0 / frames));
	}

	private static final String NBT = "{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b}";

	private static String named(String name) {
		//? if >=1.21.5 {
		/*return "{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,CustomNameVisible:1b,CustomName:\"" + name + "\"}";
		*///?} else
		return "{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,CustomNameVisible:1b,CustomName:'\"" + name + "\"'}";
	}

	private void report(String label, FrameStats stats) {
		TrsClient.LOGGER.info(String.format(Locale.ROOT,
				"[Benchmark] %s: Ø %.1f FPS | 1%%-Low %.1f FPS | 0,1%%-Low %.1f FPS | %d Bilder in %.1f s | längstes Bild %.1f ms | >50 ms: %d",
				label, stats.averageFps(), stats.lowFps(0.01), stats.lowFps(0.001), stats.frames(), stats.seconds(),
				stats.worstMillis(), stats.slowerThan(50)));
	}

	/** Gleichmäßige Drehung: eine Umdrehung in 12 s. */
	private void turn(Minecraft mc) {
		yaw = (yaw + 1.5f) % 360f;
		//? if >=1.17 {
		mc.player.setYRot(yaw);
		mc.player.setXRot(5);
		//?} else {
		/*mc.player.yRot = yaw;
		mc.player.xRot = 5;
		*///?}
	}

	private static int framerateLimit(Minecraft mc) {
		//? if >=1.19 {
		return mc.options.framerateLimit().get();
		//?} else
		/*return mc.options.framerateLimit;*/
	}

	private static void setFramerateLimit(Minecraft mc, int fps) {
		//? if >=1.19 {
		mc.options.framerateLimit().set(fps);
		//?} else
		/*mc.options.framerateLimit = fps;*/
	}

	private static void cmd(CapeTest.Actions actions, String format, Object... args) {
		actions.command(String.format(Locale.ROOT, format, args));
	}
}

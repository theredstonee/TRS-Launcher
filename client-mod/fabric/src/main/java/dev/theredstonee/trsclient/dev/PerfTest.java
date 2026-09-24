package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.compat.PerfOptions;
import dev.theredstonee.trsclient.core.module.Category;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.BoostPreset;
import dev.theredstonee.trsclient.core.perf.GameOptions;
import dev.theredstonee.trsclient.core.perf.PerfCheck;
import dev.theredstonee.trsclient.core.perf.Performance;
import dev.theredstonee.trsclient.perf.PerfHooks;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Locale;

/**
 * Selbsttest der Leistungs-Funktionen (Teil des Autotests, {@code -PtrsAutotestOnly=perf}): baut eine
 * schwebende Testszene mit vielen Wesen (vor/hinter einer Wand und weit weg), Truhen und Dauer-Partikeln,
 * misst die Bildrate ohne TRS-Leistung und nach „FPS-Boost: Hoch“ (VSync/FPS-Grenze vorher aus),
 * prüft Dynamische FPS (Hintergrund vorgetäuscht), zeigt Leistungs-Kategorie und FPS-Boost-Seite und
 * macht zum Schluss alles rückgängig.
 */
public final class PerfTest {
	private static final int MEASURE_TICKS = 100;
	private int phase;
	private int wait;
	private int x, y, z;
	private boolean spam;
	private double baseline;
	private double boosted;
	/** Erst nur die TRS-Module messen (Vanilla-Optionen unverändert), dann die ganze Stufe. */
	private boolean modulesStage = true;
	private double modulesOnly;
	private boolean checkShot;
	private long framesAt;
	private long framesMark;
	private int oldVsync = GameOptions.NONE;
	private int oldLimit = -1;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		Performance perf = PerfHooks.get();
		if (perf == null) return false;
		// Vanillas eigene AFK-Bremse (ab 1.21.2) soll die Messung nicht verfälschen.
		//? if >=1.21.2 {
		/*mc.getFramerateLimitTracker().onInputReceived();
		*///?}
		if (spam) particles(actions);
		face(mc);
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0: {
				modules.redstoneSignal.setEnabled(false);
				modules.redstoneClock.setEnabled(false);
				modules.minimap.setEnabled(false);
				modules.waypoints.setEnabled(false);
				TrsClient.get().sprintToggle().set(false);
				// Messbedingungen: kein VSync, keine FPS-Grenze, Dynamische FPS aus (das Testfenster hat selten den Fokus).
				PerfOptions options = new PerfOptions();
				oldVsync = options.get(GameOptions.Opt.VSYNC);
				options.set(GameOptions.Opt.VSYNC, 0);
				oldLimit = framerateLimit(mc);
				setFramerateLimit(mc, 260);
				modules.fpsBoost.setEnabled(false);
				modules.dynamicFps.setEnabled(false);
				x = (int) Math.floor(Mc.x(mc.player));
				y = (int) Math.floor(Mc.y(mc.player)) + 40;
				z = (int) Math.floor(Mc.z(mc.player));
				actions.command("gamerule sendCommandFeedback false");
				actions.command("gamerule send_command_feedback false");
				actions.command("gamerule doMobSpawning false");
				actions.command("time set day");
				actions.command("weather rain");
				cmd(actions, "fill %d %d %d %d %d %d air", x - 32, y, z - 8, x + 32, y + 6, z + 56);
				cmd(actions, "fill %d %d %d %d %d %d air", x - 32, y + 7, z - 8, x + 32, y + 12, z + 56);
				cmd(actions, "fill %d %d %d %d %d %d smooth_stone", x - 32, y, z - 8, x + 32, y, z + 56);
				// Wand, dahinter 80 Schweine
				cmd(actions, "fill %d %d %d %d %d %d stone", x - 14, y + 1, z + 10, x + 14, y + 9, z + 10);
				for (int i = 0; i < 80; i++) {
					cmd(actions, "summon pig %d %d %d {NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b}",
							x - 12 + (i % 10) * 2 + 1, y + 1, z + 13 + (i / 10));
				}
				// 60 Kühe weit weg (über 48 Blöcke)
				for (int i = 0; i < 60; i++) {
					cmd(actions, "summon cow %d %d %d {NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b}",
							x - 29 + (i % 20) * 3, y + 1, z + 50 + (i / 20) * 2);
				}
				// 20 Schafe gut sichtbar davor
				for (int i = 0; i < 20; i++) {
					cmd(actions, "summon sheep %d %d %d {NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b}",
							x - 9 + i, y + 1, z + 5 + (i % 3));
				}
				// 300 Truhen in 40–44 Blöcken
				cmd(actions, "fill %d %d %d %d %d %d chest", x - 29, y + 1, z + 40, x + 29, y + 1, z + 44);
				cmd(actions, "tp @p %d %d %d 0 10", x, y + 1, z);
				spam = true;
				wait = 140;
				return true;
			}
			case 1:
				framesMark = PerfHooks.frames;
				framesAt = System.currentTimeMillis();
				wait = MEASURE_TICKS;
				return true;
			case 2: {
				baseline = fps();
				actions.shot("trsclient-perf-scene-off");
				TrsClient.LOGGER.info("[Autotest] Leistung AUS: {} FPS ({} Hooks: {})", fmt(baseline), mc.getSingleplayerServer() != null ? "SP" : "MP",
						PerfHooks.stats());
				// Zuerst nur die TRS-Module auf „Hoch“ (ohne Vanilla-Optionen), Dynamische FPS für die Messung aus.
				perf.setGame(null);
				perf.applyPreset(BoostPreset.HIGH, System.currentTimeMillis());
				perf.setGame(new PerfOptions());
				modules.dynamicFps.setEnabled(false);
				perf.refresh();
				setFramerateLimit(mc, 260);
				wait = 60;
				return true;
			}
			case 3:
				framesMark = PerfHooks.frames;
				framesAt = System.currentTimeMillis();
				wait = MEASURE_TICKS;
				return true;
			case 4: {
				if (modulesStage) {
					modulesStage = false;
					modulesOnly = fps();
					actions.shot("trsclient-perf-scene-modules");
					double pm = baseline > 0 ? (modulesOnly - baseline) / baseline * 100 : 0;
					TrsClient.LOGGER.info("[Autotest] Leistung nur TRS-Module (Hoch, Vanilla-Optionen unverändert): {} FPS ({}{} %)",
							fmt(modulesOnly), pm >= 0 ? "+" : "", fmt(pm));
					// Jetzt die ganze Stufe: Module + Vanilla-Optionen.
					perf.applyPreset(BoostPreset.HIGH, System.currentTimeMillis());
					modules.dynamicFps.setEnabled(false);
					perf.refresh();
					setFramerateLimit(mc, 260);
					phase = 3;
					wait = 60;
					return true;
				}
				boosted = fps();
				actions.shot("trsclient-perf-scene-high");
				double pct = baseline > 0 ? (boosted - baseline) / baseline * 100 : 0;
				TrsClient.LOGGER.info("[Autotest] Leistung HOCH: {} FPS (vorher {} FPS, {}{} %)", fmt(boosted), fmt(baseline),
						pct >= 0 ? "+" : "", fmt(pct));
				TrsClient.LOGGER.info("[Autotest] Leistung Hooks: {}", PerfHooks.stats());
				TrsClient.LOGGER.info("[Autotest] Leistung Vergleich im Menü: vorher {} / nachher {} ({})", fmt(perf.meter().before()),
						fmt(perf.meter().after()), perf.meter().compareState());
				Mc.setScreen(new TrsMenuScreen(null).showCategory(Category.PERFORMANCE));
				wait = 20;
				return true;
			}
			case 5:
				actions.shot("trsclient-perf-category");
				Mc.setScreen(new TrsMenuScreen(null).select(modules.fpsBoost));
				wait = 30;
				return true;
			case 6: {
				actions.shot("trsclient-perf-boost");
				List<PerfCheck.Finding> findings = perf.findings(System.currentTimeMillis());
				StringBuilder sb = new StringBuilder();
				for (PerfCheck.Finding f : findings) sb.append(f.id).append(f.fixable() ? "*" : "").append(' ');
				TrsClient.LOGGER.info("[Autotest] Leistungs-Check: {} | Renderer: {} | Mods: {} | fehlen: {}", sb.toString().trim(),
						perf.game() == null ? "-" : perf.game().renderer(), perf.compat().detected(), perf.compat().missingRecommended());
				Mc.setScreen(new TrsMenuScreen(null).select(modules.fpsBoost).scrollSettings(150));
				wait = 20;
				return true;
			}
			case 7:
				if (!checkShot) {
					checkShot = true;
					actions.shot("trsclient-perf-check");
					Mc.setScreen(new TrsMenuScreen(null).select(modules.entityCulling));
					phase = 7;
					wait = 20;
					return true;
				}
				actions.shot("trsclient-perf-culling");
				Mc.setScreen(null);
				// Dynamische FPS: Hintergrund vortäuschen → etwa die eingestellten FPS im Hintergrund.
				modules.dynamicFps.setEnabled(true);
				modules.dynamicFpsUnfocused.set(15);
				PerfHooks.forceUnfocused(true);
				wait = 20;
				return true;
			case 8:
				framesMark = PerfHooks.frames;
				framesAt = System.currentTimeMillis();
				wait = 60;
				return true;
			case 9: {
				double background = fps();
				PerfHooks.forceUnfocused(false);
				TrsClient.LOGGER.info("[Autotest] Dynamische FPS im Hintergrund: {} FPS (Soll 15, Zustand {})", fmt(background),
						perf.dynamicFps().state());
				framesMark = PerfHooks.frames;
				framesAt = System.currentTimeMillis();
				wait = 20;
				return true;
			}
			case 10: {
				TrsClient.LOGGER.info("[Autotest] Dynamische FPS nach dem Vortäuschen: {} FPS (Zustand {} – echtes Fenster {})", fmt(fps()), perf.dynamicFps().state(), mc.isWindowActive() ? "im Vordergrund" : "im Hintergrund");
				spam = false;
				PerfOptions options = new PerfOptions();
				int n = perf.undo(System.currentTimeMillis());
				if (oldVsync != GameOptions.NONE) options.set(GameOptions.Opt.VSYNC, oldVsync);
				if (oldLimit > 0) setFramerateLimit(mc, oldLimit);
				options.save();
				TrsClient.LOGGER.info("[Autotest] Leistung rückgängig: {} Einstellungen, Sichtweite wieder {}, Grafik {}", n,
						options.get(GameOptions.Opt.VIEW_DISTANCE), options.get(GameOptions.Opt.GRAPHICS));
				actions.command("weather clear");
				actions.command("kill @e[type=!player]");
				cmd(actions, "fill %d %d %d %d %d %d air", x - 32, y, z - 8, x + 32, y + 12, z + 20);
				cmd(actions, "fill %d %d %d %d %d %d air", x - 32, y, z + 21, x + 32, y + 12, z + 56);
				cmd(actions, "tp @p %d %d %d", x, y - 40, z);
				wait = 20;
				return true;
			}
			case 11:
				// Leistungs-Check mit den ursprünglichen Einstellungen (nach „Rückgängig“)
				Mc.setScreen(new TrsMenuScreen(null).select(modules.fpsBoost).scrollSettings(150));
				wait = 30;
				return true;
			case 12: {
				actions.shot("trsclient-perf-check-original");
				StringBuilder sb = new StringBuilder();
				for (PerfCheck.Finding f : perf.findings(System.currentTimeMillis())) sb.append(f.id).append(f.fixable() ? "*" : "").append(' ');
				TrsClient.LOGGER.info("[Autotest] Leistungs-Check (ursprüngliche Einstellungen): {}", sb.toString().trim());
				Mc.setScreen(null);
				wait = 5;
				return true;
			}
			default:
				return false;
		}
	}

	/** Bilder je Sekunde seit der letzten Marke (eigene Zählung in Minecraft#runTick). */
	private double fps() {
		long ms = Math.max(1, System.currentTimeMillis() - framesAt);
		return (PerfHooks.frames - framesMark) * 1000.0 / ms;
	}

	private void particles(CapeTest.Actions actions) {
		cmd(actions, "particle minecraft:campfire_cosy_smoke %d %d %d 3 1 1 0.01 15 force", x, y + 3, z + 7);
		cmd(actions, "particle minecraft:explosion %d %d %d 4 1 1 0 4 force", x, y + 3, z + 8);
		cmd(actions, "particle minecraft:flame %d %d %d 5 2 1 0.01 40 force", x, y + 2, z + 6);
	}

	private void face(Minecraft mc) {
		if (phase == 0 || phase > 10) return;
		//? if >=1.17 {
		mc.player.setYRot(0);
		mc.player.setXRot(10);
		//?} else {
		/*mc.player.yRot = 0;
		mc.player.xRot = 10;
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

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}
}

package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.Category;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.BoostPreset;
import dev.theredstonee.trsclient.core.perf.GameOptions;
import dev.theredstonee.trsclient.core.perf.PerfCheck;
import dev.theredstonee.trsclient.core.perf.Performance;
import dev.theredstonee.trsclient.perf.LegacyPerf;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumParticleTypes;

import java.util.List;
import java.util.Locale;

/**
 * Selbsttest der Leistungs-Funktionen auf Forge 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=perf}): Testszene mit
 * vielen Lebewesen vor/hinter einer Wand und weit weg plus Dauer-Partikeln, Bildrate ohne TRS-Leistung und
 * nach „FPS-Boost: Hoch“, Dynamische FPS im (vorgetäuschten) Hintergrund, Menü-Screenshots, Rückgängig.
 */
public final class PerfTest {
	private static final int MEASURE_TICKS = 100;
	private int phase;
	private int wait;
	private int x, y, z;
	private boolean spam;
	private double baseline;
	/** Erst nur die TRS-Module messen (Vanilla-Optionen unverändert), dann die ganze Stufe. */
	private boolean modulesStage = true;
	private long framesAt;
	private long framesMark;
	private int oldVsync = GameOptions.NONE;
	private int oldLimit = -1;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		EntityPlayer player = Mc.player();
		if (player == null) return false;
		LegacyPerf lp = LegacyPerf.get();
		Performance perf = lp.performance();
		if (perf == null) return false;
		if (spam) particles();
		if (phase > 0 && phase <= 10) {
			player.rotationYaw = 0;
			player.prevRotationYaw = 0;
			player.rotationPitch = 10;
			player.prevRotationPitch = 10;
			player.rotationYawHead = 0;
		}
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
				oldVsync = lp.get(GameOptions.Opt.VSYNC);
				lp.set(GameOptions.Opt.VSYNC, 0);
				oldLimit = mc.gameSettings.limitFramerate;
				mc.gameSettings.limitFramerate = 260;
				modules.fpsBoost.setEnabled(false);
				modules.dynamicFps.setEnabled(false);
				x = (int) Math.floor(player.posX);
				y = (int) Math.floor(player.posY) + 30;
				z = (int) Math.floor(player.posZ);
				actions.command("gamerule sendCommandFeedback false");
				actions.command("gamerule doMobSpawning false");
				actions.command("time set 1000");
				actions.command("weather rain");
				cmd(actions, "fill %d %d %d %d %d %d minecraft:air", x - 30, y, z - 6, x + 30, y + 8, z + 30);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:air", x - 30, y, z + 31, x + 30, y + 8, z + 58);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:stone", x - 30, y, z - 6, x + 30, y, z + 58);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:stone", x - 14, y + 1, z + 10, x + 14, y + 8, z + 10);
				for (int i = 0; i < 80; i++) summon(actions, PIG, x - 12 + (i % 10) * 2 + 1, y + 1, z + 13 + (i / 10));
				for (int i = 0; i < 60; i++) summon(actions, COW, x - 29 + (i % 20) * 3, y + 1, z + 52 + (i / 20) * 2);
				for (int i = 0; i < 20; i++) summon(actions, SHEEP, x - 9 + i, y + 1, z + 5 + (i % 3));
				cmd(actions, "tp @p %d %d %d 0 10", x, y + 1, z);
				spam = true;
				wait = 140;
				return true;
			}
			case 1:
				mark(lp);
				wait = MEASURE_TICKS;
				return true;
			case 2:
				baseline = fps(lp);
				actions.shot("perf-scene-off");
				TrsClient.LOGGER.info("[Autotest] Leistung AUS: {} FPS ({})", fmt(baseline), lp.stats());
				perf.setGame(null);
				perf.applyPreset(BoostPreset.HIGH, System.currentTimeMillis());
				perf.setGame(lp);
				modules.dynamicFps.setEnabled(false);
				perf.refresh();
				wait = 60;
				return true;
			case 3:
				mark(lp);
				wait = MEASURE_TICKS;
				return true;
			case 4: {
				if (modulesStage) {
					modulesStage = false;
					double modulesOnly = fps(lp);
					actions.shot("perf-scene-modules");
					double pm = baseline > 0 ? (modulesOnly - baseline) / baseline * 100 : 0;
					TrsClient.LOGGER.info("[Autotest] Leistung nur TRS-Module (Hoch, Vanilla-Optionen unverändert): {} FPS ({}{} %)",
							fmt(modulesOnly), pm >= 0 ? "+" : "", fmt(pm));
					perf.applyPreset(BoostPreset.HIGH, System.currentTimeMillis());
					modules.dynamicFps.setEnabled(false);
					perf.refresh();
					mc.gameSettings.limitFramerate = 260;
					phase = 3;
					wait = 60;
					return true;
				}
				double boosted = fps(lp);
				actions.shot("perf-scene-high");
				double pct = baseline > 0 ? (boosted - baseline) / baseline * 100 : 0;
				TrsClient.LOGGER.info("[Autotest] Leistung HOCH: {} FPS (vorher {} FPS, {}{} %)", fmt(boosted), fmt(baseline),
						pct >= 0 ? "+" : "", fmt(pct));
				TrsClient.LOGGER.info("[Autotest] Leistung Hooks: {}", lp.stats());
				TrsClient.LOGGER.info("[Autotest] Leistung Vergleich im Menü: vorher {} / nachher {} ({})", fmt(perf.meter().before()),
						fmt(perf.meter().after()), perf.meter().compareState());
				mc.displayGuiScreen(new TrsMenuScreen(null).showCategory(Category.PERFORMANCE));
				wait = 20;
				return true;
			}
			case 5:
				actions.shot("perf-category");
				mc.displayGuiScreen(new TrsMenuScreen(null).select(modules.fpsBoost));
				wait = 30;
				return true;
			case 6: {
				actions.shot("perf-boost");
				List<PerfCheck.Finding> findings = perf.findings(System.currentTimeMillis());
				StringBuilder sb = new StringBuilder();
				for (PerfCheck.Finding f : findings) sb.append(f.id).append(f.fixable() ? "*" : "").append(' ');
				TrsClient.LOGGER.info("[Autotest] Leistungs-Check: {} | Renderer: {} | Mods: {} | fehlen: {}", sb.toString().trim(),
						lp.renderer(), perf.compat().detected(), perf.compat().missingRecommended());
				mc.displayGuiScreen(new TrsMenuScreen(null).select(modules.worldDetails));
				wait = 20;
				return true;
			}
			case 7:
				actions.shot("perf-details");
				mc.displayGuiScreen(null);
				modules.dynamicFps.setEnabled(true);
				modules.dynamicFpsUnfocused.set(15);
				lp.forceUnfocused(true);
				wait = 20;
				return true;
			case 8:
				mark(lp);
				wait = 60;
				return true;
			case 9:
				TrsClient.LOGGER.info("[Autotest] Dynamische FPS im Hintergrund: {} FPS (Soll 15, Zustand {})", fmt(fps(lp)),
						perf.dynamicFps().state());
				lp.forceUnfocused(false);
				mark(lp);
				wait = 20;
				return true;
			case 10: {
				TrsClient.LOGGER.info("[Autotest] Dynamische FPS nach dem Vortäuschen: {} FPS (Zustand {} – echtes Fenster {})", fmt(fps(lp)), perf.dynamicFps().state(), org.lwjgl.opengl.Display.isActive() ? "im Vordergrund" : "im Hintergrund");
				spam = false;
				int n = perf.undo(System.currentTimeMillis());
				if (oldVsync != GameOptions.NONE) lp.set(GameOptions.Opt.VSYNC, oldVsync);
				if (oldLimit > 0) mc.gameSettings.limitFramerate = oldLimit;
				lp.save();
				TrsClient.LOGGER.info("[Autotest] Leistung rückgängig: {} Einstellungen, Sichtweite wieder {}, Grafik {}", n,
						lp.get(GameOptions.Opt.VIEW_DISTANCE), lp.get(GameOptions.Opt.GRAPHICS));
				actions.command("weather clear");
				actions.command("kill @e[type=!Player]");
				actions.command("kill @e[type=!player]");
				cmd(actions, "tp @p %d %d %d", x, y - 30, z);
				wait = 20;
				return true;
			}
			case 11:
				mc.displayGuiScreen(new TrsMenuScreen(null).select(modules.fpsBoost).scrollSettings(150));
				wait = 30;
				return true;
			case 12: {
				actions.shot("perf-check-original");
				StringBuilder sb = new StringBuilder();
				for (PerfCheck.Finding f : perf.findings(System.currentTimeMillis())) sb.append(f.id).append(f.fixable() ? "*" : "").append(' ');
				TrsClient.LOGGER.info("[Autotest] Leistungs-Check (ursprüngliche Einstellungen): {}", sb.toString().trim());
				mc.displayGuiScreen(null);
				wait = 5;
				return true;
			}
			default:
				return false;
		}
	}

	//? if >=1.11 {
	/*private static final String PIG = "pig";
	private static final String COW = "cow";
	private static final String SHEEP = "sheep";
	*///?} else {
	private static final String PIG = "Pig";
	private static final String COW = "Cow";
	private static final String SHEEP = "Sheep";
	//?}

	private static void summon(CapeTest.Actions actions, String type, int x, int y, int z) {
		cmd(actions, "summon %s %d %d %d {NoAI:1b,Silent:1b,Invulnerable:1b}", type, x, y, z);
	}

	/** Partikel direkt im Client (sonst schreibt jeder Befehl eine Log-Zeile). */
	private void particles() {
		if (Mc.world() == null) return;
		for (int i = 0; i < 12; i++) {
			double px = x - 4 + (i % 8), pz = z + 6 + (i % 3);
			Mc.world().spawnParticle(EnumParticleTypes.SMOKE_LARGE, px, y + 2, pz, 0, 0.03, 0);
			Mc.world().spawnParticle(EnumParticleTypes.FLAME, px, y + 1.5, pz, 0, 0.01, 0);
		}
		Mc.world().spawnParticle(EnumParticleTypes.EXPLOSION_LARGE, x, y + 3, z + 8, 0, 0, 0);
	}

	private void mark(LegacyPerf lp) {
		framesMark = lp.frames;
		framesAt = System.currentTimeMillis();
	}

	private double fps(LegacyPerf lp) {
		long ms = Math.max(1, System.currentTimeMillis() - framesAt);
		return (lp.frames - framesMark) * 1000.0 / ms;
	}

	private static void cmd(CapeTest.Actions actions, String format, Object... args) {
		actions.command(String.format(Locale.ROOT, format, args));
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}
}

package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.compat.PerfOptions;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.GameOptions;
import dev.theredstonee.trsclient.core.perf.JfrControl;
import dev.theredstonee.trsclient.core.perf.RealBench;
import dev.theredstonee.trsclient.perf.PerfHooks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;

/**
 * Startet den realistischen Benchmark ({@link RealBench}) ohne den übrigen Autotest: Testwelt mit festem Seed
 * ({@code -Dtrsclient.bench.seed}), Mitte {@code -Dtrsclient.bench.center=x,z}. Mit {@code -Dtrsclient.bench.vanilla=true}
 * läuft der TRS Client gar nicht erst an (nur dieser Messhaken und die Bildzeit-Messung in Minecraft#runTick) –
 * so misst dieselbe Szene reines Vanilla.
 */
public final class RealBenchRunner {
	private final boolean vanilla = Boolean.getBoolean("trsclient.bench.vanilla");
	private final long seed = Long.getLong("trsclient.bench.seed", 20260925L);
	private final RealBench bench;
	/** {@code -Dtrsclient.bench.center=locate}: nur das nächste Dorf suchen (Ergebnis im Chat-Protokoll) und beenden. */
	private final boolean locate = "locate".equals(System.getProperty("trsclient.bench.center"));
	private int step;
	private int wait;
	private TrsConfig before;

	private RealBenchRunner() {
		String[] c = System.getProperty("trsclient.bench.center", "0,0").replace("locate", "0,0").split(",");
		int cx = Integer.parseInt(c[0].trim());
		int cz = Integer.parseInt(c.length > 1 ? c[1].trim() : "0");
		bench = new RealBench(cx, cz, System.getProperty("trsclient.bench.label", vanilla ? "vanilla" : "trs"), PerfHooks.FRAME_STATS,
				Integer.getInteger("trsclient.bench.seconds", 20) * 20);
	}

	/** true = Benchmark aktiv (dann ohne übrigen Autotest). */
	public static boolean installIfRequested() {
		if (!Boolean.getBoolean("trsclient.autotest") || !"realbench".equals(System.getProperty("trsclient.autotest.only"))) return false;
		RealBenchRunner runner = new RealBenchRunner();
		ClientTickEvents.END_CLIENT_TICK.register(runner::tick);
		TrsClient.LOGGER.info("[RealBench] aktiv ({})", runner.vanilla ? "Vanilla – TRS Client aus" : "TRS Client");
		return true;
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		switch (step) {
			case 0:
				if (Mc.overlay() != null || Mc.screen() == null) return; // Ressourcen laden noch
				mc.options.pauseOnLostFocus = false;
				//? if >=1.19.4
				mc.options.onboardAccessibility = false;
				mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
				if (!vanilla) {
					// Frische Standard-Einstellungen des TRS Clients – wie bei einem neuen Spieler.
					TrsModules modules = TrsClient.get().modules();
					before = modules.registry.capture();
					modules.registry.apply(new TrsModules().registry.capture());
					// Das Testfenster hat selten den Fokus: Dynamische FPS würde sonst im Hintergrund bremsen.
					modules.dynamicFps.setEnabled(false);
					// -Dtrsclient.bench.off=all|hud|<id,id…>: Module für die Kosten-Messung abschalten.
					String off = System.getProperty("trsclient.bench.off", "");
					if (!off.isEmpty()) {
						java.util.List<String> ids = java.util.Arrays.asList(off.split(","));
						for (dev.theredstonee.trsclient.core.module.Module m : modules.registry.all()) {
							boolean hud = m instanceof dev.theredstonee.trsclient.core.module.HudModule;
							if (ids.contains("all") || (ids.contains("hud") && hud) || ids.contains(m.id())) m.setEnabled(false);
						}
						StringBuilder on = new StringBuilder();
						for (dev.theredstonee.trsclient.core.module.Module m : modules.registry.all()) {
							if (m.isEnabled()) on.append(on.length() == 0 ? "" : ",").append(m.id());
						}
						TrsClient.LOGGER.info("[RealBench] Module an: {}", on.length() == 0 ? "keine" : on);
					}
					if (PerfHooks.get() != null) PerfHooks.get().refresh();
				}
				Mc.setScreen(null);
				AutoTest.startWorld(mc, "trs-realbench-" + AutoTest.MC_VERSION, seed);
				step++;
				wait = 20;
				break;
			case 1:
				if (locate) {
					if (mc.level == null || mc.player == null || Mc.screen() != null) return;
					// Als Spieler senden: die Antwort landet im Chat und damit als [CHAT]-Zeile im Protokoll.
					//? if >=1.19 {
					Mc.sendChat("/locate structure #minecraft:village");
					//?} else
					/*Mc.sendChat("/locate village");*/
					// Antwort als Chat-Zeile im Protokoll: "[CHAT] The nearest … is at [x, ~, z]"
					step = 10;
					wait = 100;
					return;
				}
				if (!bench.tick(game(mc), System.nanoTime())) step++;
				break;
			case 10:
				TrsClient.LOGGER.info("[RealBench] Dorf-Suche fertig (Koordinaten siehe [CHAT]-Zeilen)");
				AutoTest.disconnect(mc);
				mc.stop();
				step++;
				break;
			default:
				break;
		}
	}

	private RealBench.Game game(final Minecraft mc) {
		return new RealBench.Game() {
			@Override
			public boolean ready() {
				return mc.level != null && mc.player != null && Mc.screen() == null;
			}

			@Override
			public void command(String command) {
				AutoTest.command(mc, command);
			}

			@Override
			public int groundY(int x, int z) {
				if (mc.level == null || !mc.level.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
				return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			}

			@Override
			public void place(double x, double y, double z, float yaw, float pitch) {
				if (mc.player == null) return;
				// Wie ein Spieler, der sich umsieht: Vanillas AFK-Bremse (ab 1.21.2: 30 FPS nach 1 min) greift nicht.
				//? if >=1.21.2 {
				/*mc.getFramerateLimitTracker().onInputReceived();
				*///?}
				//? if >=1.17 {
				mc.player.getAbilities().flying = true;
				mc.player.setYRot(yaw);
				mc.player.setXRot(pitch);
				//?} else {
				/*mc.player.abilities.flying = true;
				mc.player.yRot = yaw;
				mc.player.xRot = pitch;
				*///?}
				mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
				mc.player.setPos(x, y, z);
			}

			@Override
			public double[] position() {
				if (mc.player == null) return new double[]{Double.NaN, Double.NaN, Double.NaN};
				//? if >=1.15 {
				return new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()};
				//?} else
				/*return new double[]{mc.player.x, mc.player.y, mc.player.z};*/
			}

			@Override
			public void shot(String name) {
				AutoTest.shot(mc, name);
			}

			@Override
			public void log(String line) {
				TrsClient.LOGGER.info(line);
			}

			@Override
			public int width() {
				return Mc.window().getWidth();
			}

			@Override
			public int height() {
				return Mc.window().getHeight();
			}

			@Override
			public String setup() {
				PerfOptions o = new PerfOptions();
				int max = o.get(GameOptions.Opt.MAX_FPS);
				return String.format(Locale.ROOT, "MC %s, %s, Sichtweite %d, Grafik %d, VSync %s, Max. Bildrate %s, Seed %d",
						AutoTest.MC_VERSION, vanilla ? "Vanilla (TRS aus)" : "TRS Client", o.get(GameOptions.Opt.VIEW_DISTANCE),
						o.get(GameOptions.Opt.GRAPHICS), o.get(GameOptions.Opt.VSYNC) == 1 ? "an" : "aus",
						max >= GameOptions.UNLIMITED_FPS ? "unbegrenzt" : String.valueOf(max), seed);
			}

			@Override
			public void profile(boolean on) {
				if (on) {
					String file = JfrControl.start();
					if (file != null) TrsClient.LOGGER.info("[RealBench] JFR: {}", file);
				} else {
					JfrControl.stop();
				}
			}

			@Override
			public void maximize() {
				try {
					//? if >=26.3 {
					/*org.lwjgl.sdl.SDLVideo.SDL_MaximizeWindow(Mc.window().handle());
					*///?} elif >=1.21.9 {
					/*org.lwjgl.glfw.GLFW.glfwMaximizeWindow(Mc.window().handle());
					*///?} else
					org.lwjgl.glfw.GLFW.glfwMaximizeWindow(Mc.window().getWindow());
				} catch (RuntimeException | LinkageError e) {
					TrsClient.LOGGER.warn("[RealBench] Fenster nicht maximiert: {}", e.toString());
				}
			}

			@Override
			public void finish() {
				if (before != null) TrsClient.get().modules().registry.apply(before);
				if (!vanilla) TrsClient.get().saveConfig();
				AutoTest.disconnect(mc);
				mc.stop();
			}
		};
	}
}

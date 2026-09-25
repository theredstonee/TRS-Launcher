package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.config.TrsConfig;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.FrameStats;
import dev.theredstonee.trsclient.core.perf.JfrControl;
import dev.theredstonee.trsclient.core.perf.RealBench;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
//? if >=1.9 {
/*import net.minecraft.util.math.BlockPos;
*///?} else
import net.minecraft.util.BlockPos;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Realistischer Benchmark für Legacy-Forge ({@code -PtrsAutotestOnly=realbench}, siehe {@link RealBench}): Welt mit
 * festem Seed, Dorf-Mitte aus {@code -Dtrsclient.bench.center=x,z} oder – bei „locate“ bzw. ohne Angabe – über den
 * Dorf-Generator des Servers gesucht (1.8.9 hat kein /locate). Mit {@code -Dtrsclient.bench.vanilla=true} ist der
 * TRS Client aus (nur Forge + dieser Messablauf).
 */
public final class RealBenchRunner {
	/** Bildzeiten – eigene Messung vor jedem Bild (RenderTickEvent START), auch ohne TRS Client. */
	public static final FrameStats FRAME_STATS = new FrameStats();

	private final boolean vanilla = Boolean.getBoolean("trsclient.bench.vanilla");
	private final long seed = Long.getLong("trsclient.bench.seed", 20260925L);
	private final String center = System.getProperty("trsclient.bench.center", "locate");
	private final String label = System.getProperty("trsclient.bench.label", vanilla ? "vanilla" : "trs");
	private final String mcVersion = Mc.version();
	private RealBench bench;
	private volatile BlockPos found;
	private volatile boolean searched;
	private int step;
	private int wait;
	private TrsConfig before;

	/** true = Benchmark aktiv (dann ohne übrigen Autotest). */
	public static boolean installIfRequested() {
		if (!Boolean.getBoolean("trsclient.autotest") || !"realbench".equals(System.getProperty("trsclient.autotest.only"))) return false;
		RealBenchRunner runner = new RealBenchRunner();
		MinecraftForge.EVENT_BUS.register(runner);
		TrsClient.LOGGER.info("[RealBench] aktiv ({})", runner.vanilla ? "Vanilla – TRS Client aus" : "TRS Client");
		return true;
	}

	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		if (event.phase == TickEvent.Phase.START) FRAME_STATS.frame(System.nanoTime());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase == TickEvent.Phase.END) tick(Minecraft.getMinecraft());
	}

	private void tick(final Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		switch (step) {
			case 0:
				if (!(mc.currentScreen instanceof GuiMainMenu) && !(mc.currentScreen != null
						&& mc.currentScreen.getClass().getSimpleName().contains("Title"))) return; // lädt noch
				mc.gameSettings.pauseOnLostFocus = false;
				if (!vanilla) {
					TrsModules modules = TrsClient.get().modules();
					before = modules.registry.capture();
					modules.registry.apply(new TrsModules().registry.capture());
					modules.dynamicFps.setEnabled(false);
				}
				String world = "trs-realbench-" + mcVersion;
				if (mc.getSaveLoader().canLoadWorld(world)) {
					mc.launchIntegratedServer(world, world, null);
				} else {
					mc.launchIntegratedServer(world, world, Mc.creativeWorld(seed));
				}
				step++;
				wait = 20;
				break;
			case 1: {
				if (Mc.world() == null || Mc.player() == null || mc.currentScreen != null) return;
				String[] c = center.split(",");
				if (c.length == 2) {
					start(Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()));
					break;
				}
				final IntegratedServer server = mc.getIntegratedServer();
				if (!searched && server != null) {
					searched = true;
					server.addScheduledTask(new Runnable() {
						@Override
						public void run() {
							found = locateVillage((WorldServer) server.getEntityWorld());
						}
					});
					wait = 40;
					return;
				}
				BlockPos p = found;
				TrsClient.LOGGER.info("[RealBench] Dorf: {}", p == null ? "nicht gefunden – nehme 0 0" : p.getX() + " " + p.getZ());
				start(p == null ? 0 : p.getX(), p == null ? 0 : p.getZ());
				break;
			}
			case 2:
				if (!bench.tick(game(mc), System.nanoTime())) step++;
				break;
			default:
				break;
		}
	}

	private void start(int x, int z) {
		bench = new RealBench(x, z, label, FRAME_STATS);
		step = 2;
	}

	/** Nächstes Dorf über den Dorf-Generator des Welt-Generators (Felder/Methoden nach Typ gesucht). */
	private static BlockPos locateVillage(WorldServer world) {
		try {
			Object provider = world.getChunkProvider();
			for (Object generator : fieldsOf(provider)) {
				for (Object gen : fieldsOf(generator)) {
					if (gen == null || !gen.getClass().getSimpleName().equals("MapGenVillage")) continue;
					for (Class<?> k = gen.getClass(); k != null; k = k.getSuperclass()) {
						for (Method m : k.getDeclaredMethods()) {
							Class<?>[] p = m.getParameterTypes();
							if (m.getReturnType() == BlockPos.class && p.length >= 2 && p[0] == World.class && p[1] == BlockPos.class) {
								m.setAccessible(true);
								Object[] args = p.length == 2 ? new Object[]{world, world.getSpawnPoint()}
										: new Object[]{world, world.getSpawnPoint(), Boolean.FALSE};
								return (BlockPos) m.invoke(gen, args);
							}
						}
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			TrsClient.LOGGER.warn("[RealBench] Dorf-Suche: {}", e.toString());
		}
		return null;
	}

	private static java.util.List<Object> fieldsOf(Object o) throws IllegalAccessException {
		java.util.List<Object> out = new java.util.ArrayList<Object>();
		if (o == null) return out;
		for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
			for (Field f : k.getDeclaredFields()) {
				if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				f.setAccessible(true);
				out.add(f.get(o));
			}
		}
		return out;
	}

	private RealBench.Game game(final Minecraft mc) {
		return new RealBench.Game() {
			@Override
			public boolean ready() {
				return Mc.world() != null && Mc.player() != null && mc.currentScreen == null;
			}

			@Override
			public void command(final String command) {
				final IntegratedServer server = mc.getIntegratedServer();
				if (server == null) return;
				server.addScheduledTask(new Runnable() {
					@Override
					public void run() {
						server.getCommandManager().executeCommand(server, command);
					}
				});
			}

			@Override
			public int groundY(int x, int z) {
				WorldClient w = Mc.world();
				BlockPos pos = new BlockPos(x, 64, z);
				if (w == null || !w.isBlockLoaded(pos)) return Integer.MIN_VALUE;
				int y = w.getHeight(pos).getY();
				return y <= 0 ? Integer.MIN_VALUE : y;
			}

			@Override
			public void place(double x, double y, double z, float yaw, float pitch) {
				EntityPlayer p = Mc.player();
				if (p == null) return;
				p.capabilities.isFlying = true;
				p.motionX = 0;
				p.motionY = 0;
				p.motionZ = 0;
				p.setPositionAndRotation(x, y, z, yaw, pitch);
				p.prevRotationYaw = yaw;
				p.prevRotationPitch = pitch;
			}

			@Override
			public double[] position() {
				EntityPlayer p = Mc.player();
				return p == null ? new double[]{Double.NaN, Double.NaN, Double.NaN} : new double[]{p.posX, p.posY, p.posZ};
			}

			@Override
			public void shot(String name) {
				ScreenShotHelper.saveScreenshot(Mc.gameDir(), "trsclient-" + mcVersion + "-" + name + ".png", mc.displayWidth,
						mc.displayHeight, mc.getFramebuffer());
			}

			@Override
			public void log(String line) {
				TrsClient.LOGGER.info(line);
			}

			@Override
			public int width() {
				return mc.displayWidth;
			}

			@Override
			public int height() {
				return mc.displayHeight;
			}

			@Override
			public String setup() {
				GameSettings s = mc.gameSettings;
				return String.format(Locale.ROOT, "MC %s, %s, Sichtweite %d, Grafik %d, VSync %s, Max. Bildrate %s, Seed %d", mcVersion,
						vanilla ? "Vanilla (TRS aus)" : "TRS Client", s.renderDistanceChunks, s.fancyGraphics ? 1 : 0,
						s.enableVsync ? "an" : "aus", s.limitFramerate >= 260 ? "unbegrenzt" : String.valueOf(s.limitFramerate), seed);
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
				// LWJGL 2 kann nicht maximieren – die Fenstergröße kommt aus --width/--height.
			}

			@Override
			public void finish() {
				if (before != null) {
					TrsClient.get().modules().registry.apply(before);
					TrsClient.get().saveConfig();
				}
				WorldClient w = Mc.world();
				if (w != null) {
					w.sendQuittingDisconnectingPacket();
					mc.loadWorld((WorldClient) null);
				}
				mc.shutdown();
			}
		};
	}
}

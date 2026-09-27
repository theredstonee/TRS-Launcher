package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.circuit.Circuit;
import dev.theredstonee.trsclient.core.circuit.CircuitCheck;
import dev.theredstonee.trsclient.core.circuit.CircuitLibrary;
import dev.theredstonee.trsclient.core.circuit.CircuitLibraryPage;
import dev.theredstonee.trsclient.core.circuit.Circuits;
import dev.theredstonee.trsclient.core.circuit.Placement;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
//? if >=1.9 {
/*import net.minecraft.util.math.BlockPos;
*///?} else
import net.minecraft.util.BlockPos;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Selbsttest „Schaltungs-Bibliothek“ unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=circuits}): Bibliothek,
 * Detailseite, Vorlage teilweise nachgebaut (mit den alten Blocknamen – prüft die Übersetzung), Platzieren nach
 * Blick. Bilder: trsclient-&lt;mc&gt;-circuits-*.png. Eigene Welt trs-circuits-&lt;mc&gt;.
 */
public final class CircuitTest {
	private final String mcVersion = Mc.version();
	private final String world = "trs-circuits-" + Mc.version();
	private int phase = -1;
	private int wait;
	private int waited;
	private int bx;
	private int by;
	private int bz;
	private dev.theredstonee.trsclient.core.config.TrsConfig before;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new CircuitTest());
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
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Schaltungen: Fehler", e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void tick(Minecraft mc) {
		GuiScreen screen = mc.currentScreen;
		TrsModules modules = TrsClient.get().modules();
		String me = mc.getSession().getUsername();
		Circuits circuits = Circuits.get();
		CircuitLibrary lib = CircuitLibrary.get();
		switch (phase) {
			case -1:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				mc.getSaveLoader().deleteWorldDirectory(world);
				mc.launchIntegratedServer(world, world, Mc.creativeWorld(20260928L));
				phase++;
				return;
			case 0:
				if (Mc.world() == null || Mc.player() == null || mc.currentScreen != null) {
					if (waited++ > 900) throw new IllegalStateException("Welt lädt nicht");
					return;
				}
				before = modules.registry.capture();
				command(mc, "gamerule sendCommandFeedback false");
				command(mc, "gamerule logAdminCommands false");
				command(mc, "time set 1000");
				command(mc, "weather clear");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.dynamicFps.setEnabled(false);
				modules.circuits.circuitLibrary.setEnabled(true);
				bx = (int) Math.floor(Mc.player().posX) + 3;
				// in der Luft bauen (die Testwelt kann im Meer beginnen – Wasser würde nachlaufen)
				by = (int) Math.floor(Mc.player().posY) + 12;
				bz = (int) Math.floor(Mc.player().posZ) + 3;
				command(mc, String.format("fill %d %d %d %d %d %d minecraft:stone", bx - 6, by - 1, bz - 6, bx + 12, by - 1, bz + 12));
				command(mc, String.format("fill %d %d %d %d %d %d minecraft:air", bx - 6, by, bz - 6, bx + 12, by + 8, bz + 12));
				command(mc, String.format("setblock %d %d %d minecraft:barrier", bx - 3, by + 1, bz - 3));
				command(mc, String.format(Locale.ROOT, "tp %s %.1f %d %.1f 0 30", me, bx - 2.5, by + 2, bz - 2.5));
				phase++;
				wait = 30;
				return;
			case 1:
				CircuitLibraryPage.requestOpen();
				mc.displayGuiScreen(new TrsMenuScreen(null));
				phase++;
				wait = 25;
				return;
			case 2:
				shot(mc, "circuits-library");
				CircuitLibraryPage.requestOpen(lib.byId("piston_door_2x2"));
				mc.displayGuiScreen(new TrsMenuScreen(null));
				phase++;
				wait = 30;
				return;
			case 3: {
				shot(mc, "circuits-detail");
				mc.displayGuiScreen(null);
				Circuit xor = lib.byId("xor_gate");
				circuits.placeAt(xor, bx, by, bz, 0, false);
				build(mc, xor, new Placement(bx, by, bz, 0, false));
				command(mc, String.format("setblock %d %d %d minecraft:barrier", bx + 2, by + 4, bz - 4));
				command(mc, String.format(Locale.ROOT, "tp %s %.1f %d %.1f 0 50", me, bx + 2.5, by + 5, bz - 3.5));
				phase++;
				wait = 60;
				return;
			}
			case 4: {
				CircuitCheck check = circuits.check();
				TrsClient.LOGGER.info("[Autotest] Schaltung: {}/{} richtig, {} falsch, {} fehlen", check == null ? -1 : check.correct(),
						check == null ? -1 : check.total(), check == null ? -1 : check.wrong(), check == null ? -1 : check.missing());
				shot(mc, "circuits-ghost");
				circuits.startPlacing(lib.byId("not_gate"), false);
				phase++;
				wait = 20;
				return;
			}
			case 5:
				shot(mc, "circuits-placing");
				circuits.remove();
				if (before != null) {
					modules.registry.apply(before);
					TrsClient.get().saveConfig();
				}
				TrsClient.LOGGER.info("[Autotest] Schaltungen: fertig");
				phase = 999;
				mc.displayGuiScreen(new GuiMainMenu());
				wait = 10;
				return;
			case 999:
				mc.shutdown();
				return;
			default:
		}
	}

	/** Etwa zwei Drittel richtig setzen (alte Blocknamen), einen Verstärker falsch herum, den Rest weglassen. */
	private void build(Minecraft mc, Circuit c, Placement p) {
		final IntegratedServer server = mc.getIntegratedServer();
		if (server == null) return;
		int[] pos = new int[3];
		int n = 0;
		boolean wrongDone = false;
		final Map<int[], IBlockState> todo = new java.util.LinkedHashMap<int[], IBlockState>();
		for (Circuit.Cell cell : c.cells) {
			if (cell.spec.optional) continue;
			n++;
			p.toWorld(c, cell.x, cell.y, cell.z, pos);
			Map<String, String> props = new HashMap<String, String>(p.props(cell.spec));
			String key = cell.spec.def.key;
			if (!wrongDone && "repeater".equals(key)) {
				props.put("facing", "north".equals(props.get("facing")) ? "south" : "north");
				wrongDone = true;
			} else if (n % 3 == 0) {
				continue;
			}
			String id;
			if (cell.spec.def.anySolid()) id = "stone";
			else if ("repeater".equals(key)) id = "unpowered_repeater";
			else if ("comparator".equals(key)) id = "unpowered_comparator";
			else if ("redstone_wall_torch".equals(key)) id = "redstone_torch";
			else if ("redstone_torch".equals(key)) {
				id = "redstone_torch";
				props.put("facing", "up");
			} else if ("lever".equals(key)) {
				String face = props.remove("face");
				if (!"wall".equals(face)) props.put("facing", "up_x");
				id = "lever";
			} else id = key;
			IBlockState state = state(id, props);
			if (state != null) todo.put(new int[] {pos[0], pos[1], pos[2]}, state);
		}
		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				World w = server.worldServers[0];
				for (Map.Entry<int[], IBlockState> e : todo.entrySet()) {
					w.setBlockState(new BlockPos(e.getKey()[0], e.getKey()[1], e.getKey()[2]), e.getValue(), 2);
				}
			}
		});
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static IBlockState state(String id, Map<String, String> props) {
		Block b = Block.getBlockFromName("minecraft:" + id);
		if (b == null) return null;
		IBlockState s = b.getDefaultState();
		for (Object k : s.getProperties().keySet()) {
			IProperty p = (IProperty) k;
			String want = props.get(p.getName());
			if (want == null) continue;
			for (Object v : p.getAllowedValues()) {
				if (p.getName((Comparable) v).equalsIgnoreCase(want)) {
					s = s.withProperty(p, (Comparable) v);
					break;
				}
			}
		}
		return s;
	}

	private static void command(Minecraft mc, final String command) {
		final IntegratedServer server = mc.getIntegratedServer();
		if (server == null) return;
		server.addScheduledTask(new Runnable() {
			@Override
			public void run() {
				server.getCommandManager().executeCommand(server, command);
			}
		});
	}

	private void shot(Minecraft mc, String name) {
		//? if <1.12 {
		mc.guiAchievement.clearAchievements();
		//?}
		String file = "trsclient-" + mcVersion + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Screenshot {}", file);
	}
}

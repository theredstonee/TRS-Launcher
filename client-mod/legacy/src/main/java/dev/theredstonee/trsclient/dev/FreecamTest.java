package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.cinematic.LegacyCinematic;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cinematic.CameraCollision;
import dev.theredstonee.trsclient.core.cinematic.Cinematic;
import dev.theredstonee.trsclient.core.cinematic.Freecam;
import dev.theredstonee.trsclient.core.module.CinematicModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
//? if >=1.9 {
/*import net.minecraft.util.math.BlockPos;
*///?} else
import net.minecraft.util.BlockPos;

import java.util.Locale;

/**
 * Selbsttest freie Kamera in Legacy-Forge ({@code -PtrsAutotestOnly=freecam}): auf einer Plattform in der Luft stehen
 * drei Wände quer zur Flugrichtung (+Z): Stein, Glasscheiben und Stein mit offener Tür. Mit Fair Play hält die Kamera
 * vor dem Stein an, durch Scheiben und Tür fliegt sie hindurch; ohne Fair Play auch durch Stein. Die Figur bleibt
 * stehen. Screenshots {@code freecam-player/-stone/-glass/-door/-noclip/-end}.
 */
public final class FreecamTest {
	private static final double SPEED = 10;
	private static final int FLIGHT_TICKS = 30;

	private int phase;
	private int wait;
	private boolean ok = true;
	private int bx;
	private int by;
	private int bz;
	private double px;
	private double py;
	private double pz;
	private final Freecam.Input forward = new Freecam.Input();
	private final Freecam.Input none = new Freecam.Input();

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		EntityPlayerSP player = Mc.player();
		if (player == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		Cinematic c = Cinematic.get();
		CinematicModules s = modules.cinematic;
		String name = player.getName();
		switch (phase++) {
			case 0:
				s.cinematic.setEnabled(true);
				s.freecamFairPlay.set(true);
				s.freecamSpeed.set(SPEED);
				modules.minimap.setEnabled(false);
				mc.gameSettings.hideGUI = false;
				forward.forward = true;
				actions.command("time set 1000");
				actions.command("weather clear");
				actions.command("gamemode 1 " + name);
				bx = (int) Math.floor(player.posX);
				bz = (int) Math.floor(player.posZ);
				by = 120;
				actions.command(fill(bx - 16, by - 1, bz - 6, bx + 16, by - 1, bz + 14, "stone"));
				actions.command(fill(bx - 16, by, bz - 6, bx + 16, by + 8, bz + 14, "air"));
				actions.command(fill(bx - 3, by, bz + 5, bx + 3, by + 5, bz + 5, "stone"));
				actions.command(fill(bx + 7, by, bz + 5, bx + 13, by + 5, bz + 5, "glass_pane"));
				actions.command(fill(bx - 13, by, bz + 5, bx - 7, by + 5, bz + 5, "stone"));
				// Offene Holztür: unten (Bit 4 = offen), oben (Bit 8 = obere Hälfte).
				actions.command(String.format(Locale.ROOT, "setblock %d %d %d wooden_door 4", bx - 10, by, bz + 5));
				actions.command(String.format(Locale.ROOT, "setblock %d %d %d wooden_door 8", bx - 10, by + 1, bz + 5));
				actions.command(String.format(Locale.ROOT, "tp %s %.1f %d %.1f 0 0", name, bx + 0.5, by, bz + 0.5));
				wait = 40;
				return true;
			case 1:
				px = player.posX;
				py = player.posY;
				pz = player.posZ;
				check("Tür steht", Mc.world().getBlockState(new BlockPos(bx - 10, by, bz + 5)).getBlock() instanceof net.minecraft.block.BlockDoor);
				c.toggleFreecam(LegacyCinematic.host());
				check("freie Kamera an", c.freecam());
				check("Figur hat leere Eingabe", !(player.movementInput instanceof net.minecraft.util.MovementInputFromOptions));
				c.testInput(none);
				c.freecamState().teleport(bx + 0.5, by + 3.5, bz - 4.5, 0, 25);
				wait = 10;
				return true;
			case 2: {
				Entity view = mc.getRenderViewEntity();
				check("Hilfs-Kamera ist die Ansicht", view != player);
				check("Kamera folgt der freien Kamera", view != null && Math.abs(view.posZ - (bz - 4.5)) < 0.05);
				actions.shot("freecam-player");
				fly(c, bx + 0.5, by + 1.6, bz + 1.5);
				return true;
			}
			case 3: {
				double wall = bz + 5 - CameraCollision.MARGIN;
				double z = c.freecamState().z();
				check("Fair Play: vor Stein angehalten (z " + fmt(z) + ", Wand " + (bz + 5) + ")", Math.abs(z - wall) < 0.05);
				c.testInput(none);
				wait = 4;
				return true;
			}
			case 4:
				actions.shot("freecam-stone");
				fly(c, bx + 10.5, by + 1.6, bz + 1.5);
				return true;
			case 5:
				check("durch Glasscheiben (z " + fmt(c.freecamState().z()) + ")", c.freecamState().z() > bz + 7);
				look(c, 180);
				return true;
			case 6:
				actions.shot("freecam-glass");
				fly(c, bx - 9.5, by + 1.0, bz + 1.5);
				return true;
			case 7:
				check("durch die offene Tür (z " + fmt(c.freecamState().z()) + ")", c.freecamState().z() > bz + 7);
				look(c, 180);
				return true;
			case 8:
				actions.shot("freecam-door");
				s.freecamFairPlay.set(false);
				fly(c, bx + 0.5, by + 1.6, bz + 1.5);
				return true;
			case 9:
				check("Fair Play aus: durch Stein (z " + fmt(c.freecamState().z()) + ")", c.freecamState().z() > bz + 7);
				look(c, 180);
				return true;
			case 10: {
				actions.shot("freecam-noclip");
				s.freecamFairPlay.set(true);
				double still = dist(player.posX - px, player.posY - py, player.posZ - pz);
				check("Figur ist stehen geblieben (" + fmt(still) + ")", still < 0.01);
				c.testInput(null);
				c.toggleFreecam(LegacyCinematic.host());
				wait = 5;
				return true;
			}
			case 11:
				check("freie Kamera aus", !c.freecam());
				check("Ansicht wieder die Figur", mc.getRenderViewEntity() == player);
				check("Eingabe der Figur zurück", player.movementInput instanceof net.minecraft.util.MovementInputFromOptions);
				actions.shot("freecam-end");
				TrsClient.LOGGER.info("[Autotest] Freecam: {}", ok ? "ALLES OK" : "FEHLER (siehe oben)");
				return false;
			default:
				return false;
		}
	}

	private void fly(Cinematic c, double x, double y, double z) {
		c.freecamState().teleport(x, y, z, 0, 0);
		c.testInput(forward);
		wait = FLIGHT_TICKS;
	}

	private void look(Cinematic c, float yaw) {
		c.testInput(none);
		Freecam f = c.freecamState();
		f.teleport(f.x(), f.y(), f.z(), yaw, 10);
		wait = 4;
	}

	private void check(String what, boolean pass) {
		if (!pass) ok = false;
		TrsClient.LOGGER.info("[Autotest] Freecam {}: {}", pass ? "OK" : "FEHLER", what);
	}

	private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x1, y1, z1, x2, y2, z2, block);
	}

	private static double dist(double x, double y, double z) {
		return Math.sqrt(x * x + y * y + z * z);
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}
}

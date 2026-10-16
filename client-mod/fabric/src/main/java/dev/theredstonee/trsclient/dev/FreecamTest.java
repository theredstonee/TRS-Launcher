package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.cinematic.CinematicHooks;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cinematic.CameraCollision;
import dev.theredstonee.trsclient.core.cinematic.Cinematic;
import dev.theredstonee.trsclient.core.cinematic.Freecam;
import dev.theredstonee.trsclient.core.module.CinematicModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Selbsttest freie Kamera ({@code -PtrsAutotestOnly=freecam}): auf einer Plattform in der Luft stehen drei Wände quer
 * zur Flugrichtung (+Z): Stein, Glasscheiben und Stein mit offener Tür. Mit Fair Play hält die Kamera vor dem Stein
 * an, durch Scheiben und Tür fliegt sie hindurch; ohne Fair Play auch durch Stein. Die Figur bleibt stehen.
 * Screenshots {@code freecam-player/-stone/-glass/-door/-noclip/-end}.
 */
public final class FreecamTest {
	/** Fluggeschwindigkeit im Test (Blöcke/s) und Ticks je Flug (1,5 s ≈ 15 Blöcke, Wand bei +3,5). */
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
		if (mc.player == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		Cinematic c = Cinematic.get();
		CinematicModules s = modules.cinematic;
		switch (phase++) {
			case 0:
				s.cinematic.setEnabled(true);
				s.freecamFairPlay.set(true);
				s.freecamSpeed.set(SPEED);
				modules.minimap.setEnabled(false);
				Mc.setHudHidden(false);
				forward.forward = true;
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("gamemode creative @p");
				bx = (int) Math.floor(Mc.x(mc.player));
				bz = (int) Math.floor(Mc.z(mc.player));
				by = 120;
				actions.command(fill(bx - 16, by - 1, bz - 6, bx + 16, by - 1, bz + 14, "minecraft:smooth_stone"));
				actions.command(fill(bx - 16, by, bz - 6, bx + 16, by + 8, bz + 14, "minecraft:air"));
				// Stein (Mitte), Glasscheiben (rechts, +X), Stein mit offener Tür (links, −X) – je bei z + 5.
				actions.command(fill(bx - 3, by, bz + 5, bx + 3, by + 5, bz + 5, "minecraft:stone"));
				actions.command(fill(bx + 7, by, bz + 5, bx + 13, by + 5, bz + 5, "minecraft:glass_pane"));
				actions.command(fill(bx - 13, by, bz + 5, bx - 7, by + 5, bz + 5, "minecraft:stone"));
				actions.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:oak_door[half=lower,open=true]", bx - 10, by, bz + 5));
				actions.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:oak_door[half=upper,open=true]", bx - 10, by + 1, bz + 5));
				actions.command(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 0 0", bx + 0.5, by, bz + 0.5));
				wait = 30;
				return true;
			case 1:
				px = Mc.x(mc.player);
				py = Mc.y(mc.player);
				pz = Mc.z(mc.player);
				c.toggleFreecam(CinematicHooks.host());
				check("freie Kamera an", c.freecam());
				check("Figur hat leere Eingabe", !(mc.player.input instanceof net.minecraft.client.player.KeyboardInput));
				c.testInput(none);
				// Etwas zurück und hoch: die eigene Figur ist zu sehen, dahinter die Wände.
				c.freecamState().teleport(bx + 0.5, by + 3.5, bz - 4.5, 0, 25);
				wait = 10;
				return true;
			case 2:
				check("Ansicht gelöst", !firstPerson(mc));
				check("Kamera folgt der freien Kamera", Math.abs(Mc.cameraZ() - (bz - 4.5)) < 0.05);
				actions.shot("trsclient-freecam-player");
				fly(c, bx + 0.5, by + 1.6, bz + 1.5);
				return true;
			case 3: {
				double wall = bz + 5 - CameraCollision.MARGIN;
				check("Fair Play: vor Stein angehalten (z " + fmt(Mc.cameraZ()) + ", Wand " + (bz + 5) + ")",
						Math.abs(Mc.cameraZ() - wall) < 0.05);
				c.testInput(none);
				wait = 4;
				return true;
			}
			case 4:
				// Bild: nur Stein, kein Blick dahinter.
				actions.shot("trsclient-freecam-stone");
				fly(c, bx + 10.5, by + 1.6, bz + 1.5);
				return true;
			case 5:
				check("durch Glasscheiben (z " + fmt(Mc.cameraZ()) + ")", Mc.cameraZ() > bz + 7);
				look(c, 180);
				return true;
			case 6:
				actions.shot("trsclient-freecam-glass");
				fly(c, bx - 9.5, by + 1.0, bz + 1.5);
				return true;
			case 7:
				check("durch die offene Tür (z " + fmt(Mc.cameraZ()) + ")", Mc.cameraZ() > bz + 7);
				look(c, 180);
				return true;
			case 8:
				actions.shot("trsclient-freecam-door");
				s.freecamFairPlay.set(false);
				fly(c, bx + 0.5, by + 1.6, bz + 1.5);
				return true;
			case 9:
				check("Fair Play aus: durch Stein (z " + fmt(Mc.cameraZ()) + ")", Mc.cameraZ() > bz + 7);
				look(c, 180);
				return true;
			case 10: {
				actions.shot("trsclient-freecam-noclip");
				s.freecamFairPlay.set(true);
				double still = Math.sqrt(sq(Mc.x(mc.player) - px) + sq(Mc.y(mc.player) - py) + sq(Mc.z(mc.player) - pz));
				check("Figur ist stehen geblieben (" + fmt(still) + ")", still < 0.01);
				c.testInput(null);
				c.toggleFreecam(CinematicHooks.host());
				wait = 5;
				return true;
			}
			case 11: {
				check("freie Kamera aus", !c.freecam());
				check("Ansicht wieder erste Person", firstPerson(mc));
				check("Eingabe der Figur zurück", mc.player.input instanceof net.minecraft.client.player.KeyboardInput);
				double back = Math.sqrt(sq(Mc.cameraX() - px) + sq(Mc.cameraZ() - pz));
				check("Kamera wieder bei der Figur", back < 0.5);
				actions.shot("trsclient-freecam-end");
				TrsClient.LOGGER.info("[Autotest] Freecam: {}", ok ? "ALLES OK" : "FEHLER (siehe oben)");
				return false;
			}
			default:
				return false;
		}
	}

	/** Freie Kamera an den Start setzen (Blick +Z) und vorwärts fliegen lassen. */
	private void fly(Cinematic c, double x, double y, double z) {
		c.freecamState().teleport(x, y, z, 0, 0);
		c.testInput(forward);
		wait = FLIGHT_TICKS;
	}

	/** Anhalten und zurückschauen. */
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

	private static boolean firstPerson(Minecraft mc) {
		//? if >=1.16 {
		return mc.options.getCameraType().isFirstPerson();
		//?} else
		/*return mc.options.thirdPersonView == 0;*/
	}

	private static double sq(double v) {
		return v * v;
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}
}

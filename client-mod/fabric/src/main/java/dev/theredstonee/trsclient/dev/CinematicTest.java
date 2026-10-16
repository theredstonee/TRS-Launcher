package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.cinematic.CinematicHooks;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cinematic.Cinematic;
import dev.theredstonee.trsclient.core.module.CinematicModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest Kamera-Fahrten ({@code -PtrsAutotestOnly=cinematic}): drei Keyframes an verschiedenen Kamera-Lagen setzen
 * (Spieler per Teleport bewegt), Fahrt abspielen und prüfen: Kamera bewegt sich weg von der Figur, Figur bleibt stehen,
 * HUD aus, Sichtfeld der Fahrt; am Ende alles zurück. Screenshots {@code cinematic-start/-mid/-end}.
 */
public final class CinematicTest {
	private int phase;
	private int wait;
	private boolean ok = true;
	private double px;
	private double py;
	private double pz;
	private double cx;
	private double cy;
	private double cz;
	private long playStart;
	private double sx;
	private double sy;
	private double sz;

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
				s.hideHud.set(true);
				s.record.set(false);
				s.preview.set(true);
				s.seconds.set(1.5);
				s.timing.set(CinematicModules.Timing.PER_SEGMENT);
				s.smoothing.set(CinematicModules.Smoothing.SMOOTH);
				modules.minimap.setEnabled(false);
				Mc.setHudHidden(false);
				c.clear(CinematicHooks.host());
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("execute as @p at @s run tp @s ~ ~ ~ 0 0");
				wait = 15;
				return true;
			case 1:
				sx = Mc.x(mc.player);
				sy = Mc.y(mc.player);
				sz = Mc.z(mc.player);
				c.addKeyframe(CinematicHooks.host());
				actions.command("execute as @p at @s run tp @s ~6 ~3 ~2 90 20");
				wait = 15;
				return true;
			case 2:
				c.addKeyframe(CinematicHooks.host());
				actions.command("execute as @p at @s run tp @s ~-2 ~2 ~8 180 -15");
				wait = 15;
				return true;
			case 3:
				c.addKeyframe(CinematicHooks.host());
				check("drei Keyframes", c.keyframes() == 3);
				actions.command(String.format(java.util.Locale.ROOT, "execute as @p run tp @s %.2f %.2f %.2f 0 0", sx, sy, sz));
				wait = 50; // Vorschau-Partikel sichtbar werden lassen
				return true;
			case 4:
				actions.shot("trsclient-cinematic-preview");
				px = Mc.x(mc.player);
				py = Mc.y(mc.player);
				pz = Mc.z(mc.player);
				c.togglePlay(CinematicHooks.host());
				playStart = System.nanoTime();
				check("Fahrt läuft", c.playing());
				check("Dauer 3 s", Math.abs(c.duration() - 3.0) < 1e-6);
				wait = 6;
				return true;
			case 5:
				cx = Mc.cameraX();
				cy = Mc.cameraY();
				cz = Mc.cameraZ();
				check("HUD aus", Mc.hudHidden());
				check("Ansicht gelöst (dritte Person)", !firstPerson(mc));
				actions.shot("trsclient-cinematic-start");
				wait = 22;
				return true;
			case 6: {
				double moved = Math.sqrt(sq(Mc.cameraX() - cx) + sq(Mc.cameraY() - cy) + sq(Mc.cameraZ() - cz));
				double fromPlayer = Math.sqrt(sq(Mc.cameraX() - px) + sq(Mc.cameraY() - py) + sq(Mc.cameraZ() - pz));
				check("Kamera bewegt sich (" + fmt(moved) + " Blöcke)", moved > 1.0);
				check("Kamera weg von der Figur (" + fmt(fromPlayer) + " Blöcke)", fromPlayer > 2.0);
				check("Fahrt läuft noch", c.playing());
				actions.shot("trsclient-cinematic-mid");
				TrsClient.LOGGER.info("[Autotest] Kamera-Fahrt: nach {} ms Kamera bei {} {} {} (Blick {}°/{}°)",
						(System.nanoTime() - playStart) / 1_000_000, fmt(Mc.cameraX()), fmt(Mc.cameraY()), fmt(Mc.cameraZ()),
						fmt(Mc.cameraYaw()), fmt(Mc.cameraPitch()));
				wait = 60;
				return true;
			}
			case 7: {
				check("Fahrt zu Ende", !c.playing());
				check("HUD wieder an", !Mc.hudHidden());
				check("Ansicht wieder erste Person", firstPerson(mc));
				double still = Math.sqrt(sq(Mc.x(mc.player) - px) + sq(Mc.y(mc.player) - py) + sq(Mc.z(mc.player) - pz));
				check("Figur ist stehen geblieben (" + fmt(still) + ")", still < 0.6);
				double back = Math.sqrt(sq(Mc.cameraX() - px) + sq(Mc.cameraY() - py) + sq(Mc.cameraZ() - pz));
				check("Kamera wieder bei der Figur", back < 2.0);
				actions.shot("trsclient-cinematic-end");
				c.clear(CinematicHooks.host());
				TrsClient.LOGGER.info("[Autotest] Kamera-Fahrt: {}", ok ? "ALLES OK" : "FEHLER (siehe oben)");
				return false;
			}
			default:
				return false;
		}
	}

	private void check(String what, boolean pass) {
		if (!pass) ok = false;
		TrsClient.LOGGER.info("[Autotest] Kamera-Fahrt {}: {}", pass ? "OK" : "FEHLER", what);
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
		return String.format(java.util.Locale.ROOT, "%.2f", v);
	}
}

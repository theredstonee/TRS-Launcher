package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.cinematic.LegacyCinematic;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cinematic.Cinematic;
import dev.theredstonee.trsclient.core.module.CinematicModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;

/**
 * Selbsttest Kamera-Fahrten in Legacy-Forge ({@code -PtrsAutotestOnly=cinematic}): drei Keyframes (Spieler per Teleport
 * bewegt), Fahrt abspielen, prüfen: Hilfs-Kamera ist die Ansicht und bewegt sich, Figur bleibt stehen, HUD aus; am Ende
 * alles zurück. Screenshots {@code cinematic-preview/-start/-mid/-end}.
 */
public final class CinematicTest {
	private int phase;
	private int wait;
	private boolean ok = true;
	private double px;
	private double py;
	private double pz;
	private double sx;
	private double sy;
	private double sz;
	private double cx;
	private double cy;
	private double cz;

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
				s.hideHud.set(true);
				s.record.set(false);
				s.preview.set(true);
				s.seconds.set(1.5);
				s.timing.set(CinematicModules.Timing.PER_SEGMENT);
				s.smoothing.set(CinematicModules.Smoothing.SMOOTH);
				modules.minimap.setEnabled(false);
				mc.gameSettings.hideGUI = false;
				c.clear(LegacyCinematic.host());
				actions.command("time set 1000");
				actions.command("weather clear");
				actions.command("tp " + name + " ~ ~ ~ 0 0");
				wait = 20;
				return true;
			case 1:
				sx = player.posX;
				sy = player.posY;
				sz = player.posZ;
				c.addKeyframe(LegacyCinematic.host());
				actions.command("tp " + name + " ~6 ~3 ~2 90 20");
				wait = 20;
				return true;
			case 2:
				c.addKeyframe(LegacyCinematic.host());
				actions.command("tp " + name + " ~-2 ~2 ~8 180 -15");
				wait = 20;
				return true;
			case 3:
				c.addKeyframe(LegacyCinematic.host());
				check("drei Keyframes", c.keyframes() == 3);
				actions.command(String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f 0 0", name, sx, sy, sz));
				wait = 50;
				return true;
			case 4:
				actions.shot("cinematic-preview");
				px = player.posX;
				py = player.posY;
				pz = player.posZ;
				c.togglePlay(LegacyCinematic.host());
				check("Fahrt läuft", c.playing());
				check("Dauer 3 s", Math.abs(c.duration() - 3.0) < 1e-6);
				wait = 6;
				return true;
			case 5: {
				Entity view = mc.getRenderViewEntity();
				cx = view.posX;
				cy = view.posY;
				cz = view.posZ;
				check("HUD aus", Mc.hudHidden());
				check("Hilfs-Kamera ist die Ansicht", view != player);
				actions.shot("cinematic-start");
				wait = 22;
				return true;
			}
			case 6: {
				Entity view = mc.getRenderViewEntity();
				double moved = dist(view.posX - cx, view.posY - cy, view.posZ - cz);
				double fromPlayer = dist(view.posX - px, view.posY + 1.62 - (py + player.getEyeHeight()), view.posZ - pz);
				check("Kamera bewegt sich (" + fmt(moved) + " Blöcke)", moved > 1.0);
				check("Kamera weg von der Figur (" + fmt(fromPlayer) + " Blöcke)", fromPlayer > 2.0);
				check("Fahrt läuft noch", c.playing());
				actions.shot("cinematic-mid");
				wait = 60;
				return true;
			}
			case 7: {
				check("Fahrt zu Ende", !c.playing());
				check("HUD wieder an", !Mc.hudHidden());
				check("Ansicht wieder die Figur", mc.getRenderViewEntity() == player);
				check("Figur ist stehen geblieben (" + fmt(dist(player.posX - px, player.posY - py, player.posZ - pz)) + ")",
						dist(player.posX - px, player.posY - py, player.posZ - pz) < 0.6);
				actions.shot("cinematic-end");
				c.clear(LegacyCinematic.host());
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

	private static double dist(double x, double y, double z) {
		return Math.sqrt(x * x + y * y + z * z);
	}

	private static String fmt(double v) {
		return String.format(java.util.Locale.ROOT, "%.2f", v);
	}
}

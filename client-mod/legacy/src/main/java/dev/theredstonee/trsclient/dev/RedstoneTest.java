package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.redstone.RedstoneReadout;
import dev.theredstonee.trsclient.core.redstone.RedstoneTools;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Locale;

/**
 * Selbsttest der Redstone-Werkzeuge (Forge 1.8.9–1.12.2): baut vor dem Spieler eine kleine Schaltung –
 * Staubleitung an einem Redstone-Block, Komparator (Subtrahieren) mit Seiteneingang und eine Fackel-Uhr
 * (Fackel + Verstärker mit 4 Ticks = 1 Hz) –, schaut nacheinander darauf und macht Screenshots, dazu das
 * Welt-Overlay und die Redstone-Seite im TRS-Menü. Blöcke über Namen + Metadaten (in 1.8–1.12 gleich).
 */
public final class RedstoneTest {
	private int phase;
	private int wait;
	private int bx, by, bz;
	private float yaw, pitch;
	private boolean aiming;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		EntityPlayer player = Mc.player();
		if (player == null) return false;
		if (aiming) face(player, yaw, pitch);
		if (wait > 0) {
			wait--;
			return true;
		}
		RedstoneTools tools = TrsClient.get().redstone();
		switch (phase++) {
			case 0:
				modules.redstoneSignal.setEnabled(true);
				modules.redstoneClock.setEnabled(true);
				modules.redstoneOverlay.setEnabled(false);
				modules.redstoneOverlayRadius.set(12);
				modules.redstoneSignal.resetLayout();
				modules.redstoneClock.resetLayout();
				modules.minimap.setEnabled(false);
				modules.waypoints.setEnabled(false);
				TrsClient.get().sprintToggle().set(false);
				bx = (int) Math.floor(player.posX);
				by = (int) Math.floor(player.posY);
				bz = (int) Math.floor(player.posZ);
				actions.command("gamerule sendCommandFeedback false");
				actions.command("time set 1000");
				cmd(actions, "fill %d %d %d %d %d %d minecraft:air", bx - 8, by, bz - 2, bx + 10, by + 6, bz + 15);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:stone", bx - 8, by - 1, bz - 2, bx + 10, by - 1, bz + 15);
				// Staubleitung nach Osten an einem Redstone-Block: 15, 14, 13 …
				cmd(actions, "setblock %d %d %d minecraft:redstone_block", bx - 5, by, bz + 5);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:redstone_wire", bx - 4, by, bz + 5, bx + 6, by, bz + 5);
				// Komparator (Blick nach Norden = Metadaten 2, Subtrahieren +4): hinten Redstone-Block, Seite Staub 11 → 4
				cmd(actions, "setblock %d %d %d minecraft:redstone_block", bx + 3, by, bz + 7);
				cmd(actions, "setblock %d %d %d minecraft:unpowered_comparator 6", bx + 3, by, bz + 8);
				cmd(actions, "fill %d %d %d %d %d %d minecraft:redstone_wire", bx + 4, by, bz + 8, bx + 8, by, bz + 8);
				cmd(actions, "setblock %d %d %d minecraft:redstone_torch 5", bx + 9, by, bz + 8);
				cmd(actions, "setblock %d %d %d minecraft:redstone_wire", bx + 3, by, bz + 9);
				// Fackel-Uhr: Stein B, Fackel an seiner Ostseite, Staub rundherum, Verstärker (4 Ticks) zurück in B
				cmd(actions, "setblock %d %d %d minecraft:stone", bx - 2, by, bz + 10);
				cmd(actions, "setblock %d %d %d minecraft:redstone_wire", bx - 1, by, bz + 11);
				cmd(actions, "setblock %d %d %d minecraft:redstone_wire", bx - 1, by, bz + 12);
				cmd(actions, "setblock %d %d %d minecraft:redstone_wire", bx - 2, by, bz + 12);
				cmd(actions, "setblock %d %d %d minecraft:unpowered_repeater 12", bx - 2, by, bz + 11);
				cmd(actions, "setblock %d %d %d minecraft:redstone_torch 1", bx - 1, by, bz + 10);
				look(actions, bx + 1, bz + 3, bx + 1 + 0.5, by + 0.03, bz + 5 + 0.5);
				wait = 30;
				return true;
			case 1: {
				RedstoneReadout r = tools.readout();
				TrsClient.LOGGER.info("[Autotest] Redstone Staub: valid={} name={} signal={} (erwartet 10)", r.valid, r.name, r.signal);
				actions.shot("redstone-signal");
				look(actions, bx + 1, bz + 8, bx + 3 + 0.5, by + 0.06, bz + 8 + 0.5);
				wait = 15;
				return true;
			}
			case 2: {
				RedstoneReadout r = tools.readout();
				TrsClient.LOGGER.info("[Autotest] Redstone Komparator: valid={} name={} Ausgang={} Details={} (Staub dahinter: {})",
						r.valid, r.name, r.signal, r.details, dust(bx + 3, by, bz + 9));
				actions.shot("redstone-comparator");
				modules.redstoneOverlay.setEnabled(true);
				look(actions, bx + 1, bz + 1, bx + 1 + 0.5, by, bz + 7 + 0.5);
				wait = 30;
				return true;
			}
			case 3:
				TrsClient.LOGGER.info("[Autotest] Redstone Overlay: {} Staub im Cache", tools.cache().size());
				actions.shot("redstone-overlay");
				look(actions, bx - 4, bz + 11, bx - 2 + 0.5, by + 0.06, bz + 11 + 0.5);
				wait = 100;
				return true;
			case 4:
				TrsClient.LOGGER.info("[Autotest] Redstone Takt: sichtbar={} {} {}", tools.clockVisible(), tools.clockName(), tools.clockLines());
				actions.shot("redstone-clock");
				aiming = false;
				mc.displayGuiScreen(new TrsMenuScreen(null).select(modules.redstoneSignal));
				wait = 20;
				return true;
			case 5:
				actions.shot("redstone-menu");
				mc.displayGuiScreen(null);
				modules.redstoneOverlay.setEnabled(false);
				wait = 5;
				return true;
			default:
				return false;
		}
	}

	/** Stärke des Staubs an (x, y, z) laut Spiel – Soll-Wert für die berechnete Komparator-Ausgabe. */
	private static int dust(int x, int y, int z) {
		return dev.theredstonee.trsclient.compat.RedstoneProbe.dustPowerAt(x, y, z);
	}

	private static void cmd(CapeTest.Actions actions, String format, Object... args) {
		actions.command(String.format(Locale.ROOT, format, args));
	}

	/** Spieler auf Feld (sx, sz) stellen und von dort auf einen Punkt blicken lassen (jeden Tick gehalten). */
	private void look(CapeTest.Actions actions, int sx, int sz, double x, double y, double z) {
		double ex = sx + 0.5;
		double ey = by + 1.62;
		double ez = sz + 0.5;
		cmd(actions, "tp @p %.2f %d %.2f", ex, by, ez);
		double dx = x - ex, dy = y - ey, dz = z - ez;
		yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		aiming = true;
	}

	private static void face(EntityPlayer player, float yaw, float pitch) {
		player.rotationYaw = yaw;
		player.prevRotationYaw = yaw;
		player.rotationPitch = pitch;
		player.prevRotationPitch = pitch;
		player.rotationYawHead = yaw;
	}
}

package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.redstone.RedstoneReadout;
import dev.theredstonee.trsclient.core.redstone.RedstoneTools;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Selbsttest der Redstone-Werkzeuge (Teil des Autotests): baut vor dem Spieler eine kleine Schaltung
 * (Staubleitung an einem Redstone-Block, Komparator im Subtrahier-Modus mit Seiteneingang, Observer-Takt),
 * schaut nacheinander auf Staub, Komparator und Takt und macht Screenshots – dazu das Welt-Overlay und
 * die Redstone-Seite im TRS-Menü. Loggt Soll/Ist (berechnete Komparator-Ausgabe gegen den Staub dahinter).
 */
public final class RedstoneTest {
	private int phase;
	private int wait;
	private int bx, by, bz;
	private float yaw, pitch;
	private boolean aiming;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		if (aiming) {
			mc.mouseHandler.releaseMouse();
			face(mc, yaw, pitch);
		}
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
				bx = (int) Math.floor(Mc.x(mc.player));
				by = (int) Math.floor(Mc.y(mc.player));
				bz = (int) Math.floor(Mc.z(mc.player));
				actions.command("time set day");
				actions.command("weather clear");
				// Befehls-Rückmeldungen nicht in den Chat (Name der Spielregel ab 1.21.11 neu)
				actions.command("gamerule sendCommandFeedback false");
				actions.command("gamerule send_command_feedback false");
				cmd(actions, "fill %d %d %d %d %d %d air", bx - 8, by, bz - 2, bx + 10, by + 6, bz + 15);
				cmd(actions, "fill %d %d %d %d %d %d smooth_stone", bx - 8, by - 1, bz - 2, bx + 10, by - 1, bz + 15);
				// Staubleitung nach Osten an einem Redstone-Block: 15, 14, 13 …
				cmd(actions, "setblock %d %d %d redstone_block", bx - 5, by, bz + 5);
				cmd(actions, "fill %d %d %d %d %d %d redstone_wire", bx - 4, by, bz + 5, bx + 6, by, bz + 5);
				// Komparator (Subtrahieren): hinten Redstone-Block (15), Seite Staub 11 → Ausgang 4
				cmd(actions, "setblock %d %d %d redstone_block", bx + 3, by, bz + 7);
				cmd(actions, "setblock %d %d %d comparator[facing=north,mode=subtract]", bx + 3, by, bz + 8);
				cmd(actions, "fill %d %d %d %d %d %d redstone_wire", bx + 4, by, bz + 8, bx + 8, by, bz + 8);
				cmd(actions, "setblock %d %d %d redstone_torch", bx + 9, by, bz + 8);
				cmd(actions, "setblock %d %d %d redstone_wire", bx + 3, by, bz + 9);
				// Takt: zwei Observer, die sich gegenseitig beobachten
				cmd(actions, "setblock %d %d %d observer[facing=south]", bx - 2, by, bz + 10);
				cmd(actions, "setblock %d %d %d observer[facing=north]", bx - 2, by, bz + 11);
				cmd(actions, "setblock %d %d %d redstone_lamp", bx - 2, by, bz + 9);
				look(actions, bx + 1, bz + 3, bx + 1 + 0.5, by + 0.03, bz + 5 + 0.5);
				wait = 30;
				return true;
			case 1: {
				RedstoneReadout r = tools.readout();
				TrsClient.LOGGER.info("[Autotest] Redstone Staub: valid={} name={} signal={} (erwartet 10)", r.valid, r.name, r.signal);
				actions.shot("trsclient-redstone-signal");
				look(actions, bx + 1, bz + 8, bx + 3 + 0.5, by + 0.06, bz + 8 + 0.5);
				wait = 15;
				return true;
			}
			case 2: {
				RedstoneReadout r = tools.readout();
				int behind = TrsClient.get().redstone().cache().size(); // nur fürs Log (Overlay noch aus → 0)
				TrsClient.LOGGER.info("[Autotest] Redstone Komparator: valid={} name={} Ausgang={} Details={} (Staub dahinter: {}) cache={}",
						r.valid, r.name, r.signal, r.details, dustPower(mc, bx + 3, by, bz + 9), behind);
				actions.shot("trsclient-redstone-comparator");
				// Overlay an und von oben über das Feld schauen
				modules.redstoneOverlay.setEnabled(true);
				look(actions, bx + 1, bz + 1, bx + 1 + 0.5, by, bz + 7 + 0.5);
				wait = 30;
				return true;
			}
			case 3:
				TrsClient.LOGGER.info("[Autotest] Redstone Overlay: {} Staub im Cache", tools.cache().size());
				actions.shot("trsclient-redstone-overlay");
				look(actions, bx, bz + 10, bx - 2 + 0.5, by + 0.5, bz + 10 + 0.5);
				wait = 80;
				return true;
			case 4:
				TrsClient.LOGGER.info("[Autotest] Redstone Takt: sichtbar={} {} {}", tools.clockVisible(), tools.clockName(), tools.clockLines());
				actions.shot("trsclient-redstone-clock");
				// wegschauen: Messung läuft weiter
				look(actions, bx, bz + 10, bx + 6 + 0.5, by + 0.5, bz + 13 + 0.5);
				wait = 20;
				return true;
			case 5:
				TrsClient.LOGGER.info("[Autotest] Redstone Takt nach Wegschauen: sichtbar={} {}", tools.clockVisible(), tools.clockLines());
				actions.shot("trsclient-redstone-clock-away");
				aiming = false;
				Mc.setScreen(new TrsMenuScreen(null).select(modules.redstoneSignal));
				wait = 20;
				return true;
			case 6:
				actions.shot("trsclient-redstone-menu");
				Mc.setScreen(null);
				modules.redstoneOverlay.setEnabled(false);
				wait = 5;
				return true;
			default:
				return false;
		}
	}

	private static int dustPower(Minecraft mc, int x, int y, int z) {
		net.minecraft.world.level.block.state.BlockState s = mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
		return s.getBlock() == net.minecraft.world.level.block.Blocks.REDSTONE_WIRE
				? s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER) : -1;
	}

	private static void cmd(CapeTest.Actions actions, String format, Object... args) {
		actions.command(String.format(Locale.ROOT, format, args));
	}

	/**
	 * Spieler auf Feld (sx, sz) stellen (in Reichweite) und von dort auf einen Punkt blicken lassen –
	 * die Richtung wird jeden Tick gehalten.
	 */
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

	private static void face(Minecraft mc, float yaw, float pitch) {
		//? if >=1.17 {
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		//?} else {
		/*mc.player.yRot = yaw;
		mc.player.xRot = pitch;
		*///?}
		mc.player.yHeadRot = yaw;
	}
}

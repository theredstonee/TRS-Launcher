package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest „Quietscheente“ ({@code -PtrsAutotestOnly=duck}, mit TRS-API-Attrappe, die dem eigenen Spieler die Ente
 * aufsetzt): wartet, bis die Textur da ist, und fotografiert die Ente in der 3. Person von vorne und hinten, beim
 * Quaken (Schleichen beginnt) und im Fallen (Flügel schlagen).
 */
public final class DuckTest {
	private int phase;
	private int wait;
	private int tries;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		if (phase > 0 && phase < 9) face(mc);
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0:
				actions.command("gamerule logAdminCommands false");
				actions.command("gamerule sendCommandFeedback false");
				actions.command("time set day");
				actions.command("weather clear");
				actions.command("effect clear @p");
				actions.command("execute as @p at @s run fill ~-6 ~ ~-6 ~6 ~6 ~6 air");
				actions.command("execute as @p at @s run fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 grass_block");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.crosshair.setEnabled(false);
				Mc.setScreen(null);
				camera(mc, true);
				wait = 20;
				return true;
			case 1: {
				// Warten, bis Lookup und Textur da sind (höchstens 15 s).
				Object tex = OnlineHooks.features() == null ? null : OnlineHooks.features().hatTexture(mc.player.getUUID());
				if (tex == null && tries++ < 300) {
					phase = 1;
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Ente: Textur {} nach {} Ticks", tex != null ? "geladen" : "FEHLT", tries);
				dev.theredstonee.trsclient.compat.ChatLines.chat().clearMessages(false);
				wait = 10;
				return true;
			}
			case 2:
				actions.shot("trsclient-duck-front");
				camera(mc, false);
				wait = 10;
				return true;
			case 3:
				actions.shot("trsclient-duck-back");
				camera(mc, true);
				hold(sneakKey(mc), true);
				wait = 3;
				return true;
			case 4:
				actions.shot("trsclient-duck-quack");
				hold(sneakKey(mc), false);
				wait = 20;
				return true;
			case 5:
				actions.command("execute as @p at @s run tp @s ~ ~4 ~");
				wait = 7;
				return true;
			case 6:
				actions.shot("trsclient-duck-air");
				wait = 30;
				return true;
			case 7:
				// Kopf schnell drehen: die Ente hängt hinterher.
				turn = 70f;
				wait = 1;
				return true;
			case 8:
				actions.shot("trsclient-duck-turn");
				turn = 0f;
				wait = 10;
				return true;
			default:
				camera(mc, false);
				camera0(mc);
				return false;
		}
	}

	private float turn;

	private void face(Minecraft mc) {
		//? if >=1.17 {
		mc.player.setYRot(turn);
		mc.player.setXRot(0);
		//?} else {
		/*mc.player.yRot = turn;
		mc.player.xRot = 0;
		*///?}
		mc.player.yHeadRot = turn;
	}

	/** 3. Person von vorne ({@code front}) bzw. von hinten. */
	private static void camera(Minecraft mc, boolean front) {
		//? if >=1.17 {
		mc.options.setCameraType(front ? net.minecraft.client.CameraType.THIRD_PERSON_FRONT : net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		//?}
	}

	private static void camera0(Minecraft mc) {
		//? if >=1.17 {
		mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
		//?}
	}

	private static KeyMapping sneakKey(Minecraft mc) {
		//? if >=1.15 {
		return mc.options.keyShift;
		//?} else
		/*return mc.options.keySneak;*/
	}

	private static void hold(KeyMapping key, boolean down) {
		//? if >=1.15 {
		key.setDown(down);
		//?} else {
		/*((dev.theredstonee.trsclient.mixin.KeyMappingAccessor) key).trsclient$setDown(down);
		*///?}
	}
}

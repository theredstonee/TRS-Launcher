package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.online.OnlineHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//? if >=1.16 {
import net.minecraft.client.CameraType;
//?}

/**
 * Selbsttest für TRS-Umhang, Umhang-Physik und Abzeichen (Teil des Autotests, gleicher Code für alle Loader):
 * Third-Person-Ansicht von hinten auf einer frei geräumten Bahn, warten bis der TRS-Umhang (lokale API-Attrappe,
 * {@code -PtrsApi=http://127.0.0.1:8787}) geladen ist, dann Screenshots: Stehen, nächstes Animationsbild,
 * Laufen, Springen, Schleichen und die Tabliste mit Abzeichen.
 */
public final class CapeTest {
	/** Screenshot und Server-Befehl kommen aus dem Autotest des jeweiligen Loaders. */
	public interface Actions {
		void shot(String name);

		void command(String command);
	}

	private int phase;
	private int wait;
	private int tries;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, Actions actions) {
		if (mc.player == null) return false;
		if (phase > 1) {
			// Das Testfenster kann den Fokus bekommen – Mausbewegungen des Benutzers sollen die Kamera nicht drehen.
			mc.mouseHandler.releaseMouse();
			face(mc, 0f, 15f);
		}
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0:
				modules.trsOnline.setEnabled(true);
				modules.trsCapes.set(true);
				modules.badgeTab.set(true);
				modules.badgeNametag.set(true);
				modules.capePhysics.setEnabled(true);
				modules.minimap.setEnabled(false);
				TrsClient.get().sprintToggle().set(false);
				//? if >=1.17 {
				/*actions.command("item replace entity @p armor.chest with air");
				*///?} else
				actions.command("replaceitem entity @p armor.chest air");
				actions.command("time set day");
				actions.command("weather clear");
				// Freie, ebene Bahn nach Süden (Blickrichtung), damit Laufen/Springen frei sichtbar ist.
				actions.command("execute as @p at @s run fill ~-6 ~ ~-8 ~6 ~7 ~28 air");
				actions.command("execute as @p at @s run fill ~-6 ~-1 ~-8 ~6 ~-1 ~28 grass_block");
				actions.command("scoreboard objectives add trstest dummy");
				actions.command("scoreboard objectives setdisplay list trstest");
				actions.command("execute as @p at @s run tp @s ~ ~ ~ 0 5");
				camera(mc, true);
				hud(mc, false);
				modules.zoomFactor.set(2.0);
				TrsClient.get().setForceZoom(true);
				wait = 5;
				return true;
			case 1: {
				// Auf Anmeldung + Textur warten (höchstens 20 s)
				Object tex = OnlineHooks.capeTexture(mc.player);
				if (tex == null && tries++ < 400) {
					phase = 1;
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] TRS-Umhang: {} (Status {}, Physik aktiv für {} Spieler)",
						tex != null ? tex : "NICHT geladen", OnlineHooks.features().online().status(),
						OnlineHooks.features().physics().active());
				wait = 40;
				return true;
			}
			case 2:
				actions.shot("trsclient-cape-stand");
				wait = 3;
				return true;
			case 3:
				actions.shot("trsclient-cape-frame");
				hold(mc.options.keyUp, true);
				mc.player.setSprinting(true);
				wait = 25;
				return true;
			case 4:
				actions.shot("trsclient-cape-walk");
				hold(mc.options.keyUp, false);
				hold(mc.options.keyJump, true);
				wait = 2;
				return true;
			case 5:
				hold(mc.options.keyJump, false);
				wait = 7;
				return true;
			case 6:
				actions.shot("trsclient-cape-jump");
				wait = 20;
				return true;
			case 7:
				hold(sneakKey(mc), true);
				wait = 25;
				return true;
			case 8:
				actions.shot("trsclient-cape-sneak");
				hold(sneakKey(mc), false);
				hud(mc, true);
				TrsClient.get().setForceZoom(false);
				hold(mc.options.keyPlayerList, true);
				wait = 5;
				return true;
			case 9:
				actions.shot("trsclient-cape-tab");
				hold(mc.options.keyPlayerList, false);
				TrsClient.LOGGER.info("[Autotest] Abzeichen Tabliste={} Name={} → \"{}\"",
						OnlineHooks.badge(mc.player.getUUID(), true), OnlineHooks.badge(mc.player.getUUID(), false),
						OnlineHooks.badged(OnlineHooks.text("Spieler")).getString());
				actions.command("scoreboard objectives remove trstest");
				camera(mc, false);
				KeyMapping.releaseAll();
				return false;
			default:
				return false;
		}
	}

	/** Blickrichtung des Spielers setzen (Süden = 0). */
	private static void face(Minecraft mc, float yaw, float pitch) {
		//? if >=1.17 {
		/*mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		*///?} else {
		mc.player.yRot = yaw;
		mc.player.xRot = pitch;
		//?}
		mc.player.yHeadRot = yaw;
	}

	private static void camera(Minecraft mc, boolean thirdPerson) {
		//? if >=1.16 {
		mc.options.setCameraType(thirdPerson ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
		//?} else
		/*mc.options.thirdPersonView = thirdPerson ? 1 : 0;*/
	}

	private static void hud(Minecraft mc, boolean visible) {
		//? if >=26.2 {
		/*if (mc.gui.hud.isHidden() == visible) mc.gui.hud.toggle();
		*///?} else
		mc.options.hideGui = !visible;
	}

	/** Taste "Schleichen" (bis 1.14 keySneak, danach keyShift). */
	private static KeyMapping sneakKey(Minecraft mc) {
		//? if >=1.15 {
		return mc.options.keyShift;
		//?} else
		/*return mc.options.keySneak;*/
	}

	private static void hold(KeyMapping key, boolean down) {
		//? if >=1.15 {
		key.setDown(down);
		//?} else
		/*KeyMapping.set(key.getKey(), down);*/
	}
}

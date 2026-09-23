package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.online.LegacyOnline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Selbsttest für TRS-Umhang, Umhang-Physik und Abzeichen (Forge 1.8.9–1.12.2): Third-Person-Ansicht von hinten
 * auf einer frei geräumten Bahn, warten bis der TRS-Umhang (lokale API-Attrappe, {@code -PtrsApi=…}) geladen ist,
 * dann Screenshots: Stehen, nächstes Animationsbild, Laufen, Springen, Schleichen und die Tabliste mit Abzeichen.
 */
public final class CapeTest {
	/** Screenshot und Server-Befehl kommen aus dem Autotest. */
	public interface Actions {
		void shot(String name);

		void command(String command);
	}

	private int phase;
	private int wait;
	private int tries;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, Actions actions) {
		EntityPlayer player = Mc.player();
		if (player == null) return false;
		if (phase > 1) {
			// Das Testfenster kann den Fokus bekommen – Mausbewegungen des Benutzers sollen die Kamera nicht drehen.
			mc.setIngameNotInFocus();
			player.rotationYaw = 0f;
			player.rotationPitch = 15f;
			player.rotationYawHead = 0f;
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
				actions.command("replaceitem entity @p slot.armor.chest air");
				actions.command("time set 1000");
				actions.command("weather clear");
				// Freie, ebene Bahn nach Süden (Blickrichtung), damit Laufen/Springen frei sichtbar ist.
				actions.command("execute @p ~ ~ ~ fill ~-6 ~ ~-8 ~6 ~7 ~28 air");
				actions.command("execute @p ~ ~ ~ fill ~-6 ~-1 ~-8 ~6 ~-1 ~28 grass");
				actions.command("scoreboard objectives add trstest dummy");
				actions.command("scoreboard objectives setdisplay list trstest");
				mc.gameSettings.thirdPersonView = 1;
				mc.gameSettings.hideGUI = true;
				modules.zoomFactor.set(2.0);
				TrsClient.get().setForceZoom(true);
				wait = 5;
				return true;
			case 1: {
				// Auf Anmeldung + Textur warten (höchstens 20 s)
				Object tex = LegacyOnline.features().capeTexture(player.getUniqueID());
				if (tex == null && tries++ < 400) {
					phase = 1;
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] TRS-Umhang: {} (Status {}, Physik aktiv für {} Spieler, Umhang-Textur {})",
						tex != null ? tex : "NICHT geladen", LegacyOnline.features().online().status(),
						LegacyOnline.features().physics().active(), Mc.player() == null ? null : cape(mc));
				wait = 40;
				return true;
			}
			case 2:
				actions.shot("cape-stand");
				wait = 3;
				return true;
			case 3:
				actions.shot("cape-frame");
				hold(mc.gameSettings.keyBindForward, true);
				player.setSprinting(true);
				wait = 25;
				return true;
			case 4:
				actions.shot("cape-walk");
				hold(mc.gameSettings.keyBindForward, false);
				hold(mc.gameSettings.keyBindJump, true);
				wait = 2;
				return true;
			case 5:
				hold(mc.gameSettings.keyBindJump, false);
				wait = 7;
				return true;
			case 6:
				actions.shot("cape-jump");
				wait = 20;
				return true;
			case 7:
				hold(mc.gameSettings.keyBindSneak, true);
				wait = 25;
				return true;
			case 8:
				actions.shot("cape-sneak");
				hold(mc.gameSettings.keyBindSneak, false);
				mc.gameSettings.hideGUI = false;
				TrsClient.get().setForceZoom(false);
				hold(mc.gameSettings.keyBindPlayerList, true);
				wait = 5;
				return true;
			case 9: {
				actions.shot("cape-tab");
				hold(mc.gameSettings.keyBindPlayerList, false);
				NetworkPlayerInfo info = Mc.connection() == null ? null : Mc.connection().getPlayerInfo(player.getUniqueID());
				TrsClient.LOGGER.info("[Autotest] Abzeichen Tabliste={} Name={} → Tabliste \"{}\", Namensschild \"{}\"",
						LegacyOnline.features().badge(player.getUniqueID(), true),
						LegacyOnline.features().badge(player.getUniqueID(), false),
						info == null || info.getDisplayName() == null ? null : info.getDisplayName().getFormattedText(),
						player.getDisplayName().getFormattedText());
				actions.command("scoreboard objectives remove trstest");
				mc.gameSettings.thirdPersonView = 0;
				KeyBinding.unPressAllKeys();
				return false;
			}
			default:
				return false;
		}
	}

	private static Object cape(Minecraft mc) {
		return ((net.minecraft.client.entity.AbstractClientPlayer) Mc.player()).getLocationCape();
	}

	private static void hold(KeyBinding key, boolean down) {
		KeyBinding.setKeyBindState(key.getKeyCode(), down);
	}
}

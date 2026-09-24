package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.cape.CapeSettings;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.online.LegacyOnline;
import dev.theredstonee.trsclient.render.ColorPass;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Selbsttest für die Umhang-Physik-Einstellungen und das Modul „Farben“ unter Forge 1.8.9–1.12.2
 * ({@code -PtrsAutotestOnly=capecolor} springt direkt hierher): Einstellungsseite mit Live-Vorschau,
 * Umhang im Böen-Wind, Stufen-Stil, Spielbild mit Sättigung 0 % und 200 %, Farben-Seite im Menü.
 */
public final class CapeColorTest {
	private int phase;
	private int wait;
	private int tries;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		EntityPlayer player = Mc.player();
		if (player == null) return false;
		if (phase > 4) {
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
				modules.capePhysics.reset();
				modules.capePhysics.setEnabled(true);
				modules.minimap.setEnabled(false);
				modules.colors.reset();
				actions.command("replaceitem entity @p slot.armor.chest air");
				actions.command("time set 1000");
				actions.command("weather clear");
				actions.command("execute @p ~ ~ ~ fill ~-6 ~ ~-8 ~6 ~7 ~28 air");
				actions.command("execute @p ~ ~ ~ fill ~-6 ~-1 ~-8 ~6 ~-1 ~28 grass");
				actions.command("execute @p ~ ~ ~ fill ~-6 ~ ~20 ~6 ~4 ~20 wool 14");
				actions.command("execute @p ~ ~ ~ fill ~-2 ~ ~19 ~2 ~3 ~19 wool 5");
				actions.command("execute @p ~ ~ ~ fill ~-1 ~ ~18 ~1 ~2 ~18 wool 3");
				wait = 5;
				return true;
			case 1: {
				Object tex = LegacyOnline.features() == null ? null : LegacyOnline.features().capeTexture(player.getUniqueID());
				if (tex == null && tries++ < 400) {
					phase = 1;
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Umhang-Vorschau: Umhang {}", tex != null ? tex : "(Vanilla/keiner)");
				wait = 40;
				return true;
			}
			case 2: {
				net.minecraft.client.entity.AbstractClientPlayer self = (net.minecraft.client.entity.AbstractClientPlayer) player;
				TrsClient.LOGGER.info("[Autotest] Umhang am Spieler: info={} getragen={} Textur={} Physik für {} Spieler",
						self.hasPlayerInfo(), self.isWearing(net.minecraft.entity.player.EnumPlayerModelParts.CAPE),
						self.getLocationCape(), LegacyOnline.features().physics().active());
				mc.displayGuiScreen(new TrsMenuScreen(null).select(modules.capePhysics));
				wait = 20;
				return true;
			}
			case 3:
				actions.shot("capephysics-menu");
				wait = 55;
				return true;
			case 4:
				actions.shot("capephysics-menu-walk");
				TrsClient.LOGGER.info("[Autotest] Vorschau geht={} (Physik aktiv für {} Spieler)",
						LegacyOnline.features().physics().previewWalking(), LegacyOnline.features().physics().active());
				mc.displayGuiScreen(null);
				modules.capeWindMode.set(CapeSettings.Wind.GUSTS);
				modules.capeWind.set(200);
				mc.gameSettings.thirdPersonView = 1;
				mc.gameSettings.hideGUI = true;
				modules.zoomFactor.set(2.0);
				TrsClient.get().setForceZoom(true);
				// Von der Seite (Freelook), damit das Anheben durch Wind sichtbar ist.
				TrsClient.get().pvp().forceFreelook(100F);
				wait = 60;
				return true;
			case 5:
				actions.shot("cape-wind");
				wait = 30;
				return true;
			case 6:
				actions.shot("cape-wind-2");
				modules.capeStyle.set(CapeSettings.Style.BLOCKY);
				modules.capeMovement.set(CapeSettings.Movement.DUNGEONS);
				wait = 40;
				return true;
			case 7:
				actions.shot("cape-blocky");
				modules.capePhysics.reset();
				TrsClient.get().setForceZoom(false);
				TrsClient.get().pvp().forceFreelook(Float.NaN);
				wait = 5;
				return true;
			case 8:
				// Freelook stellt beim Beenden die alte Perspektive her – danach erst in die Ich-Perspektive.
				mc.gameSettings.thirdPersonView = 0;
				mc.gameSettings.hideGUI = false;
				modules.colors.setEnabled(true);
				modules.colorSaturation.set(0);
				wait = 15;
				return true;
			case 9:
				actions.shot("colors-0");
				modules.colorSaturation.set(200);
				wait = 10;
				return true;
			case 10:
				actions.shot("colors-200");
				TrsClient.LOGGER.info("[Autotest] Farben: Durchgang lief {}× (unterstützt={})", ColorPass.applied,
						ColorPass.supported());
				modules.colorSaturation.set(140);
				modules.colorContrast.set(115);
				modules.colorTemperature.set(40);
				mc.displayGuiScreen(new TrsMenuScreen(null).select(modules.colors));
				wait = 20;
				return true;
			case 11:
				actions.shot("colors-menu");
				mc.displayGuiScreen(null);
				modules.colors.reset();
				return false;
			default:
				return false;
		}
	}
}

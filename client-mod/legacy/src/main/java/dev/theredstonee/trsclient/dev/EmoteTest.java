package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.emote.EmoteController;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.online.LegacyEmotes;
import dev.theredstonee.trsclient.screen.EmoteWheelScreen;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest für Emote-Rad und Emotes (Forge 1.8.9–1.12.2, nach {@link CapeTest}; braucht die lokale API-Attrappe
 * mit Emotes): Rad (Screenshot), Winken über das Rad, Tanzen und Verbeugen (Screenshots), Wartezeit, Abbruch durch
 * Bewegung und ein gesperrtes Emote.
 */
public final class EmoteTest {
	private int phase;
	private int wait;
	private int tries;
	private EmoteWheelScreen screen;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (Mc.player() == null) return false;
		EmoteController emotes = LegacyEmotes.emotes();
		if (emotes == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		long now = System.currentTimeMillis();
		switch (phase++) {
			case 0:
				modules.trsOnline.setEnabled(true);
				modules.emotes.setEnabled(true);
				modules.emoteOthers.set(true);
				modules.emoteCamera.set(TrsModules.EmoteCamera.FRONT);
				mc.gameSettings.thirdPersonView = 0;
				mc.gameSettings.hideGUI = true;
				actions.command("tp @p ~ ~ ~ 0 5");
				wait = 5;
				return true;
			case 1:
				// Auf Anmeldung + Emote-Liste warten (höchstens 20 s).
				if (emotes.state() != EmoteController.State.READY && tries++ < 400) {
					phase = 1;
					return true;
				}
				TrsClient.LOGGER.info("[Autotest] Emotes: Zustand {}, winken frei={}, redstone_tanz frei={}, Stream={}",
						emotes.state(), emotes.unlocked("winken"), emotes.unlocked("redstone_tanz"),
						LegacyEmotes.eventsConnected());
				screen = new EmoteWheelScreen();
				Mc.setScreen(screen);
				screen.wheel().hover("winken");
				wait = 14;
				return true;
			case 2:
				actions.shot("emote-wheel");
				screen.wheel().confirmNow();
				// Näher heran (wie im Umhang-Test), damit die Pose im Bild groß genug ist.
				modules.zoomFactor.set(2.0);
				TrsClient.get().setForceZoom(true);
				wait = 16;
				return true;
			case 3:
				TrsClient.LOGGER.info("[Autotest] Emote Winken läuft={}, Kamera={} (2 = von vorn), Rad offen={}",
						emotes.selfPlaying(now), mc.gameSettings.thirdPersonView, Mc.screen() != null);
				actions.shot("emote-wave");
				TrsClient.LOGGER.info("[Autotest] Emote gleich nochmal (Wartezeit): {}", emotes.play("klatschen", now));
				wait = 40;
				return true;
			case 4:
				TrsClient.LOGGER.info("[Autotest] Emote Tanzen: {}", emotes.play("tanzen", now));
				wait = 28;
				return true;
			case 5:
				actions.shot("emote-dance");
				wait = 16;
				return true;
			case 6:
				TrsClient.LOGGER.info("[Autotest] Emote Verbeugen: {}", emotes.play("verbeugen", now));
				wait = 16;
				return true;
			case 7:
				actions.shot("emote-bow");
				wait = 30;
				return true;
			case 8:
				TrsClient.LOGGER.info("[Autotest] Emote Jubeln: {}", emotes.play("jubeln", now));
				wait = 8;
				return true;
			case 9:
				actions.command("tp @p ~1 ~ ~");
				// Der Befehl läuft im Server-Thread – etwas Zeit lassen.
				wait = 20;
				return true;
			case 10:
				TrsClient.LOGGER.info("[Autotest] Bewegung beendet Emote: {}, Kamera zurück: {}", !emotes.selfPlaying(now),
						mc.gameSettings.thirdPersonView == 0);
				TrsClient.LOGGER.info("[Autotest] Gesperrtes Emote: {}", emotes.play("redstone_tanz", now + 5000));
				mc.gameSettings.hideGUI = false;
				TrsClient.get().setForceZoom(false);
				return false;
			default:
				return false;
		}
	}
}

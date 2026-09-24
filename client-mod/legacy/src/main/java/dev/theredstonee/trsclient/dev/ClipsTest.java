package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.TrsKeys;
import dev.theredstonee.trsclient.core.clips.ClipStatus;
import dev.theredstonee.trsclient.core.clips.Clips;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Selbsttest Clips & Aufnahme (Forge 1.8.9–1.12.2, allein mit {@code -PtrsAutotestOnly=clips}): drückt die
 * Tastenbelegungen F9/F10 über Minecrafts eigene Tastenverarbeitung ({@link KeyBinding#onTick}) und macht
 * Screenshots des HUD-Elements.
 */
public final class ClipsTest {
	private int phase;
	private int wait;
	private int waited;

	private static void log(String what) {
		ClipStatus s = Clips.get().status();
		TrsClient.LOGGER.info("[Autotest] Clips {}: verbunden={} verfügbar={} grund={} puffer={} aufnahme={} clip={}s", what,
				s.connected, s.available, s.reason, s.buffer, s.recording, s.clipSeconds);
	}

	/** Wie ein echter Tastendruck: die Belegung (F9/F10 oder vom Spieler geändert) zählt einen Klick. */
	private static void press(KeyBinding binding) {
		KeyBinding.onTick(binding.getKeyCode());
	}

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (dev.theredstonee.trsclient.compat.Mc.player() == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0:
				modules.clips.setEnabled(true);
				modules.clips.resetLayout();
				log("Start");
				wait = 20;
				return true;
			case 1:
				if (!Clips.get().status().buffer && waited++ < 60) {
					phase--;
					wait = 20;
					return true;
				}
				log("vor F9");
				actions.shot("clips-buffer");
				press(TrsKeys.saveClip);
				wait = 50;
				return true;
			case 2:
				log("nach F9");
				actions.shot("clips-saved");
				press(TrsKeys.toggleRecording);
				wait = 100;
				return true;
			case 3:
				log("Aufnahme");
				actions.shot("clips-recording");
				press(TrsKeys.toggleRecording);
				wait = 50;
				return true;
			case 4:
				log("nach Stopp");
				actions.shot("clips-recording-saved");
				return false;
			default:
				return false;
		}
	}
}

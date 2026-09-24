package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.core.clips.ClipStatus;
import dev.theredstonee.trsclient.core.clips.Clips;
import dev.theredstonee.trsclient.core.module.TrsModules;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest Clips & Aufnahme (Teil des Autotests, allein mit {@code -PtrsAutotestOnly=clips}): drückt die
 * echten Tasten F9 (Clip) und F10 (Aufnahme an/aus) und macht Screenshots des HUD-Elements. Ohne Launcher
 * zeigt es den Hinweis, mit Launcher (oder Attrappe) Puffer, roten Punkt und "Clip gespeichert".
 */
public final class ClipsTest {
	private int phase;
	private int wait;

	private static void log(String what) {
		ClipStatus s = Clips.get().status();
		TrsClient.LOGGER.info("[Autotest] Clips {}: verbunden={} verfügbar={} grund={} puffer={} aufnahme={} clip={}s", what,
				s.connected, s.available, s.reason, s.buffer, s.recording, s.clipSeconds);
	}

	/**
	 * Wie ein Tastendruck, aber ohne Fensterfokus (im echten Launcher-Start liegt das Spielfenster evtl.
	 * im Hintergrund): Minecraft zählt einen Klick für alle Belegungen dieser Taste – die Mod liest ihn wie immer
	 * über consumeClick.
	 */
	private static void press(String keyName) {
		net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(keyName));
	}

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		switch (phase++) {
			case 0:
				modules.clips.setEnabled(true);
				modules.clips.resetLayout();
				log("Start");
				// Auf den Puffer warten (Launcher braucht einige Sekunden bis zum ersten Segment).
				wait = 20;
				return true;
			case 1:
				if (!Clips.get().status().buffer && waited++ < 60) {
					phase--;
					wait = 20;
					return true;
				}
				log("vor F9");
				actions.shot("trsclient-clips-buffer");
				press("key.keyboard.f9");
				// Der Launcher speichert nach dem Ende des laufenden Segments (bis ~3 s).
				wait = 50;
				return true;
			case 2:
				log("nach F9");
				actions.shot("trsclient-clips-saved");
				press("key.keyboard.f10");
				wait = 100;
				return true;
			case 3:
				log("Aufnahme");
				actions.shot("trsclient-clips-recording");
				press("key.keyboard.f10");
				wait = 50;
				return true;
			case 4:
				log("nach Stopp");
				actions.shot("trsclient-clips-recording-saved");
				return false;
			default:
				return false;
		}
	}

	private int waited;
}

package dev.theredstonee.trsclient.halloween;

import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.halloween.Miyako;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * MiyakoMCs Intro-Video (nur moderner Fabric-Pfad): meldet {@link Miyako} als video-fähig und öffnet beim ersten Start
 * – sobald das Paket da ist und er noch nie das Intro sah – den {@link MiyakoVideoScreen}. Alle anderen Versionen und
 * Lader überspringen das Video still (siehe {@code HalloweenHooks}).
 */
public final class MiyakoHooks {
	private static boolean failed;

	private MiyakoHooks() {
	}

	public static void install() {
		//? if >=1.20 {
		Miyako.get().setVideoSupported(true);
		ClientTickEvents.END_CLIENT_TICK.register(MiyakoHooks::tick);
		//?}
	}

	private static void tick(Minecraft mc) {
		if (failed) return;
		try {
			if (!Miyako.get().wantsIntro()) return;
			// Nicht mitten in einem laufenden Video erneut öffnen. Mc.screen() statt mc.screen (Feld ab 26.2 weg).
			if (Mc.screen() instanceof MiyakoVideoScreen) return;
			Miyako.get().introStarted();
			Mc.setScreen(new MiyakoVideoScreen(Mc.screen()));
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			Miyako.get().introSkipped();
		}
	}
}

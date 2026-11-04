package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.minigames.MinigameSelfTest;
import dev.theredstonee.trsclient.core.minigames.MinigamesUi;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.screen.TrsMenuHost;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest Minispiele ({@code -PtrsAutotestOnly=minigames}): Der Ablauf steht in {@link MinigameSelfTest}, hier nur die
 * Anbindung an Minecraft. Screenshots: trsclient-&lt;mc&gt;-minigames-*.png; Messwerte im Log unter „[Autotest] Minispiele“.
 */
public final class MinigamesTest implements MinigameSelfTest.Hook {
	private final MinigameSelfTest test = new MinigameSelfTest(this);
	private Minecraft mc;
	private boolean started;

	public static void install() {
		MinigamesTest t = new MinigamesTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(t::tick);
	}

	private void tick(Minecraft minecraft) {
		mc = minecraft;
		if (!started) {
			if (Mc.overlay() != null || Mc.screen() == null) return;
			mc.options.pauseOnLostFocus = false;
			started = true;
		}
		// Vanillas eigene AFK-Bremse (ab 1.21.2, 30 FPS nach einer Minute ohne Eingabe) soll die Messung nicht verfälschen:
		// Wer spielt, drückt Tasten, der Bot nicht.
		//? if >=1.21.2 {
		/*mc.getFramerateLimitTracker().onInputReceived();
		*///?}
		try {
			test.tick();
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Minispiele: Fehler", e);
			mc.stop();
		}
	}

	@Override
	public MinigamesUi open() {
		MinigamesUi ui = new MinigamesUi(new TrsMenuHost(new TrsTitleScreen()));
		Mc.setScreen(new TrsUiScreen("minigames", ui));
		return ui;
	}

	@Override
	public void shot(String name) {
		AutoTest.shot(mc, "trsclient-minigames-" + name);
		log("Bild " + name);
	}

	@Override
	public void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Minispiele: {}", text);
	}

	@Override
	public void theme(String design) {
		Theme.set(Theme.of("dark", design, null, design));
	}

	@Override
	public void reloadResources() {
		mc.reloadResourcePacks();
	}

	@Override
	public void startWorld() {
		AutoTest.startWorld(mc);
	}

	@Override
	public boolean worldReady() {
		return mc.level != null && mc.player != null && Mc.screen() == null;
	}

	@Override
	public void finish() {
		Theme.set(Theme.of("dark", "redstone", null));
		Theme.loadFrom(mc.gameDirectory.toPath().resolve("config"));
		mc.stop();
	}
}

package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Selbsttest „Suche in der Tastenbelegung“ ({@code -PtrsAutotestOnly=keysearch}) unter 1.8.9–1.12.2: öffnet
 * Optionen → Steuerung vom Titelbildschirm, sucht nach key:2, Maustasten, Konflikten, Mod, unbelegt und per Taste;
 * Bilder trsclient-&lt;mc&gt;-keysearch-*.png, Zeilenzahlen im Log. Beendet das Spiel danach.
 */
public final class KeySearchTest {
	private int phase;
	private int wait;
	private int all;
	private int dropBefore;
	private final String mcVersion = Mc.version();

	private static final String[][] STEPS = {
			{ "jump", "name" },
			{ "key:2", "key2" },
			{ "mouse", "mouse" },
			{ "conflict", "conflict" },
			{ "mod:trsclient", "mod" },
			{ "unbound", "unbound" },
			{ "gibtsnicht", "none" },
	};

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new KeySearchTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		try {
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Tastenbelegung: Fehler in Phase {}", phase, e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-keysearch-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		GuiScreen screen = mc.currentScreen;
		int p = phase;
		if (p == 0) {
			if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
			mc.gameSettings.pauseOnLostFocus = false;
			// Konflikt anlegen: „Fallenlassen“ zusätzlich auf 2 (wie Schnellleiste 2) – am Ende zurück.
			dropBefore = mc.gameSettings.keyBindDrop.getKeyCode();
			mc.gameSettings.keyBindDrop.setKeyCode(Keyboard.KEY_2);
			KeyBinding.resetKeyBindingArrayAndHash();
			mc.displayGuiScreen(new GuiControls(screen, mc.gameSettings));
			phase++;
			wait = 20;
			return;
		}
		if (p == 1) {
			all = TrsClient.keySearch.testRows();
			TrsClient.LOGGER.info("[Autotest] Tastenbelegung: Suche {}, {} Zeilen", all >= 0 ? "erkannt" : "FEHLT", all);
			shot(mc, "all");
			phase++;
			wait = 5;
			return;
		}
		int i = (p - 2) / 2;
		if (i < STEPS.length) {
			if ((p - 2) % 2 == 0) {
				TrsClient.keySearch.testQuery(STEPS[i][0]);
				wait = 5;
			} else {
				TrsClient.LOGGER.info("[Autotest] Suche „{}“: {} von {} Zeilen", STEPS[i][0], TrsClient.keySearch.testRows(), all);
				shot(mc, STEPS[i][1]);
				wait = 3;
			}
			phase++;
			return;
		}
		int q = p - 2 - STEPS.length * 2;
		if (q == 0) {
			TrsClient.keySearch.testCapture("key.keyboard.2");
			phase++;
			wait = 5;
			return;
		}
		if (q == 1) {
			TrsClient.LOGGER.info("[Autotest] Taste 2 gedrückt: {} Zeilen", TrsClient.keySearch.testRows());
			shot(mc, "captured");
			TrsClient.keySearch.testQuery("");
			TrsClient.LOGGER.info("[Autotest] Suche geleert: {} von {} Zeilen", TrsClient.keySearch.testRows(), all);
			mc.gameSettings.keyBindDrop.setKeyCode(dropBefore);
			KeyBinding.resetKeyBindingArrayAndHash();
			TrsClient.LOGGER.info("[Autotest] fertig, beende das Spiel");
			phase = 999;
			mc.shutdown();
		}
	}
}

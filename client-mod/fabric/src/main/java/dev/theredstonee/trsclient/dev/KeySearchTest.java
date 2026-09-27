package dev.theredstonee.trsclient.dev;

import com.mojang.blaze3d.platform.InputConstants;
import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.menus.KeySearchUi;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest „Suche in der Tastenbelegung“ ({@code -PtrsAutotestOnly=keysearch}): öffnet Steuerung → Tastenbelegung,
 * sucht nach Name, key:2, Maustasten, Konflikten, Mod, unbelegt und per Tastendruck; Screenshots
 * trsclient-&lt;mc&gt;-keysearch-*.png, Zeilenzahlen im Log.
 */
public final class KeySearchTest {
	private int phase;
	private int wait;
	private int all;
	private KeyMapping conflictKey;
	private String conflictBefore;

	private static final String[][] STEPS = {
			{ "springen", "name" },
			{ "key:2", "key2" },
			{ "mouse", "mouse" },
			{ "conflict", "conflict" },
			{ "mod:trsclient", "mod" },
			{ "unbound", "unbound" },
			{ "gibtsnicht", "none" },
	};

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, CapeTest.Actions actions) {
		if (wait > 0) {
			wait--;
			return true;
		}
		int p = phase++;
		if (p == 0) {
			// Konflikt anlegen: „Fallenlassen“ zusätzlich auf 2 (wie Schnellleiste 2) – am Ende zurück.
			conflictKey = mc.options.keyDrop;
			conflictBefore = conflictKey.saveString();
			conflictKey.setKey(InputConstants.getKey("key.keyboard.2"));
			KeyMapping.resetMapping();
			Mc.setScreen(keyBindsScreen(mc));
			wait = 20;
			return true;
		}
		if (p == 1) {
			all = KeySearchUi.testRows();
			TrsClient.LOGGER.info("[Autotest] Tastenbelegung: Suche {}, {} Zeilen", all >= 0 ? "erkannt" : "FEHLT", all);
			TrsClient.LOGGER.info("[Autotest] Konflikte: {}", KeySearchUi.testConflicts());
			actions.shot("trsclient-keysearch-all");
			wait = 5;
			return true;
		}
		int i = (p - 2) / 2;
		if (i < STEPS.length) {
			if ((p - 2) % 2 == 0) {
				KeySearchUi.testQuery(STEPS[i][0]);
				wait = 5;
			} else {
				TrsClient.LOGGER.info("[Autotest] Suche „{}“: {} von {} Zeilen", STEPS[i][0], KeySearchUi.testRows(), all);
				actions.shot("trsclient-keysearch-" + STEPS[i][1]);
				wait = 3;
			}
			return true;
		}
		int q = p - 2 - STEPS.length * 2;
		if (q == 0) {
			KeySearchUi.testQuery("");
			KeySearchUi.testStartCapture();
			TrsClient.LOGGER.info("[Autotest] Taste drücken: wartet={}", KeySearchUi.testCapturing());
			wait = 3;
			return true;
		}
		if (q == 1) {
			actions.shot("trsclient-keysearch-capturing");
			KeySearchUi.testCapture("key.keyboard.2");
			wait = 5;
			return true;
		}
		if (q == 2) {
			TrsClient.LOGGER.info("[Autotest] Taste 2 gedrückt: {} Zeilen", KeySearchUi.testRows());
			actions.shot("trsclient-keysearch-captured");
			KeySearchUi.testQuery("");
			TrsClient.LOGGER.info("[Autotest] Suche geleert: {} von {} Zeilen", KeySearchUi.testRows(), all);
			conflictKey.setKey(InputConstants.getKey(conflictBefore));
			KeyMapping.resetMapping();
			Mc.setScreen(null);
			wait = 5;
			return true;
		}
		return false;
	}

	private static net.minecraft.client.gui.screens.Screen keyBindsScreen(Minecraft mc) {
		//? if >=1.21 {
		return new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(null, mc.options);
		//?} elif >=1.18 {
		/*return new net.minecraft.client.gui.screens.controls.KeyBindsScreen(null, mc.options);
		*///?} else
		/*return new net.minecraft.client.gui.screens.controls.ControlsScreen(null, mc.options);*/
	}
}

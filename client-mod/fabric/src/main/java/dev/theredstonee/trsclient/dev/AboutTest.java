package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.legal.AuthorNotice;
import dev.theredstonee.trsclient.core.ui.menu.ModMenu;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;

/**
 * Selbsttest „Info“ ({@code -PtrsAutotestOnly=about}): öffnet die Seite auf Englisch, Deutsch und Spanisch, schreibt den
 * Stand des Urheberhinweises ins Log und macht je ein Bild (about-en/-de/-es). Ohne Welt, ohne Netz.
 */
public final class AboutTest {
	private static final String[] LANGUAGES = {"en", "de", "es"};
	private int phase;
	private int wait;

	public static void install() {
		AboutTest test = new AboutTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		if (phase == 0) {
			if (Mc.overlay() != null || Mc.screen() == null) return;
			mc.options.pauseOnLostFocus = false;
			phase++;
			wait = 20;
			return;
		}
		int step = phase - 1;
		if (step == LANGUAGES.length * 2) {
			mc.stop();
			phase++;
			return;
		}
		String language = LANGUAGES[step / 2];
		if (step % 2 == 0) {
			I18n.override(language);
			TrsMenuScreen menu = new TrsMenuScreen(new TrsTitleScreen());
			((ModMenu) menu.ui()).showAbout();
			Mc.setScreen(menu);
			wait = 30;
		} else {
			TrsClient.LOGGER.info("[Autotest] Info {}: Hinweis unverändert={}", language, AuthorNotice.intact());
			AutoTest.shot(mc, "trsclient-about-" + language);
			wait = 10;
		}
		phase++;
	}
}

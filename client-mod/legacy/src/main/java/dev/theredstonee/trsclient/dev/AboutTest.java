package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.legal.AuthorNotice;
import dev.theredstonee.trsclient.core.ui.menu.ModMenu;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Selbsttest „Info“ unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=about}): öffnet die Seite auf Englisch, Deutsch und
 * Spanisch, schreibt den Stand des Urheberhinweises ins Log und macht je ein Bild (about-en/-de/-es). Ohne Welt, ohne Netz.
 */
public final class AboutTest {
	private static final String[] LANGUAGES = {"en", "de", "es"};
	private int phase;
	private int wait;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new AboutTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		if (wait > 0) {
			wait--;
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (phase == 0) {
			if (mc.currentScreen == null) return;
			mc.gameSettings.pauseOnLostFocus = false;
			phase++;
			wait = 20;
			return;
		}
		int step = phase - 1;
		if (step == LANGUAGES.length * 2) {
			mc.shutdown();
			phase++;
			return;
		}
		String language = LANGUAGES[step / 2];
		if (step % 2 == 0) {
			I18n.override(language);
			TrsMenuScreen menu = new TrsMenuScreen(new TrsTitleScreen());
			((ModMenu) menu.ui()).showAbout();
			mc.displayGuiScreen(menu);
			wait = 30;
		} else {
			TrsClient.LOGGER.info("[Autotest] Info {}: Hinweis unverändert={}", language, AuthorNotice.intact());
			String file = "trsclient-" + Mc.version() + "-about-" + language + ".png";
			ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
			TrsClient.LOGGER.info("[Autotest] Screenshot {}", file);
			wait = 10;
		}
		phase++;
	}
}

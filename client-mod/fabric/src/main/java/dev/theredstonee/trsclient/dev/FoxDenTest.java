package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.fox.FoxScene;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.screen.TrsMenuHost;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

/**
 * Selbsttest Design „Fuchsbau“ ({@code -PtrsAutotestOnly=fox}): Startbildschirm in der Dämmerung (Dunkel, Hell = goldene
 * Stunde, OLED), Einführung (Design-Wahl), TRS-Menü (Seite „Menü-Stil“ mit der Design-Wahl), Serverliste,
 * Ladebildschirm der Welt, Pausenmenü und TRS-Menü in der Welt. Lage des schlafenden Fuchses steht im Log (für die
 * Nahaufnahme). Danach gilt wieder das Aussehen aus der Launcher-Datei; das Spiel endet.
 * Screenshots: trsclient-&lt;mc&gt;-fox-*.png.
 */
public final class FoxDenTest {
	private int phase;
	private int wait;
	private int waited;

	public static void install() {
		FoxDenTest test = new FoxDenTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Fuchsbau: {}", text);
	}

	private void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-fox-" + name);
		log("Bild " + name + " (Szene Ø " + Math.round(FoxScene.shared().averageMicros()) + " µs, Menü Ø "
				+ Math.round(MenuSkin.averageMicros()) + " µs)");
	}

	private boolean waitFor(boolean ready, int maxTries) {
		if (!ready && waited++ < maxTries) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	/** Ladebildschirm mit festem Fortschritt (62 %) – zeigt den glimmenden Ast mit Flamme an der Glutkante. */
	private static dev.theredstonee.trsclient.core.ui.UiScreen loadingPreview() {
		return new dev.theredstonee.trsclient.core.ui.UiScreen() {
			@Override
			protected void draw(dev.theredstonee.trsclient.core.ui.Canvas c, int width, int height, int mouseX, int mouseY, float dt) {
				MenuSkin.loading(c, width, height, "Preparing the world", "62%", 0.62f, false);
			}

			@Override
			protected void onClosed() {
			}
		};
	}

	private static void fox(String theme) {
		Theme.set(Theme.of(theme, "fox", null, Theme.FOX));
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Fuchsbau: Fehler in Phase {}", phase, e);
			mc.stop();
			phase = 999;
		}
	}

	private void step(Minecraft mc) {
		Screen screen = Mc.screen();
		TrsModules modules = TrsClient.get().modules();
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || screen == null) return;
				mc.options.pauseOnLostFocus = false;
				fox("dark");
				Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 40;
				return;
			case 1: {
				shot(mc, "title");
				int[] b = screen == null ? null : FoxScene.shared().foxBounds(screen.width, screen.height);
				if (b != null) log("Fuchs bei x=" + b[0] + " y=" + b[1] + " w=" + b[2] + " h=" + b[3] + " (GUI " + screen.width + "x" + screen.height + ")");
				else log("kein Fuchs");
				fox("light");
				phase++;
				wait = 20;
				return;
			}
			case 2:
				shot(mc, "title-light");
				fox("oled");
				phase++;
				wait = 10;
				return;
			case 3:
				shot(mc, "title-oled");
				fox("dark");
				new TrsMenuHost(new TrsTitleScreen()).openIntro();
				phase++;
				wait = 25;
				return;
			case 4:
				shot(mc, "intro");
				Mc.setScreen(new TrsMenuScreen(new TrsTitleScreen()).select(modules.menuStyle));
				phase++;
				wait = 25;
				return;
			case 5:
				shot(mc, "trsmenu");
				Mc.setScreen(new dev.theredstonee.trsclient.screen.TrsUiScreen("loading", loadingPreview()));
				phase = 50;
				wait = 20;
				return;
			case 50:
				shot(mc, "loading-bar");
				Mc.setScreen(new JoinMultiplayerScreen(new TrsTitleScreen()));
				phase = 6;
				wait = 40;
				return;
			case 6:
				shot(mc, "multiplayer");
				AutoTest.startWorld(mc);
				phase++;
				return;
			case 7:
				// Ladebildschirm abpassen (während die Welt entsteht bzw. lädt).
				if (screen instanceof LevelLoadingScreen) {
					wait = 2;
					phase++;
					return;
				}
				if (mc.level != null && waited++ > 20) {
					log("Ladebildschirm verpasst");
					waited = 0;
					phase += 2;
				}
				return;
			case 8:
				if (screen instanceof LevelLoadingScreen) shot(mc, "loading");
				phase++;
				return;
			case 9:
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null, 600)) return;
				wait = 40;
				phase++;
				return;
			case 10:
				Mc.setScreen(new PauseScreen(true));
				phase++;
				wait = 15;
				return;
			case 11:
				shot(mc, "pause");
				Mc.setScreen(new TrsMenuScreen(screen).select(modules.titleScreen));
				phase++;
				wait = 25;
				return;
			case 12:
				shot(mc, "trsmenu-ingame");
				// Wieder das Aussehen aus der Launcher-Datei (bzw. Standard).
				Theme.set(Theme.of("dark", "redstone", null));
				Theme.loadFrom(mc.gameDirectory.toPath().resolve("config"));
				log("fertig");
				phase++;
				mc.stop();
				return;
			default:
		}
	}
}

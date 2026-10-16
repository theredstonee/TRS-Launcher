package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.halloween.Halloween;
import dev.theredstonee.trsclient.core.halloween.HalloweenService;
import dev.theredstonee.trsclient.core.halloween.Jumpscares;
import dev.theredstonee.trsclient.core.halloween.SpookyFx;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;

/**
 * Selbsttest Halloween 2026 ({@code -PtrsAutotestOnly=halloween}): Event erzwungen, Kürbis-Suche als Beispiel-Zustand
 * (7/13). Bilder trsclient-&lt;mc&gt;-halloween-*.png: Startbildschirm (mit Hinweis „Flackern &amp; Rauschen“, dann ohne),
 * TRS-Menü Seite Halloween, Ladebildschirm (Mondlauf), in der Welt mit Fledermäusen und Geister-Spur, „Hinter dir!“
 * (Ansicht von vorne) und ein Jumpscare (Gesicht). Stumm (kein Grusel-Ton), danach endet das Spiel.
 */
public final class HalloweenTest {
	private int phase;
	private int wait;
	private int waited;

	public static void install() {
		Halloween.force(Boolean.TRUE);
		HalloweenService.get().demoState();
		HalloweenTest test = new HalloweenTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Halloween: {}", text);
	}

	private static void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-halloween-" + name);
		log("Bild " + name + " (Menü Ø " + Math.round(MenuSkin.averageMicros()) + " µs; "
				+ Jumpscares.get().info(System.currentTimeMillis()) + ")");
	}

	private boolean waitFor(boolean ready, int maxTries) {
		if (!ready && waited++ < maxTries) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			step(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Halloween: Fehler in Phase {}", phase, e);
			mc.stop();
			phase = 999;
		}
	}

	/** TRS-Menü auf der Seite „Halloween“ (ModMenu#halloweenPage, sonst die Modulseite). */
	static Screen halloweenMenu(Screen parent, TrsModules modules) {
		TrsMenuScreen screen = new TrsMenuScreen(parent);
		try {
			Object ui = screen.ui();
			ui.getClass().getMethod("showHalloween").invoke(ui);
		} catch (ReflectiveOperationException | RuntimeException e) {
			log("halloweenPage() fehlt – Modulseite: " + e);
			screen.select(modules.halloween.halloween);
		}
		return screen;
	}

	/** Teilchen vor die Kamera: Fledermäuse aus einem „Ziel“ 4 Blöcke voraus und eine Geister-Spur quer davor. */
	private static void spawnFx(Player p) {
		double yaw = Math.toRadians(Mc.yRot(p));
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double x = Mc.x(p) + fx * 4;
		double z = Mc.z(p) + fz * 4;
		SpookyFx fxs = SpookyFx.get();
		fxs.hit(x, Mc.y(p) + 1.2, z);
		fxs.hit(x + fz * 0.8, Mc.y(p) + 1.0, z - fx * 0.8);
		long now = System.currentTimeMillis();
		for (int i = 0; i < 8; i++) {
			double side = -2.5 + i * 0.6;
			fxs.trail("autotest", Mc.x(p) + fx * 6 + fz * side, Mc.y(p), Mc.z(p) + fz * 6 - fx * side, Mc.yRot(p) + 90, now + i * 80L);
		}
	}

	private void step(Minecraft mc) {
		Screen screen = Mc.screen();
		TrsModules modules = TrsClient.get().modules();
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || screen == null) return;
				mc.options.pauseOnLostFocus = false;
				Mc.setScreen(new TrsTitleScreen());
				phase++;
				wait = 40;
				return;
			case 1:
				shot(mc, "title");
				// Hinweis „Flackern & Rauschen“ als gesehen markieren – zweites Bild ohne; Kürbis am Titel „finden“
				// (gegen die Attrappe: Toast „Kürbis gefunden!“).
				modules.halloween.local.markFlickerHintShown();
				HalloweenService.get().findPumpkin("client-title");
				phase++;
				wait = 10;
				return;
			case 2:
				shot(mc, "title-clean");
				Mc.setScreen(halloweenMenu(new TrsTitleScreen(), modules));
				phase++;
				wait = 25;
				return;
			case 3:
				shot(mc, "trsmenu-halloween");
				AutoTest.startWorld(mc);
				phase++;
				return;
			case 4:
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
			case 5:
				if (screen instanceof LevelLoadingScreen) shot(mc, "loading");
				phase++;
				return;
			case 6:
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null, 600)) return;
				wait = 40;
				phase++;
				return;
			case 7:
				spawnFx(mc.player);
				phase++;
				wait = 4;
				return;
			case 8:
				shot(mc, "ingame-fx");
				Mc.setCameraMode(2);
				phase++;
				wait = 10;
				return;
			case 9:
				Jumpscares.get().forceForTest(Jumpscares.Type.BEHIND_YOU, System.currentTimeMillis());
				phase++;
				wait = 3;
				return;
			case 10:
				shot(mc, "behind-you");
				// Flügel (Rücken): Ansicht von hinten, Zustände Ruhe / Luft / Schleichen.
				Mc.setCameraMode(1);
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = dev.theredstonee.trsclient.core.cosmetic.v2.WingState.IDLE;
				phase = 100;
				wait = 20;
				return;
			case 100:
				shot(mc, "wings-idle");
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = dev.theredstonee.trsclient.core.cosmetic.v2.WingState.AIR;
				phase++;
				wait = 20;
				return;
			case 101:
				shot(mc, "wings-air");
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = dev.theredstonee.trsclient.core.cosmetic.v2.WingState.SNEAK;
				phase++;
				wait = 20;
				return;
			case 102:
				shot(mc, "wings-sneak");
				dev.theredstonee.trsclient.core.cosmetic.v2.WingState.forced = -1;
				Mc.setCameraMode(0);
				phase = 11;
				wait = 10;
				return;
			case 11:
				Jumpscares.get().forceForTest(Jumpscares.Type.CHEST, System.currentTimeMillis());
				phase++;
				wait = 8;
				return;
			case 12:
				shot(mc, "jumpscare");
				phase++;
				wait = 40;
				return;
			case 13:
				Jumpscares.get().forceForTest(Jumpscares.Type.CAVE, System.currentTimeMillis());
				phase++;
				wait = 70;
				return;
			case 14:
				shot(mc, "cave-eyes");
				phase++;
				wait = 120;
				return;
			case 15:
				Halloween.force(null);
				log("fertig");
				phase++;
				mc.stop();
				return;
			default:
		}
	}
}

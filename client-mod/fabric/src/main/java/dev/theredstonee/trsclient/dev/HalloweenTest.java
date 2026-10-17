package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.halloween.Halloween;
import dev.theredstonee.trsclient.core.halloween.HalloweenService;
import dev.theredstonee.trsclient.core.halloween.Jumpscares;
import dev.theredstonee.trsclient.core.halloween.Miyako;
import dev.theredstonee.trsclient.core.halloween.SpookyFx;
import dev.theredstonee.trsclient.halloween.MiyakoVideoScreen;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.menus.MenuSkin;
import dev.theredstonee.trsclient.screen.TrsMenuScreen;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.WardrobeScreen;
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

	/** Ein einfaches 160×90-Schreckbild (dunkel, zwei glühende Augen, gezackter Mund) für den Offline-Test. */
	private static int[] fakeScareImage() {
		int w = 160;
		int h = 90;
		int[] px = new int[w * h];
		for (int i = 0; i < px.length; i++) px[i] = 0xFF0A0510;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				// Zwei Augen.
				if ((sq(x - 58) + sq(y - 38) < 120) || (sq(x - 102) + sq(y - 38) < 120)) px[i] = 0xFFFF3B2F;
				else if ((sq(x - 58) + sq(y - 38) < 240) || (sq(x - 102) + sq(y - 38) < 240)) px[i] = 0x66FF281E | 0xFF000000;
				// Gezackter Mund.
				if (y > 58 && y < 72 && x > 50 && x < 110 && ((x + (y % 4)) % 8 < 4)) px[i] = 0xFFB9A2EA;
			}
		}
		return px;
	}

	private static int sq(int v) {
		return v * v;
	}

	/** Liegt das echte Bild-Paket (frames/0001.jpg) im Cache? */
	private static boolean hasFrames() {
		java.nio.file.Path d = Miyako.get().framesDir();
		return d != null && java.nio.file.Files.isRegularFile(d.resolve("0001.jpg"));
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
				// Garderobe: Kürbis „hinter dir, wenn du dich umziehst“ muss neben den Füßen sichtbar sein.
				Mc.setScreen(WardrobeScreen.create(new TrsTitleScreen()));
				phase = 50;
				wait = 60;
				return;
			case 50:
				shot(mc, "wardrobe-pumpkin");
				Mc.setScreen(new TrsTitleScreen());
				AutoTest.startWorld(mc);
				phase = 4;
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
				phase = 200;
				wait = 40;
				return;
			// --- Schreck-Erweiterung 0.19.2: neue Jumpscare-Arten ---
			case 200:
				Jumpscares.get().forceForTest(Jumpscares.Type.FACE_PRESS, System.currentTimeMillis());
				phase++;
				wait = 4;
				return;
			case 201:
				shot(mc, "face-press");
				Jumpscares.get().forceForTest(Jumpscares.Type.WHISPER_CRAWL, System.currentTimeMillis());
				phase++;
				wait = 24;
				return;
			case 202:
				shot(mc, "whisper-crawl");
				Jumpscares.get().forceForTest(Jumpscares.Type.THIRTEENTH, System.currentTimeMillis());
				phase++;
				wait = 8;
				return;
			case 203:
				shot(mc, "thirteenth");
				Jumpscares.get().forceForTest(Jumpscares.Type.FLASH, System.currentTimeMillis());
				phase++;
				wait = 1;
				return;
			case 204:
				shot(mc, "flash");
				// MiyakoMC: Timer-Bild-Schreck (synthetisches Bild, damit es offline etwas zeigt).
				Miyako.get().setImageForTest(fakeScareImage(), 160, 90);
				Miyako.get().forceImageForTest(System.currentTimeMillis());
				phase++;
				wait = 4;
				return;
			case 205:
				shot(mc, "miyako-timer");
				// Echtes Intro-Video nur, wenn das Paket (Attrappe + -Dtrsclient.miyako.test) da ist.
				if (Miyako.get().videoSupported() && hasFrames()) {
					Mc.setScreen(new MiyakoVideoScreen(new TrsTitleScreen()));
					phase = 210;
					wait = 25;
				} else {
					log("kein Miyako-Paket – Video übersprungen");
					phase = 15;
					wait = 5;
				}
				return;
			case 210:
				shot(mc, "miyako-video-1");
				phase++;
				wait = 60;
				return;
			case 211:
				shot(mc, "miyako-video-2");
				Mc.setScreen(new TrsTitleScreen());
				phase = 15;
				wait = 5;
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

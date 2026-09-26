package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.social.SocialOverlay;
import dev.theredstonee.trsclient.core.social.Toasts;
import dev.theredstonee.trsclient.core.ui.social.QuickReplyUi;
import dev.theredstonee.trsclient.core.ui.social.SocialUi;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Selbsttest Sozial ({@code -PtrsAutotestOnly=social}, mit API-Attrappe über {@code -PtrsApi}): Sozial-Bildschirm vom
 * Titelbildschirm (Liste, Unterhaltung, Kontextmenü, Bildauswahl, Melden, Bild groß, Gruppe, Freunde), danach in einer
 * kleinen Testwelt drei Toasts und die Schnellantwort. Jede Phase ist abgesichert (Fehler → Log, weiter). Beendet das
 * Spiel danach. Screenshots: trsclient-&lt;mc&gt;-social-*.png.
 */
public final class SocialTest {
	private static final String BOB = "069a79f444e94726a5befca90e38aaf5";
	private int phase;
	private int wait;
	private int waited;
	/** Nur Toasts neben Vanilla-Toasts und über einem Menü ({@code -PtrsAutotestOnly=socialtoasts}). */
	private boolean toastsOnly;

	public static void install() {
		SocialTest test = new SocialTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	/** Kurzer Lauf: Testwelt, Vanilla-Toasts + Sozial-Toasts (Ausweichen), dann über dem Pausemenü. */
	public static void installToasts() {
		SocialTest test = new SocialTest();
		test.toastsOnly = true;
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Sozial: {}", text);
	}

	private static void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-social-" + name);
		log("Bild " + name);
	}

	private boolean waitFor(boolean ready, int maxTries) {
		if (!ready && waited++ < maxTries) {
			wait = 5;
			return false;
		}
		waited = 0;
		return true;
	}

	private static SocialUi social(Screen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof SocialUi ? (SocialUi) ((TrsUiScreen) s).ui() : null;
	}

	private static QuickReplyUi quick(Screen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof QuickReplyUi ? (QuickReplyUi) ((TrsUiScreen) s).ui() : null;
	}

	private void tick(Minecraft mc) {
		if (wait > 0) {
			wait--;
			return;
		}
		int current = phase;
		try {
			step(mc);
		} catch (RuntimeException | LinkageError e) {
			TrsClient.LOGGER.error("[Autotest] Sozial: Fehler in Phase {} – weiter", current, e);
			if (phase == current) phase++;
			wait = 5;
		}
		if (phase > 100) {
			phase = -1000;
			mc.stop();
		}
	}

	private void step(Minecraft mc) {
		Screen screen = Mc.screen();
		SocialUi ui = social(screen);
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || screen == null) return;
				mc.options.pauseOnLostFocus = false;
				//? if >=1.19 {
				mc.options.renderDistance().set(2);
				//?}
				Mc.setScreen(new TrsTitleScreen());
				phase = toastsOnly ? 20 : 1;
				wait = 20;
				return;
			case 1:
				Mc.setScreen(MenuScreens.social(new TrsTitleScreen()));
				phase++;
				wait = 5;
				return;
			case 2:
				// Liste geladen (Anmeldung an der Attrappe + GET /v1/chat/conversations), höchstens ~10 s.
				if (!waitFor(ui != null && ui.ready(), 200)) return;
				wait = 30; // Gesichter
				phase++;
				return;
			case 3:
				shot(mc, "list");
				if (ui != null && !ui.openConversationNamed("Bob")) log("keine Unterhaltung in der Liste");
				phase++;
				wait = 60;
				return;
			case 4:
				shot(mc, "chat");
				if (ui != null) ui.testContextMenu();
				phase++;
				wait = 5;
				return;
			case 5:
				shot(mc, "menu");
				if (ui != null) {
					ui.testCloseOverlays();
					ui.testPicker(true);
				}
				phase++;
				wait = 40;
				return;
			case 6:
				shot(mc, "picker");
				if (ui != null) {
					ui.testPicker(false);
					ui.testReport();
				}
				phase++;
				wait = 5;
				return;
			case 7:
				shot(mc, "report");
				if (ui != null) {
					ui.testCloseOverlays();
					ui.testLightbox();
				}
				phase++;
				wait = 40;
				return;
			case 8:
				shot(mc, "lightbox");
				if (ui != null) {
					ui.testCloseOverlays();
					ui.testGroupDialog();
				}
				phase++;
				wait = 10;
				return;
			case 9:
				shot(mc, "group");
				if (ui != null) {
					ui.testCloseOverlays();
					ui.showTab(1);
				}
				phase++;
				wait = 30;
				return;
			case 10:
				shot(mc, "friends");
				Mc.setScreen(null);
				AutoTest.startWorld(mc);
				phase++;
				return;
			case 11:
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null, 600)) return;
				wait = 40;
				phase++;
				return;
			case 12: {
				String conv = firstConversation();
				// Einladung zuerst, dann die Nachricht: die Schnelltaste nimmt den neuesten (= Antworten).
				boolean b = SocialOverlay.testToast(Toasts.Kind.INVITE, "Carla", "Lädt dich ein: Survival", null,
						"c00000000000000000002", new Chat.Invite("play.trs-test.net", "Survival"));
				boolean a = SocialOverlay.testToast(Toasts.Kind.MESSAGE, "Bob", "Hi! Kommst du auf den Server?", BOB, conv, null);
				boolean c = SocialOverlay.testToast(Toasts.Kind.ONLINE, "Emil", "ist jetzt online", null, null, null);
				log("Toasts " + a + "/" + b + "/" + c + " (Unterhaltung " + conv + ")");
				phase++;
				wait = 20;
				return;
			}
			case 13:
				shot(mc, "toasts");
				log("zuletzt gezeichnet vor " + (System.currentTimeMillis() - SocialOverlay.lastFrame()) + " ms");
				SocialOverlay.QuickAction a = SocialOverlay.takeQuickAction();
				if (a == null) a = SocialOverlay.QuickAction.reply(firstConversation(), "Bob");
				Mc.setScreen(MenuScreens.socialAction(a, null));
				phase++;
				wait = 5;
				return;
			case 14: {
				QuickReplyUi q = quick(screen);
				if (q != null) q.testType("Bin gleich da");
				else log("Schnellantwort nicht offen: " + screen);
				phase++;
				wait = 10;
				return;
			}
			case 15:
				shot(mc, "quickreply");
				Mc.setScreen(null);
				log("fertig");
				phase = 101;
				return;
			// --- nur Toasts (Ausweichen vor Vanilla-Toasts, über Menüs) ---
			case 20:
				Mc.setScreen(null);
				AutoTest.startWorld(mc);
				phase++;
				return;
			case 21: {
				TrsOnline online = TrsOnline.current();
				boolean socialReady = online != null && online.social() != null;
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null && socialReady, 600)) return;
				wait = 40;
				phase++;
				return;
			}
			case 22:
				log("Vanilla-Toasts: " + vanillaToasts(mc));
				phase++;
				wait = 12; // Vanilla fährt herein
				return;
			case 23: {
				boolean msg = SocialOverlay.testToast(Toasts.Kind.MESSAGE, "Bob", "Hi! Kommst du auf den Server?", BOB, firstConversation(), null);
				boolean on = SocialOverlay.testToast(Toasts.Kind.ONLINE, "Emil", "ist jetzt online", null, null, null);
				log("Toasts " + msg + "/" + on);
				phase++;
				wait = 24;
				return;
			}
			case 24:
				shot(mc, "avoid");
				log("Vanilla-Unterkante " + SocialOverlay.lastVanillaBottom() + " px, Versatz " + SocialOverlay.avoidOffset()
						+ " px, zuletzt gezeichnet vor " + (System.currentTimeMillis() - SocialOverlay.lastFrame()) + " ms");
				Mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
				phase++;
				wait = 10;
				return;
			case 25:
				shot(mc, "avoid-menu");
				log("über Menü: zuletzt gezeichnet vor " + (System.currentTimeMillis() - SocialOverlay.lastFrame()) + " ms");
				Mc.setScreen(null);
				log("fertig");
				phase = 101;
				return;
			default:
		}
	}

	/** Zwei Vanilla-System-Toasts oben rechts (ab 1.20.3; darunter nicht Teil des Tests). */
	private static String vanillaToasts(Minecraft mc) {
		//? if >=26.2 {
		/*net.minecraft.client.gui.components.toasts.ToastManager tm = mc.gui.toastManager();
		*///?} elif >=1.21.2 {
		/*net.minecraft.client.gui.components.toasts.ToastManager tm = mc.getToastManager();
		*///?} elif >=1.20.3 {
		net.minecraft.client.gui.components.toasts.ToastComponent tm = mc.getToasts();
		//?} else {
		/*return "in dieser Version nicht im Test";
		*///?}
		//? if >=1.20.3 {
		net.minecraft.network.chat.Component title = net.minecraft.network.chat.Component.literal("Vanilla-Toast");
		net.minecraft.client.gui.components.toasts.SystemToast.add(tm,
				net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title,
				net.minecraft.network.chat.Component.literal("Rezept/Tutorial/Fortschritt"));
		net.minecraft.client.gui.components.toasts.SystemToast.add(tm,
				net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.WORLD_BACKUP, title,
				net.minecraft.network.chat.Component.literal("zweiter Platz"));
		return "2";
		//?}
	}

	private static String firstConversation() {
		TrsOnline online = TrsOnline.current();
		if (online == null) return null;
		List<Chat.Conversation> list = online.social().store().sorted();
		return list.isEmpty() ? null : list.get(0).id;
	}
}

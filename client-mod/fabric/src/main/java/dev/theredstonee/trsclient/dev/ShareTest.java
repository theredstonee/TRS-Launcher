package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.online.TrsOnline;
import dev.theredstonee.trsclient.core.social.Chat;
import dev.theredstonee.trsclient.core.ui.WaypointSaveUi;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import dev.theredstonee.trsclient.core.ui.social.SocialUi;
import dev.theredstonee.trsclient.core.waypoint.Waypoint;
import dev.theredstonee.trsclient.core.waypoint.WaypointShare;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;

/**
 * Selbsttest Teilen-Paket ({@code -PtrsAutotestOnly=share}, gegen die lokal gebaute API über {@code -PtrsApi}):
 * Koordinaten und „[Als Link teilen]“ im Minecraft-Chat, Fenster „Als Wegpunkt speichern“, Wegpunkt-Karten im
 * Sozial-Chat (senden, übernehmen, auf der Weltkarte zeigen), Zielauswahl, Bild als Link teilen und „Geteilt“ in
 * Clips &amp; Bilder. Ab 1.16 (davor keine Chat-Links). Screenshots: trsclient-&lt;mc&gt;-share-*.png. Beendet das Spiel.
 */
public final class ShareTest {
	private int phase;
	private int wait;
	private int waited;
	private Chat.Waypoint card;

	public static void install() {
		ShareTest test = new ShareTest();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(test::tick);
	}

	private static void log(String text) {
		TrsClient.LOGGER.info("[Autotest] Teilen: {}", text);
	}

	private static void shot(Minecraft mc, String name) {
		AutoTest.shot(mc, "trsclient-share-" + name);
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

	private static ClipsUi clips(Screen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof ClipsUi ? (ClipsUi) ((TrsUiScreen) s).ui() : null;
	}

	private static WaypointSaveUi saveUi(Screen s) {
		return s instanceof TrsUiScreen && ((TrsUiScreen) s).ui() instanceof WaypointSaveUi ? (WaypointSaveUi) ((TrsUiScreen) s).ui() : null;
	}

	private static boolean signedIn() {
		TrsOnline online = TrsOnline.current();
		return online != null && online.social() != null && online.social().signedIn();
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
			TrsClient.LOGGER.error("[Autotest] Teilen: Fehler in Phase {} – weiter", current, e);
			if (phase == current) phase++;
			wait = 5;
		}
		if (phase > 100) {
			phase = -1000;
			// Zwischenablage des Nutzers wiederherstellen (der Test kopiert Links hinein).
			if (savedClipboard != null) mc.keyboardHandler.setClipboard(savedClipboard);
			mc.stop();
		}
	}

	private String savedClipboard;

	private void step(Minecraft mc) {
		Screen screen = Mc.screen();
		switch (phase) {
			case 0:
				if (Mc.overlay() != null || screen == null) return;
				mc.options.pauseOnLostFocus = false;
				//? if >=1.19 {
				mc.options.renderDistance().set(2);
				//?}
				TrsClient.get().modules().worldMap.setEnabled(true);
				savedClipboard = mc.keyboardHandler.getClipboard();
				Mc.setScreen(null);
				AutoTest.startWorld(mc);
				phase++;
				return;
			case 1:
				if (!waitFor(mc.level != null && mc.player != null && Mc.screen() == null && signedIn(), 600)) return;
				wait = 60;
				phase++;
				return;
			case 2: {
				// Echtes Bildschirmfoto (für „[Als Link teilen]“ und „Clips & Bilder“), dann zwei Chatzeilen.
				shot(mc, "world");
				phase++;
				wait = 20;
				return;
			}
			case 3: {
				ChatLines.addMessage(Mc.text("<Bob> Treffpunkt: x: 120 y: 70 z: -35, Portal 100 64 -20"));
				File file = new File(mc.gameDirectory, "screenshots/trsclient-" + AutoTest.MC_VERSION + "-share-world.png");
				Component line = screenshotLine(file);
				if (line != null) ChatLines.addMessage(line);
				log("Bildschirmfoto-Zeile: " + (line != null) + ", Datei da: " + file.isFile());
				Mc.setScreen(chatScreen());
				phase++;
				wait = 10;
				return;
			}
			case 4:
				shot(mc, "chat-links");
				// Klick auf die markierten Koordinaten (Stil aus der geschmückten Zeile).
				log("Koordinaten-Klick: " + clickInsertion(Mc.text("x: 120 y: 70 z: -35"), "120 70 -35"));
				phase++;
				wait = 10;
				return;
			case 5: {
				WaypointSaveUi ui = saveUi(screen);
				log("Speichern-Fenster offen: " + (ui != null));
				shot(mc, "save-popup");
				if (ui != null) ui.testSave("Treffpunkt");
				phase++;
				wait = 10;
				return;
			}
			case 6: {
				boolean found = false;
				for (Waypoint w : dev.theredstonee.trsclient.core.map.MapEngine.get().waypoints()) {
					if (w.name.equals("Treffpunkt") && w.x == 120 && w.y == 70 && w.z == -35) found = true;
				}
				log("Wegpunkt aus dem Chat gespeichert: " + found);
				// „[Als Link teilen]“ an der Bildschirmfoto-Zeile.
				File file = new File(mc.gameDirectory, "screenshots/trsclient-" + AutoTest.MC_VERSION + "-share-world.png");
				Component line = screenshotLine(file);
				Component decorated = line == null ? null : dev.theredstonee.trsclient.qol.ChatLinks.decorate(line);
				log("Teilen-Klick: " + (decorated != null && clickInsertion(decorated, "trs-share:")));
				phase++;
				return;
			}
			case 7: {
				// Upload läuft; die Rückmeldung kommt als Chatzeile + Zwischenablage.
				String clip = mc.keyboardHandler.getClipboard();
				if (!waitFor(clip != null && clip.contains("/s/"), 120)) return;
				log("Zwischenablage: " + clip);
				Mc.setScreen(chatScreen());
				phase++;
				wait = 10;
				return;
			}
			case 8:
				shot(mc, "chat-shared");
				Mc.setScreen(MenuScreens.social(null));
				phase++;
				wait = 10;
				return;
			case 9: {
				SocialUi ui = social(screen);
				if (!waitFor(ui != null && ui.ready(), 200)) return;
				if (ui != null && !ui.openConversationNamed("Bob")) log("keine Unterhaltung mit Bob");
				phase++;
				wait = 60;
				return;
			}
			case 10: {
				SocialUi ui = social(screen);
				card = WaypointShare.here("Testpunkt");
				log("eigene Karte: " + (card == null ? "keine" : card.coords() + " " + card.dimension + " welt=" + card.worldId));
				if (ui != null && card != null) ui.testSendWaypoint(card);
				phase++;
				wait = 60;
				return;
			}
			case 11:
				shot(mc, "social-card");
				if (card != null) {
					log("Bobs Karte (Server) passt: " + WaypointShare.check(
							Chat.Waypoint.of("Bobs Basis", 1204, 71, -388, "minecraft:overworld", "server", "play.example.net", null, null)));
					log("Übernehmen eigene Karte: " + WaypointShare.adopt(card));
				}
				phase++;
				wait = 5;
				return;
			case 12: {
				SocialUi ui = social(screen);
				Chat.Waypoint spot = WaypointShare.card("Aussichtspunkt", 40, 90, 12, WaypointShare.currentDimension(),
						WaypointShare.currentWorldKey(), 0x4DD8E0);
				if (ui != null && spot != null) ui.testShareTarget(spot);
				phase++;
				wait = 20;
				return;
			}
			case 13:
				shot(mc, "share-target");
				log("Anzeigen: " + (card == null ? "-" : WaypointShare.show(card)));
				phase++;
				wait = 40;
				return;
			case 14:
				shot(mc, "map-focus");
				Mc.setScreen(MenuScreens.clips(null));
				phase++;
				wait = 30;
				return;
			case 15: {
				ClipsUi ui = clips(screen);
				if (!waitFor(ui != null && ui.settled(), 100)) return;
				if (ui != null) {
					ui.openFirstImage();
					log("Als Link teilen: " + ui.testShareFirst());
				}
				phase++;
				wait = 5;
				return;
			}
			case 16: {
				ClipsUi ui = clips(screen);
				if (!waitFor(ui != null && !ui.testSharing(), 120)) return;
				log("Meldung: " + (ui == null ? "-" : ui.testNotice()));
				wait = 5;
				phase++;
				return;
			}
			case 17: {
				shot(mc, "clips-shared");
				ClipsUi ui = clips(screen);
				if (ui != null) ui.testShowShared();
				phase++;
				wait = 80;
				return;
			}
			case 18:
				shot(mc, "clips-list");
				Mc.setScreen(null);
				log("fertig");
				phase = 101;
				return;
			default:
		}
	}

	private static Screen chatScreen() {
		//? if >=1.21.9 {
		/*return new net.minecraft.client.gui.screens.ChatScreen("", false);
		*///?} else
		return new net.minecraft.client.gui.screens.ChatScreen("");
	}

	/** Wie die Vanilla-Zeile „Bildschirmfoto gespeichert als …“ (Dateiname mit „Datei öffnen“). */
	private static Component screenshotLine(File file) {
		//? if >=1.21.5 {
		/*return Mc.text("Saved screenshot as ").append(Mc.text(file.getName()).withStyle(s -> s.withUnderlined(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenFile(file))));
		*///?} elif >=1.16 {
		return Mc.text("Saved screenshot as ").append(Mc.text(file.getName()).withStyle(s -> s.withUnderlined(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.OPEN_FILE,
						file.getAbsolutePath()))));
		//?} else
		/*return null;*/
	}

	/** Geschmückte Komponente durchsuchen und den Stil mit passendem Einfüge-Text „anklicken“. */
	private static boolean clickInsertion(Component raw, String insertionPrefix) {
		//? if >=1.16 {
		Component c = raw.getString().startsWith("x:") ? dev.theredstonee.trsclient.qol.ChatLinks.decorate(raw) : raw;
		final net.minecraft.network.chat.Style[] hit = {null};
		c.visit((net.minecraft.network.chat.FormattedText.StyledContentConsumer<Object>) (style, text) -> {
			if (hit[0] == null && style.getInsertion() != null && style.getInsertion().startsWith(insertionPrefix)) hit[0] = style;
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return hit[0] != null && dev.theredstonee.trsclient.qol.ChatLinks.onClick(hit[0]);
		//?} else
		/*return false;*/
	}
}

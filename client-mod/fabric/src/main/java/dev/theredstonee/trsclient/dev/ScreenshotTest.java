package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.compat.Keys;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.screenshot.Screenshots;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import dev.theredstonee.trsclient.core.ui.screenshot.ScreenshotEditorUi;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Selbsttest „Screenshot-Werkzeuge“ ({@code -PtrsAutotestOnly=screenshots}): echter F2-Druck durch Minecrafts
 * Tastaturverarbeitung → Vorschau oben rechts, Chatzeile mit Aktionen; Chat öffnen → Vorschau mit Knöpfen und
 * Tooltip, Bild über der Chatzeile, eigene Zeile (wie mit Essential); Klick auf „Bearbeiten“ → Editor mit Formen,
 * Zuschnitt + Drehung, Teilen-Menü, Kopie speichern; Favorit → Reiter „Favoriten“ in Clips &amp; Bilder.
 * Bilder: trsclient-&lt;mc&gt;-screenshots-*.png. „Bild kopieren“ nur mit {@code -Dtrsclient.autotest.clipboard=true}
 * (sonst würde der Test die Zwischenablage des Rechners überschreiben).
 */
public final class ScreenshotTest {
	private int phase;
	private int wait;
	private int waited;
	private Path shot;
	private ScreenshotEditorUi editor;

	/** Ein Tick; true = noch nicht fertig. */
	public boolean step(Minecraft mc, TrsModules modules, CapeTest.Actions actions) {
		if (mc.player == null) return false;
		if (wait > 0) {
			wait--;
			return true;
		}
		Screenshots s = Screenshots.get();
		switch (phase) {
			case 0:
				if (s == null) {
					if (waited++ > 100) throw new IllegalStateException("Screenshot-Dienst fehlt");
					return true;
				}
				actions.command("time set 1000");
				actions.command("weather clear");
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.comfort.screenshots.setEnabled(true);
				modules.comfort.shotToast.set(true);
				modules.comfort.shotSeconds.set(15);
				modules.comfort.shotChatActions.set(true);
				Mc.setScreen(null);
				phase++;
				wait = 30;
				return true;
			case 1:
				// Echter F2-Druck (KeyboardHandler → Screenshot.grab → Datei + Vanilla-Chatzeile).
				AutoTest.key(mc, Keys.code("key.keyboard.f2"));
				phase++;
				waited = 0;
				return true;
			case 2:
				if (!s.toast().visible()) {
					if (waited++ > 200) throw new IllegalStateException("keine Vorschau nach F2");
					return true;
				}
				shot = s.toast().file();
				TrsClient.LOGGER.info("[Autotest] Screenshot erkannt: {} (Chat: {})", shot.getFileName(), chatLine(mc));
				phase++;
				wait = 20;
				return true;
			case 3:
				actions.shot("trsclient-screenshots-toast");
				//? if >=1.21.9 {
				/*Mc.setScreen(new ChatScreen("", false));
				*///?} else
				Mc.setScreen(new ChatScreen(""));
				phase++;
				wait = 5;
				return true;
			case 4: {
				int[] c = s.toast().center();
				if (c != null) AutoTest.moveMouse(mc, c[0], c[1]);
				phase++;
				wait = 10;
				return true;
			}
			case 5: {
				TrsClient.LOGGER.info("[Autotest] Vorschau überfahren: Knöpfe {}", s.toast().hovered());
				actions.shot("trsclient-screenshots-toast-hover");
				int[] b = s.toast().buttonCenter(0);
				if (b != null) AutoTest.moveMouse(mc, b[0], b[1]);
				phase++;
				wait = 8;
				return true;
			}
			case 6: {
				actions.shot("trsclient-screenshots-toast-tooltip");
				// Maus über die Chatzeile mit dem Screenshot → Bild-Vorschau am Zeiger.
				int[] line = chatLineAt(mc);
				if (line != null) AutoTest.moveMouse(mc, line[0], line[1]);
				TrsClient.LOGGER.info("[Autotest] Chatzeile unter der Maus: {}", line == null ? "-" : "y=" + line[1]);
				phase++;
				wait = 12;
				return true;
			}
			case 7: {
				actions.shot("trsclient-screenshots-chat-hover");
				// Eigene Zeile (wie mit Essential, das die Vanilla-Zeile verschluckt).
				String rel = s.relative(shot);
				boolean own = rel != null && s.platform().addChatLine(rel);
				TrsClient.LOGGER.info("[Autotest] eigene Chatzeile: {}", own);
				AutoTest.moveMouse(mc, 0, 0);
				phase++;
				wait = 8;
				return true;
			}
			case 8: {
				actions.shot("trsclient-screenshots-chat-own");
				// Klick auf „Bearbeiten“ (oben links in der Vorschau) – ein echter Mausklick.
				int[] c = s.toast().center();
				if (c != null) AutoTest.moveMouse(mc, c[0], c[1]);
				phase++;
				wait = 4;
				return true;
			}
			case 9: {
				int[] b = s.toast().buttonCenter(0);
				if (b != null) AutoTest.click(mc, b[0], b[1]);
				else s.edit(shot);
				phase++;
				waited = 0;
				return true;
			}
			case 10:
				editor = editor();
				if (editor == null || !editor.ready()) {
					if (waited++ > 300) throw new IllegalStateException("Editor lädt nicht");
					return true;
				}
				actions.shot("trsclient-screenshots-editor-empty");
				editor.testDraw();
				phase++;
				waited = 0;
				return true;
			case 11:
				if (!editor.ready()) {
					if (waited++ > 300) throw new IllegalStateException("Editor-Vorschau fehlt");
					return true;
				}
				actions.shot("trsclient-screenshots-editor");
				editor.testTool(0);
				editor.testCropRotate();
				phase++;
				waited = 0;
				return true;
			case 12:
				if (!editor.ready()) {
					if (waited++ > 300) throw new IllegalStateException("Editor-Vorschau (gedreht) fehlt");
					return true;
				}
				actions.shot("trsclient-screenshots-editor-crop");
				editor.testTool(1);
				editor.testShareMenu();
				phase++;
				wait = 6;
				return true;
			case 13:
				actions.shot("trsclient-screenshots-editor-share");
				editor.testSave();
				phase++;
				waited = 0;
				return true;
			case 14:
				if (editor.testSaving() || editor.testSaved() == null) {
					if (waited++ > 400) throw new IllegalStateException("Kopie nicht gespeichert: " + editor.testNotice());
					return true;
				}
				try {
					TrsClient.LOGGER.info("[Autotest] Kopie gespeichert: {} ({} Bytes), Original noch da: {}",
							editor.testSaved().getFileName(), Files.size(editor.testSaved()), Files.exists(shot));
				} catch (java.io.IOException e) {
					TrsClient.LOGGER.error("[Autotest] Kopie unlesbar", e);
				}
				actions.shot("trsclient-screenshots-editor-saved");
				Mc.setScreen(null);
				phase++;
				wait = 5;
				return true;
			case 15: {
				// Favorit → Reiter „Favoriten“ in Clips & Bilder.
				boolean wasFav = s.isFavorite(shot);
				if (!wasFav) s.run(Screenshots.Action.FAVORITE, shot, null);
				TrsClient.LOGGER.info("[Autotest] Favorit: {}", s.isFavorite(shot));
				Mc.setScreen(MenuScreens.clips(null));
				ClipsUi ui = clipsUi();
				if (ui != null) ui.testShowFavorites();
				phase++;
				waited = 0;
				return true;
			}
			case 16: {
				ClipsUi ui = clipsUi();
				if (ui != null && !ui.settled() && waited++ < 200) return true;
				actions.shot("trsclient-screenshots-favorites");
				if (Boolean.getBoolean("trsclient.autotest.clipboard")) {
					s.run(Screenshots.Action.COPY, shot, (text, error) -> TrsClient.LOGGER.info("[Autotest] Bild kopieren: {} ({})", text,
							error ? "Fehler" : "ok"));
					wait = 40;
				}
				phase++;
				return true;
			}
			case 17:
				// Aufräumen: Favorit wieder entfernen, Bildschirm schließen.
				if (s.isFavorite(shot)) s.run(Screenshots.Action.FAVORITE, shot, null);
				Mc.setScreen(null);
				phase++;
				return false;
			default:
				return false;
		}
	}

	private static ScreenshotEditorUi editor() {
		if (Mc.screen() instanceof TrsUiScreen && ((TrsUiScreen) Mc.screen()).ui() instanceof ScreenshotEditorUi) {
			return (ScreenshotEditorUi) ((TrsUiScreen) Mc.screen()).ui();
		}
		return null;
	}

	private static ClipsUi clipsUi() {
		if (Mc.screen() instanceof TrsUiScreen && ((TrsUiScreen) Mc.screen()).ui() instanceof ClipsUi) {
			return (ClipsUi) ((TrsUiScreen) Mc.screen()).ui();
		}
		return null;
	}

	/** Text der neuesten Chatzeile (für das Protokoll). */
	private static String chatLine(Minecraft mc) {
		int[] at = chatLineAt(mc);
		return at == null ? "-" : ChatLines.lineAt(at[0], at[1], Mc.window().getGuiScaledHeight());
	}

	/** GUI-Position über der Chatzeile mit einem Bildschirmfoto-Namen oder null. */
	private static int[] chatLineAt(Minecraft mc) {
		int h = Mc.window().getGuiScaledHeight();
		for (int y = h - 42; y > h / 3; y--) {
			String line = ChatLines.lineAt(12, y, h);
			if (line != null && line.contains(".png")) return new int[]{12, y};
		}
		return null;
	}
}

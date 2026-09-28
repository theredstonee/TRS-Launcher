package dev.theredstonee.trsclient.dev;

import dev.theredstonee.trsclient.TrsClient;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.HudModule;
import dev.theredstonee.trsclient.core.module.Module;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.screenshot.Screenshots;
import dev.theredstonee.trsclient.core.ui.clips.ClipsUi;
import dev.theredstonee.trsclient.core.ui.screenshot.ScreenshotEditorUi;
import dev.theredstonee.trsclient.screen.MenuScreens;
import dev.theredstonee.trsclient.screen.TrsTitleScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Selbsttest „Screenshot-Werkzeuge“ unter 1.8.9–1.12.2 ({@code -PtrsAutotestOnly=screenshots}): Bildschirmfoto wie
 * mit F2 (Vanilla-Weg inkl. Chatzeile) → Vorschau + Aktionszeile, Chat öffnen → Knöpfe/Tooltip/Bild über der Zeile,
 * „Bearbeiten“ → Editor (Formen, Zuschnitt + Drehung, Teilen-Menü, Kopie speichern), Favorit → Clips &amp; Bilder.
 * Bilder: trsclient-&lt;mc&gt;-screenshots-*.png.
 */
public final class ScreenshotTest {
	private final String mcVersion = Mc.version();
	private final String world = "trs-shots-" + Mc.version();
	private int phase = -1;
	private int wait;
	private int waited;
	private Path shot;
	private ScreenshotEditorUi editor;
	private dev.theredstonee.trsclient.core.config.TrsConfig before;

	public static void install() {
		MinecraftForge.EVENT_BUS.register(new ScreenshotTest());
	}

	@SubscribeEvent
	public void onTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			tick(mc);
		} catch (RuntimeException e) {
			TrsClient.LOGGER.error("[Autotest] Screenshots: Fehler", e);
			phase = 999;
			mc.shutdown();
		}
	}

	private void tick(Minecraft mc) {
		GuiScreen screen = mc.currentScreen;
		TrsModules modules = TrsClient.get().modules();
		Screenshots s = Screenshots.get();
		switch (phase) {
			case -1:
				if (!(screen instanceof TrsTitleScreen) && !(screen instanceof GuiMainMenu)) return;
				mc.gameSettings.pauseOnLostFocus = false;
				mc.getSaveLoader().deleteWorldDirectory(world);
				mc.launchIntegratedServer(world, world, Mc.creativeWorld(20260928L));
				phase++;
				return;
			case 0:
				if (Mc.world() == null || Mc.player() == null || mc.currentScreen != null || s == null) {
					if (waited++ > 900) throw new IllegalStateException("Welt lädt nicht");
					return;
				}
				before = modules.registry.capture();
				for (Module m : modules.registry.all()) {
					if (m instanceof HudModule) m.setEnabled(false);
				}
				modules.comfort.screenshots.setEnabled(true);
				modules.comfort.shotToast.set(true);
				modules.comfort.shotSeconds.set(15);
				modules.comfort.shotChatActions.set(true);
				phase++;
				wait = 30;
				return;
			case 1:
				// Wie F2: Vanilla speichert unter screenshots/<datum>.png und schreibt die Zeile in den Chat.
				mc.ingameGUI.getChatGUI().printChatMessage(ScreenShotHelper.saveScreenshot(Mc.gameDir(), mc.displayWidth,
						mc.displayHeight, mc.getFramebuffer()));
				phase++;
				waited = 0;
				return;
			case 2:
				if (!s.toast().visible()) {
					if (waited++ > 200) throw new IllegalStateException("keine Vorschau nach dem Screenshot");
					return;
				}
				shot = s.toast().file();
				TrsClient.LOGGER.info("[Autotest] Screenshot erkannt: {}", shot.getFileName());
				phase++;
				wait = 30;
				return;
			case 3:
				shot(mc, "screenshots-toast");
				mc.displayGuiScreen(new GuiChat());
				phase++;
				wait = 5;
				return;
			case 4: {
				int[] c = s.toast().center();
				if (c != null) mouse(mc, c[0], c[1]);
				phase++;
				wait = 10;
				return;
			}
			case 5: {
				TrsClient.LOGGER.info("[Autotest] Vorschau überfahren: Knöpfe {}", s.toast().hovered());
				shot(mc, "screenshots-toast-hover");
				int[] b = s.toast().buttonCenter(0);
				if (b != null) mouse(mc, b[0], b[1]);
				phase++;
				wait = 8;
				return;
			}
			case 6: {
				shot(mc, "screenshots-toast-tooltip");
				// Neueste Chatzeile = die Aktionszeile („» [Bearbeiten] …“) → Bild am Zeiger.
				ScaledResolution res = Mc.scaledResolution();
				mouse(mc, 20, res.getScaledHeight() - 44);
				phase++;
				wait = 12;
				return;
			}
			case 7: {
				shot(mc, "screenshots-chat-hover");
				int[] c = s.toast().center();
				if (c != null) mouse(mc, c[0], c[1]);
				phase = 70;
				wait = 5;
				return;
			}
			case 70: {
				// Klick auf „Bearbeiten“ (oben links in der Vorschau).
				int[] b = s.toast().buttonCenter(0);
				if (b == null || !s.mouseClicked(b[0], b[1], 0)) s.edit(shot);
				phase = 8;
				waited = 0;
				return;
			}
			case 8:
				editor = editor(mc);
				if (editor == null || !editor.ready()) {
					if (waited++ > 300) throw new IllegalStateException("Editor lädt nicht");
					return;
				}
				shot(mc, "screenshots-editor-empty");
				editor.testDraw();
				phase++;
				waited = 0;
				return;
			case 9:
				if (!editor.ready()) {
					if (waited++ > 300) throw new IllegalStateException("Editor-Vorschau fehlt");
					return;
				}
				shot(mc, "screenshots-editor");
				editor.testTool(0);
				editor.testCropRotate();
				phase++;
				waited = 0;
				return;
			case 10:
				if (!editor.ready()) {
					if (waited++ > 300) throw new IllegalStateException("Editor-Vorschau (gedreht) fehlt");
					return;
				}
				shot(mc, "screenshots-editor-crop");
				editor.testTool(1);
				editor.testShareMenu();
				phase++;
				wait = 6;
				return;
			case 11:
				shot(mc, "screenshots-editor-share");
				editor.testSave();
				phase++;
				waited = 0;
				return;
			case 12:
				if (editor.testSaving() || editor.testSaved() == null) {
					if (waited++ > 400) throw new IllegalStateException("Kopie nicht gespeichert: " + editor.testNotice());
					return;
				}
				try {
					TrsClient.LOGGER.info("[Autotest] Kopie gespeichert: {} ({} Bytes), Original noch da: {}",
							editor.testSaved().getFileName(), Files.size(editor.testSaved()), Files.exists(shot));
				} catch (java.io.IOException e) {
					TrsClient.LOGGER.error("[Autotest] Kopie unlesbar", e);
				}
				shot(mc, "screenshots-editor-saved");
				if (!s.isFavorite(shot)) s.run(Screenshots.Action.FAVORITE, shot, null);
				mc.displayGuiScreen(MenuScreens.clips(null));
				if (clipsUi(mc) != null) clipsUi(mc).testShowFavorites();
				phase++;
				waited = 0;
				return;
			case 13: {
				ClipsUi ui = clipsUi(mc);
				if (ui != null && !ui.settled() && waited++ < 200) return;
				shot(mc, "screenshots-favorites");
				if (s.isFavorite(shot)) s.run(Screenshots.Action.FAVORITE, shot, null);
				if (before != null) {
					modules.registry.apply(before);
					TrsClient.get().saveConfig();
				}
				TrsClient.LOGGER.info("[Autotest] Screenshots: fertig");
				phase = 999;
				mc.displayGuiScreen(new GuiMainMenu());
				wait = 10;
				return;
			}
			case 999:
				mc.shutdown();
				return;
			default:
		}
	}

	private static ScreenshotEditorUi editor(Minecraft mc) {
		if (mc.currentScreen instanceof TrsUiScreen && ((TrsUiScreen) mc.currentScreen).ui() instanceof ScreenshotEditorUi) {
			return (ScreenshotEditorUi) ((TrsUiScreen) mc.currentScreen).ui();
		}
		return null;
	}

	private static ClipsUi clipsUi(Minecraft mc) {
		if (mc.currentScreen instanceof TrsUiScreen && ((TrsUiScreen) mc.currentScreen).ui() instanceof ClipsUi) {
			return (ClipsUi) ((TrsUiScreen) mc.currentScreen).ui();
		}
		return null;
	}

	/** LWJGL-Maus an eine GUI-Position (Ursprung unten links). */
	private static void mouse(Minecraft mc, int gx, int gy) {
		ScaledResolution res = Mc.scaledResolution();
		int px = gx * mc.displayWidth / res.getScaledWidth();
		int py = mc.displayHeight - 1 - gy * mc.displayHeight / res.getScaledHeight();
		Mouse.setCursorPosition(px, py);
	}

	private void shot(Minecraft mc, String name) {
		String file = "trsclient-" + mcVersion + "-" + name + ".png";
		ScreenShotHelper.saveScreenshot(Mc.gameDir(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		TrsClient.LOGGER.info("[Autotest] Screenshot {}", file);
	}
}

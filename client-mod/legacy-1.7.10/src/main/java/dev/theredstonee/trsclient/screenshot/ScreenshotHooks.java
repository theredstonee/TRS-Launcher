package dev.theredstonee.trsclient.screenshot;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.screenshot.Screenshots;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.screen.TrsMenuHost;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.ui.BrandCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Mouse;

import java.nio.file.Path;

/**
 * Screenshot-Werkzeuge in Forge 1.7.10: Vorschau im HUD und über jedem Bildschirm, Bild-Editor, Favoriten, Bild
 * kopieren. 1.7.10 hat kein abbrechbares Maus-Ereignis für Bildschirme – ein Klick auf die Vorschau wird am Wechsel der
 * linken Maustaste erkannt (der Bildschirm darunter bekommt ihn ebenfalls; oben rechts liegt dort meist nichts). Ohne
 * TRS Online (Link/Senden „in dieser Version nicht verfügbar“) und ohne eigene Chatzeile.
 */
public final class ScreenshotHooks {
	private static volatile Screenshots service;
	private static TrsModules modules;
	private static boolean wasDown;

	private ScreenshotHooks() {
	}

	static final Screenshots.Platform PLATFORM = new Screenshots.Platform() {
		@Override
		public Path gameDir() {
			return Minecraft.getMinecraft().mcDataDir.toPath().toAbsolutePath();
		}

		@Override
		public Path configDir() {
			return gameDir().resolve("config");
		}

		@Override
		public boolean openUi(String title, UiScreen ui) {
			Minecraft.getMinecraft().displayGuiScreen(new TrsUiScreen(ui));
			return true;
		}

		@Override
		public void closeUi() {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.currentScreen instanceof TrsUiScreen) mc.displayGuiScreen(null);
		}

		@Override
		public boolean copyText(String text) {
			GuiScreen.setClipboardString(text);
			return true;
		}

		@Override
		public void notice(String text) {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.ingameGUI != null) mc.ingameGUI.func_110326_a(text, false);
		}

		@Override
		public void playClick() {
			new TrsMenuHost(null).playClick();
		}

		@Override
		public boolean online() {
			return false;
		}
	};

	/** Beim Start des Mods: Ereignisse anmelden (der Dienst startet beim ersten Tick). */
	public static void install(TrsModules m) {
		modules = m;
		MinecraftForge.EVENT_BUS.register(new Render());
		FMLCommonHandler.instance().bus().register(new Ticks());
	}

	/** Tick-Events kommen in 1.7.10 nur über den FML-Bus. */
	public static final class Ticks {
		@SubscribeEvent
		public void onTick(TickEvent.ClientTickEvent event) {
			if (event.phase != TickEvent.Phase.END) return;
			Screenshots s = service;
			if (s == null && modules != null) {
				try {
					s = service = Screenshots.install(PLATFORM, modules);
				} catch (RuntimeException | LinkageError e) {
					s = null;
				}
				modules = null;
			}
			if (s != null) s.tick();
		}
	}

	/** Zeichnen (Forge-Bus). */
	public static final class Render {
		@SubscribeEvent
		public void onOverlay(RenderGameOverlayEvent.Post event) {
			if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
			draw(event.resolution.getScaledWidth(), event.resolution.getScaledHeight(), -1, -1, false);
		}

		@SubscribeEvent(priority = EventPriority.LOWEST)
		public void onScreenDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
			GuiScreen gui = event.gui;
			if (gui == null) return;
			draw(gui.width, gui.height, event.mouseX, event.mouseY, true);
			// Klick = Wechsel der linken Maustaste auf „gedrückt“.
			boolean down = Mouse.isButtonDown(0);
			Screenshots s = service;
			if (down && !wasDown && s != null) s.mouseClicked(event.mouseX, event.mouseY, 0);
			wasDown = down;
		}
	}

	private static void draw(int width, int height, int mx, int my, boolean mouseFree) {
		Screenshots s = service;
		if (s == null || !s.active()) return;
		try {
			BrandCanvas c = BrandCanvas.of(Minecraft.getMinecraft().fontRenderer);
			c.push();
			c.raise(410f);
			s.render(c, width, height, mx, my, mouseFree);
			c.pop();
		} catch (RuntimeException ignored) {
			// darf nie stören
		}
	}
}

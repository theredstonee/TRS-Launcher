package dev.theredstonee.trsclient.screenshot;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.screenshot.Screenshots;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.screen.TrsMenuHost;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.ui.BrandCanvas;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Path;

/**
 * Screenshot-Werkzeuge in Forge 1.13.2: Vorschau im HUD und über jedem Bildschirm, Klick über
 * {@code MouseClickedEvent.Pre} (abbrechbar), Bild-Editor, Favoriten, Bild kopieren. Ohne TRS Online (Link/Senden
 * „in dieser Version nicht verfügbar“) und ohne eigene Chatzeile.
 */
public final class ScreenshotHooks {
	private static volatile Screenshots service;
	private static TrsModules modules;

	private ScreenshotHooks() {
	}

	static final Screenshots.Platform PLATFORM = new Screenshots.Platform() {
		@Override
		public Path gameDir() {
			return Minecraft.getInstance().gameDir.toPath().toAbsolutePath();
		}

		@Override
		public Path configDir() {
			return gameDir().resolve("config");
		}

		@Override
		public boolean openUi(String title, UiScreen ui) {
			Minecraft.getInstance().displayGuiScreen(new TrsUiScreen(ui));
			return true;
		}

		@Override
		public void closeUi() {
			Minecraft mc = Minecraft.getInstance();
			if (mc.currentScreen instanceof TrsUiScreen) mc.displayGuiScreen(null);
		}

		@Override
		public boolean copyText(String text) {
			Minecraft.getInstance().keyboardListener.setClipboardString(text);
			return true;
		}

		@Override
		public void notice(String text) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.ingameGUI != null) mc.ingameGUI.setOverlayMessage(text, false);
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
		net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ScreenshotHooks.Events());
	}

	/** Forge-Ereignisse. */
	public static final class Events {
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

		@SubscribeEvent
		public void onOverlay(RenderGameOverlayEvent.Post event) {
			if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
			draw(mc.mainWindow.getScaledWidth(), mc.mainWindow.getScaledHeight(), -1, -1, false);
		}

		@SubscribeEvent(priority = EventPriority.LOWEST)
		public void onScreenDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
			if (event.getGui() == null) return;
			draw(event.getGui().width, event.getGui().height, event.getMouseX(), event.getMouseY(), true);
		}

		@SubscribeEvent(priority = EventPriority.HIGHEST)
		public void onClick(GuiScreenEvent.MouseClickedEvent.Pre event) {
			Screenshots s = service;
			if (s != null && s.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) event.setCanceled(true);
		}
	}

	private static void draw(int width, int height, int mx, int my, boolean mouseFree) {
		Screenshots s = service;
		if (s == null || !s.active()) return;
		try {
			BrandCanvas c = BrandCanvas.of(Minecraft.getInstance().fontRenderer);
			c.push();
			c.raise(410f);
			s.render(c, width, height, mx, my, mouseFree);
			c.pop();
		} catch (RuntimeException ignored) {
			// darf nie stören
		}
	}
}

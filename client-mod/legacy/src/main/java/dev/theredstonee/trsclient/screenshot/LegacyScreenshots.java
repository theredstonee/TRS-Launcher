package dev.theredstonee.trsclient.screenshot;

import dev.theredstonee.trsclient.compat.ChatCompat;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.screenshot.Screenshots;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

import java.nio.file.Path;

/**
 * Screenshot-Werkzeuge in Legacy-Forge 1.8.9–1.12.2: Vorschau im HUD ({@code RenderGameOverlayEvent.Post}) und über
 * jedem Bildschirm ({@code DrawScreenEvent.Post}), Klick über {@code MouseInputEvent.Pre} (abbrechbar), eigene
 * Chatzeile mit Aktionen (die Vanilla-Zeile lässt sich hier nicht abfangen – sie geht direkt an den Chat), Bild beim
 * Überfahren der Chatzeile.
 */
public final class LegacyScreenshots {
	private static volatile Screenshots service;
	private static TrsModules modules;

	private LegacyScreenshots() {
	}

	static final Screenshots.Platform PLATFORM = new Screenshots.Platform() {
		@Override
		public Path gameDir() {
			return Mc.gameDir().toPath().toAbsolutePath();
		}

		@Override
		public Path configDir() {
			return gameDir().resolve("config");
		}

		@Override
		public boolean openUi(String title, UiScreen ui) {
			Mc.setScreen(new TrsUiScreen(title, ui));
			return true;
		}

		@Override
		public void closeUi() {
			if (Mc.screen() instanceof TrsUiScreen) Mc.setScreen(null);
		}

		@Override
		public boolean copyText(String text) {
			Mc.setClipboard(text);
			return true;
		}

		@Override
		public void notice(String text) {
			Mc.actionBar(text);
		}

		@Override
		public void playClick() {
			Mc.clickSound();
		}

		@Override
		public boolean addChatLine(String relativeName) {
			Screenshots s = service;
			if (s == null) return false;
			// Die Vanilla-Zeile „Screenshot gespeichert als …“ geht hier direkt an den Chat und lässt sich nicht
			// erweitern: ohne Essential nur die Aktionen darunter, mit Essential (verschluckt die Vanilla-Zeile) die
			// ganze Zeile.
			boolean essential = dev.theredstonee.trsclient.core.screenshot.EssentialInterop.present();
			ChatCompat.printScreenshotLine(essential ? s.chatLine(relativeName) : s.compactLine(relativeName));
			return true;
		}
	};

	/** Beim Start des Mods (der Dienst startet beim ersten Tick). */
	public static void install(TrsModules m) {
		modules = m;
	}

	/** Client-Tick (Ende). */
	public static void tick() {
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

	/** Im HUD (ohne offenen Bildschirm). */
	public static void hud(int width, int height) {
		if (Mc.screen() != null || Mc.hudHidden()) return;
		draw(width, height, -1, -1, false);
	}

	/** Nach einem Bildschirm (Maus frei); über der Chatzeile die Bild-Vorschau. */
	public static void afterScreen(GuiScreen screen) {
		Screenshots s = service;
		if (s == null || screen == null) return;
		int[] m = mouse(screen);
		if (screen instanceof GuiChat) {
			try {
				String ins = ChatCompat.insertionAt(Mouse.getX(), Mouse.getY());
				if (ins != null) s.chatHover(ins, m[0], m[1]);
				else s.chatHover(ChatCompat.componentAt(Mouse.getX(), Mouse.getY()), m[0], m[1]);
			} catch (RuntimeException ignored) {
				// ohne Vorschau weiter
			}
		}
		draw(screen.width, screen.height, m[0], m[1], true);
	}

	private static void draw(int width, int height, int mx, int my, boolean mouseFree) {
		Screenshots s = service;
		if (s == null || !s.active()) return;
		try {
			Gfx g = Gfx.of(width, height);
			GfxCanvas c = GfxCanvas.of(g, Mc.font());
			c.push();
			c.raise(410f);
			s.render(c, width, height, mx, my, mouseFree);
			c.pop();
		} catch (RuntimeException ignored) {
			// darf nie stören
		}
	}

	/** Maus-Ereignis in einem Bildschirm (MouseInputEvent.Pre): true = verbraucht (abbrechen). */
	public static boolean onMouse(GuiScreen screen) {
		Screenshots s = service;
		if (s == null || screen == null || !Mouse.getEventButtonState()) return false;
		int[] m = eventMouse(screen);
		return s.mouseClicked(m[0], m[1], Mouse.getEventButton());
	}

	/** Klick im Chat auf unsere Marker (Einfüge-Text): true = verbraucht. */
	public static boolean onChatClick() {
		Screenshots s = service;
		if (s == null) return false;
		try {
			return s.onMarker(ChatCompat.insertionAt(Mouse.getX(), Mouse.getY()));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static int[] mouse(GuiScreen s) {
		Minecraft mc = Mc.mc();
		int x = Mouse.getX() * s.width / Math.max(1, mc.displayWidth);
		int y = s.height - Mouse.getY() * s.height / Math.max(1, mc.displayHeight) - 1;
		return new int[]{x, y};
	}

	private static int[] eventMouse(GuiScreen s) {
		Minecraft mc = Mc.mc();
		int x = Mouse.getEventX() * s.width / Math.max(1, mc.displayWidth);
		int y = s.height - Mouse.getEventY() * s.height / Math.max(1, mc.displayHeight) - 1;
		return new int[]{x, y};
	}
}

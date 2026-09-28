package dev.theredstonee.trsclient.screenshot;

import com.mojang.blaze3d.platform.Window;
import dev.theredstonee.trsclient.compat.ChatLines;
import dev.theredstonee.trsclient.compat.Mc;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.screenshot.Screenshots;
import dev.theredstonee.trsclient.core.ui.UiScreen;
import dev.theredstonee.trsclient.qol.ChatLinks;
import dev.theredstonee.trsclient.screen.TrsMenuHost;
import dev.theredstonee.trsclient.screen.TrsUiScreen;
import dev.theredstonee.trsclient.ui.Gfx;
import dev.theredstonee.trsclient.ui.GfxCanvas;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;

/**
 * Anbindung der Screenshot-Werkzeuge ({@link Screenshots}) an Minecraft: Plattform (Ordner, Editor öffnen,
 * Zwischenablage, Aktionsleiste, Chatzeile), Zeichnen der Vorschau im HUD und nach jedem Bildschirm (aus
 * {@code SocialHooks}), Mausklick bei offenem Bildschirm (MouseHandlerMixin) und die Bild-Vorschau über der Chatzeile.
 * Dieselbe Datei in allen Mojmap-Bäumen (Fabric, NeoForge, Forge, Forge-Mojmap-Legacy).
 */
public final class ScreenshotHooks {
	private static volatile Screenshots service;
	private static boolean chatHoverBroken;

	private ScreenshotHooks() {
	}

	static final Screenshots.Platform PLATFORM = new Screenshots.Platform() {
		@Override
		public Path gameDir() {
			return Mc.mc().gameDirectory.toPath().toAbsolutePath();
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
			ChatLinks.notice(text);
		}

		@Override
		public void playClick() {
			new TrsMenuHost(null).playClick();
		}

		@Override
		public boolean addChatLine(String relativeName) {
			return ChatLinks.addScreenshotLine(relativeName);
		}

		@Override
		public boolean decoratesVanillaLine() {
			return ChatLinks.DECORATES;
		}
	};

	private static TrsModules modules;

	/** Beim Start des Mods (der Dienst startet beim ersten Tick – dann gibt es Spielordner und Fenster sicher). */
	public static void install(TrsModules m) {
		modules = m;
	}

	/** Client-Tick. */
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

	/** Ist etwas zu zeichnen (billig)? */
	public static boolean active() {
		Screenshots s = service;
		return s != null && (s.active() || Mc.screen() instanceof ChatScreen);
	}

	/**
	 * Zeichnen: im HUD ({@code mouseFree} false) bzw. nach einem Bildschirm (true – dann reagiert die Vorschau auf die
	 * Maus und über der Chatzeile erscheint das Bild).
	 */
	public static void draw(final Gfx g, boolean raise, final boolean mouseFree) {
		final Screenshots s = service;
		if (s == null) return;
		try {
			final int w = g.width();
			final int h = g.height();
			final double[] m = mouseFree ? guiMouse() : new double[]{-1, -1};
			if (mouseFree && !chatHoverBroken && Mc.screen() instanceof ChatScreen) {
				try {
					String line = ChatLines.lineAt(m[0], m[1], h);
					if (line != null && m[0] < chatWidth()) s.chatHover(line, (int) m[0], (int) m[1]);
				} catch (RuntimeException | LinkageError e) {
					chatHoverBroken = true;
				}
			}
			if (!s.active()) return;
			if (raise) {
				g.overlayLayer();
				g.push();
				g.raise(460f);
			}
			try {
				final GfxCanvas c = GfxCanvas.of(g, Mc.mc().font);
				g.managed(new Runnable() {
					@Override
					public void run() {
						s.render(c, w, h, (int) m[0], (int) m[1], mouseFree);
					}
				});
			} finally {
				if (raise) g.pop();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Die Vorschau darf das Spiel nie stören.
		}
	}

	/** Maustaste gedrückt (MouseHandlerMixin, vor dem Bildschirm): true = von der Vorschau verbraucht. */
	public static boolean onMousePress(int button) {
		Screenshots s = service;
		Screen screen = Mc.screen();
		if (s == null || screen == null) return false;
		try {
			double[] m = guiMouse();
			return s.mouseClicked(m[0], m[1], button);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Mausposition in GUI-Koordinaten. */
	static double[] guiMouse() {
		Window w = Mc.window();
		double x = Mc.mc().mouseHandler.xpos() * w.getGuiScaledWidth() / Math.max(1, w.getScreenWidth());
		double y = Mc.mc().mouseHandler.ypos() * w.getGuiScaledHeight() / Math.max(1, w.getScreenHeight());
		return new double[]{x, y};
	}

	/** Rechte Kante des Chats (Breite aus den Optionen × Chat-Größe, großzügig). */
	private static double chatWidth() {
		return 4 + 330 * Math.max(0.3, Mc.chatScale());
	}
}

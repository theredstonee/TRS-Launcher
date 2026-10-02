package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.ui.BorderlessPlan;
import dev.theredstonee.trsclient.core.ui.BorderlessState;
import dev.theredstonee.trsclient.core.ui.WindowMemory;
import net.minecraft.client.Minecraft;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Randloses Vollbild unter LWJGL 2. {@code org.lwjgl.opengl.Window.undecorated} gilt nur beim Erzeugen des Fensters,
 * darum wird die Anzeige einmal neu aufgebaut. Die Vollbild-Option bleibt an, {@code Display.setFullscreen(false)}
 * nimmt nur den exklusiven Modus weg.
 */
public final class BorderlessDisplay {
	private static final String UNDECORATED = "org.lwjgl.opengl.Window.undecorated";
	private static final WindowMemory MEMORY = new WindowMemory();
	private static DisplayMode windowedMode;

	private BorderlessDisplay() {
	}

	public static void tick(Minecraft mc) {
		if (mc == null) return;
		try {
			boolean exclusive = Display.isFullscreen();
			boolean borderless = BorderlessState.active();
			boolean option = mc.gameSettings != null && mc.gameSettings.fullScreen;
			if (!exclusive && !borderless) {
				windowedMode = Display.getDisplayMode();
				MEMORY.track(Display.getX(), Display.getY(), Display.getWidth(), Display.getHeight());
			}
			switch (BorderlessPlan.step(BorderlessHooks.want(), option, exclusive, borderless)) {
				case ENTER:
					enter(mc);
					break;
				case RESTORE_WINDOW:
					restore(mc);
					break;
				case TO_EXCLUSIVE:
					toExclusive(mc);
					break;
				default:
					break;
			}
		} catch (Throwable ignored) {
		}
	}

	private static void enter(Minecraft mc) throws LWJGLException {
		if (!MEMORY.saved()) MEMORY.track(8, 31, 854, 480);
		System.setProperty(UNDECORATED, "true");
		Display.setFullscreen(false);
		forceMode(Display.getDesktopDisplayMode());
		Display.setLocation(0, 0);
		BorderlessState.mark(true);
		resize(mc);
	}

	private static void restore(Minecraft mc) throws LWJGLException {
		System.setProperty(UNDECORATED, "false");
		DisplayMode mode = windowedMode;
		if (mode == null) mode = new DisplayMode(MEMORY.saved() ? MEMORY.width() : 854, MEMORY.saved() ? MEMORY.height() : 480);
		forceMode(mode);
		if (Display.isFullscreen()) Display.setFullscreen(false);
		if (MEMORY.saved()) Display.setLocation(MEMORY.x(), MEMORY.y());
		BorderlessState.mark(false);
		MEMORY.clear();
		resize(mc);
	}

	private static void toExclusive(Minecraft mc) throws LWJGLException {
		System.setProperty(UNDECORATED, "false");
		Display.setFullscreen(true);
		BorderlessState.mark(false);
		resize(mc);
	}

	/** Gleiche Auflösung baut das Fenster nicht neu – dann käme der Rahmen nicht an. */
	private static void forceMode(DisplayMode mode) throws LWJGLException {
		if (mode == null) return;
		DisplayMode now = Display.getDisplayMode();
		if (now != null && now.getWidth() == mode.getWidth() && now.getHeight() == mode.getHeight() && !Display.isFullscreen()) {
			Display.setDisplayMode(new DisplayMode(mode.getWidth(), Math.max(1, mode.getHeight() - 1)));
		}
		Display.setDisplayMode(mode);
	}

	private static void resize(Minecraft mc) {
		mc.displayWidth = Math.max(1, Display.getWidth());
		mc.displayHeight = Math.max(1, Display.getHeight());
		mc.resize(mc.displayWidth, mc.displayHeight);
	}
}

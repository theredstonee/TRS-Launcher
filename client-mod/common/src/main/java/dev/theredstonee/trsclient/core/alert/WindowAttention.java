package dev.theredstonee.trsclient.core.alert;

import java.lang.reflect.Method;

/**
 * Taskleisten-Blinken („Aufmerksamkeit anfordern“), ohne feste Abhängigkeit: GLFW (Minecraft 1.13–26.2, LWJGL 3,
 * {@code glfwRequestWindowAttention}) bzw. SDL (ab 26.3, {@code SDL_FlashWindow} bis zum Fokus). Legacy-Versionen
 * (LWJGL 2) haben so etwas nicht – dort wird es weggelassen. Jeder Fehler (fehlende Funktion, alte native Bibliothek)
 * wird geschluckt und schaltet das Blinken für den Rest der Sitzung ab.
 */
public final class WindowAttention {
	/** SDL_FLASH_UNTIL_FOCUSED */
	private static final int SDL_FLASH_UNTIL_FOCUSED = 2;
	private static volatile boolean broken;
	private static Method glfw;
	private static Method sdl;
	private static boolean looked;

	private WindowAttention() {
	}

	/** Gibt es eine Möglichkeit (LWJGL 3 / SDL geladen)? */
	public static boolean available() {
		lookup();
		return !broken && (glfw != null || sdl != null);
	}

	/**
	 * Lässt das Fenster in der Taskleiste blinken, bis es den Fokus bekommt. Nur aus dem Render-/Haupt-Thread aufrufen.
	 *
	 * @param handle Fenster-Handle (GLFW- bzw. SDL-Fenster), 0 = nichts tun
	 * @return true, wenn angefordert
	 */
	public static boolean request(long handle) {
		if (handle == 0 || broken) return false;
		lookup();
		try {
			if (sdl != null) {
				sdl.invoke(null, Long.valueOf(handle), Integer.valueOf(SDL_FLASH_UNTIL_FOCUSED));
				return true;
			}
			if (glfw != null) {
				glfw.invoke(null, Long.valueOf(handle));
				return true;
			}
		} catch (Throwable t) {
			broken = true;
		}
		return false;
	}

	private static synchronized void lookup() {
		if (looked) return;
		looked = true;
		try {
			Class<?> c = Class.forName("org.lwjgl.sdl.SDLVideo");
			sdl = c.getMethod("SDL_FlashWindow", long.class, int.class);
		} catch (Throwable ignored) {
			sdl = null;
		}
		if (sdl != null) return;
		try {
			Class<?> c = Class.forName("org.lwjgl.glfw.GLFW");
			glfw = c.getMethod("glfwRequestWindowAttention", long.class);
		} catch (Throwable ignored) {
			glfw = null;
		}
	}
}

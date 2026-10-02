package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.core.ui.BorderlessPlan;
import dev.theredstonee.trsclient.core.ui.BorderlessState;
import dev.theredstonee.trsclient.core.ui.WindowMemory;
import net.minecraft.client.MainWindow;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

/** Randloses Vollbild für 1.13.2 (LWJGL 3, ohne Mixin). */
public final class BorderlessGlfw {
	private static final int DECORATED = 0x20005;
	private static final int DONT_CARE = -1;
	private static final WindowMemory MEMORY = new WindowMemory();

	private BorderlessGlfw() {
	}

	public static void sync(Minecraft mc) {
		if (mc == null || mc.mainWindow == null) return;
		try {
			MainWindow main = mc.mainWindow;
			long window = main.getHandle();
			if (window == 0L) return;
			boolean option = mc.gameSettings != null && mc.gameSettings.fullScreen;
			long monitor = GLFW.glfwGetWindowMonitor(window);
			boolean exclusive = monitor != 0L;
			boolean borderless = BorderlessState.active();
			if (!exclusive && !borderless) {
				int[] x = new int[1];
				int[] y = new int[1];
				int[] w = new int[1];
				int[] h = new int[1];
				GLFW.glfwGetWindowPos(window, x, y);
				GLFW.glfwGetWindowSize(window, w, h);
				MEMORY.track(x[0], y[0], w[0], h[0]);
			}
			switch (BorderlessPlan.step(BorderlessHooks.want(), option, exclusive, borderless)) {
				case ENTER:
					enter(window, monitor);
					break;
				case RESTORE_WINDOW:
					restore(window);
					break;
				case TO_EXCLUSIVE:
					toExclusive(window);
					break;
				default:
					break;
			}
		} catch (Throwable ignored) {
		}
	}

	private static void enter(long window, long monitor) {
		if (monitor == 0L) return;
		int[] mx = new int[1];
		int[] my = new int[1];
		GLFW.glfwGetMonitorPos(monitor, mx, my);
		int[] w = new int[1];
		int[] h = new int[1];
		GLFW.glfwGetWindowSize(window, w, h);
		if (w[0] < 1 || h[0] < 1) {
			GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
			if (mode == null) return;
			w[0] = mode.width();
			h[0] = mode.height();
		}
		if (!MEMORY.saved()) MEMORY.track(mx[0] + 8, my[0] + 31, 854, 480);
		GLFW.glfwSetWindowAttrib(window, DECORATED, 0);
		BorderlessState.mark(true);
		GLFW.glfwSetWindowMonitor(window, 0L, mx[0], my[0], w[0], h[0], DONT_CARE);
	}

	private static void restore(long window) {
		int x = MEMORY.saved() ? MEMORY.x() : 8;
		int y = MEMORY.saved() ? MEMORY.y() : 31;
		int w = MEMORY.saved() ? MEMORY.width() : 854;
		int h = MEMORY.saved() ? MEMORY.height() : 480;
		GLFW.glfwSetWindowAttrib(window, DECORATED, 1);
		BorderlessState.mark(false);
		MEMORY.clear();
		GLFW.glfwSetWindowMonitor(window, 0L, x, y, w, h, DONT_CARE);
	}

	private static void toExclusive(long window) {
		long monitor = GLFW.glfwGetPrimaryMonitor();
		PointerBuffer monitors = GLFW.glfwGetMonitors();
		int[] wx = new int[1];
		int[] wy = new int[1];
		GLFW.glfwGetWindowPos(window, wx, wy);
		if (monitors != null) {
			for (int i = 0; i < monitors.limit(); i++) {
				long mon = monitors.get(i);
				int[] mx = new int[1];
				int[] my = new int[1];
				GLFW.glfwGetMonitorPos(mon, mx, my);
				GLFWVidMode mode = GLFW.glfwGetVideoMode(mon);
				if (mode == null) continue;
				if (wx[0] >= mx[0] && wy[0] >= my[0] && wx[0] < mx[0] + mode.width() && wy[0] < my[0] + mode.height()) {
					monitor = mon;
					break;
				}
			}
		}
		GLFWVidMode mode = monitor == 0L ? null : GLFW.glfwGetVideoMode(monitor);
		if (mode == null) return;
		GLFW.glfwSetWindowAttrib(window, DECORATED, 1);
		BorderlessState.mark(false);
		GLFW.glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
	}
}

package dev.theredstonee.trsclient.perf;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.LowLatency;
import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.core.perf.Performance;
import net.minecraft.client.Minecraft;

/**
 * „Niedrige Eingabeverzögerung“ für die Mojmap-Bäume (dieselbe Datei in Fabric, NeoForge, Forge): GPU-Zäune über
 * LWJGL/OpenGL 3.2 und das späte Lesen der Fenster-Ereignisse. Die Logik steht in {@link LowLatency}.
 */
public final class LatencyHooks {
	private static LowLatency latency;
	private static TrsModules modules;
	/** Messwert des letzten Bildes (für den Autotest). */
	public static volatile long lastWaitNanos;
	private static long polls;
	/** Messung (Autotest/Modulseite): Beginn des Bildes (Vanilla hat die Eingaben gerade davor gelesen). */
	private static long frameHead;

	private LatencyHooks() {
	}

	public static void init(TrsModules m) {
		modules = m;
		latency = new LowLatency(new GlFences());
		//? if >=26.3 {
		/*latency.latePollSupported = false;
		*///?} else
		latency.latePollSupported = true;
	}

	public static LowLatency get() {
		return latency;
	}

	private static boolean active() {
		TrsModules m = modules;
		if (m == null || !m.lowLatency.isEnabled()) return false;
		Performance p = Performance.current();
		return p == null || p.active(PerfFeature.LOW_LATENCY);
	}

	/** Am Anfang jedes Bildes (aus PerfHooks.beforeFrame, nach der FPS-Grenze). */
	public static void frameStart() {
		LowLatency ll = latency;
		if (ll == null) return;
		frameHead = System.nanoTime();
		try {
			boolean on = active();
			lastWaitNanos = ll.frameStart(on ? modules.lowLatencyMode.get() : null, ll.measuring(System.currentTimeMillis()));
		} catch (RuntimeException | LinkageError e) {
			// nie das Spiel stören
		}
	}

	/** Direkt bevor Minecraft die Mausbewegung anwendet (LatencyMixin). */
	public static void beforeMouse() {
		LowLatency ll = latency;
		if (ll == null) return;
		long now = System.nanoTime();
		boolean poll = ll.latePollSupported && active() && modules.lowLatencyLatePoll.get();
		if (poll) {
			try {
				//? if <26.3 {
				org.lwjgl.glfw.GLFW.glfwPollEvents();
				polls++;
				//?}
			} catch (RuntimeException | LinkageError e) {
				ll.latePollSupported = false;
				poll = false;
			}
		}
		// Alter der Eingaben beim Anwenden: ohne spätes Lesen seit dem letzten Lesen durch Vanilla (Bildanfang).
		if (ll.measuring(System.currentTimeMillis()) && frameHead != 0) ll.inputAge(poll ? 0 : now - frameHead);
	}

	/** Wie oft die Ereignisse spät gelesen wurden (Autotest). */
	public static long polls() {
		return polls;
	}

	/** Je Tick: Zustand der Vanilla-Option „Maus-Rohdaten“ für die Modulseite. */
	public static void tick(Minecraft mc) {
		LowLatency ll = latency;
		if (ll == null || mc.options == null) return;
		try {
			//? if >=26.3 {
			/*ll.rawMouse = 2;
			*///?} elif >=1.19 {
			/*ll.rawMouse = com.mojang.blaze3d.platform.InputConstants.isRawMouseInputSupported()
					? (mc.options.rawMouseInput().get() ? 1 : 0) : -1;
			*///?} else {
			ll.rawMouse = com.mojang.blaze3d.platform.InputConstants.isRawMouseInputSupported()
					? (mc.options.rawMouseInput ? 1 : 0) : -1;
			//?}
		} catch (RuntimeException | LinkageError e) {
			ll.rawMouse = -1;
		}
	}

	/** OpenGL-Zäune (ab OpenGL 3.2 bzw. ARB_sync) – im Render-Thread. */
	static final class GlFences implements LowLatency.Gpu {
		private static final int SYNC_GPU_COMMANDS_COMPLETE = 0x9117;
		private static final int SYNC_FLUSH_COMMANDS_BIT = 0x1;
		private static final int ALREADY_SIGNALED = 0x911A;
		private static final int CONDITION_SATISFIED = 0x911C;
		private Boolean supported;

		@Override
		public boolean supported() {
			if (supported == null) {
				org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
				supported = caps.OpenGL32 || caps.GL_ARB_sync;
			}
			return supported;
		}

		@Override
		public long fence() {
			return org.lwjgl.opengl.GL32.glFenceSync(SYNC_GPU_COMMANDS_COMPLETE, 0);
		}

		@Override
		public boolean await(long fence, long timeoutNanos) {
			int r = org.lwjgl.opengl.GL32.glClientWaitSync(fence, SYNC_FLUSH_COMMANDS_BIT, timeoutNanos);
			return r == ALREADY_SIGNALED || r == CONDITION_SATISFIED;
		}

		@Override
		public boolean signaled(long fence) {
			int r = org.lwjgl.opengl.GL32.glClientWaitSync(fence, 0, 0L);
			return r == ALREADY_SIGNALED || r == CONDITION_SATISFIED;
		}

		@Override
		public void delete(long fence) {
			org.lwjgl.opengl.GL32.glDeleteSync(fence);
		}
	}
}

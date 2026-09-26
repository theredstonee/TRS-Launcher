package dev.theredstonee.trsclient.perf;

import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.perf.LowLatency;
import dev.theredstonee.trsclient.core.perf.PerfFeature;
import dev.theredstonee.trsclient.core.perf.Performance;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * „Niedrige Eingabeverzögerung“ für Forge 1.8.9–1.12.2 (LWJGL 2). Aufruf zu Beginn des Bildes ({@code RenderTickEvent}
 * START), also nach den Ticks und direkt bevor {@code EntityRenderer} die Maus liest:
 * <ol>
 *   <li>GPU-Zaun: höchstens 0/1 fertige Bilder in der Warteschlange der Grafikkarte ({@link LowLatency}).</li>
 *   <li>Fenster-Nachrichten + Maus neu lesen ({@code Display.processMessages}, {@code Mouse.poll}): Vanilla schläft bei
 *   einer FPS-Grenze nach dem Lesen ({@code Display.sync}) – ohne das wäre die Mausbewegung um diese Wartezeit alt.</li>
 * </ol>
 * Maus-Rohdaten (Raw Input) kann LWJGL 2 nicht – die Modulseite sagt das ehrlich.
 */
public final class LegacyLatency {
	private static LowLatency latency;
	private static TrsModules modules;
	public static volatile long lastWaitNanos;
	private static long polls;
	/** Ende des vorigen Bildes (danach liest Vanilla die Eingaben, dann schläft Display.sync bei einer FPS-Grenze). */
	private static long lastFrameEnd;

	private LegacyLatency() {
	}

	public static void init(TrsModules m) {
		modules = m;
		latency = new LowLatency(new Fences());
		latency.latePollSupported = true;
		latency.rawMouse = -1;
	}

	public static LowLatency get() {
		return latency;
	}

	public static long polls() {
		return polls;
	}

	private static boolean active() {
		TrsModules m = modules;
		if (m == null || !m.lowLatency.isEnabled()) return false;
		Performance p = Performance.current();
		return p == null || p.active(PerfFeature.LOW_LATENCY);
	}

	/** RenderTickEvent START (nach der FPS-Grenze von LegacyPerf). */
	public static void frameStart() {
		LowLatency ll = latency;
		if (ll == null) return;
		try {
			boolean on = active();
			lastWaitNanos = ll.frameStart(on ? modules.lowLatencyMode.get() : null, ll.measuring(System.currentTimeMillis()));
			boolean poll = on && modules.lowLatencyLatePoll.get() && Display.isCreated() && Mouse.isCreated();
			if (poll) {
				Display.processMessages();
				Mouse.poll();
				polls++;
			}
			// Alter der Mausbewegung, wenn gleich gelesen wird: ohne spätes Lesen seit dem Ende des vorigen Bildes.
			if (ll.measuring(System.currentTimeMillis()) && lastFrameEnd != 0) ll.inputAge(poll ? 0 : System.nanoTime() - lastFrameEnd);
		} catch (RuntimeException | LinkageError e) {
			// nie das Spiel stören
		}
	}

	/** RenderTickEvent END: danach tauscht Vanilla die Bilder und liest die Eingaben (Display.update). */
	public static void frameEnd() {
		lastFrameEnd = System.nanoTime();
	}

	/** OpenGL-Zäune über LWJGL 2 ({@link GLSync}-Objekte, hier auf Zahlen abgebildet). */
	static final class Fences implements LowLatency.Gpu {
		private static final int SYNC_GPU_COMMANDS_COMPLETE = 0x9117;
		private static final int SYNC_FLUSH_COMMANDS_BIT = 0x1;
		private static final int ALREADY_SIGNALED = 0x911A;
		private static final int CONDITION_SATISFIED = 0x911C;
		private final GLSync[] slots = new GLSync[16];
		private long next = 1;
		private Boolean supported;

		@Override
		public boolean supported() {
			if (supported == null) {
				ContextCapabilities caps = GLContext.getCapabilities();
				supported = caps.OpenGL32 || caps.GL_ARB_sync;
			}
			return supported;
		}

		@Override
		public long fence() {
			GLSync s = GL32.glFenceSync(SYNC_GPU_COMMANDS_COMPLETE, 0);
			if (s == null) return 0;
			long id = next++;
			GLSync old = slots[(int) (id % slots.length)];
			if (old != null) GL32.glDeleteSync(old);
			slots[(int) (id % slots.length)] = s;
			return id;
		}

		private GLSync of(long id) {
			return id <= 0 ? null : slots[(int) (id % slots.length)];
		}

		@Override
		public boolean await(long fence, long timeoutNanos) {
			GLSync s = of(fence);
			if (s == null) return true;
			int r = GL32.glClientWaitSync(s, SYNC_FLUSH_COMMANDS_BIT, timeoutNanos);
			return r == ALREADY_SIGNALED || r == CONDITION_SATISFIED;
		}

		@Override
		public boolean signaled(long fence) {
			GLSync s = of(fence);
			if (s == null) return true;
			int r = GL32.glClientWaitSync(s, 0, 0L);
			return r == ALREADY_SIGNALED || r == CONDITION_SATISFIED;
		}

		@Override
		public void delete(long fence) {
			GLSync s = of(fence);
			if (s == null) return;
			GL32.glDeleteSync(s);
			slots[(int) (fence % slots.length)] = null;
		}
	}
}

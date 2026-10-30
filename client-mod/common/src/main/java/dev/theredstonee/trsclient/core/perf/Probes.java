package dev.theredstonee.trsclient.core.perf;

/**
 * Optional time stamps around the TRS Client's own work on the render thread (tick, world cosmetics, HUD). Without an
 * installed {@link Sink} (open-source build, or nobody measuring) {@link #begin()} returns 0 and {@link #end} does
 * nothing – no clock reads at all.
 */
public final class Probes {
	/** TRS Client tick work. */
	public static final int TRS_TICK = 0;
	/** TRS parts while drawing the world (capes, head cosmetics, wings, pets). */
	public static final int TRS_WORLD = 1;
	/** TRS HUD (displays, minimap, toasts). */
	public static final int TRS_HUD = 2;

	/** Receives the measured sections (render thread). */
	public interface Sink {
		void section(int part, long nanos);

		/** Time the render thread deliberately waited (frame limiter, latency fence). */
		void waited(long nanos);
	}

	private static volatile Sink sink;

	private Probes() {
	}

	public static void install(Sink s) {
		sink = s;
	}

	public static boolean active() {
		return sink != null;
	}

	public static long begin() {
		return sink != null ? System.nanoTime() : 0L;
	}

	public static void end(int part, long start) {
		Sink s = sink;
		if (s != null && start != 0L) s.section(part, System.nanoTime() - start);
	}

	public static void waited(long nanos) {
		Sink s = sink;
		if (s != null && nanos > 0) s.waited(nanos);
	}
}

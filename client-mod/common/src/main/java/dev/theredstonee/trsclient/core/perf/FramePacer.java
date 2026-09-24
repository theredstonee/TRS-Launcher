package dev.theredstonee.trsclient.core.perf;

/**
 * Begrenzt die Bildrate, indem es vor einem Bild so lange wartet, bis seit dem letzten Bild
 * {@code 1/fps} Sekunden vergangen sind. Geschlafen wird in kurzen Scheiben: nach jeder fragt
 * {@link Wake}, ob die Grenze noch gilt – kommt das Fenster zurück in den Vordergrund, geht es
 * sofort mit voller Bildrate weiter (nicht erst nach einer ganzen Sekunde bei 1 FPS).
 */
public final class FramePacer {
	/** Längste Schlaf-Scheibe (danach wird der Fensterzustand neu geprüft). */
	public static final long SLICE_NANOS = 10_000_000L;
	/** Nie länger als eine Sekunde am Stück (1 FPS). */
	private static final long MAX_WAIT_NANOS = 1_000_000_000L;

	/** Gilt die Grenze noch? (live abgefragt – z. B. Fensterfokus direkt beim System) */
	public interface Wake {
		boolean stillLimited();
	}

	/** Uhr und Schlafen (austauschbar für Tests). */
	public interface Clock {
		long nanoTime();

		void sleep(long nanos) throws InterruptedException;
	}

	public static final Clock SYSTEM = new Clock() {
		@Override
		public long nanoTime() {
			return System.nanoTime();
		}

		@Override
		public void sleep(long nanos) throws InterruptedException {
			Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
		}
	};

	private final Clock clock;
	private long lastFrame;

	public FramePacer() {
		this(SYSTEM);
	}

	public FramePacer(Clock clock) {
		this.clock = clock;
	}

	/** Wie lange vor diesem Bild gewartet werden müsste (0 = sofort). */
	public long waitNanos(long now, int fps) {
		if (fps <= 0 || lastFrame == 0) return 0;
		long period = 1_000_000_000L / fps;
		long due = lastFrame + period;
		return Math.max(0, Math.min(MAX_WAIT_NANOS, due - now));
	}

	/**
	 * Vor einem Bild aufrufen. Wartet bei {@code fps > 0} bis zum nächsten Bild-Zeitpunkt
	 * (in Scheiben, jederzeit abbrechbar über {@code wake}).
	 * @return tatsächlich gewartete Nanosekunden
	 */
	public long pace(int fps, Wake wake) {
		long start = clock.nanoTime();
		long remaining = waitNanos(start, fps);
		long waited = 0;
		try {
			while (remaining > 0) {
				long slice = Math.min(SLICE_NANOS, remaining);
				clock.sleep(slice);
				long now = clock.nanoTime();
				waited = now - start;
				if (wake != null && !wake.stillLimited()) break;
				remaining = waitNanos(now, fps);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		lastFrame = clock.nanoTime();
		return waited;
	}

	/** Bild ohne Begrenzung merken (damit der erste begrenzte Frame nicht sofort wartet). */
	public void mark() {
		lastFrame = clock.nanoTime();
	}
}

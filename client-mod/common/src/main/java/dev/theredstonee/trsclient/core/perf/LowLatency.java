package dev.theredstonee.trsclient.core.perf;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.ChoiceSetting;

/**
 * „Niedrige Eingabeverzögerung“: kürzere Zeit von der Mausbewegung bis zum Bild – im Prinzip wie NVIDIAs Low-Latency-
 * Modus bzw. Reflex, aber nur mit Mitteln, die jede OpenGL-Version hat.
 *
 * <p>Ist die Grafikkarte voll ausgelastet, rechnet die CPU dem Bild bis zu mehrere Bilder voraus; jedes wartende Bild
 * verlängert die Zeit von der Eingabe bis zur Anzeige um eine Bilddauer. Hier setzt der Client am Anfang jedes Bildes
 * einen GPU-Zaun ({@code glFenceSync}) und wartet, bis höchstens {@link Mode#queue()} Bilder in der Warteschlange stehen.
 * Danach liest der Loader die Eingaben neu ({@code glfwPollEvents} bzw. {@code Display.processMessages}), damit die
 * Wartezeit die Maus nicht wieder älter macht.
 *
 * <p>Kosten: CPU und GPU arbeiten weniger parallel – je nach System ein paar Prozent FPS, bei „Maximal“ mehr. Am Ping
 * zum Server ändert sich nichts. Die Messwerte (Wartezeit, Warteschlange) zeigt die Modulseite.
 */
public final class LowLatency {
	/** Wie viele fertig abgeschickte Bilder die GPU noch vor sich haben darf. */
	public enum Mode implements ChoiceSetting.Option {
		BALANCED("Balanced (max. 1 frame queued)", 1),
		MAXIMUM("Maximum (no frame queued)", 0);

		private final String label;
		private final int queue;

		Mode(String label, int queue) {
			this.label = label;
			this.queue = queue;
		}

		@Override
		public String label() {
			return label;
		}

		public int queue() {
			return queue;
		}
	}

	/** GPU-Zäune der jeweiligen Grafik-Schnittstelle (je Loader). Alle Aufrufe im Render-Thread. */
	public interface Gpu {
		/** Gibt es Zäune (OpenGL 3.2 / ARB_sync) im aktuellen Kontext? */
		boolean supported();

		/** Neuer Zaun hinter allen bisher abgeschickten Befehlen (Rückgabe: Handle, 0 = Fehler). */
		long fence();

		/**
		 * Wartet höchstens {@code timeoutNanos} auf den Zaun.
		 *
		 * @return true = erreicht
		 */
		boolean await(long fence, long timeoutNanos);

		/** Schon erreicht (ohne zu warten)? */
		boolean signaled(long fence);

		void delete(long fence);
	}

	/** Längste Wartezeit je Bild – danach geht es trotzdem weiter (Treiber hängt, Fenster verdeckt …). */
	static final long MAX_WAIT_NANOS = 50_000_000L;
	static final int RING = 4;

	private final Gpu gpu;
	private final long[] fences = new long[RING];
	private int count;
	private boolean broken;

	// Messung
	private long frames;
	private long waitNanos;
	private long queuedSum;
	private long measuredFrames;
	private long ageSum;
	private long ageCount;

	private static volatile LowLatency current;

	/** Maus-Rohdaten (Vanilla-Option): -1 = gibt es in dieser Version nicht, 0 = aus, 1 = an (vom Loader gesetzt). */
	public volatile int rawMouse = -1;
	/** Kann der Loader Eingaben direkt vor dem Bild neu lesen? */
	public volatile boolean latePollSupported;
	/** Messen auch ohne Modus, bis zu diesem Zeitpunkt (Modulseite offen). */
	private volatile long measureUntil;

	public LowLatency(Gpu gpu) {
		this.gpu = gpu;
		current = this;
	}

	/** Die Umsetzung des laufenden Spiels (null, wo es sie nicht gibt). */
	public static LowLatency current() {
		return current;
	}

	/** Modulseite sichtbar: ein paar Sekunden lang auch ohne Modus die Warteschlange zählen. */
	public void watch(long nowMillis) {
		measureUntil = nowMillis + 3000;
	}

	/** Soll gerade gemessen werden (Seite offen oder Autotest)? */
	public boolean measuring(long nowMillis) {
		return nowMillis < measureUntil || Boolean.getBoolean("trsclient.latency.measure");
	}

	/** Läuft es in diesem Spiel (Zäune vorhanden, kein Fehler)? */
	public boolean available() {
		if (broken || gpu == null) return false;
		try {
			return gpu.supported();
		} catch (Throwable t) {
			broken = true;
			return false;
		}
	}

	/**
	 * Am Anfang jedes Bildes (Render-Thread, vor Ticks und Eingaben).
	 *
	 * @param mode    null = aus (dann nur messen, wenn {@code measure})
	 * @param measure Warteschlange auch ohne Warten zählen (Modulseite offen, Autotest)
	 * @return gewartete Nanosekunden
	 */
	public long frameStart(Mode mode, boolean measure) {
		if (mode == null && !measure) {
			clear();
			return 0;
		}
		if (!available()) return 0;
		try {
			long waited = 0;
			// Wie viele Bilder hängen noch? (Zäune sind in Reihenfolge – der erste unerreichte zählt ab da.)
			int queued = 0;
			for (int i = 0; i < count; i++) {
				if (!gpu.signaled(fences[i])) {
					queued = count - i;
					break;
				}
			}
			long f = gpu.fence();
			if (f == 0) {
				broken = true;
				return 0;
			}
			push(f);
			if (mode != null) {
				// Zaun, der erreicht sein muss: der von vor queue() Bildern (0 = der gerade gesetzte).
				int idx = count - 1 - mode.queue();
				if (idx >= 0) {
					long t0 = System.nanoTime();
					gpu.await(fences[idx], MAX_WAIT_NANOS);
					waited = System.nanoTime() - t0;
				}
			}
			dropSignaled();
			frames++;
			waitNanos += waited;
			queuedSum += queued;
			measuredFrames++;
			return waited;
		} catch (Throwable t) {
			broken = true;
			clear();
			return 0;
		}
	}

	private void push(long f) {
		if (count == RING) {
			gpu.delete(fences[0]);
			System.arraycopy(fences, 1, fences, 0, RING - 1);
			count--;
		}
		fences[count++] = f;
	}

	/** Erreichte Zäune bis auf den neuesten löschen. */
	private void dropSignaled() {
		while (count > 1 && gpu.signaled(fences[0])) {
			gpu.delete(fences[0]);
			System.arraycopy(fences, 1, fences, 0, count - 1);
			count--;
		}
	}

	private void clear() {
		if (gpu == null) {
			count = 0;
			return;
		}
		for (int i = 0; i < count; i++) {
			try {
				gpu.delete(fences[i]);
			} catch (Throwable ignored) {
				// Kontext weg – nichts mehr zu tun
			}
		}
		count = 0;
	}

	/**
	 * Alter der Mausbewegung in dem Moment, in dem das Spiel sie anwendet (Nanosekunden seit dem letzten Lesen der
	 * Fenster-Ereignisse; 0 = gerade eben gelesen). Vom Loader je Bild gemeldet, nur solange gemessen wird.
	 */
	public void inputAge(long nanos) {
		ageSum += Math.max(0, nanos);
		ageCount++;
	}

	/** Messwerte seit dem letzten {@link #resetStats()}. */
	public Stats stats() {
		Stats s = new Stats();
		s.frames = measuredFrames;
		s.avgWaitMillis = measuredFrames == 0 ? 0 : waitNanos / 1e6 / measuredFrames;
		s.avgQueued = measuredFrames == 0 ? 0 : (double) queuedSum / measuredFrames;
		s.avgInputAgeMillis = ageCount == 0 ? -1 : ageSum / 1e6 / ageCount;
		return s;
	}

	public void resetStats() {
		frames = 0;
		waitNanos = 0;
		queuedSum = 0;
		measuredFrames = 0;
		ageSum = 0;
		ageCount = 0;
	}

	public long frames() {
		return frames;
	}

	public static final class Stats {
		public long frames;
		/** Ø Wartezeit je Bild auf die GPU (ms) – so viel früher wird die Eingabe jetzt gelesen. */
		public double avgWaitMillis;
		/** Ø Bilder, die beim Start eines neuen Bildes noch in der GPU-Warteschlange standen. */
		public double avgQueued;
		/** Ø Alter der Mausbewegung beim Anwenden in ms (-1 = nicht gemessen). */
		public double avgInputAgeMillis = -1;

		@Override
		public String toString() {
			return String.format(java.util.Locale.ROOT, "frames=%d wait=%.2fms queued=%.2f inputAge=%.2fms", frames, avgWaitMillis,
					avgQueued, avgInputAgeMillis);
		}
	}

	/** Name des Modus in der aktiven Sprache (für Anzeigen außerhalb der Einstellung). */
	public static String label(Mode m) {
		return I18n.trOr("setting.lowLatency.mode." + m.name().toLowerCase(java.util.Locale.ROOT), m.label());
	}
}

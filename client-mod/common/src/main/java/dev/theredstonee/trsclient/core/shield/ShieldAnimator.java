package dev.theredstonee.trsclient.core.shield;

/**
 * Weicher Übergang zwischen normaler Haltung (0) und Blocken (1) für eine Hand. Der Wert folgt dem Fortschritt der
 * Benutzung: Hochnehmen dauert {@link #RAISE_MS}, Absenken {@link #LOWER_MS}; dazwischen eine S-Kurve, damit das Schild
 * weich anfährt und weich ankommt. Zeit in Nanosekunden (System.nanoTime), damit er unabhängig von der Bildrate ist.
 */
public final class ShieldAnimator {
	/** Dauer bis zur vollen Block-Haltung. */
	public static final long RAISE_MS = 140;
	/** Dauer zurück in die normale Haltung. */
	public static final long LOWER_MS = 160;
	/** Längere Pausen (Hand nicht gezeichnet, Menü offen …) springen direkt ans Ziel statt nachzuholen. */
	static final long GAP_MS = 250;

	private double progress;
	private long last;
	private boolean started;

	/**
	 * Nächstes Bild.
	 *
	 * @param blocking die Hand blockt gerade mit dem Schild
	 * @param now      System.nanoTime()
	 * @return Überblendung 0..1 (bereits geglättet)
	 */
	public float update(boolean blocking, long now) {
		double target = blocking ? 1 : 0;
		if (!started) {
			started = true;
			progress = target;
			last = now;
			return value();
		}
		long dt = now - last;
		last = now;
		if (dt < 0) dt = 0;
		if (dt > GAP_MS * 1_000_000L) {
			progress = target;
			return value();
		}
		double step = dt / (double) ((blocking ? RAISE_MS : LOWER_MS) * 1_000_000L);
		progress = blocking ? Math.min(1, progress + step) : Math.max(0, progress - step);
		return value();
	}

	/** Aktueller geglätteter Wert ohne Fortschreiben. */
	public float value() {
		return (float) smooth(progress);
	}

	/** Roher (linearer) Fortschritt 0..1. */
	public double progress() {
		return progress;
	}

	public void reset() {
		started = false;
		progress = 0;
	}

	/** S-Kurve 3t² − 2t³ (Anfang und Ende ohne Ruck). */
	static double smooth(double t) {
		if (t <= 0) return 0;
		if (t >= 1) return 1;
		return t * t * (3 - 2 * t);
	}
}

package dev.theredstonee.trsclient.core.perf;

/**
 * Misst die Bildrate selbst (Bilder je 500-ms-Fenster, die letzten 30 s) und vergleicht
 * „vorher/nachher“: {@link #compare} merkt sich den Durchschnitt der letzten Sekunden, wartet
 * nach der Änderung kurz (Chunks bauen neu) und misst dann einige Sekunden lang.
 * Bilder, in denen Dynamische FPS absichtlich bremst, zählen nicht.
 */
public final class FpsMeter {
	private static final long WINDOW = 500;
	private static final int SAMPLES = 60;
	/** Nach einer Änderung so lange warten, bevor „nachher“ gemessen wird. */
	public static final long SETTLE_MILLIS = 1500;
	/** So lange wird „nachher“ gemessen. */
	public static final long MEASURE_MILLIS = 4000;
	/** „Vorher“ = Durchschnitt dieser Zeitspanne vor der Änderung. */
	public static final long BEFORE_MILLIS = 3000;

	public enum Compare {
		NONE, MEASURING, DONE
	}

	private final double[] fps = new double[SAMPLES];
	private final long[] at = new long[SAMPLES];
	private int count;
	private int head;
	private long windowStart;
	private int frames;
	private boolean throttled;

	private Compare compare = Compare.NONE;
	private double before;
	private double after;
	private long measureFrom;
	private long measureTo;
	private String reason = "";
	private int retries;

	/** Ein Bild wurde gezeichnet. {@code limited} = Dynamische FPS bremst gerade absichtlich. */
	public void frame(long nowMillis, boolean limited) {
		if (windowStart == 0) {
			windowStart = nowMillis;
			throttled = limited;
			return;
		}
		frames++;
		throttled |= limited;
		long elapsed = nowMillis - windowStart;
		if (elapsed >= WINDOW) {
			if (!throttled && elapsed < WINDOW * 4) push(nowMillis, frames * 1000.0 / elapsed);
			windowStart = nowMillis;
			frames = 0;
			throttled = limited;
		}
		updateCompare(nowMillis);
	}

	private void push(long now, double value) {
		fps[head] = value;
		at[head] = now;
		head = (head + 1) % SAMPLES;
		if (count < SAMPLES) count++;
	}

	/** Durchschnitt der Messwerte im Zeitraum [from, to]; 0 = keine Werte. */
	public double average(long from, long to) {
		double sum = 0;
		int n = 0;
		for (int i = 0; i < count; i++) {
			if (at[i] >= from && at[i] <= to) {
				sum += fps[i];
				n++;
			}
		}
		return n == 0 ? 0 : sum / n;
	}

	/** Durchschnitt der letzten {@code millis} Millisekunden. */
	public double recent(long nowMillis, long millis) {
		return average(nowMillis - millis, nowMillis);
	}

	/** Startet einen Vorher/Nachher-Vergleich (z. B. nach „FPS-Boost: Hoch“). */
	public void compare(long nowMillis, String reason) {
		double b = recent(nowMillis, BEFORE_MILLIS);
		// Läuft schon ein Vergleich, bleibt dessen „vorher“ (mehrere Klicks hintereinander).
		if (compare == Compare.MEASURING && before > 0) b = before;
		if (b <= 0) b = recent(nowMillis, BEFORE_MILLIS * 3);
		before = b;
		after = 0;
		measureFrom = nowMillis + SETTLE_MILLIS;
		measureTo = measureFrom + MEASURE_MILLIS;
		compare = Compare.MEASURING;
		retries = 0;
		this.reason = reason == null ? "" : reason;
	}

	private void updateCompare(long now) {
		if (compare != Compare.MEASURING || now < measureTo) return;
		// Hänger (Ressourcen neu laden, Chunks neu bauen) → zu wenige Messwerte: Fenster neu starten.
		if (count(measureFrom, measureTo) < 4 && retries < 3) {
			retries++;
			measureFrom = now + WINDOW;
			measureTo = measureFrom + MEASURE_MILLIS;
			return;
		}
		after = average(measureFrom, measureTo);
		compare = Compare.DONE;
	}

	private int count(long from, long to) {
		int n = 0;
		for (int i = 0; i < count; i++) {
			if (at[i] >= from && at[i] <= to) n++;
		}
		return n;
	}

	public Compare compareState() {
		return compare;
	}

	public double before() {
		return before;
	}

	public double after() {
		return after;
	}

	/** Was verglichen wird (z. B. "Hoch"). */
	public String reason() {
		return reason;
	}

	/** Änderung in Prozent (0 = unbekannt). */
	public double percent() {
		if (compare != Compare.DONE || before <= 0 || after <= 0) return 0;
		return (after - before) / before * 100.0;
	}

	/** Fortschritt der Nachher-Messung 0..1. */
	public float progress(long nowMillis) {
		if (compare != Compare.MEASURING) return compare == Compare.DONE ? 1f : 0f;
		long total = measureTo - (measureFrom - SETTLE_MILLIS);
		return Math.max(0f, Math.min(1f, (nowMillis - (measureFrom - SETTLE_MILLIS)) / (float) total));
	}
}

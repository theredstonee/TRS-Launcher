package dev.theredstonee.trsclient.core.perf;

import java.util.Arrays;

/**
 * Bildzeiten für den Benchmark: je Bild ein Zeitstempel, daraus Durchschnitt und „1 %-Low“
 * (Bildrate aus dem Mittel der langsamsten 1 % aller Bilder). Nimmt nur auf, solange
 * {@link #start} läuft – sonst kostet der Aufruf je Bild nur einen Feldvergleich.
 */
public final class FrameStats {
	private static final int CAPACITY = 1 << 18;

	private final long[] times = new long[CAPACITY];
	private volatile boolean recording;
	private long last;
	private int count;
	private long startNanos;
	private long endNanos;

	/** Aufnahme neu beginnen. */
	public void start(long nowNanos) {
		count = 0;
		last = 0;
		startNanos = nowNanos;
		endNanos = nowNanos;
		recording = true;
	}

	/** Aufnahme beenden. */
	public void stop(long nowNanos) {
		recording = false;
		endNanos = nowNanos;
	}

	public boolean recording() {
		return recording;
	}

	/** Je Bild (Beginn des Bildes). */
	public void frame(long nowNanos) {
		if (!recording) return;
		if (last != 0 && count < CAPACITY) times[count++] = nowNanos - last;
		last = nowNanos;
		endNanos = nowNanos;
	}

	/** Anzahl gemessener Bildzeiten. */
	public int frames() {
		return count;
	}

	/** Dauer der Aufnahme in Sekunden. */
	public double seconds() {
		return (endNanos - startNanos) / 1e9;
	}

	/** Durchschnittliche Bildrate (Bilder / Zeit). */
	public double averageFps() {
		long sum = 0;
		for (int i = 0; i < count; i++) sum += times[i];
		return sum <= 0 ? 0 : count * 1e9 / sum;
	}

	/**
	 * Bildrate der langsamsten {@code share} (0.01 = 1 %) aller Bilder: Mittel ihrer Bildzeiten.
	 */
	public double lowFps(double share) {
		if (count == 0) return 0;
		long[] sorted = Arrays.copyOf(times, count);
		Arrays.sort(sorted);
		int n = Math.max(1, (int) Math.round(count * share));
		long sum = 0;
		for (int i = count - n; i < count; i++) sum += sorted[i];
		return sum <= 0 ? 0 : n * 1e9 / sum;
	}

	/** Längste Bildzeit in Millisekunden (größter Ruckler). */
	public double worstMillis() {
		long max = 0;
		for (int i = 0; i < count; i++) max = Math.max(max, times[i]);
		return max / 1e6;
	}

	/** Bilder, die länger als {@code millis} dauerten (spürbare Ruckler). */
	public int slowerThan(double millis) {
		long limit = (long) (millis * 1e6);
		int n = 0;
		for (int i = 0; i < count; i++) if (times[i] > limit) n++;
		return n;
	}
}

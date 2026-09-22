package dev.theredstonee.trsclient.core.input;

/**
 * Zählt Klicks in einem gleitenden Zeitfenster (Standard: 1000 ms).
 * Ringpuffer mit festen Zeitstempeln – keine Allokationen pro Klick oder Abfrage.
 */
public final class ClickCounter {
	/** Standard-Fenster für "Klicks pro Sekunde". */
	public static final long DEFAULT_WINDOW_MS = 1000L;

	private final long windowMs;
	private final long[] stamps;
	/** Index des ältesten gespeicherten Klicks. */
	private int head;
	private int size;

	public ClickCounter() {
		this(DEFAULT_WINDOW_MS, 128);
	}

	public ClickCounter(long windowMs, int capacity) {
		if (windowMs <= 0) throw new IllegalArgumentException("windowMs muss > 0 sein");
		if (capacity <= 0) throw new IllegalArgumentException("capacity muss > 0 sein");
		this.windowMs = windowMs;
		this.stamps = new long[capacity];
	}

	/** Registriert einen Klick zum Zeitpunkt {@code nowMs}. Ist der Puffer voll, fällt der älteste weg. */
	public void record(long nowMs) {
		evict(nowMs);
		if (size == stamps.length) {
			head = (head + 1) % stamps.length;
			size--;
		}
		stamps[(head + size) % stamps.length] = nowMs;
		size++;
	}

	/** Anzahl der Klicks innerhalb des Fensters (nowMs - window, nowMs]. */
	public int count(long nowMs) {
		evict(nowMs);
		return size;
	}

	/** Verwirft alle Klicks. */
	public void reset() {
		head = 0;
		size = 0;
	}

	private void evict(long nowMs) {
		long cutoff = nowMs - windowMs;
		while (size > 0 && stamps[head] <= cutoff) {
			head = (head + 1) % stamps.length;
			size--;
		}
	}
}

package dev.theredstonee.trsclient.core.util;

/**
 * Begrenzt, wie oft etwas passieren darf (Auto-GG, Text-Hotkeys): Mindestabstand
 * zwischen zwei Auslösungen UND Höchstzahl innerhalb eines Zeitfensters.
 * Damit kann der Client nie den Chat fluten (Kick/Mute auf Servern).
 */
public final class RateLimiter {
	private final long minIntervalMs;
	private final int maxPerWindow;
	private final long windowMs;
	private final long[] times;
	/** Anzahl gültiger Einträge in {@link #times} (Ringpuffer). */
	private int count;
	private int next;

	public RateLimiter(long minIntervalMs, int maxPerWindow, long windowMs) {
		this.minIntervalMs = Math.max(0, minIntervalMs);
		this.maxPerWindow = Math.max(1, maxPerWindow);
		this.windowMs = Math.max(1, windowMs);
		this.times = new long[this.maxPerWindow];
	}

	/** Darf jetzt ausgelöst werden? (ohne Nebenwirkung) */
	public boolean allowed(long now) {
		if (count > 0 && now - last() < minIntervalMs) return false;
		if (count < maxPerWindow) return true;
		// Ältester Eintrag im Ringpuffer steht an der nächsten Schreibstelle.
		return now - times[next] >= windowMs;
	}

	/** Löst aus, wenn erlaubt; true = darf gesendet werden. */
	public boolean tryAcquire(long now) {
		if (!allowed(now)) return false;
		times[next] = now;
		next = (next + 1) % maxPerWindow;
		if (count < maxPerWindow) count++;
		return true;
	}

	/** Zeitpunkt der letzten Auslösung (0 = noch nie). */
	public long last() {
		if (count == 0) return 0;
		return times[(next - 1 + maxPerWindow) % maxPerWindow];
	}

	public void reset() {
		count = 0;
		next = 0;
	}
}

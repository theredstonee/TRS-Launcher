package dev.theredstonee.trsclient.core.redstone;

/**
 * Misst, wie schnell ein Redstone-Bauteil schaltet: je Spiel-Tick ein Messwert (Signal 0–15),
 * daraus steigende Flanken (aus → an), Frequenz in Hz, Periode und Pulslänge in Ticks.
 * Der Verlauf der letzten {@link #HISTORY} Ticks dient als Oszilloskop.
 * Ticks sind Spiel-Ticks (20 je Sekunde); ein Redstone-Tick sind zwei Spiel-Ticks.
 */
public final class FrequencyMeter {
	public static final int HISTORY = 200;
	public static final int TICKS_PER_SECOND = 20;
	private static final int MAX_RISES = 128;
	/** Größte Lücke, die mit dem letzten Wert aufgefüllt wird (sonst Neustart). */
	private static final int MAX_GAP = 40;

	private final int[] levels = new int[HISTORY];
	private final long[] rises = new long[MAX_RISES];
	private int riseStart;
	private int riseCount;
	private long lastTick = Long.MIN_VALUE;
	private long samples;
	private int lastLevel;
	private long onSince = -1;
	private int lastPulse = -1;

	/** Alles vergessen (neues Bauteil, Weltwechsel). */
	public void reset() {
		riseStart = 0;
		riseCount = 0;
		lastTick = Long.MIN_VALUE;
		samples = 0;
		lastLevel = 0;
		onSince = -1;
		lastPulse = -1;
	}

	/**
	 * Messwert für Tick {@code tick}. Ticks müssen steigen; ein zweiter Wert im selben Tick zählt nicht,
	 * kleine Lücken werden mit dem vorigen Wert gefüllt, große Lücken oder Rücksprünge starten neu.
	 */
	public void sample(long tick, int level) {
		level = Math.max(0, Math.min(15, level));
		if (samples > 0 && tick == lastTick) return; // derselbe Tick zählt nur einmal
		if (samples > 0 && (tick < lastTick || tick - lastTick > MAX_GAP)) reset();
		if (samples > 0) {
			for (long t = lastTick + 1; t < tick; t++) {
				levels[(int) Math.floorMod(t, (long) HISTORY)] = lastLevel;
				samples++;
			}
		}
		boolean wasOn = samples > 0 && lastLevel > 0;
		boolean on = level > 0;
		if (samples > 0 && on && !wasOn) {
			addRise(tick);
			onSince = tick;
		} else if (samples > 0 && !on && wasOn && onSince >= 0) {
			lastPulse = (int) (tick - onSince);
			onSince = -1;
		}
		levels[(int) Math.floorMod(tick, (long) HISTORY)] = level;
		samples++;
		lastTick = tick;
		lastLevel = level;
	}

	private void addRise(long tick) {
		if (riseCount < MAX_RISES) {
			rises[(riseStart + riseCount) % MAX_RISES] = tick;
			riseCount++;
		} else {
			rises[riseStart] = tick;
			riseStart = (riseStart + 1) % MAX_RISES;
		}
	}

	/** Zahl der steigenden Flanken in den letzten {@code window} Ticks (bis einschließlich {@code now}). */
	public int risesWithin(long now, int window) {
		int n = 0;
		for (int i = 0; i < riseCount; i++) {
			long t = rises[(riseStart + i) % MAX_RISES];
			if (t > now - window && t <= now) n++;
		}
		return n;
	}

	/**
	 * Mittlere Periode in Spiel-Ticks über die Flanken im Fenster; 0 = kein Takt erkennbar
	 * (weniger als zwei Flanken, oder seit der letzten Flanke ist deutlich mehr Zeit vergangen
	 * als eine Periode – der Takt steht).
	 */
	public double periodTicks(long now, int window) {
		long first = Long.MIN_VALUE;
		long last = Long.MIN_VALUE;
		int n = 0;
		for (int i = 0; i < riseCount; i++) {
			long t = rises[(riseStart + i) % MAX_RISES];
			if (t <= now - window || t > now) continue;
			if (n == 0) first = t;
			last = t;
			n++;
		}
		if (n < 2) return 0;
		double period = (double) (last - first) / (n - 1);
		if (period <= 0) return 0;
		if (now - last > period * 2 + 2) return 0;
		return period;
	}

	/** Frequenz in Hz (volle Schwingungen je Sekunde) oder 0. */
	public double hertz(long now, int window) {
		double p = periodTicks(now, window);
		return p <= 0 ? 0 : TICKS_PER_SECOND / p;
	}

	/** Zustandswechsel je Sekunde (an + aus = zwei je Schwingung). */
	public double changesPerSecond(long now, int window) {
		return hertz(now, window) * 2;
	}

	/** Länge des letzten vollständigen An-Pulses in Spiel-Ticks oder -1. */
	public int lastPulseTicks() {
		return lastPulse;
	}

	/** Messwert von vor {@code ago} Ticks (0 = letzter) oder -1, wenn es ihn (noch) nicht gibt. */
	public int level(int ago) {
		if (ago < 0 || ago >= HISTORY || ago >= samples) return -1;
		return levels[(int) Math.floorMod(lastTick - ago, (long) HISTORY)];
	}

	/** Zahl der Messwerte seit dem letzten Neustart. */
	public long samples() {
		return samples;
	}

	public long lastTick() {
		return lastTick;
	}
}

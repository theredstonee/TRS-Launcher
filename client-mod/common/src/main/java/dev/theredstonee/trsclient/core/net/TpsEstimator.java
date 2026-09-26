package dev.theredstonee.trsclient.core.net;

/**
 * Schätzt die Ticks je Sekunde des Servers aus den Zeit-Paketen, die jeder Vanilla-Server alle 20 Ticks schickt:
 * gezählte Spielzeit geteilt durch vergangene echte Zeit über ein Fenster von einigen Sekunden. Rein passiv – es wird
 * nichts gesendet. Netz-Schwankungen verfälschen einzelne Abstände, über das Fenster mitteln sie sich weg (±0,1 TPS).
 */
public final class TpsEstimator {
	static final int SIZE = 16;
	/** Mindestens so viel echte Zeit im Fenster, bevor ein Wert angezeigt wird. */
	static final long MIN_SPAN_NANOS = 3_000_000_000L;
	/** Kommt länger kein Zeit-Paket, gilt die Schätzung als unbekannt. */
	static final long STALE_NANOS = 6_000_000_000L;

	private final long[] ticks = new long[SIZE];
	private final long[] nanos = new long[SIZE];
	private int head;
	private int count;

	public synchronized void reset() {
		head = 0;
		count = 0;
	}

	public synchronized void add(long gameTime, long nowNanos) {
		if (count > 0) {
			int last = (head + SIZE - 1) % SIZE;
			long dt = gameTime - ticks[last];
			// Rückwärts (Weltwechsel, /time) oder unplausibler Sprung: neu anfangen.
			if (dt < 0 || dt > 20L * 60 * 60) {
				count = 0;
				head = 0;
			}
		}
		ticks[head] = gameTime;
		nanos[head] = nowNanos;
		head = (head + 1) % SIZE;
		if (count < SIZE) count++;
	}

	/** Geschätzte TPS oder {@code NaN}, solange zu wenig (oder zu alte) Daten da sind. */
	public synchronized double tps(long nowNanos) {
		if (count < 4) return Double.NaN;
		int last = (head + SIZE - 1) % SIZE;
		int first = (head + SIZE - count) % SIZE;
		if (nowNanos - nanos[last] > STALE_NANOS) return Double.NaN;
		long span = nanos[last] - nanos[first];
		if (span < MIN_SPAN_NANOS) return Double.NaN;
		// Steigung der Ausgleichsgeraden (Ticks über Sekunden) – einzelne verspätete Pakete verschieben sie kaum.
		double mt = 0;
		double mx = 0;
		for (int i = 0; i < count; i++) {
			int k = (first + i) % SIZE;
			mt += (nanos[k] - nanos[first]) / 1e9;
			mx += ticks[k] - ticks[first];
		}
		mt /= count;
		mx /= count;
		double num = 0;
		double den = 0;
		for (int i = 0; i < count; i++) {
			int k = (first + i) % SIZE;
			double t = (nanos[k] - nanos[first]) / 1e9 - mt;
			num += t * (ticks[k] - ticks[first] - mx);
			den += t * t;
		}
		if (den <= 0) return Double.NaN;
		double tps = num / den;
		if (tps < 0 || tps > 1000) return Double.NaN;
		// Vanilla-Takt 20; kleine Überschreitungen sind Messrauschen der Ankunftszeiten.
		if (tps > 20 && tps < 21.5) tps = 20;
		return tps;
	}
}

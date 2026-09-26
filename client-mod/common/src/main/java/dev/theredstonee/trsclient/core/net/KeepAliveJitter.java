package dev.theredstonee.trsclient.core.net;

/**
 * Passive Schwankung der Laufzeit Server → Client aus den Keepalive-Paketen – ohne selbst etwas zu senden.
 *
 * <p>Vanilla-Server (und Spigot/Paper) nehmen als Keepalive-Kennung ihre eigene Millisekunden-Uhr. Die Uhr des Servers
 * kennt der Client nicht, aber ihr Versatz ist fest: Ändert sich {@code Ankunft − Kennung} von Paket zu Paket, hat sich
 * die Laufzeit geändert. Das ergibt den Jitter der Hin-Richtung. Sind die Kennungen keine Zeitstempel (Abstände passen
 * nicht zur Ankunft), gibt es keinen Wert. Außerdem: auffällig lange Pausen zwischen zwei Keepalives (Zeitüberschreitung).
 */
public final class KeepAliveJitter {
	static final int WINDOW = 8;
	/** So weit dürfen Kennungs- und Ankunftsabstand auseinander liegen, damit die Kennung als Zeitstempel gilt. */
	static final long TOLERANCE_MS = 1500;

	private long prevId = Long.MIN_VALUE;
	private double prevArrival;
	private double prevOffset;
	private final double[] variation = new double[WINDOW];
	private int count;
	private int head;
	private int consistent;
	private double lastInterval;
	private int stalls;

	public synchronized void reset() {
		prevId = Long.MIN_VALUE;
		count = 0;
		head = 0;
		consistent = 0;
		lastInterval = 0;
		stalls = 0;
	}

	public synchronized void add(long id, long nowNanos) {
		double arrival = nowNanos / 1e6;
		double offset = arrival - id;
		if (prevId != Long.MIN_VALUE) {
			long dId = id - prevId;
			double dArrival = arrival - prevArrival;
			if (dId > 0 && dId < 120_000 && Math.abs(dArrival - dId) < TOLERANCE_MS) {
				variation[head] = Math.abs(offset - prevOffset);
				head = (head + 1) % WINDOW;
				if (count < WINDOW) count++;
				consistent++;
			} else {
				consistent = 0;
				count = 0;
				head = 0;
			}
			if (lastInterval > 0 && dArrival > lastInterval * 2.5 && dArrival > 3000) stalls++;
			lastInterval = lastInterval <= 0 ? dArrival : lastInterval * 0.7 + dArrival * 0.3;
		}
		prevId = id;
		prevArrival = arrival;
		prevOffset = offset;
	}

	/** Mittlere Änderung der Laufzeit zwischen zwei Keepalives in ms, oder -1 (unbekannt). */
	public synchronized double jitter() {
		if (consistent < 3 || count == 0) return -1;
		double sum = 0;
		for (int i = 0; i < count; i++) sum += variation[i];
		return sum / count;
	}

	/** Auffällige Pausen zwischen Keepalives seit Verbindungsbeginn. */
	public synchronized int stalls() {
		return stalls;
	}
}

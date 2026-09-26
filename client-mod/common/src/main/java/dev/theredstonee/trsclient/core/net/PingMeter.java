package dev.theredstonee.trsclient.core.net;

import java.util.Arrays;

/**
 * Ehrliche Ping-Anzeige: sammelt Laufzeiten, rechnet Mittel, Jitter und Zeitüberschreitungen und merkt sich einen
 * kurzen Verlauf.
 *
 * <p>Quelle je Version:
 * <ul>
 *   <li>{@link Source#ACTIVE} (ab Minecraft 1.20.2): eigene Ping-Anfragen mit Zeitstempel, wie sie Vanilla für die
 *   Netzwerk-Grafik (F3) schickt – nur viel seltener (Standard alle 2 s, höchstens 1 je Sekunde, immer nur eine offen).
 *   Das ist die echte Hin- und Rücklaufzeit zum Server.</li>
 *   <li>{@link Source#SERVER} (ältere Versionen oder Server ohne Antwort): der Wert aus der Spielerliste, den der Server
 *   selbst misst und nur alle paar Sekunden schickt. Ältere Versionen erlauben keine eigene Messung, ohne Pakete zu
 *   fälschen – das tun wir nicht.</li>
 * </ul>
 * Threadsicher: Antworten kommen im Netty-Thread, Anzeige und Senden im Hauptthread.
 */
public final class PingMeter {
	public enum Source {
		NONE, ACTIVE, SERVER
	}

	/** Verlaufslänge (Einträge; -1 = Zeitüberschreitung). */
	public static final int HISTORY = 90;
	/** Ohne Antwort nach so langer Zeit gilt eine Anfrage als verloren. */
	public static final long TIMEOUT_MS = 5000;
	/** Nie öfter als einmal je Sekunde fragen. */
	public static final long MIN_INTERVAL_MS = 1000;
	/** Server, die auf die ersten drei Anfragen nie antworten, bekommen keine weiteren (Rückfall auf den Server-Wert). */
	static final int GIVE_UP_AFTER = 3;
	/** Spielerlisten-Wert als Verlaufspunkt alle so viele ms. */
	static final long SERVER_SAMPLE_MS = 2000;
	static final int ATTEMPTS = 30;

	private final long[] history = new long[HISTORY];
	private int head;
	private int count;

	private long outstanding = NetPlatform.NONE;
	private long sentAt;
	private long lastSend = Long.MIN_VALUE;
	private int pongs;
	private int consecutiveTimeouts;
	private boolean activeOff;

	private final boolean[] attempts = new boolean[ATTEMPTS];
	private int attemptHead;
	private int attemptCount;
	private int timeoutsTotal;

	private int serverLatency = -1;
	private long serverSampleAt = Long.MIN_VALUE;
	private boolean activeSupported;

	private long spikeThreshold = 100;
	private long spikeAt = Long.MIN_VALUE;
	private long spikeValue;

	private final TpsEstimator tps = new TpsEstimator();
	private final KeepAliveJitter keepAlive = new KeepAliveJitter();

	public TpsEstimator tps() {
		return tps;
	}

	public KeepAliveJitter keepAlive() {
		return keepAlive;
	}

	/** Neue Verbindung oder getrennt: alles vergessen. */
	public synchronized void reset() {
		head = 0;
		count = 0;
		outstanding = NetPlatform.NONE;
		lastSend = Long.MIN_VALUE;
		pongs = 0;
		consecutiveTimeouts = 0;
		activeOff = false;
		attemptHead = 0;
		attemptCount = 0;
		timeoutsTotal = 0;
		serverLatency = -1;
		serverSampleAt = Long.MIN_VALUE;
		spikeAt = Long.MIN_VALUE;
		tps.reset();
		keepAlive.reset();
	}

	public synchronized void spikeThreshold(long ms) {
		spikeThreshold = Math.max(20, ms);
	}

	/** Erlaubt die Version eigene Anfragen? (vor {@link #server} setzen, sonst zählt der Server-Wert einmal mit) */
	public synchronized void activeSupported(boolean supported) {
		activeSupported = supported;
	}

	/** Welche Quelle gerade gilt. */
	public synchronized Source source() {
		if (activeSupported && !activeOff) return Source.ACTIVE;
		return serverLatency >= 0 || count > 0 ? Source.SERVER : Source.NONE;
	}

	/**
	 * Je Client-Tick (Hauptthread): Zeitüberschreitungen prüfen und entscheiden, ob jetzt eine Anfrage raus darf.
	 *
	 * @param supported erlaubt die Version eigene Anfragen?
	 * @return true = jetzt mit {@link #sent(long)} eine Anfrage senden
	 */
	public synchronized boolean shouldSend(long nowMillis, long intervalMs, boolean supported) {
		activeSupported = supported;
		if (outstanding != NetPlatform.NONE && nowMillis - sentAt >= TIMEOUT_MS) {
			outstanding = NetPlatform.NONE;
			consecutiveTimeouts++;
			timeoutsTotal++;
			attempt(true);
			push(-1);
			if (pongs == 0 && consecutiveTimeouts >= GIVE_UP_AFTER) activeOff = true;
		}
		if (!supported || activeOff || outstanding != NetPlatform.NONE) return false;
		long interval = Math.max(MIN_INTERVAL_MS, intervalMs);
		return lastSend == Long.MIN_VALUE || nowMillis - lastSend >= interval;
	}

	/** Anfrage mit diesem Zeitstempel ist raus. */
	public synchronized void sent(long millis) {
		outstanding = millis;
		sentAt = millis;
		lastSend = millis;
	}

	/**
	 * Ping-Antwort (Netty-Thread). Zählt nur Antworten auf die eigene offene Anfrage – Vanillas F3-Grafik fragt selbst
	 * auch, deren Antworten laufen unverändert an Vanilla weiter.
	 *
	 * @return true = war unsere
	 */
	public synchronized boolean pong(long time, long nowMillis) {
		if (outstanding == NetPlatform.NONE || time != outstanding) return false;
		outstanding = NetPlatform.NONE;
		long rtt = Math.max(0, nowMillis - time);
		pongs++;
		consecutiveTimeouts = 0;
		attempt(false);
		sample(rtt, nowMillis);
		return true;
	}

	/** Wert aus der Spielerliste (Hauptthread, je Tick). */
	public synchronized void server(int latency, long nowMillis) {
		// 0 = der Server hat noch keinen Wert geschickt (ältere Server nur alle ~30 s) – nicht als „0 ms“ anzeigen.
		if (latency <= 0) return;
		serverLatency = latency;
		if (activeSupported && !activeOff) return;
		if (serverSampleAt == Long.MIN_VALUE || nowMillis - serverSampleAt >= SERVER_SAMPLE_MS) {
			serverSampleAt = nowMillis;
			sample(latency, nowMillis);
		}
	}

	private void sample(long rtt, long nowMillis) {
		if (count >= 5) {
			long base = median(20);
			if (rtt >= base + spikeThreshold) {
				spikeAt = nowMillis;
				spikeValue = rtt;
			}
		}
		push(rtt);
	}

	private void push(long v) {
		history[head] = v;
		head = (head + 1) % HISTORY;
		if (count < HISTORY) count++;
	}

	private void attempt(boolean timeout) {
		attempts[attemptHead] = timeout;
		attemptHead = (attemptHead + 1) % ATTEMPTS;
		if (attemptCount < ATTEMPTS) attemptCount++;
	}

	/** Median der letzten {@code n} gültigen Werte (0 ohne Werte). */
	private long median(int n) {
		long[] v = new long[Math.min(n, count)];
		int k = 0;
		for (int i = 1; i <= count && k < v.length; i++) {
			long x = history[(head - i + HISTORY) % HISTORY];
			if (x >= 0) v[k++] = x;
		}
		if (k == 0) return 0;
		Arrays.sort(v, 0, k);
		return v[k / 2];
	}

	/** Momentaufnahme für die Anzeige (keine Allokation, wenn {@code out} wiederverwendet wird). */
	public synchronized Snapshot snapshot(Snapshot out, long nowMillis, long nowNanos) {
		Snapshot s = out != null ? out : new Snapshot();
		s.source = source();
		s.historyCount = 0;
		long last = -1;
		long sum = 0;
		int n = 0;
		long jitterSum = 0;
		int jitterN = 0;
		long prev = -1;
		long min = Long.MAX_VALUE;
		long max = -1;
		for (int i = count; i >= 1; i--) {
			long x = history[(head - i + HISTORY) % HISTORY];
			s.history[s.historyCount++] = x;
			if (x < 0) {
				prev = -1;
				continue;
			}
			last = x;
			// Mittel und Jitter über die letzten 20 Werte.
			if (i <= 20) {
				sum += x;
				n++;
				if (prev >= 0) {
					jitterSum += Math.abs(x - prev);
					jitterN++;
				}
				min = Math.min(min, x);
				max = Math.max(max, x);
			}
			prev = x;
		}
		s.current = s.source == Source.SERVER && serverLatency >= 0 ? serverLatency : last;
		s.average = n == 0 ? -1 : (double) sum / n;
		s.min = n == 0 ? -1 : min;
		s.max = max;
		if (s.source == Source.ACTIVE) {
			s.jitter = jitterN == 0 ? -1 : (double) jitterSum / jitterN;
			int t = 0;
			for (int i = 0; i < attemptCount; i++) if (attempts[i]) t++;
			s.timeoutPercent = attemptCount < 3 ? -1 : 100.0 * t / attemptCount;
		} else {
			s.jitter = keepAlive.jitter();
			s.timeoutPercent = -1;
		}
		s.timeouts = timeoutsTotal;
		s.stalls = keepAlive.stalls();
		s.tps = tps.tps(nowNanos);
		s.spike = spikeAt != Long.MIN_VALUE && nowMillis - spikeAt < 4000;
		s.spikeValue = spikeValue;
		return s;
	}

	/** Werte für die Anzeige. */
	public static final class Snapshot {
		public Source source = Source.NONE;
		/** Aktueller Ping in ms (-1 = noch keiner). */
		public long current = -1;
		public double average = -1;
		/** Mittlere Änderung zwischen zwei Messungen in ms (-1 = unbekannt). */
		public double jitter = -1;
		public long min = -1;
		public long max = -1;
		/** Anteil Zeitüberschreitungen der letzten Anfragen in % (-1 = unbekannt). */
		public double timeoutPercent = -1;
		public int timeouts;
		public int stalls;
		public double tps = Double.NaN;
		public boolean spike;
		public long spikeValue;
		/** Verlauf, ältester zuerst; -1 = Zeitüberschreitung. */
		public final long[] history = new long[HISTORY];
		public int historyCount;
	}
}

package dev.theredstonee.trsclient.core.net;

import java.util.concurrent.atomic.AtomicLong;

/** Zähler der Netzwerk-Optimierung (für die Modulseite und den Autotest). Threadsicher, ohne Sperren. */
public final class NetStats {
	public final AtomicLong decryptBytes = new AtomicLong();
	public final AtomicLong decryptNanos = new AtomicLong();
	public final AtomicLong decryptCalls = new AtomicLong();
	public final AtomicLong inflateIn = new AtomicLong();
	public final AtomicLong inflateOut = new AtomicLong();
	public final AtomicLong inflateNanos = new AtomicLong();
	public final AtomicLong inflateCalls = new AtomicLong();
	public final AtomicLong deflateBytes = new AtomicLong();
	public final AtomicLong deflateNanos = new AtomicLong();
	public final AtomicLong deflateCalls = new AtomicLong();

	/** Zustand der aktuellen Verbindung (für die Anzeige). */
	public volatile boolean fastDecrypt;
	public volatile boolean encrypted;
	public volatile boolean fastInflate;
	public volatile boolean fastDeflate;
	public volatile boolean compressed;
	/** TCP_NODELAY: -1 unbekannt/kein TCP, 0 aus, 1 an (Vanilla), 2 von TRS eingeschaltet. */
	public volatile int noDelay = -1;

	void decrypted(int bytes, long nanos) {
		decryptBytes.addAndGet(bytes);
		decryptNanos.addAndGet(nanos);
		decryptCalls.incrementAndGet();
	}

	void inflated(int in, int out, long nanos) {
		inflateIn.addAndGet(in);
		inflateOut.addAndGet(out);
		inflateNanos.addAndGet(nanos);
		inflateCalls.incrementAndGet();
	}

	void deflated(int bytes, long nanos) {
		deflateBytes.addAndGet(bytes);
		deflateNanos.addAndGet(nanos);
		deflateCalls.incrementAndGet();
	}

	/** Neue Verbindung: Zustand zurücksetzen (Zähler laufen weiter). */
	void connection() {
		fastDecrypt = false;
		encrypted = false;
		fastInflate = false;
		fastDeflate = false;
		compressed = false;
		noDelay = -1;
	}

	/** Ø Mikrosekunden je Aufruf (0 ohne Aufrufe). */
	public static double microsPerCall(AtomicLong nanos, AtomicLong calls) {
		long c = calls.get();
		return c == 0 ? 0 : nanos.get() / 1000.0 / c;
	}

	/** Durchsatz in MB/s (0 ohne Daten). */
	public static double megabytesPerSecond(AtomicLong bytes, AtomicLong nanos) {
		long n = nanos.get();
		return n == 0 ? 0 : bytes.get() / (n / 1e9) / 1e6;
	}

	public String summary() {
		return String.format(java.util.Locale.ROOT,
				"decrypt %s (%d calls, %.1f MB/s), inflate %s (%d calls, %.1f us avg), deflate %s (%d calls), tcpNoDelay %d",
				fastDecrypt ? "TRS" : (encrypted ? "vanilla" : "off"), decryptCalls.get(),
				megabytesPerSecond(decryptBytes, decryptNanos), fastInflate ? "TRS" : (compressed ? "vanilla" : "off"),
				inflateCalls.get(), microsPerCall(inflateNanos, inflateCalls), fastDeflate ? "TRS" : "vanilla",
				deflateCalls.get(), noDelay);
	}
}

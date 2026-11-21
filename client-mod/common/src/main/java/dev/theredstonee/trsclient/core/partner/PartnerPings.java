package dev.theredstonee.trsclient.core.partner;

import dev.theredstonee.trsclient.core.net.ServerPingTest;
import dev.theredstonee.trsclient.core.net.StatusPing;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Status-Ping (Server List Ping wie in Vanilla) der Partner-Server, solange die Liste zu sehen ist: Spielerzahl und
 * Latenz, alle {@link #EVERY_MS} neu, nach einem Fehler früher.
 */
public final class PartnerPings {
	static final long EVERY_MS = 30_000;
	static final long RETRY_MS = 10_000;
	static final int TIMEOUT_MS = 4000;

	private static final class Entry {
		volatile StatusPing.Result result;
		boolean running;
		long dueAt;
	}

	private final ServerPingTest.Pinger pinger;
	private final Map<String, Entry> entries = new HashMap<String, Entry>();
	private Executor pool;

	PartnerPings() {
		this(new ServerPingTest.Pinger() {
			@Override
			public StatusPing.Result ping(String address, int timeoutMs) {
				return StatusPing.ping(address, timeoutMs);
			}
		}, null);
	}

	PartnerPings(ServerPingTest.Pinger pinger, Executor pool) {
		this.pinger = pinger;
		this.pool = pool;
	}

	/** Letztes Ergebnis (null = noch keins); stößt bei Bedarf einen neuen Ping an. */
	public synchronized StatusPing.Result result(final String address, long now) {
		if (address == null) return null;
		Entry e = entries.get(address);
		if (e == null) {
			if (entries.size() >= 16) entries.clear();
			e = new Entry();
			entries.put(address, e);
		}
		if (!e.running && now >= e.dueAt) {
			e.running = true;
			final Entry entry = e;
			try {
				pool().execute(new Runnable() {
					@Override
					public void run() {
						StatusPing.Result r;
						try {
							r = pinger.ping(address, TIMEOUT_MS);
						} catch (RuntimeException ex) {
							r = null;
						}
						synchronized (PartnerPings.this) {
							entry.running = false;
							if (r != null) entry.result = r;
							entry.dueAt = System.currentTimeMillis() + (r != null && r.ok ? EVERY_MS : RETRY_MS);
						}
					}
				});
			} catch (RuntimeException ex) {
				e.running = false;
				e.dueAt = now + RETRY_MS;
			}
		}
		return e.result;
	}

	/** Läuft gerade ein Ping für die Adresse, ohne dass es schon ein Ergebnis gibt? */
	public synchronized boolean pinging(String address) {
		Entry e = entries.get(address);
		return e != null && e.result == null;
	}

	private Executor pool() {
		if (pool == null) {
			ThreadPoolExecutor ex = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(32),
					r -> {
						Thread t = new Thread(r, "TRS-PartnerPing");
						t.setDaemon(true);
						return t;
					});
			ex.allowCoreThreadTimeOut(true);
			pool = ex;
		}
		return pool;
	}
}

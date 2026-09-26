package dev.theredstonee.trsclient.core.net;

import dev.theredstonee.trsclient.core.menus.ServerPins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * „Ping-Test“ der Mehrspieler-Liste: misst alle Server per {@link StatusPing} (höchstens {@link #PARALLEL} gleichzeitig,
 * je Server eine Verbindung mit festen Zeitgrenzen) und liefert eine Reihenfolge nach Ping. Ein neuer Test geht frühestens
 * nach {@link #COOLDOWN_MS} – damit niemand Server im Sekundentakt anpingt.
 */
public final class ServerPingTest {
	public static final int PARALLEL = 4;
	public static final int TIMEOUT_MS = 3000;
	public static final long COOLDOWN_MS = 10_000;
	static final int MAX_SERVERS = 200;

	/** Austauschbar für Tests. */
	public interface Pinger {
		StatusPing.Result ping(String address, int timeoutMs);
	}

	/** Ergebnis je Server (während des Tests {@code result == null}). */
	public static final class Entry {
		public final String address;
		public volatile StatusPing.Result result;

		Entry(String address) {
			this.address = address;
		}

		public boolean done() {
			return result != null;
		}
	}

	private static final ServerPingTest SHARED = new ServerPingTest(new Pinger() {
		@Override
		public StatusPing.Result ping(String address, int timeoutMs) {
			return StatusPing.ping(address, timeoutMs);
		}
	});

	public static ServerPingTest shared() {
		return SHARED;
	}

	private final Pinger pinger;
	private final Map<String, Entry> entries = new HashMap<String, Entry>();
	private final AtomicInteger pending = new AtomicInteger();
	private ExecutorService pool;
	private long startedAt = Long.MIN_VALUE;
	private int total;

	public ServerPingTest(Pinger pinger) {
		this.pinger = pinger;
	}

	/** Läuft gerade ein Test? */
	public synchronized boolean running() {
		return pending.get() > 0;
	}

	/** Millisekunden, bis ein neuer Test erlaubt ist (0 = jetzt). */
	public synchronized long cooldownLeft(long nowMillis) {
		if (startedAt == Long.MIN_VALUE) return 0;
		return Math.max(0, startedAt + COOLDOWN_MS - nowMillis);
	}

	/** Hat dieser Test schon Ergebnisse (auch halb fertig)? */
	public synchronized boolean hasResults() {
		return !entries.isEmpty();
	}

	public synchronized int total() {
		return total;
	}

	public int finished() {
		return total() - pending.get();
	}

	/**
	 * Startet den Test für diese Adressen (doppelte zählen einmal).
	 *
	 * @return false = läuft schon oder Wartezeit noch nicht um
	 */
	public synchronized boolean start(List<String> addresses, long nowMillis) {
		if (running() || cooldownLeft(nowMillis) > 0) return false;
		entries.clear();
		startedAt = nowMillis;
		List<Entry> todo = new ArrayList<Entry>();
		for (String a : addresses) {
			String key = ServerPins.normalize(a);
			if (key == null || entries.containsKey(key) || entries.size() >= MAX_SERVERS) continue;
			Entry e = new Entry(a);
			entries.put(key, e);
			todo.add(e);
		}
		total = todo.size();
		if (todo.isEmpty()) return true;
		if (pool == null) pool = Executors.newFixedThreadPool(PARALLEL, new ThreadFactory() {
			private final AtomicInteger n = new AtomicInteger();

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "TRS-ServerPing-" + n.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		});
		pending.set(todo.size());
		for (final Entry e : todo) {
			pool.execute(new Runnable() {
				@Override
				public void run() {
					StatusPing.Result r;
					try {
						r = pinger.ping(e.address, TIMEOUT_MS);
					} catch (Throwable t) {
						r = null;
					}
					e.result = r != null ? r : StatusPing.Result.failed("error");
					pending.decrementAndGet();
				}
			});
		}
		return true;
	}

	/** Ergebnis für eine Adresse oder null (nicht im Test). */
	public synchronized Entry entry(String address) {
		String key = ServerPins.normalize(address);
		return key == null ? null : entries.get(key);
	}

	/** Test vergessen (Liste geschlossen) – laufende Pings enden von selbst an ihren Zeitgrenzen. */
	public synchronized void clear() {
		entries.clear();
		total = 0;
	}

	/**
	 * Zielreihenfolge: angeheftete Server bleiben oben (in ihrer Reihenfolge), dann erreichbare nach Ping aufsteigend,
	 * dann nicht erreichbare/ungemessene in bisheriger Reihenfolge. Stabil.
	 *
	 * @param pinned je Index: angeheftet? (null = keine)
	 */
	public synchronized int[] order(List<String> addresses, boolean[] pinned) {
		int n = addresses.size();
		Integer[] idx = new Integer[n];
		final long[] key = new long[n];
		for (int i = 0; i < n; i++) {
			idx[i] = i;
			boolean pin = pinned != null && i < pinned.length && pinned[i];
			Entry e = entry(addresses.get(i));
			StatusPing.Result r = e == null ? null : e.result;
			long lat = r != null && r.ok && r.latencyMs >= 0 ? r.latencyMs : -1;
			// Gruppen: 0 = angeheftet, 1 = mit Ping, 2 = ohne.
			key[i] = pin ? 0 : lat >= 0 ? 1L << 40 | lat : 2L << 40;
		}
		java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
			@Override
			public int compare(Integer a, Integer b) {
				int c = Long.compare(key[a], key[b]);
				return c != 0 ? c : Integer.compare(a, b);
			}
		});
		int[] out = new int[n];
		for (int i = 0; i < n; i++) out[i] = idx[i];
		return out;
	}

	/**
	 * Benachbarte Tausche (i, i+1), die eine Liste in die Zielreihenfolge bringen – so, wie Vanilla Server verschiebt
	 * (Umschalt + Pfeil). Leer, wenn sie schon passt.
	 */
	public static List<int[]> swaps(int[] target) {
		List<int[]> steps = new ArrayList<int[]>();
		int n = target.length;
		int[] cur = new int[n];
		int[] pos = new int[n];
		for (int i = 0; i < n; i++) {
			cur[i] = i;
			pos[i] = i;
		}
		for (int i = 0; i < n; i++) {
			int at = pos[target[i]];
			while (at > i) {
				steps.add(new int[]{at - 1, at});
				int a = cur[at - 1];
				cur[at - 1] = cur[at];
				cur[at] = a;
				pos[cur[at]] = at;
				pos[cur[at - 1]] = at - 1;
				at--;
			}
		}
		return steps;
	}

	/** Anzeige eines Ergebnisses: "23 ms", "…" (läuft) oder "—" (nicht erreichbar); null = nicht im Test. */
	public static String label(Entry e) {
		if (e == null) return null;
		StatusPing.Result r = e.result;
		if (r == null) return "…";
		if (!r.ok || r.latencyMs < 0) return "—";
		return r.latencyMs + " ms";
	}
}

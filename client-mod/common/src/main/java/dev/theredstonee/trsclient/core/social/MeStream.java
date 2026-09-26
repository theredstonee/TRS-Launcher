package dev.theredstonee.trsclient.core.social;

import dev.theredstonee.trsclient.core.online.PlayerEventStream;
import dev.theredstonee.trsclient.core.online.SseParser;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Der Konto-Stream {@code GET /v1/events/me} (API.md §19): ein Lese-Thread, Wiederaufnahme über
 * {@code ?lastEventId=}, Backoff 1 s, 2 s, 5 s … 30 s (zurückgesetzt nach {@code hello}), {@code 401} meldet
 * {@link #takeUnauthorized()}, {@code 429/503} mit {@code Retry-After}. Nach 60 s ohne Daten (Ping alle 20 s) gilt die
 * Verbindung als tot (Lese-Timeout des Openers).
 *
 * <p>Der Spiel-Thread ruft nur {@link #update} (billig, nie blockierend) und holt Ereignisse mit {@link #drain}.
 */
public final class MeStream {
	static final long[] BACKOFF_MS = {1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L};
	/** Obergrenze ungelesener Ereignisse; darüber → {@link #takeOverflow()} (Zustand neu laden). */
	static final int MAX_QUEUE = 2048;

	private final PlayerEventStream.Opener opener;
	private final String apiBase;
	private final ConcurrentLinkedQueue<MeEvent> queue = new ConcurrentLinkedQueue<MeEvent>();
	/** Zuletzt empfangene Ereignis-ID (aus dem Lese-Thread). */
	private volatile String lastEventId;
	private volatile boolean overflow;

	private final class Conn implements Runnable {
		final String token;
		volatile boolean hello;
		volatile boolean dead;
		volatile boolean closed;
		volatile int status;
		volatile long retryAfterMs;
		volatile PlayerEventStream.Connection connection;

		Conn(String token) {
			this.token = token;
		}

		@Override
		public void run() {
			try {
				String id = lastEventId;
				String url = apiBase + "/v1/events/me" + (id == null ? "" : "?lastEventId=" + id);
				PlayerEventStream.Connection c = opener.open(url, token);
				connection = c;
				if (closed) return;
				status = c.status();
				if (status != 200) {
					retryAfterMs = retryAfter(c.header("Retry-After"));
					return;
				}
				SseParser parser = new SseParser();
				String line;
				while (!closed && (line = c.readLine()) != null) {
					SseParser.Raw raw = parser.line(line);
					if (raw == null || raw.event == null) continue;
					if (raw.event.equals("ping")) continue;
					MeEvent e = MeEvent.parse(raw.event, raw.data, raw.id);
					if (closed) break;
					if (e != null) {
						if (e.type.equals("hello")) hello = true;
						if (queue.size() < MAX_QUEUE) queue.add(e);
						else overflow = true;
					}
					// Auch bei unbekannten/kaputten Ereignissen weiterzählen: sie sind angekommen.
					if (raw.id != null && !raw.id.isEmpty()) lastEventId = raw.id;
				}
			} catch (IOException | RuntimeException ignored) {
				// Netzfehler/Zeitüberschreitung → dead, der Spiel-Thread verbindet neu.
			} finally {
				dead = true;
				PlayerEventStream.Connection c = connection;
				if (c != null) c.close();
			}
		}

		void close() {
			closed = true;
			final PlayerEventStream.Connection c = connection;
			if (c == null) return;
			Thread t = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						c.close();
					} catch (RuntimeException ignored) {
						// egal
					}
				}
			}, "TRS-Me-Close");
			t.setDaemon(true);
			t.start();
		}
	}

	// Nur Spiel-Thread:
	private Conn current;
	private long nextConnectAt;
	private int failures;
	private boolean unauthorized;
	private int connects;

	public MeStream(PlayerEventStream.Opener opener, String apiBase) {
		this.opener = opener;
		this.apiBase = apiBase;
	}

	/** Einmal je Tick: {@code want} = Stream gebraucht (angemeldet und Sozialfunktionen an). */
	public void update(long now, String token, boolean want) {
		if (token == null || !want) {
			stop();
			return;
		}
		if (current != null && !token.equals(current.token)) {
			// Neues Token (neu angemeldet, gleiches Konto): neu verbinden, Wiederaufnahme über lastEventId.
			current.close();
			current = null;
			nextConnectAt = 0;
		}
		if (current != null && current.dead) {
			Conn c = current;
			current = null;
			if (c.hello) {
				// Ende nach spätestens 1 h oder Abbruch nach erfolgreichem Start: zügig neu (mit lastEventId).
				failures = 0;
				nextConnectAt = now + BACKOFF_MS[0];
			} else if (c.status == 401) {
				unauthorized = true;
				nextConnectAt = now + BACKOFF_MS[0];
			} else {
				long wait = BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
				failures++;
				if (c.status == 429 || c.status == 503) wait = Math.max(wait, c.retryAfterMs > 0 ? c.retryAfterMs : 10_000L);
				nextConnectAt = now + wait;
			}
		}
		if (current != null && current.hello) failures = 0;
		if (current == null && now >= nextConnectAt) {
			Conn c = new Conn(token);
			current = c;
			connects++;
			Thread t = new Thread(c, "TRS-Me-Events");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			t.start();
		}
	}

	/** Verbindung schließen (abgemeldet, Funktion aus). Die letzte ID bleibt für die Wiederaufnahme. */
	public void stop() {
		if (current != null) current.close();
		current = null;
	}

	/** Kontowechsel: alles vergessen (keine Wiederaufnahme über Konten hinweg). */
	public void reset() {
		stop();
		queue.clear();
		lastEventId = null;
		overflow = false;
		failures = 0;
		nextConnectAt = 0;
	}

	public void drain(Collection<MeEvent> out) {
		MeEvent e;
		while ((e = queue.poll()) != null) out.add(e);
	}

	public boolean connected() {
		Conn c = current;
		return c != null && c.hello && !c.dead;
	}

	/** Liefert 401 nur einmal. */
	public boolean takeUnauthorized() {
		boolean u = unauthorized;
		unauthorized = false;
		return u;
	}

	/** Liefen Ereignisse über (Warteschlange voll)? Dann Zustand per REST neu laden. Nur einmal. */
	public boolean takeOverflow() {
		boolean o = overflow;
		overflow = false;
		return o;
	}

	public String lastEventId() {
		return lastEventId;
	}

	public int connects() {
		return connects;
	}

	static long retryAfter(String raw) {
		if (raw == null) return 0;
		try {
			return Math.max(1, Math.min(900, Long.parseLong(raw.trim()))) * 1000L;
		} catch (NumberFormatException e) {
			return 30_000L;
		}
	}
}

package dev.theredstonee.trsclient.core.online;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Live-Ereignisse über sichtbare Spieler: {@code GET /v1/events/players?uuids=…} (API.md §13, Server-Sent Events).
 *
 * <p>Jeder Stream liest in einem eigenen Daemon-Thread; der Spiel-Thread ruft nur {@link #update} (billig, nie
 * blockierend) und holt Ereignisse mit {@link #drain}. Regeln der API:
 * <ul>
 *   <li>Neue Spieler-Liste = neuen Stream öffnen, erst nach dessen {@code hello} den alten schließen; höchstens
 *       alle {@link #DEBOUNCE_MS} (Verbindungen sind auf 20/min begrenzt, höchstens 3 Streams gleichzeitig).</li>
 *   <li>Abbruch (Stream endet nach spätestens 1 h, Netzfehler): neu verbinden mit Backoff 1 s, 2 s, 5 s … 60 s;
 *       429/503 mit {@code Retry-After}; 401 meldet {@link #takeUnauthorized()} (Token erneuern).</li>
 * </ul>
 */
public final class PlayerEventStream {
	public static final long DEBOUNCE_MS = 10_000L;
	public static final int MAX_UUIDS = 200;
	static final long[] BACKOFF_MS = {1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L};
	/** Keep-alive kommt alle 25 s – nach 60 s Stille gilt die Verbindung als tot. */
	static final int READ_TIMEOUT_MS = 60_000;
	/** Obergrenze ungelesener Ereignisse (danach werden neue verworfen). */
	static final int MAX_QUEUE = 512;

	/** Öffnet eine Stream-Verbindung (austauschbar für Tests). */
	public interface Opener {
		Connection open(String url, String token) throws IOException;
	}

	/** Eine offene Antwort: Status, Kopfzeilen, Zeilen des Körpers. {@link #close} darf aus jedem Thread kommen. */
	public interface Connection {
		int status();

		String header(String name);

		/** Nächste Zeile ohne Zeilenende, null am Ende. */
		String readLine() throws IOException;

		void close();
	}

	private final class Stream implements Runnable {
		final Set<String> uuids;
		final String token;
		final String url;
		volatile boolean hello;
		volatile boolean dead;
		volatile boolean closed;
		volatile int status;
		volatile long retryAfterMs;
		volatile Connection connection;

		Stream(Set<String> uuids, String token) {
			this.uuids = uuids;
			this.token = token;
			StringBuilder sb = new StringBuilder(apiBase).append("/v1/events/players?uuids=");
			boolean first = true;
			for (String u : uuids) {
				if (!first) sb.append(',');
				sb.append(u);
				first = false;
			}
			this.url = sb.toString();
		}

		@Override
		public void run() {
			try {
				Connection c = opener.open(url, token);
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
					if (raw == null) continue;
					PlayerEvent event = PlayerEvent.parse(raw.event, raw.data);
					if (event == null || closed) continue;
					if (event.type.equals("hello")) hello = true;
					else if (!event.type.equals("ping") && queue.size() < MAX_QUEUE) queue.add(event);
				}
			} catch (IOException | RuntimeException ignored) {
				// Netzfehler/Zeitüberschreitung → dead, der Spiel-Thread verbindet neu.
			} finally {
				dead = true;
				Connection c = connection;
				if (c != null) c.close();
			}
		}

		/**
		 * Aus dem Spiel-Thread: nur markieren und die Verbindung im Hintergrund schließen. Ein SSL-Socket zu
		 * schließen, während der Lese-Thread noch darin blockiert, kann bis zum Lese-Timeout (60 s) warten –
		 * im Spiel-Thread fror dadurch das Spiel ein, sobald ein Spieler kam oder ging.
		 */
		void close() {
			closed = true;
			final Connection c = connection;
			if (c != null) closeInBackground(c);
		}
	}

	private static void closeInBackground(final Connection c) {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					c.close();
				} catch (RuntimeException ignored) {
					// egal – die Verbindung ist ohnehin verworfen
				}
			}
		}, "TRS-Events-Close");
		t.setDaemon(true);
		t.start();
	}

	private final Opener opener;
	private final String apiBase;
	private final ConcurrentLinkedQueue<PlayerEvent> queue = new ConcurrentLinkedQueue<>();

	// Nur Spiel-Thread:
	private Stream current;
	private Stream pending;
	private long nextConnectAt;
	private long lastConnectAt = Long.MIN_VALUE / 2;
	private int failures;
	private boolean unauthorized;
	private int connects;

	public PlayerEventStream(Opener opener, String apiBase) {
		this.opener = opener;
		this.apiBase = apiBase;
	}

	/**
	 * Einmal pro Tick: {@code wanted} = UUIDs, deren Ereignisse gebraucht werden (eigene zuerst; höchstens
	 * {@link #MAX_UUIDS} werden benutzt). Leer oder ohne Token → alle Streams zu.
	 */
	public void update(long now, String token, Collection<String> wanted) {
		if (token == null || wanted == null || wanted.isEmpty()) {
			stop();
			return;
		}
		Set<String> want = new LinkedHashSet<>();
		for (String u : wanted) {
			String n = Uuids.normalize(u);
			if (n != null) want.add(n);
			if (want.size() >= MAX_UUIDS) break;
		}
		if (want.isEmpty()) {
			stop();
			return;
		}
		if ((current != null && !token.equals(current.token)) || (pending != null && !token.equals(pending.token))) {
			// Neues Token (neu angemeldet): alte Streams gehören zur alten Sitzung.
			stop();
			nextConnectAt = 0;
		}
		if (pending != null) {
			if (pending.hello) {
				if (current != null) current.close();
				current = pending;
				pending = null;
				failures = 0;
			} else if (pending.dead) {
				failed(pending, now);
				pending = null;
			}
		}
		if (current != null && current.dead) {
			// Ende nach spätestens 1 h oder Verbindungsabbruch: zügig neu verbinden.
			if (current.hello) {
				failures = 0;
				nextConnectAt = now + BACKOFF_MS[0];
			} else {
				failed(current, now);
			}
			current = null;
		}
		boolean need = pending == null && (current == null
				|| (!current.uuids.containsAll(want) && now - lastConnectAt >= DEBOUNCE_MS));
		if (need && now >= nextConnectAt) {
			Stream s = new Stream(want, token);
			pending = s;
			lastConnectAt = now;
			connects++;
			Thread t = new Thread(s, "TRS-Events");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			t.start();
		}
	}

	private void failed(Stream s, long now) {
		if (s.status == 401) {
			unauthorized = true;
			nextConnectAt = now + BACKOFF_MS[0];
			return;
		}
		long wait = BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
		failures++;
		if (s.status == 429 || s.status == 503) wait = Math.max(wait, s.retryAfterMs > 0 ? s.retryAfterMs : 10_000L);
		nextConnectAt = now + wait;
	}

	/** Alle Streams schließen (API aus, Modul aus, abgemeldet). */
	public void stop() {
		if (current != null) current.close();
		if (pending != null) pending.close();
		current = null;
		pending = null;
	}

	/** Neue Ereignisse (älteste zuerst) in {@code out}. */
	public void drain(Collection<PlayerEvent> out) {
		PlayerEvent e;
		while ((e = queue.poll()) != null) out.add(e);
	}

	/** Hat der Server das Token abgelehnt (401)? Liefert das nur einmal. */
	public boolean takeUnauthorized() {
		boolean u = unauthorized;
		unauthorized = false;
		return u;
	}

	/** Steht eine Verbindung (hello empfangen)? */
	public boolean connected() {
		return current != null && current.hello && !current.dead;
	}

	/** UUIDs des aktiven Streams (leer ohne Verbindung). */
	public Set<String> watching() {
		Stream s = current;
		return s == null ? java.util.Collections.<String>emptySet() : java.util.Collections.unmodifiableSet(s.uuids);
	}

	/** Anzahl der Verbindungsversuche (Tests/Statistik). */
	public int connects() {
		return connects;
	}

	static long retryAfter(String raw) {
		if (raw == null) return 0;
		try {
			return Math.max(1, Math.min(900, Long.parseLong(raw.trim()))) * 1000L;
		} catch (NumberFormatException e) {
			return 60_000L;
		}
	}

	/** Standard: HttpURLConnection (TLS über die JVM, keine Weiterleitungen). */
	public static final class UrlOpener implements Opener {
		private final String userAgent;

		public UrlOpener(String userAgent) {
			this.userAgent = userAgent;
		}

		@Override
		public Connection open(String url, String token) throws IOException {
			final HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
			c.setConnectTimeout(Http.CONNECT_TIMEOUT_MS);
			c.setReadTimeout(READ_TIMEOUT_MS);
			c.setInstanceFollowRedirects(false);
			c.setUseCaches(false);
			c.setRequestProperty("User-Agent", userAgent);
			c.setRequestProperty("Accept", "text/event-stream");
			c.setRequestProperty("Accept-Encoding", "identity");
			c.setRequestProperty("Cache-Control", "no-cache");
			c.setRequestProperty("Authorization", "Bearer " + token);
			final int status;
			try {
				status = c.getResponseCode();
			} catch (IOException e) {
				c.disconnect();
				throw e;
			}
			final InputStream in = status == 200 ? new java.io.BufferedInputStream(c.getInputStream(), 4096) : null;
			return new Connection() {
				@Override
				public int status() {
					return status;
				}

				@Override
				public String header(String name) {
					return c.getHeaderField(name);
				}

				@Override
				public String readLine() throws IOException {
					return in == null ? null : readLimitedLine(in, SseParser.MAX_DATA);
				}

				@Override
				public void close() {
					try {
						if (in != null) in.close();
					} catch (IOException ignored) {
						// egal
					}
					c.disconnect();
				}
			};
		}
	}

	/** Liest eine Zeile (LF oder CRLF) als UTF-8; zu lange Zeilen → IOException. null am Ende. */
	static String readLimitedLine(InputStream in, int max) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream(128);
		int b;
		boolean any = false;
		while ((b = in.read()) >= 0) {
			any = true;
			if (b == '\n') break;
			if (line.size() >= max) throw new IOException("Zeile zu lang");
			line.write(b);
		}
		if (!any) return null;
		byte[] bytes = line.toByteArray();
		int len = bytes.length;
		if (len > 0 && bytes[len - 1] == '\r') len--;
		return new String(bytes, 0, len, StandardCharsets.UTF_8);
	}
}

package dev.theredstonee.trsclient.core.connect;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verbindungsaufbau nach „Happy Eyeballs“ (RFC 8305): Bei mehreren Adressen eines Servers wird nicht der Reihe nach
 * je bis zum Zeitlimit des Systems (Windows ~21 s) gewartet, sondern versetzt parallel versucht – die erste Adresse
 * sofort, die nächste nach {@link Settings#delayMs} (oder sofort, wenn ein Versuch scheitert), IPv6 und IPv4
 * abwechselnd. Die erste fertige Verbindung gewinnt, alle anderen werden geschlossen.
 *
 * <p>Jeder Versuch gibt nach {@link Settings#attemptMs} auf, solange es noch andere gibt; der letzte noch laufende
 * Versuch darf bis zum Gesamtlimit {@link Settings#overallMs} weiterlaufen (so überlebt auch eine Verbindung, deren
 * erstes SYN verloren ging). Das eigentliche Verbinden steckt hinter {@link Dialer} – im Spiel nicht blockierende
 * Java-Sockets ({@link NioDialer}), in Tests eine Attrappe mit eigener Uhr.
 */
public final class AddressRacer {
	/** Zeitwerte des Rennens. */
	public static final class Settings {
		/** Versatz bis zum nächsten Versuch (RFC 8305 empfiehlt 250 ms). */
		public final long delayMs;
		/** So lange darf ein Versuch laufen, solange es noch andere gibt. */
		public final long attemptMs;
		/** Grenze für das ganze Rennen. */
		public final long overallMs;

		public Settings(long delayMs, long attemptMs, long overallMs) {
			this.delayMs = delayMs;
			this.attemptMs = attemptMs;
			this.overallMs = overallMs;
		}
	}

	public static final Settings DEFAULT = new Settings(250, 3_000, 12_000);

	/** Verbinden, austauschbar für Tests. {@code H} = Griff eines Versuchs (im Spiel ein {@code SocketChannel}). */
	public interface Dialer<H> {
		/** Verbindung starten (nicht blockierend). Ein sofortiges Ergebnis meldet der nächste {@link #poll}. */
		H start(InetSocketAddress target) throws IOException;

		/** Höchstens {@code timeoutMs} warten; fertige Versuche (verbunden oder gescheitert) in {@code out}. */
		void poll(long timeoutMs, List<Event<H>> out) throws IOException;

		/** Versuch abbrechen/schließen (nie für den Gewinner). */
		void close(H handle);
	}

	/** Ergebnis eines Versuchs aus {@link Dialer#poll}. */
	public static final class Event<H> {
		public final H handle;
		public final boolean connected;
		public final IOException error;

		public Event(H handle, boolean connected, IOException error) {
			this.handle = handle;
			this.connected = connected;
			this.error = error;
		}
	}

	/** Monotone Uhr in ms. */
	public interface Clock {
		long millis();
	}

	public static final Clock MONOTONIC = new Clock() {
		@Override
		public long millis() {
			return System.nanoTime() / 1_000_000L;
		}
	};

	/** Fortschritt fürs UI: Versuch {@code index} (1-basiert) von {@code total} startet. */
	public interface Listener {
		void attempt(int index, int total, InetSocketAddress target);
	}

	/** Abbruch von außen (Spieler hat „Abbrechen“ gedrückt). */
	public interface Cancel {
		boolean cancelled();
	}

	public enum Outcome {
		CONNECTED,
		FAILED,
		/** Eigenes Zeitlimit des Versuchs oder Gesamtlimit erreicht. */
		TIMEOUT,
		/** Lief noch, als ein anderer gewann (oder Abbruch). */
		CANCELLED
	}

	/** Ein Versuch im Protokoll. */
	public static final class Attempt {
		public final InetSocketAddress target;
		public final long startMs;
		public long endMs = -1;
		public Outcome outcome;
		public String error;

		Attempt(InetSocketAddress target, long startMs) {
			this.target = target;
			this.startMs = startMs;
		}

		void end(long now, Outcome o, String err) {
			endMs = now;
			outcome = o;
			error = err;
		}

		@Override
		public String toString() {
			String ip = target.getAddress() == null ? target.getHostString() : target.getAddress().getHostAddress();
			return ip + " " + outcome + (endMs >= 0 ? " " + (endMs - startMs) + " ms" : "") + (error != null ? " (" + error + ")" : "");
		}
	}

	/** Ergebnis: Gewinner oder null (alles gescheitert/abgebrochen), Protokoll aller Versuche. */
	public static final class Result<H> {
		public final H winner;
		public final InetSocketAddress address;
		public final List<Attempt> attempts;
		public final long totalMs;
		public final boolean cancelled;

		Result(H winner, InetSocketAddress address, List<Attempt> attempts, long totalMs, boolean cancelled) {
			this.winner = winner;
			this.address = address;
			this.attempts = attempts;
			this.totalMs = totalMs;
			this.cancelled = cancelled;
		}

		public boolean ok() {
			return winner != null;
		}
	}

	private AddressRacer() {
	}

	/**
	 * Reihenfolge nach RFC 8305 §4: {@code preferred} (zuletzt schnellste Adresse) zuerst, dann abwechselnd die
	 * Familien – beginnend mit der Familie der ersten Adresse; innerhalb einer Familie bleibt die Reihenfolge des
	 * Resolvers. Doppelte Adressen fallen weg.
	 */
	public static List<InetAddress> order(List<InetAddress> in, byte[] preferred) {
		List<InetAddress> all = new ArrayList<InetAddress>();
		for (InetAddress a : in) {
			if (a == null) continue;
			boolean dup = false;
			for (InetAddress b : all) {
				if (Arrays.equals(a.getAddress(), b.getAddress())) {
					dup = true;
					break;
				}
			}
			if (!dup) all.add(a);
		}
		if (preferred != null) {
			for (int i = 0; i < all.size(); i++) {
				if (Arrays.equals(all.get(i).getAddress(), preferred)) {
					all.add(0, all.remove(i));
					break;
				}
			}
		}
		if (all.size() < 2) return all;
		boolean firstV6 = all.get(0) instanceof Inet6Address;
		List<InetAddress> a = new ArrayList<InetAddress>();
		List<InetAddress> b = new ArrayList<InetAddress>();
		for (InetAddress x : all) ((x instanceof Inet6Address) == firstV6 ? a : b).add(x);
		List<InetAddress> out = new ArrayList<InetAddress>();
		int i = 0;
		int j = 0;
		while (i < a.size() || j < b.size()) {
			if (i < a.size()) out.add(a.get(i++));
			if (j < b.size()) out.add(b.get(j++));
		}
		return out;
	}

	/** Das Rennen. Wirft nie wegen eines einzelnen Versuchs; nur Fehler des Dialers selbst (Selector) kommen durch. */
	public static <H> Result<H> race(List<InetSocketAddress> targets, Dialer<H> dialer, Clock clock, Settings s,
			Listener listener, Cancel cancel) throws IOException {
		long start = clock.millis();
		long deadline = start + s.overallMs;
		List<Attempt> attempts = new ArrayList<Attempt>();
		Map<H, Attempt> running = new LinkedHashMap<H, Attempt>();
		List<Event<H>> events = new ArrayList<Event<H>>();
		int next = 0;
		long nextStart = start;
		boolean done = false;
		try {
			while (true) {
				long now = clock.millis();
				if (cancel != null && cancel.cancelled()) {
					closeAll(dialer, running, now, Outcome.CANCELLED);
					done = true;
					return new Result<H>(null, null, attempts, now - start, true);
				}
				if (next < targets.size() && (now >= nextStart || running.isEmpty())) {
					InetSocketAddress t = targets.get(next++);
					Attempt a = new Attempt(t, now);
					attempts.add(a);
					if (listener != null) listener.attempt(next, targets.size(), t);
					try {
						running.put(dialer.start(t), a);
						nextStart = now + s.delayMs;
					} catch (IOException e) {
						a.end(now, Outcome.FAILED, message(e));
						nextStart = now;
					}
					continue;
				}
				// Zeitlimit je Versuch – außer für den letzten, der noch übrig ist.
				Iterator<Map.Entry<H, Attempt>> it = running.entrySet().iterator();
				while (it.hasNext()) {
					Map.Entry<H, Attempt> e = it.next();
					boolean others = next < targets.size() || running.size() > 1;
					if (others && now - e.getValue().startMs >= s.attemptMs) {
						dialer.close(e.getKey());
						e.getValue().end(now, Outcome.TIMEOUT, null);
						it.remove();
						nextStart = now;
					}
				}
				if (running.isEmpty() && next >= targets.size()) {
					done = true;
					return new Result<H>(null, null, attempts, now - start, false);
				}
				if (now >= deadline) {
					closeAll(dialer, running, now, Outcome.TIMEOUT);
					done = true;
					return new Result<H>(null, null, attempts, now - start, false);
				}
				if (running.isEmpty()) continue;
				long wake = deadline;
				if (next < targets.size()) wake = Math.min(wake, nextStart);
				if (next < targets.size() || running.size() > 1) {
					for (Attempt a : running.values()) wake = Math.min(wake, a.startMs + s.attemptMs);
				}
				long wait = Math.max(1, Math.min(wake - now, 250));
				events.clear();
				dialer.poll(wait, events);
				for (Event<H> ev : events) {
					Attempt a = running.remove(ev.handle);
					if (a == null) continue;
					long t = clock.millis();
					if (ev.connected) {
						a.end(t, Outcome.CONNECTED, null);
						closeAll(dialer, running, t, Outcome.CANCELLED);
						// Weitere Gewinner desselben Durchlaufs ebenfalls schließen.
						for (Event<H> other : events) {
							if (other != ev && other.connected && other.handle != ev.handle) dialer.close(other.handle);
						}
						done = true;
						return new Result<H>(ev.handle, a.target, attempts, t - start, false);
					}
					a.end(t, Outcome.FAILED, message(ev.error));
					nextStart = t;
				}
			}
		} finally {
			if (!done) closeAll(dialer, running, clock.millis(), Outcome.CANCELLED);
		}
	}

	private static <H> void closeAll(Dialer<H> dialer, Map<H, Attempt> running, long now, Outcome o) {
		for (Map.Entry<H, Attempt> e : running.entrySet()) {
			dialer.close(e.getKey());
			e.getValue().end(now, o, null);
		}
		running.clear();
	}

	static String message(Throwable e) {
		if (e == null) return null;
		String m = e.getMessage();
		return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
	}
}

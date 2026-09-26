package dev.theredstonee.trsclient.core.hosting;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Postfach der Signalisierung (API.md §21.4): {@code hosting_signal}-Ereignisse aus dem Echtzeit-Stream landen hier,
 * die Verbindungsversuche (Hintergrund-Threads) warten darauf. Zugeordnet wird per Gegenseite + {@code sid}; Signale
 * mit alter/unbekannter sid werden verworfen (neuer Versuch derselben Gegenseite verdrängt den alten).
 *
 * <p>Angebote ({@code offer}) ohne wartenden Versuch gehen an den {@link OfferHandler} (Host).
 */
public final class SignalBox {
	/** Größte Warteschlange je Versuch. */
	static final int MAX_QUEUED = 32;

	/** Ein empfangenes Signal. */
	public static final class Signal {
		public final String from;
		public final String kind;
		public final String sid;
		public final String data;

		public Signal(String from, String kind, String sid, String data) {
			this.from = from;
			this.kind = kind;
			this.sid = sid;
			this.data = data;
		}
	}

	/** Host: neues Angebot eines Gasts. */
	public interface OfferHandler {
		void offer(String roomId, Signal s);
	}

	/** Ein laufender Versuch (eine Gegenseite, eine sid). */
	public final class Session {
		final String peer;
		final String sid;
		private final ArrayDeque<Signal> queue = new ArrayDeque<Signal>();
		private boolean closed;

		Session(String peer, String sid) {
			this.peer = peer;
			this.sid = sid;
		}

		void put(Signal s) {
			synchronized (this) {
				if (closed || queue.size() >= MAX_QUEUED) return;
				queue.add(s);
				notifyAll();
			}
		}

		/** Nächstes Signal (blockierend bis {@code timeoutMs}); null = Zeit um oder Versuch beendet. */
		public Signal poll(long timeoutMs) throws InterruptedException {
			long end = System.currentTimeMillis() + timeoutMs;
			synchronized (this) {
				while (queue.isEmpty() && !closed) {
					long left = end - System.currentTimeMillis();
					if (left <= 0) return null;
					wait(left);
				}
				return queue.poll();
			}
		}

		/** Ohne Warten. */
		public Signal pollNow() {
			synchronized (this) {
				return queue.poll();
			}
		}

		public boolean isClosed() {
			synchronized (this) {
				return closed;
			}
		}

		public String sid() {
			return sid;
		}

		public String peer() {
			return peer;
		}

		/** Beenden und austragen. */
		public void close() {
			synchronized (this) {
				closed = true;
				notifyAll();
			}
			synchronized (SignalBox.this) {
				if (sessions.get(peer) == this) sessions.remove(peer);
			}
		}
	}

	private final Map<String, Session> sessions = new HashMap<String, Session>();
	private volatile OfferHandler offers;
	private int dropped;

	public void offers(OfferHandler h) {
		this.offers = h;
	}

	/** Neuen Versuch mit {@code peer} anmelden (verdrängt einen älteren). */
	public Session open(String peer, String sid) {
		Session s = new Session(peer, sid);
		Session old;
		synchronized (this) {
			old = sessions.put(peer, s);
		}
		if (old != null) old.close();
		return s;
	}

	/** Ereignis {@code hosting_signal} (beliebiger Thread). */
	public void deliver(String roomId, Signal s) {
		if (s == null || s.from == null || s.kind == null) return;
		Session target;
		synchronized (this) {
			target = sessions.get(s.from);
		}
		if (target != null && target.sid.equals(s.sid)) {
			target.put(s);
			return;
		}
		if ("offer".equals(s.kind)) {
			OfferHandler h = offers;
			if (h != null) {
				h.offer(roomId, s);
				return;
			}
		}
		synchronized (this) {
			dropped++;
		}
	}

	/** Alles beenden (Welt zu, Kontowechsel). */
	public void clear() {
		Session[] all;
		synchronized (this) {
			all = sessions.values().toArray(new Session[0]);
			sessions.clear();
		}
		for (Session s : all) s.close();
	}

	synchronized int dropped() {
		return dropped;
	}

	synchronized int size() {
		return sessions.size();
	}
}

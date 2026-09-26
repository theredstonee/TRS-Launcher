package dev.theredstonee.trsclient.core.hosting.net;

import java.util.ArrayDeque;

/**
 * Zuverlässiger, geordneter Bytestrom über unzuverlässige Datagramme (direkte Verbindung beim Welt-Hosting).
 *
 * <p>Eigene, kompakte Umsetzung (angelehnt an TCP/KCP-Grundideen, kein fremder Code): Segmente ≤ {@link #MSS} Bytes
 * mit fortlaufender Nummer, kumulative Bestätigung + 32-Bit-SACK-Maske, Empfangsfenster, Neuübertragung nach RTO
 * (RFC 6298, mit Karn), schnelle Neuübertragung nach drei späteren Bestätigungen, Überlastkontrolle AIMD (Slow Start
 * → Congestion Avoidance, Halbieren bei Verlust), Bündelgrenze je Takt, Lebenszeichen und Abbruch nach
 * {@link #DEAD_MS} Stille. FIN läuft als nummeriertes Segment, also erst nach allen Daten.
 *
 * <p>Nicht threadsicher: gehört genau einem Netz-Thread ({@link UdpLink}); die Zeit kommt von außen (testbar).
 *
 * <p>Segment: {@code type u8 | seq u32 | ack u32 | sack u32 | wnd u16 | payload}.
 */
public final class Rudp {
	public static final int HEADER = 15;
	/** Nutzdaten je Segment (Datagramm insgesamt ≈ 1170 Bytes inkl. Rahmen/Prüfsumme – passt in jede MTU). */
	public static final int MSS = 1100;
	/** Empfangsfenster in Segmenten. */
	public static final int WINDOW = 1024;
	public static final int MAX_CWND = 768;
	public static final int INIT_CWND = 24;
	public static final long MIN_RTO = 150L;
	public static final long MAX_RTO = 3000L;
	/** Ohne jedes Paket so lange → tot. */
	public static final long DEAD_MS = 15_000L;
	/** Lebenszeichen, wenn so lange nichts gesendet wurde. */
	public static final long KEEPALIVE_MS = 1_000L;
	/** Größter ungesendeter Rückstau (Bytes), danach Abbruch. */
	public static final long MAX_BACKLOG = 64L * 1024 * 1024;

	static final int T_DATA = 1;
	static final int T_ACK = 2;
	static final int T_FIN = 3;

	/** Ausgang: ein fertiges Segment verschicken (Puffer darf danach wiederverwendet werden). */
	public interface Output {
		void send(byte[] segment, int len);
	}

	/** Eingang in Reihenfolge. */
	public interface Receiver {
		void data(byte[] b, int off, int len);

		/** Gegenseite hat geordnet geschlossen (FIN) oder die Verbindung ist tot. */
		void closed(String reason);
	}

	private static final class Seg {
		int seq;
		byte type;
		byte[] data;
		long sentAt;
		long deadline;
		int sends;
		boolean sacked;
		int skips;
		boolean fastRetx;
	}

	private final Output out;
	private final Receiver receiver;
	private final byte[] buf = new byte[HEADER + MSS];

	// Senden
	private final ArrayDeque<Seg> queue = new ArrayDeque<Seg>();
	private long queuedBytes;
	private final Seg[] flight = new Seg[WINDOW * 2];
	private int sndUna;
	private int sndNxt;
	private int peerWnd = WINDOW;
	private double cwnd = INIT_CWND;
	private double ssthresh = MAX_CWND;
	private int recoverSeq;
	private boolean inRecovery;
	private long srtt = -1;
	private long rttvar;
	private long rto = 400L;
	private long lastSend;
	private boolean finQueued;

	// Empfangen
	private int rcvNxt;
	private final byte[][] rcvBuf = new byte[WINDOW][];
	private final byte[] rcvType = new byte[WINDOW];
	private final boolean[] rcvHave = new boolean[WINDOW];
	private boolean ackPending;
	private int unackedIn;
	private long lastRecv;

	private boolean closed;
	private String closeReason;

	// Zähler (Tests/Anzeige)
	private long retransmits;
	private long segmentsSent;

	public Rudp(Output out, Receiver receiver, long now) {
		this.out = out;
		this.receiver = receiver;
		this.lastRecv = now;
		this.lastSend = now;
	}

	// --- Senden ---

	/** Bytes anhängen. false = geschlossen oder Rückstau zu groß (dann geschlossen). */
	public boolean send(byte[] b, int off, int len) {
		if (closed || finQueued) return false;
		while (len > 0) {
			int n = Math.min(MSS, len);
			Seg last = queue.peekLast();
			if (last != null && last.type == T_DATA && last.data.length < MSS) {
				// Kleine Schreibvorgänge zusammenfassen (Minecraft schreibt paketweise).
				int take = Math.min(n, MSS - last.data.length);
				byte[] merged = new byte[last.data.length + take];
				System.arraycopy(last.data, 0, merged, 0, last.data.length);
				System.arraycopy(b, off, merged, last.data.length, take);
				last.data = merged;
				off += take;
				len -= take;
				queuedBytes += take;
				continue;
			}
			Seg s = new Seg();
			s.type = T_DATA;
			s.data = new byte[n];
			System.arraycopy(b, off, s.data, 0, n);
			queue.addLast(s);
			queuedBytes += n;
			off += n;
			len -= n;
		}
		if (queuedBytes > MAX_BACKLOG) {
			close("backlog");
			return false;
		}
		return true;
	}

	/** Geordnet schließen: FIN hinter die Daten. */
	public void finish() {
		if (closed || finQueued) return;
		finQueued = true;
		Seg s = new Seg();
		s.type = T_FIN;
		s.data = new byte[0];
		queue.addLast(s);
	}

	/** Sofort schließen (ohne FIN). */
	public void close(String reason) {
		if (closed) return;
		closed = true;
		closeReason = reason;
		receiver.closed(reason);
	}

	public boolean isClosed() {
		return closed;
	}

	public String closeReason() {
		return closeReason;
	}

	/** Alles gesendet und bestätigt (auch das FIN)? */
	public boolean drained() {
		return queue.isEmpty() && sndUna == sndNxt;
	}

	public long queuedBytes() {
		return queuedBytes;
	}

	public long retransmits() {
		return retransmits;
	}

	public long segmentsSent() {
		return segmentsSent;
	}

	public double cwnd() {
		return cwnd;
	}

	public long srtt() {
		return srtt;
	}

	// --- Takt ---

	/** Regelmäßig (alle paar Millisekunden) und nach jedem Eingang: senden, neu senden, bestätigen. */
	public void tick(long now) {
		if (closed) return;
		if (now - lastRecv > DEAD_MS) {
			close("timeout");
			return;
		}
		boolean sentData = false;
		// Neuübertragungen (RTO und schnell).
		int lostThisTick = 0;
		for (int s = sndUna; diff(s, sndNxt) < 0; s++) {
			Seg seg = flight[slot(s)];
			if (seg == null || seg.sacked) continue;
			boolean fast = seg.skips >= 3 && !seg.fastRetx;
			if (now >= seg.deadline || fast) {
				if (fast) seg.fastRetx = true;
				else lostThisTick++;
				transmit(seg, now);
				retransmits++;
				sentData = true;
				if (!inRecovery) {
					inRecovery = true;
					recoverSeq = sndNxt;
					ssthresh = Math.max(4, cwnd / 2);
					cwnd = fast ? ssthresh : Math.max(4, cwnd / 2);
				}
			}
		}
		if (lostThisTick > 0) rto = Math.min(MAX_RTO, rto * 3 / 2);
		// Neue Segmente im Fenster.
		int limit = (int) Math.min(cwnd, peerWnd);
		int burst = Math.max(16, (int) (cwnd / 4));
		while (!queue.isEmpty() && diff(sndNxt, sndUna) < limit && burst-- > 0) {
			Seg seg = queue.pollFirst();
			queuedBytes -= seg.data.length;
			seg.seq = sndNxt++;
			flight[slot(seg.seq)] = seg;
			transmit(seg, now);
			sentData = true;
		}
		if (!sentData && (ackPending || now - lastSend >= KEEPALIVE_MS)) sendAck(now);
		ackPending = false;
		unackedIn = 0;
	}

	private void transmit(Seg seg, long now) {
		if (seg.sends == 0) seg.sentAt = now;
		seg.sends++;
		seg.skips = 0;
		seg.deadline = now + Math.min(MAX_RTO, rto << Math.min(4, seg.sends - 1));
		int n = header(seg.type, seg.seq);
		System.arraycopy(seg.data, 0, buf, HEADER, seg.data.length);
		out.send(buf, n + seg.data.length);
		segmentsSent++;
		lastSend = now;
		ackPending = false;
	}

	private void sendAck(long now) {
		int n = header(T_ACK, sndNxt);
		out.send(buf, n);
		lastSend = now;
	}

	private int header(int type, int seq) {
		buf[0] = (byte) type;
		Stun.putInt(buf, 1, seq);
		Stun.putInt(buf, 5, rcvNxt);
		Stun.putInt(buf, 9, sackMask());
		int used = 0;
		for (int i = 0; i < WINDOW; i++) if (rcvHave[i]) used++;
		Stun.putShort(buf, 13, Math.max(0, WINDOW - used));
		return HEADER;
	}

	private int sackMask() {
		int m = 0;
		for (int i = 0; i < 32; i++) {
			int s = rcvNxt + 1 + i;
			if (rcvHave[rslot(s)] && rcvBuf[rslot(s)] != null) m |= 1 << i;
		}
		return m;
	}

	// --- Empfangen ---

	/** Ein Segment von der Gegenseite (schon authentifiziert). */
	public void input(byte[] b, int off, int len, long now) {
		if (closed || len < HEADER) return;
		int type = b[off] & 0xFF;
		if (type != T_DATA && type != T_ACK && type != T_FIN) return;
		lastRecv = now;
		int seq = Stun.getInt(b, off + 1);
		int ack = Stun.getInt(b, off + 5);
		int sack = Stun.getInt(b, off + 9);
		peerWnd = Math.max(1, Stun.getShort(b, off + 13));
		onAck(ack, sack, now);
		if (type == T_ACK) return;
		ackPending = true;
		unackedIn++;
		int d = diff(seq, rcvNxt);
		if (d < 0 || d >= WINDOW) return; // alt oder zu weit vorn: nur bestätigen
		int slot = rslot(seq);
		if (!rcvHave[slot]) {
			rcvHave[slot] = true;
			rcvType[slot] = (byte) type;
			byte[] copy = new byte[len - HEADER];
			System.arraycopy(b, off + HEADER, copy, 0, copy.length);
			rcvBuf[slot] = copy;
		}
		// In Reihenfolge ausliefern.
		while (rcvHave[rslot(rcvNxt)]) {
			int s = rslot(rcvNxt);
			byte[] data = rcvBuf[s];
			byte t = rcvType[s];
			rcvHave[s] = false;
			rcvBuf[s] = null;
			rcvNxt++;
			if (t == T_FIN) {
				sendAck(now);
				close("closed by peer");
				return;
			}
			if (data.length > 0) receiver.data(data, 0, data.length);
			if (closed) return;
		}
		if (unackedIn >= 2 || d > 0) {
			// Lücke oder genug gesammelt: sofort bestätigen (schnelle Neuübertragung beim Sender).
			sendAck(now);
			ackPending = false;
			unackedIn = 0;
		}
	}

	private void onAck(int ack, int sack, long now) {
		if (diff(ack, sndUna) > 0 && diff(ack, sndNxt) <= 0) {
			for (int s = sndUna; diff(s, ack) < 0; s++) {
				Seg seg = flight[slot(s)];
				if (seg == null) continue;
				if (seg.sends == 1) rttSample(now - seg.sentAt);
				flight[slot(s)] = null;
				grow();
			}
			sndUna = ack;
			if (inRecovery && diff(sndUna, recoverSeq) >= 0) inRecovery = false;
		}
		if (sack != 0) {
			int highest = -1;
			for (int i = 0; i < 32; i++) {
				if ((sack & (1 << i)) == 0) continue;
				int s = ack + 1 + i;
				if (diff(s, sndUna) < 0 || diff(s, sndNxt) >= 0) continue;
				Seg seg = flight[slot(s)];
				if (seg != null && !seg.sacked) {
					seg.sacked = true;
					if (seg.sends == 1) rttSample(now - seg.sentAt);
					grow();
				}
				highest = i;
			}
			if (highest >= 0) {
				int top = ack + 1 + highest;
				for (int s = sndUna; diff(s, top) < 0; s++) {
					Seg seg = flight[slot(s)];
					if (seg != null && !seg.sacked) seg.skips++;
				}
			}
		}
	}

	private void grow() {
		if (inRecovery) return;
		if (cwnd < ssthresh) cwnd += 1;
		else cwnd += 1.0 / cwnd;
		if (cwnd > MAX_CWND) cwnd = MAX_CWND;
	}

	private void rttSample(long r) {
		if (r < 0) return;
		if (srtt < 0) {
			srtt = r;
			rttvar = r / 2;
		} else {
			rttvar = (3 * rttvar + Math.abs(srtt - r)) / 4;
			srtt = (7 * srtt + r) / 8;
		}
		rto = Math.max(MIN_RTO, Math.min(MAX_RTO, srtt + Math.max(10, 4 * rttvar)));
	}

	private static int slot(int seq) {
		return seq & (WINDOW * 2 - 1);
	}

	private static int rslot(int seq) {
		return seq & (WINDOW - 1);
	}

	/** Vorzeichenrichtiger Abstand zweier Sequenznummern (Überlauf-fest). */
	static int diff(int a, int b) {
		return a - b;
	}
}

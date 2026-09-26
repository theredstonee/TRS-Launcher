package dev.theredstonee.trsclient.core.hosting.net;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;

/**
 * Direkte Verbindung über UDP: Kandidaten sammeln (eigene Adressen + STUN), Löcher stanzen, dann ein zuverlässiger
 * Strom ({@link Rudp}) mit authentifizierten Datagrammen ({@link P2pKeys}).
 *
 * <p>Ablauf: {@link #open} → {@link #hostCandidates}/{@link #stun} (blockierend, Hintergrund-Thread) → Kandidaten über
 * die Signalisierung tauschen → {@link #punch} (blockierend, bis ein Paar steht oder die Zeit um ist) → {@link #begin}
 * startet den Netz-Thread; ab dann ist es ein {@link PeerStream}.
 *
 * <p>Datagramm: {@code "TRSP" | type u8 | connId u32 | body | HMAC-12}. Typen: PROBE/PROBE_ACK (Nonce u64 + Rolle),
 * SELECT (der Gast wählt das Paar), SEG (Rudp-Segment), CLOSE.
 */
public final class UdpLink implements PeerStream {
	/** Wer wählt das Kandidatenpaar: der Gast (CONTROLLING) oder der Host (CONTROLLED). */
	public enum Role {
		CONTROLLING, CONTROLLED
	}

	static final int P_PROBE = 0x01;
	static final int P_PROBE_ACK = 0x02;
	static final int P_SELECT = 0x03;
	static final int P_SEG = 0x10;
	static final int P_CLOSE = 0x1F;
	static final int HEAD = 9;
	static final int MAX_DATAGRAM = 1400;
	/** Abstand der Lochstanz-Proben. */
	static final long PROBE_EVERY_MS = 40L;
	static final int MAX_REMOTE = 12;
	/** Größter Rückstau aus Minecraft, bevor die Verbindung aufgibt. */
	static final long MAX_PENDING = 48L * 1024 * 1024;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final DatagramChannel channel;
	private final Selector selector;
	private final ByteBuffer rx = ByteBuffer.allocate(2048);
	private final byte[] tx = new byte[MAX_DATAGRAM + 64];
	private final ConcurrentLinkedQueue<InetSocketAddress> newRemotes = new ConcurrentLinkedQueue<InetSocketAddress>();
	private final ConcurrentLinkedQueue<byte[]> outbox = new ConcurrentLinkedQueue<byte[]>();
	private final AtomicLong pending = new AtomicLong();

	private P2pKeys keys;
	private Mac mac;
	private Role role;
	private InetSocketAddress peer;
	private Rudp rudp;
	private volatile Sink sink;
	private final List<byte[]> early = new ArrayList<byte[]>();
	/** Datagramme, die schon während {@link #punch} kamen (erstes SEG beim Host). */
	private final List<byte[]> earlyPackets = new ArrayList<byte[]>();
	private volatile boolean open = true;
	private volatile boolean closing;
	private volatile String closeReason;
	private Thread thread;
	/** Nur für Tests: Anteil verworfener ausgehender Datagramme. */
	volatile double lossForTest;
	private final Random lossRandom = new Random(7);

	private UdpLink(DatagramChannel channel, Selector selector) {
		this.channel = channel;
		this.selector = selector;
	}

	/** Neuer UDP-Anschluss auf einem freien Port (alle IPv4-Schnittstellen). */
	public static UdpLink open() throws IOException {
		DatagramChannel ch = DatagramChannel.open();
		try {
			ch.bind(new InetSocketAddress(0));
			ch.configureBlocking(false);
			Selector sel = Selector.open();
			ch.register(sel, SelectionKey.OP_READ);
			return new UdpLink(ch, sel);
		} catch (IOException | RuntimeException e) {
			ch.close();
			throw e;
		}
	}

	public int localPort() {
		try {
			return ((InetSocketAddress) channel.getLocalAddress()).getPort();
		} catch (IOException e) {
			return 0;
		}
	}

	/**
	 * Eigene Kandidaten („host“): alle IPv4-Adressen aktiver Schnittstellen mit unserem Port. Loopback nur auf Wunsch
	 * (Tests/zwei Spiele auf einem Rechner).
	 */
	public List<InetSocketAddress> hostCandidates(boolean loopback) {
		int port = localPort();
		List<InetSocketAddress> out = new ArrayList<InetSocketAddress>();
		try {
			Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
			while (all != null && all.hasMoreElements()) {
				NetworkInterface ni = all.nextElement();
				try {
					if (!ni.isUp() || ni.isPointToPoint()) continue;
					if (ni.isLoopback() && !loopback) continue;
				} catch (SocketException e) {
					continue;
				}
				for (InetAddress a : Collections.list(ni.getInetAddresses())) {
					if (!(a instanceof Inet4Address) || a.isLinkLocalAddress() || a.isAnyLocalAddress()) continue;
					if (a.isLoopbackAddress() && !loopback) continue;
					out.add(new InetSocketAddress(a, port));
					if (out.size() >= 6) return out;
				}
			}
		} catch (SocketException ignored) {
			// keine Schnittstellen lesbar
		}
		if (loopback && out.isEmpty()) out.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
		return out;
	}

	/**
	 * Öffentliche Adresse per STUN über DIESEN Anschluss (nötig, damit die NAT-Zuordnung zum späteren Datenverkehr
	 * passt). Fragt alle Server, wiederholt alle 250 ms, blockiert höchstens {@code timeoutMs}. null = keine Antwort.
	 */
	public InetSocketAddress stun(List<InetSocketAddress> servers, long timeoutMs) throws IOException {
		if (servers == null || servers.isEmpty()) return null;
		byte[] txid = new byte[12];
		RANDOM.nextBytes(txid);
		byte[] req = Stun.bindingRequest(txid);
		long end = System.currentTimeMillis() + timeoutMs;
		long nextSend = 0;
		while (true) {
			long now = System.currentTimeMillis();
			if (now >= end) return null;
			if (now >= nextSend) {
				for (InetSocketAddress s : servers) {
					if (s.isUnresolved()) continue;
					channel.send(ByteBuffer.wrap(req), s);
				}
				nextSend = now + 250;
			}
			selector.select(Math.max(1, Math.min(end, nextSend) - now));
			selector.selectedKeys().clear();
			SocketAddress from;
			while ((from = receive()) != null) {
				InetSocketAddress mapped = Stun.parseBindingResponse(rx.array(), 0, rx.position(), txid);
				if (mapped != null && from instanceof InetSocketAddress) return mapped;
			}
		}
	}

	/** Weitere Kandidaten der Gegenseite (Signal „candidate“) – auch während {@link #punch}. */
	public void addRemote(InetSocketAddress a) {
		if (a != null && !a.isUnresolved()) {
			newRemotes.add(a);
			selector.wakeup();
		}
	}

	/**
	 * Löcher stanzen: an alle Kandidaten der Gegenseite Proben schicken, auf Proben antworten. Der Gast wählt das
	 * erste Paar mit Antwort (SELECT), der Host übernimmt es beim ersten gültigen SELECT/SEG. true = Paar steht.
	 */
	public boolean punch(P2pKeys keys, Role role, List<InetSocketAddress> remote, long timeoutMs) throws IOException {
		this.keys = keys;
		this.mac = keys.mac();
		this.role = role;
		Set<InetSocketAddress> targets = new LinkedHashSet<InetSocketAddress>();
		if (remote != null) {
			for (InetSocketAddress a : remote) if (a != null && !a.isUnresolved() && targets.size() < MAX_REMOTE) targets.add(a);
		}
		long end = System.currentTimeMillis() + timeoutMs;
		long nextProbe = 0;
		long nonce = RANDOM.nextLong();
		int selectSent = 0;
		InetSocketAddress chosen = null;
		while (open) {
			InetSocketAddress extra;
			while ((extra = newRemotes.poll()) != null) if (targets.size() < MAX_REMOTE) targets.add(extra);
			long now = System.currentTimeMillis();
			if (now >= end) return false;
			if (now >= nextProbe) {
				nextProbe = now + PROBE_EVERY_MS;
				if (chosen != null) {
					sendControl(P_SELECT, nonce, chosen);
					if (++selectSent >= 3) {
						peer = chosen;
						return true;
					}
				} else {
					for (InetSocketAddress t : targets) sendControl(P_PROBE, nonce, t);
				}
			}
			selector.select(Math.max(1, Math.min(end, nextProbe) - now));
			selector.selectedKeys().clear();
			SocketAddress from;
			while ((from = receive()) != null) {
				if (!(from instanceof InetSocketAddress)) continue;
				InetSocketAddress src = (InetSocketAddress) from;
				int type = authentic(rx.array(), rx.position());
				if (type < 0) continue;
				int bodyLen = rx.position() - HEAD - P2pKeys.TAG;
				if (type == P_PROBE && bodyLen >= 9) {
					if (rx.get(HEAD + 8) == (byte) role.ordinal()) continue; // eigenes Echo
					sendControl(P_PROBE_ACK, Stun.getInt(rx.array(), HEAD) & 0xFFFFFFFFL, src);
				} else if (type == P_PROBE_ACK && role == Role.CONTROLLING && chosen == null) {
					chosen = src;
					nextProbe = 0;
				} else if ((type == P_SELECT || type == P_SEG) && role == Role.CONTROLLED) {
					peer = src;
					if (type == P_SEG) earlyPackets.add(java.util.Arrays.copyOf(rx.array(), rx.position()));
					return true;
				}
			}
		}
		return false;
	}

	/** Gewähltes Paar (nach {@link #punch}). */
	public InetSocketAddress peer() {
		return peer;
	}

	/** Netz-Thread starten (nach erfolgreichem {@link #punch}). */
	public void begin(String threadName) {
		if (peer == null || thread != null) throw new IllegalStateException("not connected");
		long now = System.currentTimeMillis();
		rudp = new Rudp(new Rudp.Output() {
			@Override
			public void send(byte[] segment, int len) {
				sendPacket(P_SEG, segment, len, peer);
			}
		}, new Rudp.Receiver() {
			@Override
			public void data(byte[] b, int off, int len) {
				deliver(b, off, len);
			}

			@Override
			public void closed(String reason) {
				closeReason = reason;
			}
		}, now);
		thread = new Thread(new Runnable() {
			@Override
			public void run() {
				loop();
			}
		}, threadName);
		thread.setDaemon(true);
		thread.start();
	}

	private void loop() {
		String reason = null;
		long closingSince = 0;
		try {
			for (byte[] p : earlyPackets) {
				rx.clear();
				rx.put(p);
				handle(peer, System.currentTimeMillis());
			}
			earlyPackets.clear();
			while (true) {
				long now = System.currentTimeMillis();
				byte[] chunk;
				while ((chunk = outbox.poll()) != null) {
					pending.addAndGet(-chunk.length);
					rudp.send(chunk, 0, chunk.length);
				}
				if (closing) {
					if (closingSince == 0) {
						closingSince = now;
						rudp.finish();
					}
					if (rudp.drained() || now - closingSince > 2000) {
						reason = closeReason == null ? "closed" : closeReason;
						sendControl(P_CLOSE, 0, peer);
						sendControl(P_CLOSE, 0, peer);
						break;
					}
				}
				rudp.tick(now);
				if (rudp.isClosed()) {
					reason = rudp.closeReason();
					break;
				}
				selector.select(outbox.isEmpty() ? 5 : 1);
				selector.selectedKeys().clear();
				SocketAddress from;
				while ((from = receive()) != null) {
					if (from instanceof InetSocketAddress) handle((InetSocketAddress) from, System.currentTimeMillis());
					if (rudp.isClosed()) break;
				}
				if (rudp.isClosed()) {
					reason = rudp.closeReason();
					break;
				}
			}
		} catch (IOException | RuntimeException e) {
			reason = "error: " + e.getClass().getSimpleName();
		} finally {
			shutdown(reason == null ? "closed" : reason);
		}
	}

	private void handle(InetSocketAddress src, long now) {
		int type = authentic(rx.array(), rx.position());
		if (type < 0) return;
		int bodyLen = rx.position() - HEAD - P2pKeys.TAG;
		if (type == P_SEG) {
			if (!src.equals(peer)) peer = src; // NAT hat umgebunden – authentifiziert, also folgen
			rudp.input(rx.array(), HEAD, bodyLen, now);
		} else if (type == P_PROBE && bodyLen >= 9 && rx.get(HEAD + 8) != (byte) role.ordinal()) {
			sendControl(P_PROBE_ACK, 0, src);
		} else if (type == P_CLOSE) {
			rudp.close("closed by peer");
		}
	}

	/** Paket-Typ, wenn Magie, Verbindung und Prüfsumme stimmen, sonst -1. Erwartet das Datagramm in {@code rx}. */
	private int authentic(byte[] b, int len) {
		if (len < HEAD + P2pKeys.TAG || b[0] != 'T' || b[1] != 'R' || b[2] != 'S' || b[3] != 'P') return -1;
		if (keys == null || Stun.getInt(b, 5) != keys.connId()) return -1;
		if (!P2pKeys.verify(mac, b, 0, len)) return -1;
		return b[4] & 0xFF;
	}

	private void sendControl(int type, long nonce, InetSocketAddress to) {
		byte[] body = new byte[9];
		Stun.putInt(body, 0, (int) (nonce >>> 32));
		Stun.putInt(body, 4, (int) nonce);
		body[8] = (byte) role.ordinal();
		sendPacket(type, body, body.length, to);
	}

	private void sendPacket(int type, byte[] body, int len, InetSocketAddress to) {
		if (to == null || keys == null) return;
		tx[0] = 'T';
		tx[1] = 'R';
		tx[2] = 'S';
		tx[3] = 'P';
		tx[4] = (byte) type;
		Stun.putInt(tx, 5, keys.connId());
		System.arraycopy(body, 0, tx, HEAD, len);
		int n = HEAD + len;
		P2pKeys.seal(mac, tx, n);
		n += P2pKeys.TAG;
		if (lossForTest > 0 && type == P_SEG && lossRandom.nextDouble() < lossForTest) return;
		try {
			channel.send(ByteBuffer.wrap(tx, 0, n), to);
		} catch (IOException ignored) {
			// UDP: verloren ist verloren – Rudp sendet neu.
		}
	}

	private SocketAddress receive() throws IOException {
		rx.clear();
		SocketAddress from = channel.receive(rx);
		return from;
	}

	private void deliver(byte[] b, int off, int len) {
		Sink s = sink;
		if (s != null) {
			s.data(b, off, len);
			return;
		}
		synchronized (early) {
			s = sink;
			if (s == null) {
				early.add(java.util.Arrays.copyOfRange(b, off, off + len));
				return;
			}
		}
		s.data(b, off, len);
	}

	// --- PeerStream ---

	@Override
	public void start(Sink s) {
		synchronized (early) {
			for (byte[] b : early) s.data(b, 0, b.length);
			early.clear();
			this.sink = s;
		}
		if (!open) reportClosed(closeReason == null ? "closed" : closeReason);
	}

	@Override
	public boolean write(byte[] b, int off, int len) {
		if (!open || closing) return false;
		if (pending.addAndGet(len) > MAX_PENDING) {
			close("send backlog");
			return false;
		}
		outbox.add(java.util.Arrays.copyOfRange(b, off, off + len));
		selector.wakeup();
		return true;
	}

	@Override
	public void close(String reason) {
		if (!open) return;
		if (closeReason == null) closeReason = reason;
		if (thread == null) {
			shutdown(reason);
			return;
		}
		closing = true;
		selector.wakeup();
	}

	private void shutdown(String reason) {
		boolean was;
		synchronized (this) {
			was = open;
			open = false;
		}
		try {
			selector.close();
		} catch (IOException ignored) {
			// egal
		}
		try {
			channel.close();
		} catch (IOException ignored) {
			// egal
		}
		if (!was) return;
		reportClosed(reason);
	}

	private final java.util.concurrent.atomic.AtomicBoolean closedReported = new java.util.concurrent.atomic.AtomicBoolean();

	private void reportClosed(String reason) {
		Sink s = sink;
		if (s != null && closedReported.compareAndSet(false, true)) s.closed(reason);
	}

	@Override
	public boolean isOpen() {
		return open && !closing;
	}

	@Override
	public Path path() {
		return Path.DIRECT;
	}

	@Override
	public InetSocketAddress remoteAddress() {
		InetSocketAddress p = peer;
		return p != null ? p : new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
	}

	/** Für Anzeige/Tests. */
	public Rudp rudp() {
		return rudp;
	}

	/** "1.2.3.4:5678" → Adresse (nur IPv4-Literale, keine DNS-Abfrage) oder null. */
	public static InetSocketAddress parseCandidate(String s) {
		if (s == null || s.length() > 21) return null;
		int colon = s.lastIndexOf(':');
		if (colon <= 0) return null;
		String host = s.substring(0, colon);
		if (!host.matches("(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])){3}")) return null;
		int port;
		try {
			port = Integer.parseInt(s.substring(colon + 1));
		} catch (NumberFormatException e) {
			return null;
		}
		if (port < 1 || port > 65535) return null;
		String[] parts = host.split("\\.");
		byte[] a = new byte[4];
		for (int i = 0; i < 4; i++) a[i] = (byte) Integer.parseInt(parts[i]);
		try {
			InetAddress ip = InetAddress.getByAddress(a);
			if (ip.isAnyLocalAddress() || ip.isMulticastAddress()) return null;
			return new InetSocketAddress(ip, port);
		} catch (java.net.UnknownHostException e) {
			return null;
		}
	}

	public static String formatCandidate(InetSocketAddress a) {
		return a.getAddress().getHostAddress() + ":" + a.getPort();
	}
}

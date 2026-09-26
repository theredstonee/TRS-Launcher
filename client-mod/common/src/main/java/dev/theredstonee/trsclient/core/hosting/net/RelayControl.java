package dev.theredstonee.trsclient.core.hosting.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Kontrollverbindung des Hosts zum TRS Relay (PROTOCOL.md §2.3): HOST_HELLO mit Host-Token, danach meldet das Relay
 * neue Gäste ({@code GUEST_OPEN pairId+uuid}); der Host öffnet je Gast eine Datenverbindung ({@link Relay#pair}).
 * PING alle {@link #PING_MS}, KICK/CLOSE_GUEST auf Wunsch. Eigener Lese-Thread; Schreiben ist synchronisiert.
 */
public final class RelayControl {
	public static final long PING_MS = 15_000L;

	/** Rückrufe aus dem Lese-Thread. */
	public interface Listener {
		void guestOpen(byte[] pairId, String uuid);

		void guestClosed(byte[] pairId);

		/** Verbindung zu (Fehlercode des Relay oder "closed"/"connection lost"). Danach kommt nichts mehr. */
		void closed(String code);
	}

	private final Socket socket;
	private final OutputStream out;
	private final Listener listener;
	private final String welcome;
	private volatile boolean open = true;
	private volatile long lastWrite;

	private RelayControl(Socket socket, String welcome, Listener listener) throws IOException {
		this.socket = socket;
		this.out = socket.getOutputStream();
		this.welcome = welcome;
		this.listener = listener;
		this.lastWrite = System.currentTimeMillis();
	}

	/** Verbinden und anmelden (blockierend). RelayException bei ERROR (z. B. expired → neues Token holen). */
	public static RelayControl connect(String host, int port, String hostToken, Listener listener) throws IOException {
		Socket s = Relay.connect(host, port);
		try {
			String welcome = Relay.handshake(s, Relay.HOST_HELLO, hostToken.getBytes(StandardCharsets.US_ASCII));
			final RelayControl c = new RelayControl(s, welcome, listener);
			Thread t = new Thread(new Runnable() {
				@Override
				public void run() {
					c.readLoop();
				}
			}, "TRS-Relay-Kontrolle");
			t.setDaemon(true);
			t.start();
			return c;
		} catch (IOException | RuntimeException e) {
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
			throw e;
		}
	}

	/** WELCOME-JSON (z. B. maxGuests). */
	public String welcome() {
		return welcome;
	}

	public boolean isOpen() {
		return open;
	}

	private void readLoop() {
		String reason = "connection lost";
		try {
			InputStream in = socket.getInputStream();
			while (true) {
				Relay.Frame f = Relay.read(in, Relay.MAX_FRAME);
				if (f.type == Relay.GUEST_OPEN && f.payload.length == 32) {
					byte[] pair = java.util.Arrays.copyOf(f.payload, 16);
					listener.guestOpen(pair, Relay.uuidHex(f.payload, 16));
				} else if (f.type == Relay.GUEST_CLOSED && f.payload.length == 16) {
					listener.guestClosed(f.payload);
				} else if (f.type == Relay.PING) {
					send(Relay.PONG, f.payload);
				} else if (f.type == Relay.ERROR) {
					reason = Relay.clean(f.text());
					break;
				}
				// PONG und Unbekanntes: ignorieren.
			}
		} catch (EOFException e) {
			reason = open ? "connection lost" : "closed";
		} catch (IOException e) {
			reason = open ? "connection lost" : "closed";
		} finally {
			open = false;
			try {
				socket.close();
			} catch (IOException ignored) {
				// egal
			}
			listener.closed(reason);
		}
	}

	/** Aus dem Takt des Hosts: PING, wenn länger nichts geschrieben wurde. */
	public void tick(long now) {
		if (open && now - lastWrite >= PING_MS) send(Relay.PING, new byte[] { 't', 'r', 's' });
	}

	/** Spieler hinauswerfen (alle Verbindungen + gesperrt, solange diese Kontrollverbindung lebt). */
	public void kick(String uuid) {
		send(Relay.KICK, Relay.uuidBytes(uuid));
	}

	public void closeGuest(byte[] pairId) {
		send(Relay.CLOSE_GUEST, pairId);
	}

	private void send(int type, byte[] payload) {
		if (!open) return;
		byte[] f = Relay.frame(type, payload);
		synchronized (out) {
			try {
				out.write(f);
				out.flush();
				lastWrite = System.currentTimeMillis();
			} catch (IOException e) {
				close();
			}
		}
	}

	/** Schließen – das Relay trennt dann alle Gäste dieser Welt. */
	public void close() {
		open = false;
		try {
			socket.close();
		} catch (IOException ignored) {
			// egal
		}
	}
}

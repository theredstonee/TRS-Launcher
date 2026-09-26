package dev.theredstonee.trsclient.core.hosting.net;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * TCP-Protokoll des TRS Relay (relay/PROTOCOL.md §2): Präambel {@code "TRSR" 01}, Frames {@code type u8 | len u16 |
 * payload}. Hilfen zum Bauen/Lesen und die beiden Handschläge (Gast, Host-Datenverbindung). Die Kontrollverbindung
 * des Hosts steckt in {@link RelayControl}, der Rohstrom in {@link RelayStream}.
 */
public final class Relay {
	public static final byte[] PREAMBLE = { 0x54, 0x52, 0x53, 0x52, 0x01 };
	public static final int MAX_FRAME = 1024;

	public static final int HOST_HELLO = 0x01;
	public static final int GUEST_HELLO = 0x02;
	public static final int PAIR = 0x03;
	public static final int GUEST_OPEN = 0x20;
	public static final int GUEST_CLOSED = 0x21;
	public static final int CLOSE_GUEST = 0x22;
	public static final int KICK = 0x23;
	public static final int PING = 0x30;
	public static final int PONG = 0x31;
	public static final int WELCOME = 0x81;
	public static final int ERROR = 0x8F;

	public static final int CONNECT_TIMEOUT_MS = 6000;
	/** Handschlag-Antwort (WELCOME/ERROR) spätestens nach … (Relay wartet beim Gast bis 10 s auf den Host). */
	public static final int HANDSHAKE_TIMEOUT_MS = 14_000;

	private Relay() {
	}

	/** Fehler des Relay ({@code ERROR}-Frame) oder im Handschlag. {@link #code} ist ein Code aus PROTOCOL.md §2.5. */
	public static final class RelayException extends IOException {
		private static final long serialVersionUID = 1L;
		public final String code;

		public RelayException(String code) {
			super("relay: " + code);
			this.code = code;
		}

		/** Mit frischem Token nochmal versuchen? */
		public boolean tokenProblem() {
			return "expired".equals(code) || "bad_token".equals(code);
		}

		/** Nach Wartezeit nochmal versuchen? */
		public boolean backOff() {
			return "rate_limited".equals(code) || "server_full".equals(code) || "shutting_down".equals(code);
		}
	}

	/** Ein gelesenes Frame. */
	public static final class Frame {
		public final int type;
		public final byte[] payload;

		Frame(int type, byte[] payload) {
			this.type = type;
			this.payload = payload;
		}

		public String text() {
			return new String(payload, StandardCharsets.US_ASCII);
		}
	}

	public static byte[] frame(int type, byte[] payload) {
		int n = payload == null ? 0 : payload.length;
		if (n > 0xFFFF) throw new IllegalArgumentException("frame too large");
		byte[] out = new byte[3 + n];
		out[0] = (byte) type;
		out[1] = (byte) (n >>> 8);
		out[2] = (byte) n;
		if (n > 0) System.arraycopy(payload, 0, out, 3, n);
		return out;
	}

	public static byte[] frame(int type, String ascii) {
		return frame(type, ascii.getBytes(StandardCharsets.US_ASCII));
	}

	/** Liest genau ein Frame (blockierend). Zu groß → RelayException("bad_frame"), Ende → EOFException. */
	public static Frame read(InputStream in, int max) throws IOException {
		DataInputStream d = in instanceof DataInputStream ? (DataInputStream) in : new DataInputStream(in);
		int type = d.read();
		if (type < 0) throw new EOFException();
		int len = d.readUnsignedShort();
		if (len > max) throw new RelayException("bad_frame");
		byte[] p = new byte[len];
		d.readFully(p);
		return new Frame(type, p);
	}

	/** 32 Hex-Zeichen → 16 Bytes. */
	public static byte[] uuidBytes(String hex) {
		if (hex == null || !hex.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("uuid");
		byte[] b = new byte[16];
		for (int i = 0; i < 16; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		return b;
	}

	public static String uuidHex(byte[] b, int off) {
		StringBuilder sb = new StringBuilder(32);
		for (int i = 0; i < 16; i++) sb.append(String.format(Locale.ROOT, "%02x", b[off + i] & 0xFF));
		return sb.toString();
	}

	static Socket connect(String host, int port) throws IOException {
		Socket s = new Socket();
		try {
			s.setTcpNoDelay(true);
			s.setKeepAlive(true);
			s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
			return s;
		} catch (IOException | RuntimeException e) {
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
			throw e;
		}
	}

	/**
	 * Handschlag senden und auf WELCOME warten. {@code extra} = Bytes, die direkt hinter dem Hello mitgehen dürfen.
	 * Liefert den WELCOME-Text (JSON). ERROR → RelayException mit Code.
	 */
	static String handshake(Socket s, int helloType, byte[] helloPayload) throws IOException {
		OutputStream out = s.getOutputStream();
		byte[] f = frame(helloType, helloPayload);
		byte[] all = new byte[PREAMBLE.length + f.length];
		System.arraycopy(PREAMBLE, 0, all, 0, PREAMBLE.length);
		System.arraycopy(f, 0, all, PREAMBLE.length, f.length);
		out.write(all);
		out.flush();
		int old = s.getSoTimeout();
		s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
		try {
			Frame r = read(s.getInputStream(), MAX_FRAME);
			if (r.type == ERROR) throw new RelayException(clean(r.text()));
			if (r.type != WELCOME) throw new RelayException("bad_frame");
			return new String(r.payload, StandardCharsets.UTF_8);
		} catch (java.net.SocketTimeoutException e) {
			throw new RelayException("timeout");
		} finally {
			s.setSoTimeout(old);
		}
	}

	static String clean(String code) {
		return code != null && code.matches("[a-z_]{1,40}") ? code : "unknown";
	}

	/** Gast: Verbindung zum Host über das Relay (nach WELCOME Rohmodus). */
	public static RelayStream guest(String host, int port, String token) throws IOException {
		Socket s = connect(host, port);
		try {
			handshake(s, GUEST_HELLO, token.getBytes(StandardCharsets.US_ASCII));
			return new RelayStream(s, "TRS-Relay-Gast");
		} catch (IOException | RuntimeException e) {
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
			throw e;
		}
	}

	/** Host: Datenverbindung für einen Gast ({@code GUEST_OPEN} → neue Verbindung + PAIR). */
	public static RelayStream pair(String host, int port, byte[] pairId) throws IOException {
		Socket s = connect(host, port);
		try {
			handshake(s, PAIR, pairId);
			return new RelayStream(s, "TRS-Relay-Host");
		} catch (IOException | RuntimeException e) {
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
			throw e;
		}
	}
}

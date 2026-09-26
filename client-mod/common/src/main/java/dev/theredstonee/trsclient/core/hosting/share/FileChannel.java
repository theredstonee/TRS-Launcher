package dev.theredstonee.trsclient.core.hosting.share;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Datei-Kanal des Welt-Hostings (docs/hosting-files.md): ein eigener Strom zwischen Gast und Host – über das TRS Relay
 * (eigene Relay-Verbindung) oder direkt (eigene P2P-Verbindung) –, der mit {@link #MAGIC} beginnt. Der Host erkennt ihn
 * am ersten Byte ({@link Sniffer}): Minecraft beginnt nie mit {@code 0x00} (Paketlänge ≥ 1 bzw. {@code 0xFE}).
 *
 * <pre>
 * Gast → Host: 00 'T' 'R' 'S' 'F' 01, dann Frames
 * Frame:       type u8 | length u32 (BE) | payload
 * GET   0x01   kind u8 (1 = Mod, 2 = Resource Pack) + SHA-256 (32 Bytes)
 * BYE   0x02   –
 * FILE  0x81   Größe u64
 * DATA  0x82   ≤ 64 KiB Daten
 * END   0x83   SHA-256 (32) der gesendeten Bytes
 * ERROR 0x8F   ASCII-Code (not_shared, not_found, changed, rate_limited, busy, bad_request, not_allowed)
 * </pre>
 *
 * Der Gast fragt nur nach Hashes, nie nach Pfaden; der Host liefert nur Dateien aus seiner Freigabeliste.
 */
public final class FileChannel {
	public static final byte[] MAGIC = { 0x00, 0x54, 0x52, 0x53, 0x46, 0x01 };
	public static final int GET = 0x01;
	public static final int BYE = 0x02;
	public static final int FILE = 0x81;
	public static final int DATA = 0x82;
	public static final int END = 0x83;
	public static final int ERROR = 0x8F;
	public static final int KIND_MOD = 1;
	public static final int KIND_PACK = 2;
	/** Größte Nutzlast eines DATA-Frames. */
	public static final int CHUNK = 64 * 1024;
	/** Größte Nutzlast eines Frames vom Gast. */
	public static final int MAX_REQUEST = 64;

	private FileChannel() {
	}

	/** Ein Frame. */
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

	public static byte[] frame(int type, byte[] payload, int off, int len) {
		byte[] out = new byte[5 + len];
		out[0] = (byte) type;
		out[1] = (byte) (len >>> 24);
		out[2] = (byte) (len >>> 16);
		out[3] = (byte) (len >>> 8);
		out[4] = (byte) len;
		System.arraycopy(payload, off, out, 5, len);
		return out;
	}

	public static byte[] frame(int type, byte[] payload) {
		return frame(type, payload, 0, payload.length);
	}

	public static byte[] get(int kind, byte[] sha256) {
		byte[] p = new byte[33];
		p[0] = (byte) kind;
		System.arraycopy(sha256, 0, p, 1, 32);
		return frame(GET, p);
	}

	public static byte[] error(String code) {
		return frame(ERROR, code.getBytes(StandardCharsets.US_ASCII));
	}

	/** Liest genau ein Frame; zu groß → IOException, Ende → EOFException. */
	public static Frame read(InputStream in, int max) throws IOException {
		int type = in.read();
		if (type < 0) throw new EOFException();
		byte[] h = readFully(in, 4);
		long len = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
		if (len > max) throw new IOException("frame too large");
		return new Frame(type, readFully(in, (int) len));
	}

	static byte[] readFully(InputStream in, int n) throws IOException {
		byte[] b = new byte[n];
		int off = 0;
		while (off < n) {
			int r = in.read(b, off, n - off);
			if (r < 0) throw new EOFException();
			off += r;
		}
		return b;
	}

	public static long u64(byte[] b, int off) {
		long v = 0;
		for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFF);
		return v;
	}

	public static byte[] u64(long v) {
		byte[] b = new byte[8];
		for (int i = 7; i >= 0; i--) {
			b[i] = (byte) v;
			v >>>= 8;
		}
		return b;
	}

	public static byte[] unhex(String hex) {
		if (hex == null || (hex.length() & 1) != 0) return null;
		byte[] b = new byte[hex.length() / 2];
		for (int i = 0; i < b.length; i++) {
			int hi = Character.digit(hex.charAt(i * 2), 16);
			int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
			if (hi < 0 || lo < 0) return null;
			b[i] = (byte) (hi << 4 | lo);
		}
		return b;
	}

	/** Nur bekannte Fehlercodes durchlassen (für Anzeige/Log). */
	public static String safeCode(String code) {
		return code != null && code.matches("not_shared|not_found|changed|rate_limited|busy|bad_request|not_allowed|too_large")
				? code : "error";
	}

	// --- Blockierendes Lesen aus einem PeerStream ---

	/** Macht aus dem Push-Empfang eines {@link PeerStream} einen blockierenden Eingabestrom (mit Zeitlimit). */
	public static final class Input extends InputStream implements PeerStream.Sink {
		private static final long MAX_BUFFERED = 8L * 1024 * 1024;
		private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();
		private byte[] head;
		private int headPos;
		private long buffered;
		private boolean closed;
		private String reason;
		private volatile long timeoutMs = 60_000L;

		public void timeout(long ms) {
			timeoutMs = ms;
		}

		@Override
		public synchronized void data(byte[] b, int off, int len) {
			if (closed || len <= 0) return;
			if (buffered + len > MAX_BUFFERED) {
				// Gegenseite hält sich nicht an das Protokoll (wir fragen immer nur eine Datei an).
				closed = true;
				reason = "overflow";
				notifyAll();
				return;
			}
			queue.add(Arrays.copyOfRange(b, off, off + len));
			buffered += len;
			notifyAll();
		}

		@Override
		public synchronized void closed(String why) {
			closed = true;
			if (reason == null) reason = why;
			notifyAll();
		}

		public synchronized String reason() {
			return reason;
		}

		private synchronized boolean fill() throws IOException {
			long end = System.currentTimeMillis() + timeoutMs;
			while ((head == null || headPos >= head.length) && queue.isEmpty()) {
				if (closed) return false;
				long left = end - System.currentTimeMillis();
				if (left <= 0) throw new InterruptedIOException("timeout");
				try {
					wait(left);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("interrupted");
				}
			}
			if (head == null || headPos >= head.length) {
				head = queue.poll();
				headPos = 0;
				buffered -= head.length;
			}
			return true;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int n = read(one, 0, 1);
			return n < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public synchronized int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) return 0;
			if (!fill()) return -1;
			int n = Math.min(len, head.length - headPos);
			System.arraycopy(head, headPos, b, off, n);
			headPos += n;
			return n;
		}
	}

	// --- Host: Strom am ersten Byte erkennen ---

	/** Wohin ein neuer Gast-Strom gehört. */
	public interface Route {
		/** Minecraft-Verbindung (gepufferte Bytes kommen zuerst). */
		void minecraft(PeerStream stream);

		/** Datei-Kanal (die {@link #MAGIC} ist schon gelesen). */
		void files(PeerStream stream);
	}

	/**
	 * Liest die ersten Bytes eines Stroms und gibt ihn dann – mit genau diesen Bytes vorn – an Minecraft oder den
	 * Datei-Kanal weiter. Minecraft wird sofort beim ersten Byte ≠ 0 weitergereicht (keine Verzögerung).
	 */
	public static void route(final PeerStream stream, final Route route) {
		final Replay replay = new Replay(stream);
		stream.start(new PeerStream.Sink() {
			private final byte[] head = new byte[MAGIC.length];
			private int have;
			private int decided; // 0 offen, 1 Minecraft, 2 Dateien

			@Override
			public void data(byte[] b, int off, int len) {
				if (decided != 0) {
					replay.data(b, off, len);
					return;
				}
				int i = off;
				int end = off + len;
				while (i < end && have < MAGIC.length) {
					head[have++] = b[i++];
					if (head[0] != 0) break;
				}
				if (head[0] != 0) {
					decided = 1;
					replay.data(head, 0, have);
					if (i < end) replay.data(b, i, end - i);
					route.minecraft(replay);
					return;
				}
				if (have < MAGIC.length) return;
				if (Arrays.equals(head, MAGIC)) {
					decided = 2;
					if (i < end) replay.data(b, i, end - i);
					route.files(replay);
				} else {
					decided = 1;
					replay.data(head, 0, have);
					if (i < end) replay.data(b, i, end - i);
					route.minecraft(replay);
				}
			}

			@Override
			public void closed(String reason) {
				replay.closed(reason);
			}
		});
	}

	/** Strom mit vorgeschalteten, schon gelesenen Bytes (für {@link #route}). */
	static final class Replay implements PeerStream {
		private final PeerStream inner;
		private final List<byte[]> early = new ArrayList<byte[]>();
		private Sink sink;
		private String closedReason;

		Replay(PeerStream inner) {
			this.inner = inner;
		}

		synchronized void data(byte[] b, int off, int len) {
			if (sink == null) {
				early.add(Arrays.copyOfRange(b, off, off + len));
				return;
			}
			sink.data(b, off, len);
		}

		void closed(String reason) {
			Sink s;
			synchronized (this) {
				closedReason = reason == null ? "closed" : reason;
				s = sink;
			}
			if (s != null) s.closed(closedReason);
		}

		@Override
		public void start(Sink s) {
			String reason;
			synchronized (this) {
				for (byte[] b : early) s.data(b, 0, b.length);
				early.clear();
				sink = s;
				reason = closedReason;
			}
			if (reason != null) s.closed(reason);
		}

		@Override
		public boolean write(byte[] b, int off, int len) {
			return inner.write(b, off, len);
		}

		@Override
		public void close(String reason) {
			inner.close(reason);
		}

		@Override
		public boolean isOpen() {
			return inner.isOpen();
		}

		@Override
		public Path path() {
			return inner.path();
		}

		@Override
		public InetSocketAddress remoteAddress() {
			return inner.remoteAddress();
		}

		@Override
		public long pendingBytes() {
			return inner.pendingBytes();
		}
	}

	/** Mit Gegendruck schreiben: wartet, bis weniger als {@code maxPending} Bytes unterwegs sind. */
	static boolean writeBlocking(PeerStream s, byte[] b, long maxPending, long timeoutMs) throws InterruptedIOException {
		long end = System.currentTimeMillis() + timeoutMs;
		while (s.isOpen() && s.pendingBytes() > maxPending) {
			if (System.currentTimeMillis() > end) return false;
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("interrupted");
			}
		}
		return s.write(b, 0, b.length);
	}
}

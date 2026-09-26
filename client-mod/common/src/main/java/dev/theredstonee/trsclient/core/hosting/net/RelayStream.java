package dev.theredstonee.trsclient.core.hosting.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rohstrom über eine TCP-Verbindung (Relay nach WELCOME, oder in Tests eine beliebige Socket). Ein Lese-Thread
 * liefert an den {@link PeerStream.Sink}, ein Schreib-Thread leert die Warteschlange – {@link #write} blockiert nie.
 */
public class RelayStream implements PeerStream {
	static final long MAX_PENDING = 48L * 1024 * 1024;
	private static final byte[] EOF = new byte[0];
	private static final InetSocketAddress PLACEHOLDER = placeholder();

	private final Socket socket;
	private final String name;
	private final LinkedBlockingQueue<byte[]> outbox = new LinkedBlockingQueue<byte[]>();
	private final AtomicLong pending = new AtomicLong();
	private final List<byte[]> early = new ArrayList<byte[]>();
	private volatile Sink sink;
	private volatile boolean open = true;
	private volatile String closeReason;
	private boolean started;

	RelayStream(Socket socket, String name) {
		this.socket = socket;
		this.name = name;
		Thread r = new Thread(new Runnable() {
			@Override
			public void run() {
				readLoop();
			}
		}, name + "-Lesen");
		r.setDaemon(true);
		r.start();
		Thread w = new Thread(new Runnable() {
			@Override
			public void run() {
				writeLoop();
			}
		}, name + "-Schreiben");
		w.setDaemon(true);
		w.start();
	}

	/** Für Tests: beliebige, schon verbundene Socket als Strom. */
	public static RelayStream of(Socket socket) {
		return new RelayStream(socket, "TRS-Strom");
	}

	private static InetSocketAddress placeholder() {
		try {
			return new InetSocketAddress(InetAddress.getByAddress("trs-relay", new byte[] { 127, 0, 0, 1 }), 0);
		} catch (UnknownHostException e) {
			return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
		}
	}

	private void readLoop() {
		String reason = "closed";
		try {
			InputStream in = socket.getInputStream();
			byte[] buf = new byte[16384];
			int n;
			while ((n = in.read(buf)) >= 0) {
				if (n > 0) deliver(buf, n);
			}
		} catch (IOException e) {
			reason = open ? "connection lost" : "closed";
		} finally {
			finish(closeReason != null ? closeReason : reason);
		}
	}

	private void deliver(byte[] buf, int n) {
		Sink s = sink;
		if (s == null) {
			synchronized (early) {
				s = sink;
				if (s == null) {
					early.add(java.util.Arrays.copyOf(buf, n));
					return;
				}
			}
		}
		s.data(buf, 0, n);
	}

	private void writeLoop() {
		try {
			OutputStream out = socket.getOutputStream();
			while (true) {
				byte[] b = outbox.take();
				if (b == EOF) break;
				pending.addAndGet(-b.length);
				out.write(b);
				if (outbox.isEmpty()) out.flush();
			}
			out.flush();
			socket.shutdownOutput();
		} catch (IOException | InterruptedException e) {
			// Verbindung weg – der Lese-Thread meldet das Ende.
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) {
				// egal
			}
		}
	}

	private void finish(String reason) {
		open = false;
		outbox.offer(EOF);
		try {
			socket.close();
		} catch (IOException ignored) {
			// egal
		}
		reportClosed(reason);
	}

	private boolean closedReported;

	private void reportClosed(String reason) {
		Sink s;
		synchronized (early) {
			s = sink;
			if (s == null || closedReported) return;
			closedReported = true;
		}
		s.closed(reason);
	}

	@Override
	public void start(Sink s) {
		synchronized (early) {
			if (started) throw new IllegalStateException("started");
			started = true;
			for (byte[] b : early) s.data(b, 0, b.length);
			early.clear();
			sink = s;
		}
		if (!open) reportClosed(closeReason == null ? "closed" : closeReason);
	}

	@Override
	public boolean write(byte[] b, int off, int len) {
		if (!open) return false;
		if (pending.addAndGet(len) > MAX_PENDING) {
			close("send backlog");
			return false;
		}
		outbox.offer(java.util.Arrays.copyOfRange(b, off, off + len));
		return true;
	}

	@Override
	public void close(String reason) {
		if (!open) return;
		if (closeReason == null) closeReason = reason;
		open = false;
		// Erst den Rest schreiben, dann schließt der Schreib-Thread die Socket; der Lese-Thread meldet das Ende.
		outbox.offer(EOF);
	}

	@Override
	public boolean isOpen() {
		return open;
	}

	@Override
	public Path path() {
		return Path.RELAY;
	}

	@Override
	public InetSocketAddress remoteAddress() {
		return PLACEHOLDER;
	}

	@Override
	public String toString() {
		return name;
	}
}

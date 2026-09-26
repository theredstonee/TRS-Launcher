package dev.theredstonee.trsclient.core.hosting.share;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test: zwei verbundene {@link PeerStream}-Enden im Speicher (Zustellung asynchron, in Reihenfolge). */
final class SharePipe implements PeerStream {
	private final ExecutorService deliver = Executors.newSingleThreadExecutor();
	private SharePipe other;
	private Sink sink;
	private final List<byte[]> early = new ArrayList<byte[]>();
	private volatile boolean open = true;
	private final Path path;
	final List<String> closeReasons = new java.util.concurrent.CopyOnWriteArrayList<String>();

	private SharePipe(Path path) {
		this.path = path;
	}

	/** [Gast-Ende, Host-Ende]. */
	static SharePipe[] pair(Path path) {
		SharePipe a = new SharePipe(path);
		SharePipe b = new SharePipe(path);
		a.other = b;
		b.other = a;
		return new SharePipe[] { a, b };
	}

	private void receive(final byte[] data) {
		deliver.execute(new Runnable() {
			@Override
			public void run() {
				Sink s;
				synchronized (SharePipe.this) {
					if (sink == null) {
						early.add(data);
						return;
					}
					s = sink;
				}
				s.data(data, 0, data.length);
			}
		});
	}

	private void remoteClosed() {
		deliver.execute(new Runnable() {
			@Override
			public void run() {
				open = false;
				Sink s;
				synchronized (SharePipe.this) {
					s = sink;
				}
				if (s != null) s.closed("closed by peer");
			}
		});
	}

	@Override
	public synchronized void start(Sink s) {
		for (byte[] b : early) s.data(b, 0, b.length);
		early.clear();
		sink = s;
	}

	@Override
	public boolean write(byte[] b, int off, int len) {
		if (!open) return false;
		other.receive(java.util.Arrays.copyOfRange(b, off, off + len));
		return true;
	}

	@Override
	public void close(String reason) {
		closeReasons.add(reason);
		if (!open) return;
		open = false;
		other.remoteClosed();
		deliver.execute(new Runnable() {
			@Override
			public void run() {
				Sink s;
				synchronized (SharePipe.this) {
					s = sink;
				}
				if (s != null) s.closed("closed");
			}
		});
	}

	@Override
	public boolean isOpen() {
		return open;
	}

	@Override
	public Path path() {
		return path;
	}

	@Override
	public InetSocketAddress remoteAddress() {
		return new InetSocketAddress(InetAddress.getLoopbackAddress(), 1);
	}
}

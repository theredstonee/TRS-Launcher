package dev.theredstonee.trsclient.core.hosting;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test: zwei verbundene {@link PeerStream}-Enden im Speicher (Zustellung asynchron, in Reihenfolge). */
final class PipeStream implements PeerStream {
	private final ExecutorService deliver = Executors.newSingleThreadExecutor();
	private PipeStream other;
	private Sink sink;
	private final List<byte[]> early = new ArrayList<byte[]>();
	private volatile boolean open = true;
	private final Path path;

	private PipeStream(Path path) {
		this.path = path;
	}

	static PipeStream[] pair(Path path) {
		PipeStream a = new PipeStream(path);
		PipeStream b = new PipeStream(path);
		a.other = b;
		b.other = a;
		return new PipeStream[] { a, b };
	}

	private void receive(final byte[] data) {
		deliver.execute(new Runnable() {
			@Override
			public void run() {
				synchronized (PipeStream.this) {
					if (sink == null) {
						early.add(data);
						return;
					}
				}
				sink.data(data, 0, data.length);
			}
		});
	}

	private void remoteClosed() {
		deliver.execute(new Runnable() {
			@Override
			public void run() {
				open = false;
				Sink s;
				synchronized (PipeStream.this) {
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
		if (!open) return;
		open = false;
		other.remoteClosed();
		deliver.execute(new Runnable() {
			@Override
			public void run() {
				Sink s;
				synchronized (PipeStream.this) {
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

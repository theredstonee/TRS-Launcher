package dev.theredstonee.trsclient.core.connect;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Echter {@link AddressRacer.Dialer}: nicht blockierende {@link SocketChannel}s an einem eigenen {@link Selector}.
 * Der Gewinner bleibt offen und nicht blockierend – Netty übernimmt ihn ({@link FastNioChannel}). {@link #close()}
 * schließt nur den Selector (der Gewinner wird dabei bloß abgemeldet).
 */
public final class NioDialer implements AddressRacer.Dialer<SocketChannel>, Closeable {
	private final Selector selector;
	private final List<AddressRacer.Event<SocketChannel>> immediate = new ArrayList<AddressRacer.Event<SocketChannel>>();

	public NioDialer() throws IOException {
		selector = Selector.open();
	}

	@Override
	public SocketChannel start(InetSocketAddress target) throws IOException {
		SocketChannel ch = SocketChannel.open();
		try {
			ch.configureBlocking(false);
			try {
				ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
			} catch (IOException | UnsupportedOperationException ignored) {
				// Netty setzt es später ohnehin noch einmal
			}
			if (ch.connect(target)) immediate.add(new AddressRacer.Event<SocketChannel>(ch, true, null));
			else ch.register(selector, SelectionKey.OP_CONNECT);
			return ch;
		} catch (IOException | RuntimeException e) {
			closeQuietly(ch);
			throw e instanceof IOException ? (IOException) e : new IOException(e.toString(), e);
		}
	}

	@Override
	public void poll(long timeoutMs, List<AddressRacer.Event<SocketChannel>> out) throws IOException {
		if (!immediate.isEmpty()) {
			out.addAll(immediate);
			immediate.clear();
			return;
		}
		selector.select(Math.max(1, timeoutMs));
		Iterator<SelectionKey> it = selector.selectedKeys().iterator();
		while (it.hasNext()) {
			SelectionKey key = it.next();
			it.remove();
			SocketChannel ch = (SocketChannel) key.channel();
			try {
				if (ch.finishConnect()) {
					key.cancel();
					out.add(new AddressRacer.Event<SocketChannel>(ch, true, null));
				}
			} catch (IOException e) {
				key.cancel();
				out.add(new AddressRacer.Event<SocketChannel>(ch, false, e));
			}
		}
	}

	@Override
	public void close(SocketChannel handle) {
		SelectionKey key = handle.keyFor(selector);
		if (key != null) key.cancel();
		closeQuietly(handle);
	}

	/** Selector schließen; offene Kanäle (der Gewinner) bleiben offen. */
	@Override
	public void close() {
		try {
			selector.close();
		} catch (IOException ignored) {
			// nichts zu tun
		}
	}

	static void closeQuietly(SocketChannel ch) {
		if (ch == null) return;
		try {
			ch.close();
		} catch (IOException ignored) {
			// schon zu
		}
	}
}

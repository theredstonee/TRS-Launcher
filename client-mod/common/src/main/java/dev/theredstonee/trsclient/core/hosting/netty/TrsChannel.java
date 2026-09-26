package dev.theredstonee.trsclient.core.hosting.netty;

import dev.theredstonee.trsclient.core.hosting.LoginSniffer;
import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.SingleThreadEventLoop;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Netty-Kanal über einen {@link PeerStream} (direkt, Relay oder öffentlicher Link) – für den integrierten Server
 * (Gast-Verbindung des Hosts) und für den Client des Gasts. Für Minecraft sieht er aus wie eine TCP-Verbindung:
 * derselbe Pipeline-Aufbau, Anmeldung bei Mojang, Kompression, Verschlüsselung.
 *
 * <p>Nutzt nur Netty-API, die es von 4.0.23 (Minecraft 1.8.9) bis 4.2 (1.21.11+) gibt. Ein eigener Kanal ohne
 * IoHandle braucht die Event-Loop nur als Ausführer – das klappt mit NIO/Epoll/IO-Loops aller Versionen.
 *
 * <p>Lesen: eingehende Bytes landen in einer Warteschlange und werden nur ausgeliefert, solange Netty lesen will
 * ({@link #doBeginRead}; respektiert {@code autoRead=false}, das Minecraft ab 1.20.5 beim Protokollwechsel nutzt).
 */
public class TrsChannel extends AbstractChannel {
	private static final ChannelMetadata METADATA = new ChannelMetadata(false);
	private static final InetSocketAddress LOCAL = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

	private final ChannelConfig config = new DefaultChannelConfig(this);
	private final ConcurrentLinkedQueue<byte[]> inbound = new ConcurrentLinkedQueue<byte[]>();
	private volatile PeerStream stream;
	/** 0 = neu, 1 = aktiv, 2 = zu. */
	private volatile int state;
	private volatile InetSocketAddress remote;
	/** Nur Event-Loop. */
	private boolean readPending;
	private volatile LoginSniffer sniffer;
	private volatile String closeReason;

	/** Client (über Bootstrap.channel(TrsChannel.class) erzeugt). */
	public TrsChannel() {
		super(null);
	}

	/** Server-Seite: schon verbundener Strom eines Gasts. */
	TrsChannel(PeerStream s, LoginSniffer sniffer) {
		super(null);
		this.stream = s;
		this.sniffer = sniffer;
		this.remote = s.remoteAddress();
		this.state = 1;
	}

	/** Weg der Verbindung (Anzeige beim Host) oder null. */
	public PeerStream.Path path() {
		PeerStream s = stream;
		return s == null ? null : s.path();
	}

	public PeerStream stream() {
		return stream;
	}

	/** Warum geschlossen (für das Log) oder null. */
	public String closeReason() {
		return closeReason;
	}

	/** Empfang starten – erst NACH der Registrierung an der Event-Loop. */
	void startReading() {
		final PeerStream s = stream;
		if (s == null) return;
		s.start(new PeerStream.Sink() {
			@Override
			public void data(byte[] b, int off, int len) {
				LoginSniffer sn = sniffer;
				if (sn != null) {
					LoginSniffer.Verdict v = sn.feed(b, off, len);
					if (v == LoginSniffer.Verdict.REJECT) {
						closeReason = "login rejected";
						s.close("login rejected");
						return;
					}
					if (v == LoginSniffer.Verdict.PASS) sniffer = null;
				}
				inbound.add(java.util.Arrays.copyOfRange(b, off, off + len));
				try {
					eventLoop().execute(new Runnable() {
						@Override
						public void run() {
							if (readPending) {
								readPending = false;
								drain();
							}
						}
					});
				} catch (RuntimeException ignored) {
					// Loop beendet – der Kanal ist ohnehin zu.
				}
			}

			@Override
			public void closed(final String reason) {
				if (closeReason == null) closeReason = reason;
				try {
					eventLoop().execute(new Runnable() {
						@Override
						public void run() {
							// Erst Gelesenes ausliefern, dann schließen.
							drain();
							if (isOpen()) unsafe().close(voidPromise());
						}
					});
				} catch (RuntimeException e) {
					state = 2;
				}
			}
		});
	}

	private void drain() {
		boolean any = false;
		byte[] b;
		while (isOpen() && (b = inbound.poll()) != null) {
			any = true;
			pipeline().fireChannelRead(Unpooled.wrappedBuffer(b));
			if (!config.isAutoRead()) break;
		}
		if (any) pipeline().fireChannelReadComplete();
	}

	@Override
	public ChannelConfig config() {
		return config;
	}

	@Override
	public boolean isOpen() {
		return state != 2;
	}

	@Override
	public boolean isActive() {
		return state == 1;
	}

	@Override
	public ChannelMetadata metadata() {
		return METADATA;
	}

	@Override
	protected AbstractUnsafe newUnsafe() {
		return new TrsUnsafe();
	}

	@Override
	protected boolean isCompatible(EventLoop loop) {
		return loop instanceof SingleThreadEventLoop;
	}

	@Override
	protected SocketAddress localAddress0() {
		return LOCAL;
	}

	@Override
	protected SocketAddress remoteAddress0() {
		return remote;
	}

	@Override
	protected void doBind(SocketAddress localAddress) {
		// nichts – kein Socket
	}

	@Override
	protected void doDisconnect() {
		doClose();
	}

	@Override
	protected void doClose() {
		state = 2;
		PeerStream s = stream;
		if (s != null) s.close(closeReason == null ? "closed" : closeReason);
		inbound.clear();
	}

	@Override
	protected void doBeginRead() {
		if (state != 1) return;
		if (inbound.isEmpty()) {
			readPending = true;
			return;
		}
		readPending = false;
		drain();
	}

	@Override
	protected void doWrite(ChannelOutboundBuffer in) throws Exception {
		PeerStream s = stream;
		while (true) {
			Object msg = in.current();
			if (msg == null) break;
			if (msg instanceof ByteBuf) {
				ByteBuf buf = (ByteBuf) msg;
				int n = buf.readableBytes();
				if (n > 0) {
					byte[] copy = new byte[n];
					buf.getBytes(buf.readerIndex(), copy);
					if (s == null || !s.write(copy, 0, n)) {
						in.remove(new java.io.IOException("stream closed"));
						throw new java.io.IOException("TRS stream closed");
					}
				}
			}
			in.remove();
		}
	}

	private final class TrsUnsafe extends AbstractUnsafe {
		@Override
		public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
			if (!ensureOpen(promise)) return;
			if (state == 1) {
				safeSetFailure(promise, new java.nio.channels.AlreadyConnectedException());
				return;
			}
			PeerStream s = TrsConnect.take(remoteAddress);
			if (s == null) {
				safeSetFailure(promise, new ConnectException("No TRS hosting connection for " + remoteAddress));
				close(voidPromise());
				return;
			}
			stream = s;
			remote = s.remoteAddress();
			state = 1;
			safeSetSuccess(promise);
			pipeline().fireChannelActive();
			startReading();
		}
	}
}

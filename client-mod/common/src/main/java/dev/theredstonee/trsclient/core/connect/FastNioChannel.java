package dev.theredstonee.trsclient.core.connect;

import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

/**
 * Nettys NIO-Kanal, der die schon im Rennen ({@link AddressRacer}) aufgebaute Verbindung übernimmt, statt selbst
 * noch einmal zu verbinden – der Server sieht so genau eine Verbindung. Minecraft erzeugt ihn über
 * {@code Bootstrap#channel(Class)} (öffentlicher Konstruktor ohne Argumente); die Verbindung holt er sich im selben
 * Thread bei {@link FastConnect#takePrepared()}. Ohne vorbereitete Verbindung verhält er sich wie ein normaler
 * {@link NioSocketChannel}.
 *
 * <p>Ist der Kanal schon verbunden, meldet Netty ihn beim Registrieren als aktiv; der spätere {@code connect} von
 * Minecraft ist dann nur noch eine Bestätigung. Will jemand (z. B. ein Proxy-Handler eines anderen Mods) an eine
 * andere Adresse, schlägt das mit einer klaren Meldung fehl und „Schnell verbinden“ ruht für diese Sitzung.
 */
public class FastNioChannel extends NioSocketChannel {
	private final InetSocketAddress prepared;

	public FastNioChannel() {
		this(FastConnect.takePrepared());
	}

	private FastNioChannel(FastConnect.Prepared p) {
		super(p != null ? p.channel : open());
		prepared = p != null ? p.target : null;
	}

	private static SocketChannel open() {
		try {
			return SelectorProvider.provider().openSocketChannel();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to open a socket", e);
		}
	}

	@Override
	protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
		if (prepared != null && javaChannel().isConnected()) {
			if (sameTarget(prepared, remoteAddress)) return true;
			FastConnect.disableForSession("connect target changed to " + remoteAddress);
			throw new ConnectException("Connection was redirected by another mod (proxy?) - please connect again");
		}
		return super.doConnect(remoteAddress, localAddress);
	}

	static boolean sameTarget(InetSocketAddress prepared, SocketAddress remote) {
		if (!(remote instanceof InetSocketAddress)) return false;
		InetSocketAddress r = (InetSocketAddress) remote;
		if (r.getPort() != prepared.getPort()) return false;
		if (r.getAddress() == null || prepared.getAddress() == null) return false;
		return r.getAddress().equals(prepared.getAddress());
	}
}

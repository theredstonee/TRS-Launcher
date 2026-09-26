package dev.theredstonee.trsclient.core.hosting.netty;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import io.netty.channel.Channel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client-Seite (Gast): ein fertiger {@link PeerStream} zum Host wird unter einer Marker-Adresse
 * ({@value #MARKER_IP}:Port) hinterlegt. Minecraft verbindet sich ganz normal mit dieser Adresse (IP-Literal, also
 * keine DNS-Abfrage); ein Mixin tauscht beim Aufbau die Kanalklasse gegen {@link TrsChannel}
 * ({@link #channelFor}), der sich den Strom hier abholt. Ohne Mixin (Legacy-Forge) nimmt der Aufrufer stattdessen
 * die {@link LoopbackBridge}.
 */
public final class TrsConnect {
	/** 127.84.82.83 = „TRS“ im Loopback-Netz: geht nie ins Netz, falls der Tausch einmal fehlt. */
	public static final String MARKER_IP = "127.84.82.83";
	static final long TTL_MS = 60_000L;
	private static final InetAddress MARKER = marker();
	private static final SecureRandom RANDOM = new SecureRandom();

	private static final class Entry {
		final PeerStream stream;
		final long expires;

		Entry(PeerStream stream, long expires) {
			this.stream = stream;
			this.expires = expires;
		}
	}

	private static final Map<Integer, Entry> PENDING = new HashMap<Integer, Entry>();

	private TrsConnect() {
	}

	private static InetAddress marker() {
		try {
			return InetAddress.getByAddress(new byte[] { 127, 84, 82, 83 });
		} catch (UnknownHostException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Strom hinterlegen; liefert die Adresse für Minecraft ("127.84.82.83:port"). */
	public static String offer(PeerStream stream) {
		synchronized (PENDING) {
			prune(System.currentTimeMillis());
			int port;
			do {
				port = 20000 + RANDOM.nextInt(40000);
			} while (PENDING.containsKey(port));
			PENDING.put(port, new Entry(stream, System.currentTimeMillis() + TTL_MS));
			return MARKER_IP + ":" + port;
		}
	}

	/** Ist das eine Marker-Adresse? */
	public static boolean isMarker(SocketAddress a) {
		return a instanceof InetSocketAddress && MARKER.equals(((InetSocketAddress) a).getAddress());
	}

	public static boolean isMarker(InetAddress a, int port) {
		return MARKER.equals(a);
	}

	/** Ziel der gerade aufgebauten Client-Verbindung (Mixin: HEAD von Connection#connect, dann Kanal-Tausch). */
	private static final ThreadLocal<SocketAddress> TARGET = new ThreadLocal<SocketAddress>();

	/** Aus dem Mixin (HEAD): Ziel merken. */
	public static void target(SocketAddress address) {
		TARGET.set(address);
	}

	/** Aus dem Mixin (Bootstrap#channel): Marker-Ziel → {@link TrsChannel}, sonst die Klasse von Minecraft. */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static Class channelForTarget(Class original) {
		SocketAddress a = TARGET.get();
		TARGET.remove();
		return isMarker(a) ? TrsChannel.class : original;
	}

	/** Für den Mixin: bei Marker-Adresse {@link TrsChannel}, sonst die Klasse von Minecraft. */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static Class channelFor(Class original, SocketAddress target) {
		return isMarker(target) ? TrsChannel.class : original;
	}

	@SuppressWarnings("rawtypes")
	public static Class channelFor(Class original, InetAddress address) {
		return MARKER.equals(address) ? TrsChannel.class : original;
	}

	/** Strom zur Adresse abholen (einmal) oder null. */
	static PeerStream take(SocketAddress a) {
		if (!isMarker(a)) return null;
		synchronized (PENDING) {
			Entry e = PENDING.remove(((InetSocketAddress) a).getPort());
			if (e == null || e.expires < System.currentTimeMillis()) return null;
			return e.stream;
		}
	}

	/** Nicht abgeholte Ströme schließen (Abbruch). */
	public static void cancel(String address) {
		if (address == null) return;
		int c = address.lastIndexOf(':');
		if (c < 0) return;
		Entry e;
		synchronized (PENDING) {
			try {
				e = PENDING.remove(Integer.parseInt(address.substring(c + 1)));
			} catch (NumberFormatException ex) {
				return;
			}
		}
		if (e != null) e.stream.close("cancelled");
	}

	private static void prune(long now) {
		Iterator<Entry> it = PENDING.values().iterator();
		while (it.hasNext()) {
			Entry e = it.next();
			if (e.expires < now) {
				it.remove();
				e.stream.close("not used");
			}
		}
	}

	/** Weg einer Minecraft-Verbindung (Kanal) oder null, wenn es keine TRS-Verbindung ist. */
	public static PeerStream.Path path(Channel ch) {
		return ch instanceof TrsChannel ? ((TrsChannel) ch).path() : null;
	}
}

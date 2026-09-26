package dev.theredstonee.trsclient.core.hosting.netty;

import dev.theredstonee.trsclient.core.hosting.LoginSniffer;
import dev.theredstonee.trsclient.core.hosting.net.PeerStream;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.lang.reflect.Constructor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host-Seite: hängt einen Gast-Strom als neue Verbindung an den integrierten Server – mit genau der Pipeline, die
 * Minecraft für echte TCP-Gäste baut. Dafür wird der (anonyme) {@code ChannelInitializer} des TCP-Listeners
 * ({@code ServerConnectionListener$1}, Legacy {@code NetworkSystem$4}) per Reflection erzeugt und an einen
 * {@link TrsChannel} gehängt; die Verbindung landet dadurch auch in Minecrafts Verbindungsliste und wird vom
 * Server-Thread bedient. Kein Socket, kein offener Port.
 *
 * <p>Die Kanäle laufen auf einer eigenen kleinen Event-Loop-Gruppe (Daemon-Threads „TRS-Hosting-Netty“).
 */
public final class ServerAttach {
	private static volatile EventLoopGroup group;
	private static volatile Class<?> cachedFor;
	private static volatile Constructor<?> cachedCtor;

	private ServerAttach() {
	}

	private static EventLoopGroup group() {
		EventLoopGroup g = group;
		if (g == null) {
			synchronized (ServerAttach.class) {
				g = group;
				if (g == null) {
					final AtomicInteger n = new AtomicInteger();
					g = new NioEventLoopGroup(2, new ThreadFactory() {
						@Override
						public Thread newThread(Runnable r) {
							Thread t = new Thread(r, "TRS-Hosting-Netty-" + n.incrementAndGet());
							t.setDaemon(true);
							return t;
						}
					});
					group = g;
				}
			}
		}
		return g;
	}

	/**
	 * Der TCP-Initializer des Listeners: die erste anonyme Innenklasse ({@code $1 … $12}), die ein
	 * {@link ChannelInitializer} ist und genau den Listener als Konstruktor-Argument nimmt (in allen Versionen der
	 * TCP-Listener; der Speicher-Kanal folgt danach).
	 */
	static Constructor<?> initializerCtor(Class<?> listenerClass) throws ReflectiveOperationException {
		if (listenerClass == cachedFor && cachedCtor != null) return cachedCtor;
		ClassLoader cl = listenerClass.getClassLoader();
		for (int i = 1; i <= 12; i++) {
			Class<?> c;
			try {
				c = Class.forName(listenerClass.getName() + "$" + i, false, cl);
			} catch (ClassNotFoundException e) {
				continue;
			}
			if (!ChannelInitializer.class.isAssignableFrom(c)) continue;
			for (Constructor<?> k : c.getDeclaredConstructors()) {
				Class<?>[] p = k.getParameterTypes();
				if (p.length == 1 && p[0].isAssignableFrom(listenerClass)) {
					k.setAccessible(true);
					cachedFor = listenerClass;
					cachedCtor = k;
					return k;
				}
			}
		}
		throw new ClassNotFoundException("TCP channel initializer of " + listenerClass.getName());
	}

	/** Prüft vorab, ob sich der Listener anbinden lässt (für „Welt hosten“ verfügbar ja/nein). */
	public static boolean supported(Object listener) {
		try {
			return listener != null && initializerCtor(listener.getClass()) != null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	/**
	 * Gast anhängen. {@code policy} prüft den Namen im Login-Start (siehe {@link LoginSniffer}). Liefert den Kanal
	 * (registriert, Empfang läuft) oder wirft.
	 */
	public static TrsChannel attach(Object listener, final PeerStream stream, LoginSniffer.Policy policy)
			throws ReflectiveOperationException {
		ChannelHandler init = (ChannelHandler) initializerCtor(listener.getClass()).newInstance(listener);
		final TrsChannel ch = new TrsChannel(stream, policy == null ? null : new LoginSniffer(policy));
		ch.pipeline().addLast(init);
		ChannelFuture f = group().register(ch);
		f.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				if (future.isSuccess()) {
					ch.startReading();
				} else {
					stream.close("register failed");
				}
			}
		});
		return ch;
	}
}

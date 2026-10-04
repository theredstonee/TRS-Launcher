package dev.theredstonee.trsclient.core.connect;

import dev.theredstonee.trsclient.core.hosting.netty.TrsConnect;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.net.NetBoost;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * „Schnell verbinden“: schnellerer Verbindungsaufbau zu Servern, in jeder Minecraft-Version gleich.
 * <ul>
 *   <li><b>DNS-Zwischenspeicher</b> ({@link DnsCache}): SRV-Ziel und Adressen werden gemerkt (TTL-gerecht, kurz) –
 *   vorab beim Auswählen/Überfahren eines Servers in der Liste ({@link #prefetch}) oder vom Launcher
 *   ({@link ConnectHints}). Der Klick verbindet dann ohne DNS-Wartezeit.</li>
 *   <li><b>Adressen-Rennen</b> ({@link AddressRacer}, RFC 8305): Hat ein Server mehrere Adressen (IPv4/IPv6,
 *   mehrere A-Einträge), wird versetzt parallel verbunden; die erste gewinnt. Kaputtes IPv6 oder eine tote Adresse
 *   kostet so 250 ms statt bis zu 21 s.</li>
 *   <li><b>Keine Rückwärts-Auflösung</b> bei IP-Adressen: Minecraft fragt sonst das DNS nach dem Namen zur IP
 *   (kann Sekunden dauern) – im Handshake steht dann die IP, wie eingegeben.</li>
 * </ul>
 * Der Handshake bleibt, wie Vanilla ihn bildet (eingegebener Name bzw. SRV-Ziel und Port) – nur das Ziel des
 * Sockets ändert sich. Mit SOCKS-Proxy (Java-Einstellungen) wird nicht gerannt.
 */
public final class FastConnect {
	public static final DnsCache CACHE = new DnsCache();

	/** Schalter (vom Loader gesetzt). */
	public interface Switch {
		boolean on();
	}

	private static final Switch OFF = new Switch() {
		@Override
		public boolean on() {
			return false;
		}
	};

	private static volatile Switch enabled = OFF;
	private static volatile Switch preResolve = OFF;
	private static volatile NetBoost.Logger log;
	private static volatile ConnectHints hints;
	private static volatile String disabledReason;
	private static volatile AddressRacer.Settings settings = AddressRacer.DEFAULT;
	static volatile DnsLookup resolver = SystemResolver.INSTANCE;
	/** Nur Tests: auch Loopback-Adressen rennen lassen. */
	static volatile boolean allowLoopback;

	private FastConnect() {
	}

	/** Beim Start des Loaders. {@code configDir} = Minecrafts config-Ordner (für die Launcher-Hinweise). */
	public static void init(Path configDir, Switch enabledSwitch, Switch preResolveSwitch, NetBoost.Logger logger) {
		if (enabledSwitch != null) enabled = enabledSwitch;
		if (preResolveSwitch != null) preResolve = preResolveSwitch;
		log = logger;
		hints = new ConnectHints(configDir);
		refreshHints();
	}

	/** Wie {@link #init}, mit den Schaltern des Moduls „Schnell verbinden“; meldet auch die Modulseite an. */
	public static void install(final dev.theredstonee.trsclient.core.module.TrsModules m, Path configDir, NetBoost.Logger logger) {
		init(configDir, new Switch() {
			@Override
			public boolean on() {
				return m.fastConnect.isEnabled();
			}
		}, new Switch() {
			@Override
			public boolean on() {
				return m.fastConnectPreResolve.get();
			}
		}, logger);
		FastConnectPanel.register(m.fastConnect);
	}

	/** Schalter „Schneller Serverwechsel“ des Moduls (für {@link ServerPacks#setup}). */
	public static Switch fastSwitchSwitch(final dev.theredstonee.trsclient.core.module.TrsModules m) {
		return new Switch() {
			@Override
			public boolean on() {
				return m.fastConnectSwitch.get();
			}
		};
	}

	/** Schalter „Server-Ressourcenpakete vorladen“ des Moduls. */
	public static Switch packsSwitch(final dev.theredstonee.trsclient.core.module.TrsModules m) {
		return new Switch() {
			@Override
			public boolean on() {
				return m.fastConnectPacks.get();
			}
		};
	}

	/**
	 * Nach {@link #install} und {@link ServerPacks#setup}: Hat der Launcher gemeldet, dass dieser Start einem Server
	 * beitritt, wird der schon jetzt aufgelöst und sein Ressourcenpaket (falls gemerkt) ungedrosselt vorgeladen.
	 */
	public static void startup() {
		ConnectHints h = hints;
		String join = h == null ? null : h.join();
		if (join == null) return;
		info("Fast connect: the TRS Launcher reports a join to " + join + " - preparing");
		// Java 8 ohne Haken am Verbindungsaufbau: die Adressen des Launchers sofort in Javas Speicher.
		primeJvm(join);
		prefetch(join);
		ServerPacks.preload(join, false);
	}

	/** Verbinden-Bildschirm öffnet (neue Verbindung): Anzeige, Paket-Zustand und Wechsel-Messung von vorn. */
	public static void connectScreenOpened() {
		STATUS.reset();
		ServerPacks.onDisconnect();
		FastSwitch.reset();
	}

	/** Grund, warum „Schnell verbinden“ für diese Sitzung ruht, oder null. */
	public static String pausedReason() {
		return disabledReason;
	}

	/** Für Tests: andere Zeitwerte. */
	static void settings(AddressRacer.Settings s) {
		settings = s == null ? AddressRacer.DEFAULT : s;
	}

	public static boolean active() {
		try {
			return disabledReason == null && enabled.on();
		} catch (RuntimeException e) {
			return false;
		}
	}

	static boolean preResolveActive() {
		try {
			return active() && preResolve.on();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Für diese Sitzung abschalten (ein anderer Mod lenkt die Verbindung um). */
	static void disableForSession(String reason) {
		if (disabledReason != null) return;
		disabledReason = reason;
		info("Fast connect paused for this session: " + reason);
	}

	/** Launcher-Hinweise neu einlesen, falls geändert (billig: nur Zeitstempel). */
	public static void refreshHints() {
		ConnectHints h = hints;
		if (h == null) return;
		int n = h.refresh(CACHE);
		if (n > 0) info("Fast connect: " + n + " server(s) pre-resolved by the TRS Launcher");
	}

	// --- Auflösen (Mixin in ServerNameResolver / ServerAddress) ---

	/**
	 * Adresse für {@code host} aus dem Speicher (zuletzt schnellste zuerst) – mit genau diesem Namen, damit Vanilla
	 * denselben Handshake-Namen liest. Bei einer IP-Adresse als Text: die Adresse mit dem Text als Namen (spart die
	 * Rückwärts-Auflösung). null = selbst auflösen lassen.
	 */
	public static InetAddress cachedAddress(String host) {
		if (!active() || host == null || host.isEmpty()) return null;
		byte[] lit = IpLiteral.parse(host);
		if (lit != null) {
			try {
				return InetAddress.getByAddress(host, lit);
			} catch (UnknownHostException e) {
				return null;
			}
		}
		refreshHints();
		List<InetAddress> l = CACHE.inetAddresses(host);
		if (l == null) {
			STATUS.resolving(host);
			return null;
		}
		return l.get(0);
	}

	/**
	 * SRV-Ziel aus dem Speicher: null = unbekannt (Vanilla fragt selbst), sonst {@link DnsCache.Srv} mit
	 * {@code record} null = „sicher kein SRV-Eintrag“.
	 */
	public static DnsCache.Srv cachedSrv(String host) {
		if (!active() || host == null || IpLiteral.is(host)) return null;
		refreshHints();
		DnsCache.Srv s = CACHE.srv(host);
		if (s == null) STATUS.resolving(host);
		return s;
	}

	/** Vanilla hat ein SRV-Ziel gefunden – merken (ein „nichts gefunden“ von Vanilla kann auch ein Fehler sein). */
	public static void learnSrv(String host, String target, int port) {
		if (!active() || host == null || target == null) return;
		if (DnsCache.key(host).equals(DnsCache.key(target)) && port == 25565) return;
		SrvRecord r = SrvRecord.parse("0 0 " + port + " " + target);
		if (r != null) CACHE.putSrv(host, r, -1);
	}

	// --- Verbinden (Mixin in Connection#connect) ---

	/** Vorbereitete Verbindung für den Netty-Kanal ({@link FastNioChannel}). */
	static final class Prepared {
		final SocketChannel channel;
		final InetSocketAddress target;

		Prepared(SocketChannel channel, InetSocketAddress target) {
			this.channel = channel;
			this.target = target;
		}
	}

	/** Abbruch nur, wenn jemand den Verbindungs-Thread unterbricht (Vanilla lässt ihn sonst einfach auslaufen). */
	private static final AddressRacer.Cancel INTERRUPTED = new AddressRacer.Cancel() {
		@Override
		public boolean cancelled() {
			return Thread.currentThread().isInterrupted();
		}
	};

	private static final ThreadLocal<Prepared> PREPARED = new ThreadLocal<Prepared>();
	/** Gewinner-Adresse für {@link #connectAddress} (auch, wenn der Kanal nicht übernommen werden kann). */
	private static final ThreadLocal<InetAddress> WINNER = new ThreadLocal<InetAddress>();

	/** Nur der Verbindungs-Thread des „Verbinden“-Bildschirms (nicht die Serverlisten-Pings). */
	static boolean connectorThread() {
		return Thread.currentThread().getName().startsWith("Server Connector");
	}

	/**
	 * HEAD von {@code Connection#connect}: bei mehreren Adressen das Rennen fahren. Danach liefern
	 * {@link #channelClass} und {@link #connectAddress} den Gewinner an Netty. Scheitern alle Adressen, wird mit
	 * einer klaren Meldung abgebrochen (Vanilla hätte nur die erste Adresse probiert, bis zu 21 s lang).
	 */
	public static void beforeConnect(InetSocketAddress target) {
		clear();
		if (target == null || target.getAddress() == null || !connectorThread()) return;
		STATUS.connecting(target);
		if (!active() || TrsConnect.isMarker(target)) return;
		String host = target.getHostString();
		int port = target.getPort();
		if (host == null || IpLiteral.is(host) || (!allowLoopback && target.getAddress().isLoopbackAddress())) return;
		if (proxied(host, port)) {
			info("Fast connect: proxy configured - connecting the vanilla way");
			return;
		}
		List<InetAddress> candidates = candidates(host, target.getAddress());
		if (candidates.size() < 2) return;
		List<InetAddress> ordered = AddressRacer.order(candidates, CACHE.winner(host));
		List<InetSocketAddress> targets = new ArrayList<InetSocketAddress>();
		for (InetAddress a : ordered) targets.add(new InetSocketAddress(a, port));
		long t0 = System.nanoTime();
		AddressRacer.Result<SocketChannel> r;
		NioDialer dialer = null;
		try {
			dialer = new NioDialer();
			r = AddressRacer.race(targets, dialer, AddressRacer.MONOTONIC, settings, STATUS, INTERRUPTED);
		} catch (IOException | RuntimeException e) {
			warn("Fast connect: race failed, connecting the vanilla way", e);
			return;
		} finally {
			if (dialer != null) dialer.close();
		}
		long ms = (System.nanoTime() - t0) / 1_000_000L;
		LAST.record(host, r, ms, targets.size());
		if (r.ok()) {
			InetAddress w = r.address.getAddress();
			CACHE.winner(host, w.getAddress());
			PREPARED.set(new Prepared(r.winner, r.address));
			WINNER.set(w);
			STATUS.connected(r.address);
			info("Fast connect: " + host + ":" + port + " -> " + w.getHostAddress() + " (" + family(w) + ") in " + ms + " ms, "
					+ r.attempts.size() + "/" + targets.size() + " attempts " + r.attempts);
			return;
		}
		if (r.cancelled) {
			info("Fast connect: cancelled after " + ms + " ms");
			throw new ConnectFailed(I18n.tr("fastConnect.cancelled"));
		}
		CACHE.invalidate(host);
		info("Fast connect: no address of " + host + ":" + port + " answered (" + ms + " ms) " + r.attempts);
		throw new ConnectFailed(I18n.tr("fastConnect.failed", targets.size(), host));
	}

	/** {@code Bootstrap#channel(Class)}: NIO-Kanal → {@link FastNioChannel} (übernimmt die Verbindung). */
	@SuppressWarnings({"rawtypes"})
	public static Class channelClass(Class original) {
		Prepared p = PREPARED.get();
		if (p == null) return original;
		if (original == io.netty.channel.socket.nio.NioSocketChannel.class) return FastNioChannel.class;
		// Epoll/KQueue (Linux/macOS mit „nativem Transport“): Kanal nicht übertragbar → schließen, Netty verbindet
		// selbst – aber gleich zur schnellsten Adresse.
		PREPARED.remove();
		NioDialer.closeQuietly(p.channel);
		return original;
	}

	/** {@code Bootstrap#connect(InetAddress, int)}: Ziel = Gewinner des Rennens (gleicher Name, andere IP). */
	public static InetAddress connectAddress(InetAddress original) {
		InetAddress w = WINNER.get();
		return w != null ? w : original;
	}

	/** Vom {@link FastNioChannel}-Konstruktor (gleicher Thread wie {@link #beforeConnect}). */
	static Prepared takePrepared() {
		Prepared p = PREPARED.get();
		PREPARED.remove();
		return p;
	}

	/** RETURN/Ende von {@code Connection#connect}: nicht übernommene Verbindung schließen. */
	public static void afterConnect() {
		clear();
	}

	private static void clear() {
		Prepared p = PREPARED.get();
		PREPARED.remove();
		WINNER.remove();
		if (p != null) NioDialer.closeQuietly(p.channel);
	}

	/** Alle Adressen des Hosts: gespeichert, sonst vom System (gerade erst von Vanilla aufgelöst → schnell). */
	static List<InetAddress> candidates(String host, InetAddress vanilla) {
		List<InetAddress> out = new ArrayList<InetAddress>();
		List<InetAddress> cached = CACHE.inetAddresses(host);
		if (cached != null) out.addAll(cached);
		else {
			List<byte[]> ips = resolver.addresses(host);
			if (!ips.isEmpty()) CACHE.putAddresses(host, ips, -1, DnsCache.Source.GAME);
			for (byte[] ip : ips) {
				try {
					out.add(InetAddress.getByAddress(host, ip));
				} catch (UnknownHostException ignored) {
					// falsche Länge
				}
			}
		}
		boolean has = false;
		for (InetAddress a : out) {
			if (java.util.Arrays.equals(a.getAddress(), vanilla.getAddress())) {
				has = true;
				break;
			}
		}
		if (!has) {
			try {
				out.add(0, InetAddress.getByAddress(host, vanilla.getAddress()));
			} catch (UnknownHostException ignored) {
				// nicht möglich
			}
		}
		return out;
	}

	/** SOCKS/Proxy laut Java-Einstellungen? Dann nicht rennen (die Verbindung soll durch den Proxy gehen). */
	static boolean proxied(String host, int port) {
		String socks = System.getProperty("socksProxyHost");
		if (socks != null && !socks.trim().isEmpty()) return true;
		try {
			ProxySelector ps = ProxySelector.getDefault();
			if (ps == null) return false;
			for (Proxy p : ps.select(new URI("socket", null, host, port, null, null, null))) {
				if (p.type() != Proxy.Type.DIRECT) return true;
			}
		} catch (Exception e) {
			return false;
		}
		return false;
	}

	static String family(InetAddress a) {
		return a instanceof Inet6Address ? "IPv6" : "IPv4";
	}

	// --- Vorab auflösen ---

	private static final ThreadPoolExecutor PREFETCH = prefetchPool();
	private static final Map<String, Long> PREFETCHED = new HashMap<String, Long>();
	/** Gleichen Server frühestens nach so langer Zeit erneut vorab auflösen (der Speicher hält ohnehin länger). */
	static final long PREFETCH_AGAIN_MS = 20_000L;

	private static ThreadPoolExecutor prefetchPool() {
		ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16),
				new java.util.concurrent.ThreadFactory() {
					@Override
					public Thread newThread(Runnable r) {
						Thread t = new Thread(r, "TRS-Resolve");
						t.setDaemon(true);
						return t;
					}
				}, new ThreadPoolExecutor.DiscardPolicy());
		ex.allowCoreThreadTimeOut(true);
		return ex;
	}

	/**
	 * Eintrag der Serverliste wurde ausgewählt/überfahren: SRV + alle Adressen im Hintergrund auflösen, damit der
	 * Klick sofort verbinden kann. Schnell und ohne Wirkung, wenn schon bekannt. {@code address} wie in der Liste
	 * ("host", "host:port", "[v6]:port").
	 */
	public static void prefetch(String address) {
		if (!preResolveActive() || address == null) return;
		final String[] hp = parse(address);
		if (hp == null || IpLiteral.is(hp[0])) return;
		final String k = (DnsCache.key(hp[0]) + ":" + hp[1]);
		long now = System.currentTimeMillis();
		synchronized (PREFETCHED) {
			Long last = PREFETCHED.get(k);
			if (last != null && now - last < PREFETCH_AGAIN_MS) return;
			if (PREFETCHED.size() > 256) PREFETCHED.clear();
			PREFETCHED.put(k, now);
		}
		PREFETCH.execute(new Runnable() {
			@Override
			public void run() {
				resolveNow(hp[0], Integer.parseInt(hp[1]));
			}
		});
	}

	/** Alles zu host:port auflösen und speichern (im aufrufenden Thread). Liefert den Host, zu dem verbunden wird. */
	static String resolveNow(String host, int port) {
		try {
			refreshHints();
			String connectHost = host;
			if (port == 25565 && CACHE.srv(host) == null) {
				DnsLookup.SrvAnswer a = resolver.srv(host);
				if (a.definitive) CACHE.putSrv(host, a.record, -1);
			}
			DnsCache.Srv s = CACHE.srv(host);
			if (port == 25565 && s != null && s.record != null) connectHost = s.record.target;
			// Vanilla löst den eingegebenen Namen immer auf (auch mit SRV) – beide vorhalten.
			resolveAddresses(host);
			if (!connectHost.equals(host)) resolveAddresses(connectHost);
			if (JvmDnsCache.supported()) probe(connectHost, port == 25565 && s != null && s.record != null ? s.record.port : port);
			return connectHost;
		} catch (RuntimeException e) {
			return host;
		}
	}

	/**
	 * Versionen ohne Haken am Verbindungsaufbau (Java 8): Bei mehreren Adressen einmal kurz das Rennen fahren (wie
	 * der Ping der Serverliste: verbinden, sofort schließen, nichts senden) und die schnellste merken – Javas
	 * Adress-Speicher bekommt sie dann zuerst ({@link #primeJvm}).
	 */
	static void probe(String host, int port) {
		if (CACHE.winner(host) != null) return;
		List<InetAddress> l = CACHE.inetAddresses(host);
		if (l == null || l.size() < 2 || proxied(host, port)) return;
		List<InetSocketAddress> targets = new ArrayList<InetSocketAddress>();
		for (InetAddress a : AddressRacer.order(l, null)) targets.add(new InetSocketAddress(a, port));
		NioDialer dialer = null;
		try {
			dialer = new NioDialer();
			AddressRacer.Result<SocketChannel> r = AddressRacer.race(targets, dialer, AddressRacer.MONOTONIC, settings, null, null);
			if (r.ok()) {
				NioDialer.closeQuietly(r.winner);
				CACHE.winner(host, r.address.getAddress().getAddress());
				info("Fast connect: probed " + host + ":" + port + " - fastest " + r.address.getAddress().getHostAddress() + " " + r.attempts);
			}
		} catch (IOException | RuntimeException e) {
			// nur ein Vorgriff
		} finally {
			if (dialer != null) dialer.close();
		}
	}

	private static void resolveAddresses(String host) {
		if (CACHE.addresses(host) != null) return;
		List<byte[]> ips = resolver.addresses(host);
		if (!ips.isEmpty()) CACHE.putAddresses(host, ips, -1, DnsCache.Source.GAME);
	}

	/**
	 * Für Versionen ohne Haken am Verbindungsaufbau (Java 8, Forge 1.7.10–1.13.2): Adressen des Servers – zuletzt
	 * schnellste zuerst – in Javas Adress-Speicher legen ({@link JvmDnsCache}). Billig; regelmäßig aufrufen, solange
	 * ein Server ausgewählt ist (Javas Speicher hält nur 30 s).
	 */
	public static void primeJvm(String address) {
		if (!active() || address == null || !JvmDnsCache.supported()) return;
		String[] hp = parse(address);
		if (hp == null || IpLiteral.is(hp[0])) return;
		long now = System.currentTimeMillis();
		String k = DnsCache.key(hp[0]) + ":" + hp[1];
		synchronized (PRIMED) {
			Long last = PRIMED.get(k);
			if (last != null && now - last < PRIME_AGAIN_MS) return;
		}
		String host = hp[0];
		if ("25565".equals(hp[1])) {
			DnsCache.Srv s = CACHE.srv(host);
			if (s != null && s.record != null) host = s.record.target;
		}
		List<InetAddress> l = CACHE.inetAddresses(host);
		if (l == null) return;
		boolean ok = JvmDnsCache.prime(host, l);
		String bare = DnsCache.key(host);
		if (ok && !bare.equals(host)) {
			List<InetAddress> l2 = CACHE.inetAddresses(bare);
			if (l2 != null) JvmDnsCache.prime(bare, l2);
		}
		if (ok) {
			synchronized (PRIMED) {
				if (PRIMED.size() > 64) PRIMED.clear();
				PRIMED.put(k, now);
			}
		}
	}

	private static final Map<String, Long> PRIMED = new HashMap<String, Long>();
	/** Javas Speicher hält 30 s – alle 5 s auffrischen reicht (und kostet fast nichts). */
	static final long PRIME_AGAIN_MS = 5_000L;

	/** {host, port} aus "host", "host:port", "[v6]:port"; Port ohne Angabe 25565; null = ungültig. */
	static String[] parse(String address) {
		String[] hp = dev.theredstonee.trsclient.core.net.StatusPing.parse(address);
		if (hp == null) return null;
		return new String[]{hp[0], hp[1] == null ? "25565" : hp[1]};
	}

	// --- Anzeige ---

	/** Was gerade passiert (für den Verbinden-Bildschirm). */
	public static final class Status implements AddressRacer.Listener {
		private volatile long since;
		private volatile int phase;
		private volatile String host = "";
		private volatile String ip = "";
		private volatile boolean v6;
		private volatile int index;
		private volatile int total;

		static final int IDLE = 0;
		static final int RESOLVING = 1;
		static final int CONNECTING = 2;
		static final int RACING = 3;

		void resolving(String h) {
			if (!connectorThread()) return;
			if (phase == IDLE) since = System.nanoTime();
			phase = RESOLVING;
			host = h;
		}

		void connecting(InetSocketAddress t) {
			if (phase == IDLE) since = System.nanoTime();
			phase = CONNECTING;
			InetAddress a = t.getAddress();
			ip = a == null ? "" : a.getHostAddress();
			v6 = a instanceof Inet6Address;
			index = 0;
			total = 0;
		}

		@Override
		public void attempt(int i, int n, InetSocketAddress t) {
			if (phase == IDLE) since = System.nanoTime();
			phase = RACING;
			InetAddress a = t.getAddress();
			ip = a == null ? "" : a.getHostAddress();
			v6 = a instanceof Inet6Address;
			index = i;
			total = n;
		}

		void connected(InetSocketAddress t) {
			phase = IDLE;
		}

		/** Die TCP-Verbindung steht (Netty: Kanal aktiv) – ab jetzt zeigt Vanilla „Anmelden …“. */
		public void connected() {
			phase = IDLE;
		}

		/**
		 * Versionen ohne Haken am Verbindungsaufbau (Forge 1.7.10–1.13.2): Verbinden-Bildschirm ist offen und geht
		 * an {@code address} – Anzeige nach der gemerkten (zuerst versuchten) Adresse.
		 */
		public void expect(String address) {
			since = System.nanoTime();
			phase = CONNECTING;
			index = 0;
			total = 0;
			ip = "";
			v6 = false;
			String[] hp = address == null ? null : parse(address);
			if (hp == null) return;
			String h = hp[0];
			DnsCache.Srv s = "25565".equals(hp[1]) ? CACHE.srv(h) : null;
			if (s != null && s.record != null) h = s.record.target;
			byte[] lit = IpLiteral.parse(h);
			List<InetAddress> l = null;
			try {
				l = lit != null ? java.util.Collections.singletonList(InetAddress.getByAddress(lit)) : CACHE.inetAddresses(h);
			} catch (UnknownHostException ignored) {
				// keine Adresse
			}
			if (l == null || l.isEmpty()) return;
			ip = l.get(0).getHostAddress();
			v6 = l.get(0) instanceof Inet6Address;
		}

		/** Verbinden-Bildschirm wurde (wieder) geöffnet. */
		public void reset() {
			phase = IDLE;
			since = System.nanoTime();
		}

		/**
		 * Zeile für den Verbinden-Bildschirm, wenn es länger als 1 s dauert (sonst null): „Löse Adresse auf …“ bzw.
		 * „Verbinde über IPv6 … (2/3)“.
		 */
		public String line() {
			int p = phase;
			if (p == IDLE || System.nanoTime() - since < 1_000_000_000L) return null;
			if (p == RESOLVING) return I18n.tr("fastConnect.status.resolving");
			String fam = v6 ? "IPv6" : "IPv4";
			if (p == RACING && total > 1) return I18n.tr("fastConnect.status.racing", fam, index, total);
			return I18n.tr("fastConnect.status.connecting", fam);
		}

		/** Für das Log/Tests. */
		public String ip() {
			return ip;
		}
	}

	public static final Status STATUS = new Status();

	/** Ergebnis des letzten Verbindungsaufbaus (Seite im TRS-Menü). */
	public static final class Last {
		public volatile String host;
		public volatile String ip;
		public volatile boolean v6;
		public volatile long ms = -1;
		public volatile int attempts;
		public volatile int addresses;
		public volatile boolean ok;

		void record(String h, AddressRacer.Result<?> r, long totalMs, int total) {
			host = h;
			ok = r.ok();
			InetAddress a = r.ok() ? r.address.getAddress() : null;
			ip = a == null ? null : a.getHostAddress();
			v6 = a instanceof Inet6Address;
			ms = totalMs;
			attempts = r.attempts.size();
			addresses = total;
		}
	}

	public static final Last LAST = new Last();

	/** Fehlermeldung für den Bildschirm „Verbindung fehlgeschlagen“ (ohne Klassennamen davor). */
	public static final class ConnectFailed extends RuntimeException {
		private static final long serialVersionUID = 1L;

		ConnectFailed(String message) {
			super(message);
		}

		@Override
		public String toString() {
			return getMessage();
		}
	}

	static void info(String m) {
		NetBoost.Logger l = log;
		if (l != null) l.info(m);
	}

	static void warn(String m, Throwable t) {
		NetBoost.Logger l = log;
		if (l != null) l.warn(m, t);
	}

	/** Für Tests. */
	static void reset() {
		clear();
		CACHE.clear();
		disabledReason = null;
		synchronized (PREFETCHED) {
			PREFETCHED.clear();
		}
		STATUS.reset();
	}
}

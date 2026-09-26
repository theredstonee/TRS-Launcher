package dev.theredstonee.trsclient.core.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Random;

/**
 * Netzwerk-Optimierung und Ping-Messung an der Netty-Pipeline der Client-Verbindung – in jeder Minecraft-Version gleich.
 *
 * <p>Zwei eigene Handler, beide reichen jede Nachricht unverändert weiter:
 * <ul>
 *   <li>{@value #GATE} ganz vorn (vor der Entschlüsselung): tauscht beim ersten verschlüsselten Datenpaket Vanillas
 *   Entschlüsselung gegen {@link FastDecrypt} und die Kompressions-Handler gegen die schlanken TRS-Unterklassen.
 *   Getauscht wird nur, wenn sicher noch kein verschlüsseltes Byte durch Vanilla lief – sonst bleibt Vanilla.</li>
 *   <li>{@value #WATCH} direkt vor dem Paket-Handler: liest Ankunftszeiten von Ping-Antworten, Zeit- und
 *   Keepalive-Paketen (für Ping, TPS-Schätzung und Jitter). Er verzögert, bündelt oder ändert nichts.</li>
 * </ul>
 * Außerdem: {@code TCP_NODELAY} an, wo die Version es aus lässt (Minecraft 1.7.10) – Vanilla ab 1.8 macht dasselbe.
 *
 * <p>Nicht angefasst: wann und wie Pakete gesendet werden (kein Zurückhalten, Bündeln oder Vorziehen), Keepalives,
 * Bewegungen, Treffer. Alles, was der Server sieht, bleibt Byte für Byte Vanilla.
 */
public final class NetBoost {
	public static final String GATE = "trsclient_gate";
	public static final String WATCH = "trsclient_watch";

	public static final NetStats STATS = new NetStats();
	private static final PingMeter PING = new PingMeter();

	/** Schalter des Moduls „Netzwerk-Optimierung“ (gesetzt vom Loader). */
	public interface Switch {
		boolean on();
	}

	private static volatile NetPlatform platform;
	private static volatile Switch optimize = new Switch() {
		@Override
		public boolean on() {
			return false;
		}
	};
	private static volatile Channel current;
	private static volatile boolean loggedError;
	private static volatile Logger logger;

	/** Kleiner Log-Zugang (die Loader haben verschiedene Logger). */
	public interface Logger {
		void info(String message);

		void warn(String message, Throwable t);
	}

	private NetBoost() {
	}

	public static void init(NetPlatform p, Switch optimizeSwitch, Logger log) {
		platform = p;
		if (optimizeSwitch != null) optimize = optimizeSwitch;
		logger = log;
	}

	public static NetPlatform platform() {
		return platform;
	}

	public static PingMeter ping() {
		return PING;
	}

	/** Die Client-Verbindung, an der die TRS-Handler hängen (oder null). */
	public static Channel channel() {
		return current;
	}

	/**
	 * Neue Client-Verbindung: Handler einsetzen. Beliebiger Thread; mehrfach aufrufen schadet nicht. Speicher-
	 * Verbindungen (Einzelspieler) bekommen nichts.
	 */
	public static void attach(final Channel ch) {
		if (ch == null || platform == null || isLocal(ch)) return;
		if (ch.eventLoop().inEventLoop()) {
			install(ch);
		} else {
			ch.eventLoop().execute(new Runnable() {
				@Override
				public void run() {
					install(ch);
				}
			});
		}
	}

	static boolean isLocal(Channel ch) {
		String n = ch.getClass().getName();
		return n.endsWith("LocalChannel") || n.contains(".local.");
	}

	private static void install(Channel ch) {
		try {
			if (!ch.isOpen()) return;
			ChannelPipeline p = ch.pipeline();
			boolean fresh = p.get(GATE) == null;
			if (fresh) {
				// Neue Verbindung: Anzeige-Zustand und Messung von vorn.
				STATS.connection();
				PING.reset();
				Gate gate = new Gate(p.get("decrypt") == null);
				if (p.get("timeout") != null) p.addAfter("timeout", GATE, gate);
				else p.addFirst(GATE, gate);
				noDelay(ch);
			}
			// Auch beim erneuten Anhängen (z. B. nach der Anmeldung) gilt diese Verbindung wieder als die aktuelle.
			current = ch;
			ensureWatch(p);
		} catch (Throwable t) {
			error("attach", t);
		}
	}

	private static void ensureWatch(ChannelPipeline p) {
		if (p.get(WATCH) == null && p.get("packet_handler") != null) p.addBefore("packet_handler", WATCH, new Watch());
	}

	/** Minecraft 1.7.10 lässt Nagle an (TCP_NODELAY = false); ab 1.8 schaltet Vanilla es selbst ein. */
	static void noDelay(Channel ch) {
		if (!(ch instanceof SocketChannel)) {
			STATS.noDelay = -1;
			return;
		}
		try {
			Boolean on = ch.config().getOption(ChannelOption.TCP_NODELAY);
			if (Boolean.TRUE.equals(on)) {
				STATS.noDelay = 1;
			} else if (optimize.on()) {
				ch.config().setOption(ChannelOption.TCP_NODELAY, Boolean.TRUE);
				STATS.noDelay = 2;
				log("TCP_NODELAY eingeschaltet (Vanilla ließ es aus)");
			} else {
				STATS.noDelay = 0;
			}
		} catch (Throwable t) {
			STATS.noDelay = -1;
		}
	}

	/**
	 * Je Client-Tick (Hauptthread): Verbindung prüfen, Ping-Anfrage senden, Server-Wert übernehmen.
	 *
	 * @param ch      aktuelle Verbindung (null = keine)
	 * @param measure Ping-Anzeige an? Nur dann gehen eigene Ping-Anfragen raus.
	 */
	public static void tick(Channel ch, boolean measure, long intervalMs, long spikeThresholdMs) {
		NetPlatform pf = platform;
		if (pf == null) return;
		try {
			if (ch == null || !ch.isOpen() || !pf.multiplayer()) {
				if (current != null && (ch == null || ch != current)) {
					current = null;
					PING.reset();
				}
				return;
			}
			if (ch != current || ch.pipeline().get(WATCH) == null) attach(ch);
			PING.spikeThreshold(spikeThresholdMs);
			PING.activeSupported(pf.activePing());
			long now = pf.millis();
			PING.server(pf.serverLatency(), now);
			if (measure && PING.shouldSend(now, intervalMs, pf.activePing())) {
				PING.sent(now);
				pf.sendPing(now);
			}
		} catch (Throwable t) {
			error("tick", t);
		}
	}

	// --- Handler ---

	/** Vorderster Handler: tauscht Entschlüsselung/Kompression im richtigen Moment. */
	static final class Gate extends ChannelInboundHandlerAdapter {
		/** Beim Einsetzen war noch keine Entschlüsselung da – nur dann darf sie getauscht werden. */
		private boolean armed;
		private ChannelHandler seenDecrypt;
		private ChannelHandler seenDecompress;
		private ChannelHandler seenCompress;

		Gate(boolean armed) {
			this.armed = armed;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			try {
				maintain(ctx.pipeline());
			} catch (Throwable t) {
				error("gate", t);
			}
			ctx.fireChannelRead(msg);
		}

		void maintain(ChannelPipeline p) {
			NetPlatform pf = platform;
			if (pf == null) return;
			ChannelHandler d = p.get("decrypt");
			if (d == null) {
				// Klartext läuft durch uns – kommt später eine Entschlüsselung dazu, sehen wir ihr erstes Byte.
				if (seenDecrypt == null) armed = true;
			} else if (d != seenDecrypt) {
				boolean first = seenDecrypt == null;
				seenDecrypt = d;
				STATS.encrypted = true;
				if (first && armed && optimize.on() && pf.vanillaDecrypt(d)) {
					ChannelHandler fast = fastDecrypt(d);
					if (fast != null) {
						p.replace(d, "decrypt", fast);
						seenDecrypt = fast;
						STATS.fastDecrypt = true;
						log("Entschlüsselung: TRS-CFB8 aktiv");
					}
				}
				armed = false;
			}
			ChannelHandler dc = p.get("decompress");
			if (dc != null && dc != seenDecompress) {
				seenDecompress = dc;
				STATS.compressed = true;
				ChannelHandler up = optimize.on() ? pf.upgradeDecompress(dc) : null;
				if (up != null) {
					p.replace(dc, "decompress", up);
					seenDecompress = up;
					STATS.fastInflate = true;
				}
			}
			ChannelHandler c = p.get("compress");
			if (c != null && c != seenCompress) {
				seenCompress = c;
				ChannelHandler up = optimize.on() ? pf.upgradeCompress(c) : null;
				if (up != null) {
					p.replace(c, "compress", up);
					seenCompress = up;
					STATS.fastDeflate = true;
				}
			}
			ensureWatch(p);
		}
	}

	/** Liest Ankunftszeiten – reicht alles unverändert weiter. */
	static final class Watch extends ChannelInboundHandlerAdapter {
		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			NetPlatform pf = platform;
			if (pf != null) {
				try {
					observe(pf, msg);
				} catch (Throwable t) {
					error("watch", t);
				}
			}
			ctx.fireChannelRead(msg);
		}
	}

	static void observe(NetPlatform pf, Object msg) {
		long pong = pf.pongTime(msg);
		if (pong != NetPlatform.NONE) {
			PING.pong(pong, pf.millis());
			return;
		}
		long gt = pf.gameTime(msg);
		if (gt != NetPlatform.NONE) {
			PING.tps().add(gt, System.nanoTime());
			return;
		}
		long ka = pf.keepAliveId(msg);
		if (ka != NetPlatform.NONE) PING.keepAlive().add(ka, System.nanoTime());
	}

	// --- Entschlüsselung ---

	/**
	 * Baut den schnellen Ersatz aus Vanillas Cipher: Minecraft nimmt das gemeinsame Geheimnis als Schlüssel UND als IV
	 * ({@code new IvParameterSpec(key.getEncoded())} in allen Versionen), also liefert {@link Cipher#getIV()} den
	 * Schlüssel – öffentliche API, kein Eingriff ins JDK. Vor dem Einsatz Selbsttest gegen das JDK-CFB8.
	 */
	static ChannelHandler fastDecrypt(ChannelHandler vanilla) {
		try {
			Cipher cipher = findCipher(vanilla, 3);
			if (cipher == null || !"AES/CFB8/NoPadding".equalsIgnoreCase(cipher.getAlgorithm())) return null;
			byte[] iv = cipher.getIV();
			if (iv == null || iv.length != 16) return null;
			if (!selfTest(iv)) return null;
			return new FastDecrypt(new FastCfb8(iv, iv));
		} catch (Throwable t) {
			error("decrypt", t);
			return null;
		}
	}

	/** Gleiche Ausgabe wie das JDK auf Zufallsdaten (über Abschnittsgrenzen hinweg)? */
	static boolean selfTest(byte[] key) throws Exception {
		byte[] data = new byte[FastCfb8.CHUNK + 777];
		new Random(0x7125).nextBytes(data);
		Cipher jdk = Cipher.getInstance("AES/CFB8/NoPadding");
		jdk.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
		byte[] expected = jdk.update(data);
		byte[] actual = new byte[data.length];
		FastCfb8 f = new FastCfb8(key, key);
		f.decrypt(data, 0, 100, actual, 0);
		f.decrypt(data, 100, data.length - 100, actual, 100);
		return Arrays.equals(expected, actual);
	}

	/** Sucht (bis {@code depth} Ebenen tief) ein Feld vom Typ {@link Cipher} – Vanilla: Handler → CipherBase → Cipher. */
	static Cipher findCipher(Object o, int depth) throws IllegalAccessException {
		if (o == null || depth <= 0) return null;
		for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			if (c.getName().startsWith("io.netty.")) break;
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isArray()) continue;
				f.setAccessible(true);
				Object v = f.get(o);
				if (v instanceof Cipher) return (Cipher) v;
			}
		}
		for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			if (c.getName().startsWith("io.netty.")) break;
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isArray()) continue;
				String tn = f.getType().getName();
				if (tn.startsWith("java.") || tn.startsWith("javax.") || tn.startsWith("io.netty.")) continue;
				f.setAccessible(true);
				Cipher inner = findCipher(f.get(o), depth - 1);
				if (inner != null) return inner;
			}
		}
		return null;
	}

	// --- Hilfen für die Loader ---

	private static volatile Field channelField;

	/** Netty-Kanal einer Minecraft-Verbindung (Feld vom Typ {@link Channel}, per Reflexion) oder null. */
	public static Channel channelOf(Object connection) {
		if (connection == null) return null;
		try {
			Field f = channelField;
			if (f == null || !f.getDeclaringClass().isInstance(connection)) {
				f = null;
				for (Class<?> c = connection.getClass(); c != null && c != Object.class && f == null; c = c.getSuperclass()) {
					for (Field x : c.getDeclaredFields()) {
						if (!Modifier.isStatic(x.getModifiers()) && Channel.class.isAssignableFrom(x.getType())) {
							x.setAccessible(true);
							f = x;
							break;
						}
					}
				}
				if (f == null) return null;
				channelField = f;
			}
			return (Channel) f.get(connection);
		} catch (Throwable t) {
			error("channel", t);
			return null;
		}
	}

	/** Erstes nicht-statisches Feld dieses Typs (für Schwellwerte der Vanilla-Handler), sonst {@code fallback}. */
	public static int intField(Object o, int fallback) {
		Object v = firstField(o, int.class);
		return v instanceof Integer ? (Integer) v : fallback;
	}

	public static boolean boolField(Object o, boolean fallback) {
		Object v = firstField(o, boolean.class);
		return v instanceof Boolean ? (Boolean) v : fallback;
	}

	private static Object firstField(Object o, Class<?> type) {
		if (o == null) return null;
		try {
			for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				if (c.getName().startsWith("io.netty.")) break;
				for (Field f : c.getDeclaredFields()) {
					if (Modifier.isStatic(f.getModifiers()) || f.getType() != type) continue;
					f.setAccessible(true);
					return f.get(o);
				}
			}
		} catch (Throwable ignored) {
			// kein Zugriff → Rückfall
		}
		return null;
	}

	private static void log(String message) {
		Logger l = logger;
		if (l != null) l.info("[TRS Netz] " + message);
	}

	static void error(String where, Throwable t) {
		if (loggedError) return;
		loggedError = true;
		Logger l = logger;
		if (l != null) l.warn("[TRS Netz] Fehler in " + where + " (Vanilla läuft weiter)", t);
	}
}

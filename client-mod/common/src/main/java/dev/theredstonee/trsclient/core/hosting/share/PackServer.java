package dev.theredstonee.trsclient.core.hosting.share;

import dev.theredstonee.trsclient.core.hosting.net.PeerStream;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gast: lokaler HTTP-Endpunkt für das Resource Pack des Hosts. Minecraft lädt Server-Packs per HTTP – der Host
 * schickt eine Platzhalter-Adresse ({@link #MARKER_PREFIX}), die der Gast auf diesen Endpunkt umschreibt:
 * {@code http://127.0.0.1:<zufälliger Port>/<128 Bit Zufall>/pack.zip}. Er gilt nur für diese Verbindung, nimmt nur
 * Verbindungen von 127.0.0.1 an, liefert höchstens {@link #MAX_SERVES}-mal und holt die Bytes jedes Mal frisch über den
 * Datei-Kanal zum Host (SHA-1 und SHA-256 werden unterwegs geprüft – passt es nicht, bricht die Übertragung ab und
 * Minecraft verwirft das Pack).
 */
public final class PackServer {
	/** Platzhalter-Adresse, die der Host verschickt ({@code .invalid} löst nie auf). */
	public static final String MARKER_PREFIX = "http://trs-pack.invalid/";
	static final int MAX_SERVES = 4;
	private static final SecureRandom RANDOM = new SecureRandom();

	/** Öffnet einen neuen Strom zum Host (P2P oder Relay) – blockierend. */
	public interface Opener {
		PeerStream open() throws IOException;
	}

	/** Liefert die angekündigten Pack-Daten (aus der API) – blockierend; null = keins. */
	public interface Resolver {
		SharedContent.Pack pack() throws IOException;
	}

	private final String sha1;
	private final Resolver resolver;
	private final Opener opener;
	private final ServerSocket socket;
	private final String token;
	private final AtomicInteger serves = new AtomicInteger();
	private volatile boolean closed;
	public volatile java.util.function.Consumer<String> log;

	private PackServer(String sha1, Resolver resolver, Opener opener, ServerSocket socket, String token) {
		this.sha1 = sha1;
		this.resolver = resolver;
		this.opener = opener;
		this.socket = socket;
		this.token = token;
	}

	/** Platzhalter-Adresse für ein Pack (Host-Seite). */
	public static String marker(String sha1) {
		return MARKER_PREFIX + sha1 + ".zip";
	}

	/** SHA-1 aus einer Platzhalter-Adresse oder null. */
	public static String markerSha1(String url) {
		if (url == null || !url.startsWith(MARKER_PREFIX) || !url.endsWith(".zip")) return null;
		String sha = url.substring(MARKER_PREFIX.length(), url.length() - 4).toLowerCase(Locale.ROOT);
		return SharedContent.SHA1.matcher(sha).matches() ? sha : null;
	}

	/**
	 * Endpunkt für das Pack mit diesem SHA-1 starten. Die übrigen Daten (Größe, SHA-256) holt {@code resolver} erst bei
	 * der Anfrage – so blockiert das Umschreiben der Adresse nie den Spiel-Thread.
	 */
	public static PackServer start(String sha1, Resolver resolver, Opener opener) throws IOException {
		ServerSocket s = new ServerSocket();
		s.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 0), 4);
		byte[] t = new byte[16];
		RANDOM.nextBytes(t);
		final PackServer ps = new PackServer(sha1, resolver, opener, s, ModScan.hex(t));
		Thread th = new Thread(new Runnable() {
			@Override
			public void run() {
				ps.acceptLoop();
			}
		}, "TRS-Pack-Endpunkt");
		th.setDaemon(true);
		th.start();
		return ps;
	}

	public String sha1() {
		return sha1;
	}

	/** Die echte Adresse für Minecraft. */
	public String url() {
		return "http://127.0.0.1:" + socket.getLocalPort() + "/" + token + "/pack.zip";
	}

	public boolean closed() {
		return closed;
	}

	public void stop() {
		closed = true;
		try {
			socket.close();
		} catch (IOException ignored) {
			// egal
		}
	}

	private void acceptLoop() {
		while (!closed) {
			final Socket c;
			try {
				c = socket.accept();
			} catch (IOException e) {
				break;
			}
			Thread th = new Thread(new Runnable() {
				@Override
				public void run() {
					handle(c);
				}
			}, "TRS-Pack-Anfrage");
			th.setDaemon(true);
			th.start();
		}
	}

	void handle(Socket c) {
		try (Socket s = c) {
			s.setSoTimeout(15_000);
			if (!s.getInetAddress().isLoopbackAddress()) return;
			String request = readHead(s.getInputStream());
			OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
			String expected = "GET /" + token + "/pack.zip ";
			if (request == null || !request.startsWith(expected) || closed) {
				status(out, "404 Not Found");
				return;
			}
			if (serves.incrementAndGet() > MAX_SERVES) {
				status(out, "429 Too Many Requests");
				return;
			}
			SharedContent.Pack pack = resolver.pack();
			if (pack == null || !pack.sha1.equals(sha1) || pack.problem() != null) {
				status(out, "404 Not Found");
				return;
			}
			PeerStream st = opener.open();
			try (FileClient fc = new FileClient(st)) {
				String head = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: " + pack.size
						+ "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
				out.write(head.getBytes(StandardCharsets.US_ASCII));
				fc.fetch(FileChannel.KIND_PACK, pack.sha256, pack.size, SharedContent.MAX_PACK, pack.sha1, out, null);
				out.flush();
				log("Resource Pack vom Host geliefert (" + pack.size / 1024 + " KB, Hashes geprüft)");
			}
		} catch (IOException | RuntimeException e) {
			// Abbruch mitten im Körper → Minecraft meldet den Fehler und verwirft das Pack.
			log("Resource Pack: " + (e instanceof FileClient.FileException ? ((FileClient.FileException) e).code
					: e.getClass().getSimpleName()));
		}
	}

	private static void status(OutputStream out, String status) throws IOException {
		out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	/** Erste Zeile der Anfrage (Kopf bis Leerzeile, höchstens 8 KB). */
	static String readHead(InputStream in) throws IOException {
		StringBuilder line = new StringBuilder();
		String first = null;
		int total = 0;
		int prev = -1;
		int lineLen = 0;
		while (total++ < 8192) {
			int b = in.read();
			if (b < 0) return null;
			if (b == '\n') {
				if (first == null) first = line.toString().trim();
				if (lineLen == 0 || (lineLen == 1 && prev == '\r')) return first;
				lineLen = 0;
				line.setLength(0);
			} else {
				if (first == null) line.append((char) b);
				lineLen++;
			}
			prev = b;
		}
		return null;
	}

	private void log(String m) {
		java.util.function.Consumer<String> l = log;
		if (l != null) l.accept(m);
	}
}

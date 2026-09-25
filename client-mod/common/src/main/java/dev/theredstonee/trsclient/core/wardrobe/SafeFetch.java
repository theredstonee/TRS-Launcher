package dev.theredstonee.trsclient.core.wardrobe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Lädt ein Bild von einer frei eingegebenen Adresse – mit Schutz gegen SSRF: nur {@code https}, keine
 * Zugangsdaten in der Adresse, der Name wird EINMAL aufgelöst und jede Adresse geprüft (keine Loopback-, privaten,
 * Link-Local-, CGNAT-, Multicast- oder reservierten Adressen), danach wird genau diese IP verbunden (kein zweites
 * DNS – schützt vor DNS-Rebinding). TLS prüft Zertifikat und Hostnamen wie üblich (SNI = Hostname).
 * Weiterleitungen werden selbst verfolgt (höchstens {@link #MAX_REDIRECTS}) und jede neue Adresse wieder geprüft.
 *
 * <p>Ein kleiner HTTP/1.1-Client (GET, {@code Connection: close}, Content-Length oder chunked), weil
 * {@code HttpURLConnection} weder die Ziel-IP festlegen noch den Host-Kopf setzen lässt.
 */
public final class SafeFetch {
	public static final int MAX_REDIRECTS = 3;
	static final int CONNECT_TIMEOUT_MS = 5000;
	static final int READ_TIMEOUT_MS = 10000;

	/** Fehler mit stabilem Code ({@code wardrobe.error.<code>}). */
	public static final class FetchException extends IOException {
		public final String code;

		public FetchException(String code) {
			super(code);
			this.code = code;
		}
	}

	/** Namensauflösung (für Tests ersetzbar). */
	public interface Resolver {
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	/** Verbindungsaufbau zu einer festen IP (für Tests ersetzbar). */
	public interface Connector {
		/** Liefert eine offene, bei https bereits verschlüsselte Verbindung zu {@code address}. */
		Socket connect(InetAddress address, String host, int port) throws IOException;
	}

	public static final class Result {
		public final byte[] body;
		public final String contentType;
		public final String finalUrl;

		Result(byte[] body, String contentType, String finalUrl) {
			this.body = body;
			this.contentType = contentType;
			this.finalUrl = finalUrl;
		}
	}

	private final Resolver resolver;
	private final Connector connector;
	private final String userAgent;

	public SafeFetch(String userAgent) {
		this(new Resolver() {
			@Override
			public InetAddress[] resolve(String host) throws UnknownHostException {
				return InetAddress.getAllByName(host);
			}
		}, TLS, userAgent);
	}

	public SafeFetch(Resolver resolver, Connector connector, String userAgent) {
		this.resolver = resolver;
		this.connector = connector;
		this.userAgent = userAgent == null ? "TRS-Client" : userAgent;
	}

	/** Standard: TLS mit Hostnamen-Prüfung über die feste IP. */
	static final Connector TLS = new Connector() {
		@Override
		public Socket connect(InetAddress address, String host, int port) throws IOException {
			Socket raw = new Socket();
			try {
				raw.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
				raw.setSoTimeout(READ_TIMEOUT_MS);
				SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
				SSLParameters params = ssl.getSSLParameters();
				params.setEndpointIdentificationAlgorithm("HTTPS");
				if (!host.contains(":") && !host.matches("[0-9.]+")) {
					// SNI nur für Namen (IP-Literale sind dort nicht erlaubt)
					java.util.List<javax.net.ssl.SNIServerName> names = new java.util.ArrayList<javax.net.ssl.SNIServerName>();
					names.add(new SNIHostName(host));
					params.setServerNames(names);
				}
				ssl.setSSLParameters(params);
				ssl.startHandshake();
				return ssl;
			} catch (IOException | RuntimeException e) {
				try {
					raw.close();
				} catch (IOException ignored) {
					// egal
				}
				throw e instanceof IOException ? (IOException) e : new IOException(e);
			}
		}
	};

	/**
	 * GET mit Größengrenze.
	 *
	 * @throws FetchException {@code invalid_url}, {@code blocked_address}, {@code unreachable}, {@code http_error},
	 *                        {@code too_large}, {@code too_many_redirects}
	 */
	public Result get(String url, int maxBytes) throws FetchException {
		String current = url == null ? "" : url.trim();
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			URI uri = check(current);
			String host = hostOf(uri);
			int port = uri.getPort() < 0 ? 443 : uri.getPort();
			InetAddress[] all;
			try {
				all = resolver.resolve(host);
			} catch (UnknownHostException e) {
				throw new FetchException("unreachable");
			}
			if (all == null || all.length == 0) throw new FetchException("unreachable");
			for (InetAddress a : all) {
				if (!isPublic(a)) throw new FetchException("blocked_address");
			}
			Response res;
			try {
				res = request(all[0], host, port, uri, maxBytes);
			} catch (FetchException e) {
				throw e;
			} catch (IOException | RuntimeException e) {
				throw new FetchException("unreachable");
			}
			if (res.status >= 300 && res.status < 400 && res.headers.get("location") != null) {
				try {
					current = uri.resolve(res.headers.get("location").trim()).toString();
				} catch (IllegalArgumentException e) {
					throw new FetchException("invalid_url");
				}
				continue;
			}
			if (res.status != 200) throw new FetchException("http_error");
			return new Result(res.body, res.headers.get("content-type"), current);
		}
		throw new FetchException("too_many_redirects");
	}

	/** Prüft Form und Schema der Adresse. */
	static URI check(String url) throws FetchException {
		if (url == null || url.isEmpty() || url.length() > 2048) throw new FetchException("invalid_url");
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new FetchException("invalid_url");
		}
		if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) throw new FetchException("https_only");
		if (uri.getRawUserInfo() != null) throw new FetchException("invalid_url");
		String host = uri.getHost();
		if (host == null || host.isEmpty()) throw new FetchException("invalid_url");
		int port = uri.getPort();
		if (port == 0 || port > 65535) throw new FetchException("invalid_url");
		return uri;
	}

	static String hostOf(URI uri) {
		String host = uri.getHost();
		if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
		return host.toLowerCase(Locale.ROOT);
	}

	/** Öffentliche Unicast-Adresse? (alles Interne, Reservierte und Besondere ist verboten) */
	public static boolean isPublic(InetAddress a) {
		if (a == null) return false;
		if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
				|| a.isMulticastAddress()) return false;
		byte[] b = a.getAddress();
		if (a instanceof Inet4Address) return publicV4(b);
		if (a instanceof Inet6Address) {
			int b0 = b[0] & 0xFF;
			int b1 = b[1] & 0xFF;
			if ((b0 & 0xFE) == 0xFC) return false; // fc00::/7 Unique Local
			if (b0 == 0xFE && (b1 & 0xC0) == 0x80) return false; // fe80::/10
			if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) return false; // fec0::/10 (alt: site-local)
			if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) return false; // Doku
			if (b0 == 0x20 && b1 == 0x02) { // 6to4: eingebettete IPv4 prüfen
				return publicV4(new byte[]{b[2], b[3], b[4], b[5]});
			}
			if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) { // 64:ff9b::/96 NAT64
				return publicV4(new byte[]{b[12], b[13], b[14], b[15]});
			}
			boolean zeroPrefix = true;
			for (int i = 0; i < 10; i++) zeroPrefix &= b[i] == 0;
			if (zeroPrefix) {
				// ::ffff:a.b.c.d (IPv4-mapped) und ::a.b.c.d (IPv4-kompatibel, veraltet)
				if ((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) return publicV4(new byte[]{b[12], b[13], b[14], b[15]});
				if (b[10] == 0 && b[11] == 0) return false;
			}
			return (b0 & 0xE0) == 0x20; // nur globaler Unicast 2000::/3
		}
		return false;
	}

	static boolean publicV4(byte[] b) {
		int a0 = b[0] & 0xFF;
		int a1 = b[1] & 0xFF;
		int a2 = b[2] & 0xFF;
		if (a0 == 0 || a0 == 10 || a0 == 127) return false;
		if (a0 == 100 && a1 >= 64 && a1 <= 127) return false; // CGNAT
		if (a0 == 169 && a1 == 254) return false;
		if (a0 == 172 && a1 >= 16 && a1 <= 31) return false;
		if (a0 == 192 && a1 == 168) return false;
		if (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) return false;
		if (a0 == 198 && (a1 == 18 || a1 == 19)) return false; // Benchmark
		if (a0 == 198 && a1 == 51 && a2 == 100) return false;
		if (a0 == 203 && a1 == 0 && a2 == 113) return false;
		return a0 < 224; // Multicast + reserviert + Broadcast
	}

	// --- HTTP/1.1 ---

	static final class Response {
		final int status;
		final Map<String, String> headers;
		final byte[] body;

		Response(int status, Map<String, String> headers, byte[] body) {
			this.status = status;
			this.headers = headers;
			this.body = body;
		}
	}

	private Response request(InetAddress address, String host, int port, URI uri, int maxBytes) throws IOException {
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
		if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
		String hostHeader = host.contains(":") ? "[" + host + "]" : host;
		if (port != 443) hostHeader += ":" + port;
		String req = "GET " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nUser-Agent: " + userAgent
				+ "\r\nAccept: image/png,image/*;q=0.8\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
		Socket socket = connector.connect(address, host, port);
		try {
			socket.setSoTimeout(READ_TIMEOUT_MS);
			OutputStream out = socket.getOutputStream();
			out.write(req.getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
			return parse(socket.getInputStream(), maxBytes);
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) {
				// egal
			}
		}
	}

	/** Liest Statuszeile, Kopfzeilen und Körper (Content-Length, chunked oder bis Verbindungsende). */
	static Response parse(InputStream in, int maxBytes) throws IOException {
		String statusLine = line(in);
		if (statusLine == null || !statusLine.startsWith("HTTP/1.")) throw new IOException("keine HTTP-Antwort");
		String[] parts = statusLine.split(" ", 3);
		if (parts.length < 2) throw new IOException("Statuszeile");
		int status;
		try {
			status = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			throw new IOException("Status");
		}
		Map<String, String> headers = new LinkedHashMap<String, String>();
		for (int i = 0; i < 100; i++) {
			String l = line(in);
			if (l == null) throw new IOException("Kopf abgebrochen");
			if (l.isEmpty()) break;
			int colon = l.indexOf(':');
			if (colon <= 0) continue;
			String name = l.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			if (!headers.containsKey(name)) headers.put(name, l.substring(colon + 1).trim());
		}
		if (status >= 300 && status < 400) return new Response(status, headers, new byte[0]);
		byte[] body;
		String te = headers.get("transfer-encoding");
		if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
			body = chunked(in, maxBytes);
		} else if (headers.get("content-length") != null) {
			long len;
			try {
				len = Long.parseLong(headers.get("content-length").trim());
			} catch (NumberFormatException e) {
				throw new IOException("Content-Length");
			}
			if (len < 0) throw new IOException("Content-Length");
			if (len > maxBytes) throw new FetchException("too_large");
			body = exactly(in, (int) len);
		} else {
			body = rest(in, maxBytes);
		}
		return new Response(status, headers, body);
	}

	private static String line(InputStream in) throws IOException {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 8192; i++) {
			int c = in.read();
			if (c < 0) return b.length() == 0 ? null : b.toString();
			if (c == '\n') {
				int n = b.length();
				if (n > 0 && b.charAt(n - 1) == '\r') b.setLength(n - 1);
				return b.toString();
			}
			b.append((char) c);
		}
		throw new IOException("Zeile zu lang");
	}

	private static byte[] exactly(InputStream in, int len) throws IOException {
		byte[] out = new byte[len];
		int off = 0;
		while (off < len) {
			int n = in.read(out, off, len - off);
			if (n < 0) throw new IOException("Körper abgebrochen");
			off += n;
		}
		return out;
	}

	private static byte[] rest(InputStream in, int max) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0) {
			if (out.size() + n > max) throw new FetchException("too_large");
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	private static byte[] chunked(InputStream in, int max) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int guard = 0; guard < 100000; guard++) {
			String sizeLine = line(in);
			if (sizeLine == null) throw new IOException("chunked abgebrochen");
			int semi = sizeLine.indexOf(';');
			String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
			int size;
			try {
				size = Integer.parseInt(hex, 16);
			} catch (NumberFormatException e) {
				throw new IOException("chunk");
			}
			if (size < 0) throw new IOException("chunk");
			if (size == 0) {
				// Trailer überspringen
				for (int i = 0; i < 100; i++) {
					String l = line(in);
					if (l == null || l.isEmpty()) break;
				}
				return out.toByteArray();
			}
			if (out.size() + (long) size > max) throw new FetchException("too_large");
			out.write(exactly(in, size), 0, size);
			line(in); // CRLF nach dem Stück
		}
		throw new IOException("zu viele Stücke");
	}
}

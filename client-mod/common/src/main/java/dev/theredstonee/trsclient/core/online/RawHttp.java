package dev.theredstonee.trsclient.core.online;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Schlanker HTTP/1.1-Client für Methoden, die {@link java.net.HttpURLConnection} nicht kann (vor allem
 * {@code PATCH}, API.md §18.3/§18.4). Eine Verbindung je Anfrage ({@code Connection: close}), TLS mit
 * Hostnamen-Prüfung und SNI, harte Zeit- und Größenlimits, keine Weiterleitungen. Klartext-HTTP nur zu
 * {@code localhost}/{@code 127.0.0.1} (lokale Test-Attrappe).
 */
public final class RawHttp implements Http {
	private final String userAgent;

	public RawHttp(String userAgent) {
		this.userAgent = userAgent == null ? "TRS-Client" : userAgent;
	}

	@Override
	public Response send(Request request) throws IOException {
		URI uri;
		try {
			uri = URI.create(request.url);
		} catch (IllegalArgumentException e) {
			throw new IOException("Adresse");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost();
		if (host == null) throw new IOException("Adresse ohne Host");
		boolean tls = scheme.equals("https");
		if (!tls && !(scheme.equals("http") && isLoopback(host))) throw new IOException("nur https");
		int port = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
		if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
		if (!request.method.matches("[A-Z]{3,7}")) throw new IOException("Methode");

		Socket socket = tls ? tlsSocket(host, port) : plainSocket(host, port);
		try {
			StringBuilder head = new StringBuilder(256);
			head.append(request.method).append(' ').append(path).append(" HTTP/1.1\r\n");
			head.append("Host: ").append(host).append(uri.getPort() > 0 ? ":" + uri.getPort() : "").append("\r\n");
			head.append("User-Agent: ").append(clean(userAgent)).append("\r\n");
			head.append("Accept-Encoding: identity\r\n");
			head.append("Connection: close\r\n");
			for (Map.Entry<String, String> h : request.headers.entrySet()) {
				head.append(clean(h.getKey())).append(": ").append(clean(h.getValue())).append("\r\n");
			}
			byte[] body = request.body;
			if (body != null || !request.method.equals("GET")) {
				head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
			}
			head.append("\r\n");
			OutputStream out = socket.getOutputStream();
			out.write(head.toString().getBytes(StandardCharsets.UTF_8));
			if (body != null) out.write(body);
			out.flush();
			return parse(new BufferedInputStream(socket.getInputStream(), 8192), request.maxBytes);
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) {
				// egal
			}
		}
	}

	static boolean isLoopback(String host) {
		return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
	}

	/** Keine Zeilenumbrüche in Kopfzeilen (Header-Injection). */
	private static String clean(String v) {
		if (v == null) return "";
		StringBuilder b = new StringBuilder(v.length());
		for (int i = 0; i < v.length(); i++) {
			char c = v.charAt(i);
			if (c >= ' ' && c != 127) b.append(c);
		}
		return b.toString();
	}

	private static Socket plainSocket(String host, int port) throws IOException {
		Socket s = new Socket();
		try {
			s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
			s.setSoTimeout(READ_TIMEOUT_MS);
			return s;
		} catch (IOException | RuntimeException e) {
			s.close();
			throw e instanceof IOException ? (IOException) e : new IOException(e);
		}
	}

	private static Socket tlsSocket(String host, int port) throws IOException {
		Socket raw = plainSocket(host, port);
		try {
			SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
			SSLParameters params = ssl.getSSLParameters();
			params.setEndpointIdentificationAlgorithm("HTTPS");
			if (!host.contains(":") && !host.matches("[0-9.]+")) {
				List<SNIServerName> names = new ArrayList<SNIServerName>();
				names.add(new SNIHostName(host));
				params.setServerNames(names);
			}
			ssl.setSSLParameters(params);
			ssl.setSoTimeout(READ_TIMEOUT_MS);
			ssl.startHandshake();
			return ssl;
		} catch (IOException | RuntimeException e) {
			raw.close();
			throw e instanceof IOException ? (IOException) e : new IOException(e);
		}
	}

	/** Statuszeile, Kopfzeilen (klein) und Körper (Content-Length, chunked oder bis zum Ende). */
	static Response parse(InputStream in, int maxBytes) throws IOException {
		String status = line(in);
		if (status == null || !status.startsWith("HTTP/1.")) throw new IOException("keine HTTP-Antwort");
		String[] parts = status.split(" ", 3);
		int code;
		try {
			code = Integer.parseInt(parts.length > 1 ? parts[1].trim() : "");
		} catch (NumberFormatException e) {
			throw new IOException("Status");
		}
		Map<String, String> headers = new LinkedHashMap<String, String>();
		for (int i = 0; i < 128; i++) {
			String l = line(in);
			if (l == null) throw new IOException("Kopf abgebrochen");
			if (l.isEmpty()) break;
			int colon = l.indexOf(':');
			if (colon <= 0) continue;
			String name = l.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			if (!headers.containsKey(name)) headers.put(name, l.substring(colon + 1).trim());
		}
		if (code == 204 || code == 304 || (code >= 100 && code < 200)) return new Response(code, headers, new byte[0]);
		String te = headers.get("transfer-encoding");
		byte[] body;
		if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
			body = chunked(in, maxBytes);
		} else if (headers.get("content-length") != null) {
			long len;
			try {
				len = Long.parseLong(headers.get("content-length").trim());
			} catch (NumberFormatException e) {
				throw new IOException("Content-Length");
			}
			if (len < 0 || len > maxBytes) throw new IOException("Antwort zu groß");
			body = exactly(in, (int) len);
		} else {
			body = rest(in, maxBytes);
		}
		return new Response(code, headers, body);
	}

	private static String line(InputStream in) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream(64);
		for (int i = 0; i < 16384; i++) {
			int c = in.read();
			if (c < 0) return b.size() == 0 ? null : b.toString("UTF-8");
			if (c == '\n') {
				byte[] bytes = b.toByteArray();
				int n = bytes.length;
				if (n > 0 && bytes[n - 1] == '\r') n--;
				return new String(bytes, 0, n, StandardCharsets.UTF_8);
			}
			b.write(c);
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
			if (out.size() + n > max) throw new IOException("Antwort zu groß");
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
			int size;
			try {
				size = Integer.parseInt((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
			} catch (NumberFormatException e) {
				throw new IOException("chunk");
			}
			if (size < 0) throw new IOException("chunk");
			if (size == 0) {
				for (int i = 0; i < 100; i++) {
					String l = line(in);
					if (l == null || l.isEmpty()) break;
				}
				return out.toByteArray();
			}
			if (out.size() + (long) size > max) throw new IOException("Antwort zu groß");
			out.write(exactly(in, size), 0, size);
			line(in);
		}
		throw new IOException("chunked zu lang");
	}
}

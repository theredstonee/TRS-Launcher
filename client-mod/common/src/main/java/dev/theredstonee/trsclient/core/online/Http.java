package dev.theredstonee.trsclient.core.online;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimaler HTTP-Zugang (Java 8, {@link HttpURLConnection}) mit harten Zeitlimits und
 * Größenbegrenzung. Als Schnittstelle, damit die Logik ohne Netz testbar ist.
 */
public interface Http {
	int CONNECT_TIMEOUT_MS = 5000;
	int READ_TIMEOUT_MS = 10000;

	Response send(Request request) throws IOException;

	/** Eine Anfrage. {@code body} null = ohne Körper. */
	final class Request {
		public final String method;
		public final String url;
		public final Map<String, String> headers = new LinkedHashMap<>();
		public byte[] body;
		/** Größte akzeptierte Antwort in Bytes (größere → IOException). */
		public int maxBytes = 64 * 1024;

		public Request(String method, String url) {
			this.method = method;
			this.url = url;
		}

		public Request header(String name, String value) {
			headers.put(name, value);
			return this;
		}
	}

	/** Antwort: Status, Kopfzeilen (Namen kleingeschrieben) und Körper. */
	final class Response {
		public final int status;
		public final Map<String, String> headers;
		public final byte[] body;

		public Response(int status, Map<String, String> headers, byte[] body) {
			this.status = status;
			this.headers = headers;
			this.body = body == null ? new byte[0] : body;
		}

		public String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}

		public String text() {
			return new String(body, java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	/** Standard: HttpURLConnection (TLS über die JVM, keine Weiterleitungen). */
	final class UrlConnection implements Http {
		private final String userAgent;

		public UrlConnection(String userAgent) {
			this.userAgent = userAgent;
		}

		@Override
		public Response send(Request request) throws IOException {
			HttpURLConnection c = (HttpURLConnection) new URL(request.url).openConnection();
			try {
				c.setConnectTimeout(CONNECT_TIMEOUT_MS);
				c.setReadTimeout(READ_TIMEOUT_MS);
				c.setInstanceFollowRedirects(false);
				c.setUseCaches(false);
				c.setRequestMethod(request.method);
				c.setRequestProperty("User-Agent", userAgent);
				c.setRequestProperty("Accept-Encoding", "identity");
				for (Map.Entry<String, String> h : request.headers.entrySet()) {
					c.setRequestProperty(h.getKey(), h.getValue());
				}
				if (request.body != null) {
					c.setDoOutput(true);
					c.setFixedLengthStreamingMode(request.body.length);
					try (OutputStream out = c.getOutputStream()) {
						out.write(request.body);
					}
				}
				int status = c.getResponseCode();
				Map<String, String> headers = new LinkedHashMap<>();
				for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
					if (h.getKey() == null || h.getValue() == null || h.getValue().isEmpty()) continue;
					headers.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue().get(0));
				}
				InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
				byte[] body = in == null ? new byte[0] : readLimited(in, request.maxBytes);
				return new Response(status, headers, body);
			} finally {
				c.disconnect();
			}
		}

		static byte[] readLimited(InputStream in, int max) throws IOException {
			try (InputStream stream = in) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int total = 0;
				int n;
				while ((n = stream.read(buf)) > 0) {
					total += n;
					if (total > max) throw new IOException("Antwort zu groß");
					out.write(buf, 0, n);
				}
				return out.toByteArray();
			}
		}
	}
}

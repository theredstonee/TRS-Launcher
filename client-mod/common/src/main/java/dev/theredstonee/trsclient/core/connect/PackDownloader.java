package dev.theredstonee.trsclient.core.connect;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lädt ein Server-Ressourcenpaket herunter und prüft es: nur http/https, höchstens {@code maxBytes}, SHA-1 muss
 * passen, Weiterleitungen werden einzeln geprüft (höchstens 3), private/lokale Ziele nur, wenn erlaubt (der Server
 * selbst ist privat). Erst die geprüfte Datei wird an ihren Platz verschoben – eine halbe Datei sieht Minecraft nie.
 */
public final class PackDownloader {
	static final int CONNECT_TIMEOUT_MS = 10_000;
	static final int READ_TIMEOUT_MS = 15_000;
	static final int MAX_REDIRECTS = 3;

	/** Ergebnis eines Downloads. */
	public static final class Result {
		public final boolean ok;
		public final long bytes;
		public final long ms;
		public final String error;

		Result(boolean ok, long bytes, long ms, String error) {
			this.ok = ok;
			this.bytes = bytes;
			this.ms = ms;
			this.error = error;
		}

		@Override
		public String toString() {
			return ok ? bytes + " B in " + ms + " ms" : "failed: " + error;
		}
	}

	private PackDownloader() {
	}

	/**
	 * @param bytesPerSecond Bandbreite (0 = unbegrenzt) – vorab Laden soll das Spiel nicht ausbremsen
	 * @param cancel         true = abbrechen (Spieler hat die Serverliste verlassen)
	 */
	public static Result download(String url, Path target, String sha1, long maxBytes, Map<String, String> headers,
			long bytesPerSecond, AtomicBoolean cancel, boolean allowPrivate) {
		long t0 = System.nanoTime();
		Path tmp = target.resolveSibling(target.getFileName() + ".trs-part");
		HttpURLConnection con = null;
		try {
			URL u = new URL(url);
			for (int hop = 0; ; hop++) {
				String scheme = u.getProtocol().toLowerCase(Locale.ROOT);
				if (!scheme.equals("http") && !scheme.equals("https")) return fail(t0, "scheme");
				if (!allowPrivate && privateHost(u.getHost())) return fail(t0, "private address");
				con = (HttpURLConnection) u.openConnection();
				con.setInstanceFollowRedirects(false);
				con.setConnectTimeout(CONNECT_TIMEOUT_MS);
				con.setReadTimeout(READ_TIMEOUT_MS);
				if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) con.setRequestProperty(h.getKey(), h.getValue());
				int code = con.getResponseCode();
				if (code >= 300 && code < 400 && code != 304) {
					String loc = con.getHeaderField("Location");
					con.disconnect();
					con = null;
					if (loc == null || hop >= MAX_REDIRECTS) return fail(t0, "redirect");
					u = new URL(u, loc);
					continue;
				}
				if (code != 200) return fail(t0, "HTTP " + code);
				break;
			}
			long length = con.getContentLengthLong();
			if (length > maxBytes) return fail(t0, "too large (" + length + " B)");
			Files.createDirectories(target.getParent());
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			long total = 0;
			long started = System.nanoTime();
			try (InputStream in = con.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
				byte[] buf = new byte[64 * 1024];
				int n;
				while ((n = in.read(buf)) > 0) {
					if (cancel != null && cancel.get()) {
						out.close();
						Files.deleteIfExists(tmp);
						return fail(t0, "cancelled");
					}
					total += n;
					if (total > maxBytes) {
						out.close();
						Files.deleteIfExists(tmp);
						return fail(t0, "too large");
					}
					md.update(buf, 0, n);
					out.write(buf, 0, n);
					if (bytesPerSecond > 0) throttle(started, total, bytesPerSecond);
				}
			}
			String hex = hex(md.digest());
			if (!hex.equalsIgnoreCase(sha1)) {
				Files.deleteIfExists(tmp);
				return fail(t0, "SHA-1 mismatch (" + hex + ")");
			}
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return new Result(true, total, (System.nanoTime() - t0) / 1_000_000L, null);
		} catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// egal
			}
			return fail(t0, AddressRacer.message(e));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// egal
			}
			return fail(t0, "interrupted");
		} finally {
			if (con != null) con.disconnect();
		}
	}

	private static void throttle(long startedNanos, long bytes, long bytesPerSecond) throws InterruptedException {
		long shouldMs = bytes * 1000L / bytesPerSecond;
		long isMs = (System.nanoTime() - startedNanos) / 1_000_000L;
		if (shouldMs > isMs) Thread.sleep(Math.min(shouldMs - isMs, 1000L));
	}

	private static Result fail(long t0, String error) {
		return new Result(false, 0, (System.nanoTime() - t0) / 1_000_000L, error);
	}

	/** SHA-1 einer vorhandenen Datei (hex, klein) oder null. */
	public static String sha1(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
			return hex(md.digest());
		} catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
			return null;
		}
	}

	static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
		}
		return sb.toString();
	}

	/** Zeigt ein Hostname (oder IP) auf eine private/lokale Adresse? Unbekannt = nicht privat (dann scheitert der Download ohnehin). */
	public static boolean privateHost(String host) {
		if (host == null || host.isEmpty()) return true;
		try {
			byte[] lit = IpLiteral.parse(host);
			InetAddress[] all = lit != null ? new InetAddress[]{InetAddress.getByAddress(lit)} : InetAddress.getAllByName(host);
			for (InetAddress a : all) if (isPrivate(a)) return true;
			return false;
		} catch (UnknownHostException e) {
			return false;
		}
	}

	/** Loopback, privat (RFC 1918), Link-local, CGNAT, IPv6 ULA, Multicast, „irgendeine“. */
	public static boolean isPrivate(InetAddress a) {
		if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
				|| a.isMulticastAddress()) return true;
		byte[] b = a.getAddress();
		if (a instanceof Inet6Address) return (b[0] & 0xFE) == 0xFC;
		return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
	}
}

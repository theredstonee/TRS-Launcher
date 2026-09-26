package dev.theredstonee.trsclient.core.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.Locale;

/**
 * Status-Ping (Server-List-Ping) wie Vanilla ihn für die Serverliste macht: Handshake → Status → Ping/Pong. Gemessen
 * wird die Zeit vom Ping bis zum Pong auf einer bestehenden Verbindung – ohne DNS und Verbindungsaufbau. Eigene
 * Umsetzung mit Java-Sockets (ein Aufruf = eine Verbindung, feste Zeitgrenzen).
 */
public final class StatusPing {
	public static final int DEFAULT_PORT = 25565;
	/** Größte Status-Antwort (Favicon inklusive). */
	static final int MAX_RESPONSE = 256 * 1024;

	private StatusPing() {
	}

	/** Ergebnis eines Status-Pings. */
	public static final class Result {
		public final boolean ok;
		/** Ping in ms (Pong-Zeit; ohne Pong die Zeit des Verbindungsaufbaus), -1 = unbekannt. */
		public final long latencyMs;
		public final long connectMs;
		public final int online;
		public final int max;
		public final String version;
		public final String error;

		Result(boolean ok, long latencyMs, long connectMs, int online, int max, String version, String error) {
			this.ok = ok;
			this.latencyMs = latencyMs;
			this.connectMs = connectMs;
			this.online = online;
			this.max = max;
			this.version = version;
			this.error = error;
		}

		static Result failed(String error) {
			return new Result(false, -1, -1, -1, -1, null, error);
		}

		@Override
		public String toString() {
			return ok ? latencyMs + " ms (connect " + connectMs + " ms, " + online + "/" + max + ")" : "failed: " + error;
		}
	}

	/** Host und Port aus einer Adresse ("host", "host:port", "[v6]:port"); null = ungültig. */
	public static String[] parse(String address) {
		if (address == null) return null;
		String a = address.trim();
		if (a.startsWith("minecraft://")) a = a.substring("minecraft://".length());
		if (a.isEmpty() || a.length() > 261) return null;
		String host;
		String port = null;
		if (a.startsWith("[")) {
			int end = a.indexOf(']');
			if (end < 0) return null;
			host = a.substring(1, end);
			if (a.length() > end + 1) {
				if (a.charAt(end + 1) != ':') return null;
				port = a.substring(end + 2);
			}
		} else {
			int colon = a.indexOf(':');
			if (colon >= 0 && colon == a.lastIndexOf(':')) {
				host = a.substring(0, colon);
				port = a.substring(colon + 1);
			} else {
				host = a;
			}
		}
		if (host.isEmpty()) return null;
		if (port != null) {
			try {
				int p = Integer.parseInt(port);
				if (p < 1 || p > 65535) return null;
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return new String[]{host, port};
	}

	/**
	 * Pingt eine Server-Adresse. Ohne Port wird wie bei Vanilla zuerst ein SRV-Eintrag {@code _minecraft._tcp.<host>}
	 * gesucht.
	 *
	 * @param timeoutMs Grenze für Verbindung und jede Antwort (gesamt höchstens das Doppelte)
	 */
	public static Result ping(String address, int timeoutMs) {
		String[] hp = parse(address);
		if (hp == null) return Result.failed("address");
		String host = hp[0];
		int port = DEFAULT_PORT;
		if (hp[1] != null) {
			port = Integer.parseInt(hp[1]);
		} else {
			String[] srv = srv(host, Math.min(2000, timeoutMs));
			if (srv != null) {
				host = srv[0];
				port = Integer.parseInt(srv[1]);
			}
		}
		return ping(hp[0], hp[1] != null ? port : DEFAULT_PORT, host, port, timeoutMs);
	}

	/**
	 * Pingt {@code host:port}; im Handshake stehen wie bei Vanilla Name und Port wie eingegeben
	 * ({@code handshakeHost}/{@code handshakePort}), nicht das SRV-Ziel.
	 */
	public static Result ping(String handshakeHost, int handshakePort, String host, int port, int timeoutMs) {
		long deadline = System.nanoTime() + timeoutMs * 2_000_000L;
		try (Socket s = new Socket()) {
			s.setTcpNoDelay(true);
			long c0 = System.nanoTime();
			s.connect(new InetSocketAddress(host, port), timeoutMs);
			long connectMs = (System.nanoTime() - c0) / 1_000_000L;
			s.setSoTimeout(timeoutMs);
			OutputStream out = s.getOutputStream();
			DataInputStream in = new DataInputStream(s.getInputStream());

			// Handshake (Protokoll -1 = „egal“, nächster Zustand 1 = Status) + Status-Anfrage.
			ByteArrayOutputStream hs = new ByteArrayOutputStream();
			DataOutputStream d = new DataOutputStream(hs);
			writeVarInt(d, 0x00);
			writeVarInt(d, -1);
			writeString(d, handshakeHost);
			d.writeShort(handshakePort);
			writeVarInt(d, 1);
			ByteArrayOutputStream packets = new ByteArrayOutputStream();
			frame(packets, hs.toByteArray());
			frame(packets, new byte[]{0x00});
			out.write(packets.toByteArray());
			out.flush();

			int len = readVarInt(in);
			if (len <= 0 || len > MAX_RESPONSE) return Result.failed("response");
			byte[] body = new byte[len];
			in.readFully(body);
			DataInputStream b = new DataInputStream(new java.io.ByteArrayInputStream(body));
			if (readVarInt(b) != 0x00) return Result.failed("response");
			int strLen = readVarInt(b);
			if (strLen < 0 || strLen > len) return Result.failed("response");
			byte[] json = new byte[strLen];
			b.readFully(json);
			int online = -1;
			int max = -1;
			String version = null;
			try {
				JsonElement e = new JsonParser().parse(new String(json, StandardCharsets.UTF_8));
				if (e.isJsonObject()) {
					JsonObject o = e.getAsJsonObject();
					if (o.has("players") && o.get("players").isJsonObject()) {
						JsonObject p = o.getAsJsonObject("players");
						if (p.has("online")) online = p.get("online").getAsInt();
						if (p.has("max")) max = p.get("max").getAsInt();
					}
					if (o.has("version") && o.get("version").isJsonObject() && o.getAsJsonObject("version").has("name")) {
						version = o.getAsJsonObject("version").get("name").getAsString();
					}
				}
			} catch (RuntimeException ignored) {
				// Server antwortet, nur ohne lesbares JSON – der Ping zählt trotzdem.
			}

			// Ping/Pong: die eigentliche Messung. Server/Proxys, die hier schon schließen, zählen mit der Zeit des
			// Verbindungsaufbaus (≈ eine Hin- und Rücklaufzeit).
			Result fallback = new Result(true, connectMs, connectMs, online, max, version, null);
			long remaining = (deadline - System.nanoTime()) / 1_000_000L;
			if (remaining <= 0) return fallback;
			try {
				s.setSoTimeout((int) Math.max(1, Math.min(timeoutMs, remaining)));
				ByteArrayOutputStream pingBody = new ByteArrayOutputStream();
				DataOutputStream pd = new DataOutputStream(pingBody);
				writeVarInt(pd, 0x01);
				pd.writeLong(System.nanoTime());
				ByteArrayOutputStream pingFrame = new ByteArrayOutputStream();
				frame(pingFrame, pingBody.toByteArray());
				long p0 = System.nanoTime();
				out.write(pingFrame.toByteArray());
				out.flush();
				int plen = readVarInt(in);
				if (plen < 9 || plen > 64) return fallback;
				byte[] pong = new byte[plen];
				in.readFully(pong);
				long rtt = (System.nanoTime() - p0) / 1_000_000L;
				return new Result(true, rtt, connectMs, online, max, version, null);
			} catch (IOException e) {
				return fallback;
			}
		} catch (SocketTimeoutException e) {
			return Result.failed("timeout");
		} catch (IOException | RuntimeException e) {
			return Result.failed(e.getClass().getSimpleName().toLowerCase(Locale.ROOT));
		}
	}

	/** SRV-Eintrag {@code _minecraft._tcp.<host>} über JNDI (wie Vanilla) – null, wenn keiner da ist. */
	static String[] srv(String host, int timeoutMs) {
		if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) return null;
		try {
			Hashtable<String, String> env = new Hashtable<String, String>();
			env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
			env.put("java.naming.provider.url", "dns:");
			env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(Math.max(200, timeoutMs / 2)));
			env.put("com.sun.jndi.dns.timeout.retries", "1");
			javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
			try {
				javax.naming.directory.Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
				javax.naming.directory.Attribute a = attrs.get("srv");
				if (a == null || a.size() == 0) return null;
				String[] parts = a.get(0).toString().trim().split("\\s+");
				if (parts.length < 4) return null;
				String target = parts[3];
				if (target.endsWith(".")) target = target.substring(0, target.length() - 1);
				return new String[]{target, String.valueOf(Integer.parseInt(parts[2]))};
			} finally {
				ctx.close();
			}
		} catch (Throwable t) {
			return null;
		}
	}

	// --- Protokoll-Hilfen ---

	static void frame(OutputStream out, byte[] body) throws IOException {
		DataOutputStream d = new DataOutputStream(out);
		writeVarInt(d, body.length);
		d.write(body);
	}

	static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	static void writeString(DataOutputStream out, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, b.length);
		out.write(b);
	}

	static int readVarInt(InputStream in) throws IOException {
		int value = 0;
		for (int i = 0; i < 5; i++) {
			int b = in.read();
			if (b < 0) throw new EOFException();
			value |= (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0) return value;
		}
		throw new IOException("VarInt too big");
	}
}

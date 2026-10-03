package dev.theredstonee.trsclient.core.connect;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IP-Adressen als Text lesen – garantiert ohne DNS-Abfrage (Javas {@code InetAddress.getByName} fragt bei allem,
 * was nicht eindeutig eine Adresse ist, das DNS). Erlaubt: IPv4 "a.b.c.d" (je 0–255, ohne Kurzformen) und IPv6 in
 * jeder Schreibweise ohne Zonen-ID, optional in eckigen Klammern.
 */
public final class IpLiteral {
	private IpLiteral() {
	}

	/** 4 oder 16 Byte, oder null, wenn {@code s} keine IP-Adresse ist. */
	public static byte[] parse(String s) {
		if (s == null) return null;
		String t = s.trim();
		if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
		if (t.isEmpty() || t.length() > 45) return null;
		byte[] v4 = v4(t);
		if (v4 != null) return v4;
		if (t.indexOf(':') < 0) return null;
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':' || c == '.';
			if (!ok) return null;
		}
		try {
			// Beginnt mit Hex-Ziffer oder ':' und enthält ':' → Java prüft nur die Schreibweise, kein DNS.
			return InetAddress.getByName(t).getAddress();
		} catch (UnknownHostException | RuntimeException e) {
			return null;
		}
	}

	public static boolean is(String s) {
		return parse(s) != null;
	}

	private static byte[] v4(String t) {
		String[] p = t.split("\\.", -1);
		if (p.length != 4) return null;
		byte[] out = new byte[4];
		for (int i = 0; i < 4; i++) {
			String x = p[i];
			if (x.isEmpty() || x.length() > 3) return null;
			int v = 0;
			for (int j = 0; j < x.length(); j++) {
				char c = x.charAt(j);
				if (c < '0' || c > '9') return null;
				v = v * 10 + (c - '0');
			}
			if (v > 255) return null;
			out[i] = (byte) v;
		}
		return out;
	}
}

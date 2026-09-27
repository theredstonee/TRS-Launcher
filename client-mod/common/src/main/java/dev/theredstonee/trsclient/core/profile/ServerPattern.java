package dev.theredstonee.trsclient.core.profile;

import dev.theredstonee.trsclient.core.camera.ServerList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Muster, mit denen ein Server-Profil einem Server zugeordnet wird:
 * <ul>
 *   <li>{@code hypixel.net} – der Server selbst und alle Subdomains ({@code mc.hypixel.net}),</li>
 *   <li>{@code *.hypixel.net} – nur Subdomains,</li>
 *   <li>{@code play.example.net:25566} – mit Port: nur dieser Port (ohne Port gilt jeder),</li>
 *   <li>{@code *} – jeder Mehrspieler-Server,</li>
 *   <li>{@link #SINGLEPLAYER} – Einzelspieler-Welten.</li>
 * </ul>
 * Groß-/Kleinschreibung, Schema ({@code minecraft://}) und ein Punkt am Ende zählen nicht. Passen mehrere Profile,
 * gewinnt das genaueste Muster ({@link #score}).
 */
public final class ServerPattern {
	/** Kontext bzw. Muster „Einzelspieler“ (keine gültige Serveradresse, kann also nicht kollidieren). */
	public static final String SINGLEPLAYER = "@singleplayer";
	/** Kontext „Mehrspieler ohne bekannte Adresse“ (z. B. Realms) – passt nur zu {@code *}. */
	public static final String UNKNOWN_SERVER = "@server";
	public static final int MAX_LENGTH = 80;
	public static final int DEFAULT_PORT = 25565;

	private ServerPattern() {
	}

	/** Bereinigtes Muster oder null, wenn es keins ist. */
	public static String normalize(String raw) {
		if (raw == null) return null;
		String p = raw.trim().toLowerCase(Locale.ROOT);
		if (p.isEmpty() || p.length() > MAX_LENGTH) return null;
		if (p.equals(SINGLEPLAYER)) return SINGLEPLAYER;
		if (p.equals("*")) return "*";
		boolean wildcard = p.startsWith("*.");
		String rest = wildcard ? p.substring(2) : p;
		String host = ServerList.host(rest);
		if (host.isEmpty() || !validHost(host)) return null;
		int port = port(rest);
		if (port == -2) return null;
		String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
		return (wildcard ? "*." : "") + h + (port > 0 ? ":" + port : "");
	}

	/** Zerlegt eine Eingabe (getrennt durch ;, Komma oder Leerzeichen) in Muster; ungültige landen in {@code invalid}. */
	public static List<String> parseList(String text, List<String> invalid) {
		List<String> out = new ArrayList<String>();
		if (text == null) return out;
		for (String part : text.split("[;,\\s]+")) {
			if (part.trim().isEmpty()) continue;
			String n = normalize(part);
			if (n == null) {
				if (invalid != null) invalid.add(part.trim());
			} else if (!out.contains(n)) {
				out.add(n);
			}
		}
		return out;
	}

	/** Muster für die Adresse eines Servers (Hostname ohne Port bzw. {@link #SINGLEPLAYER}). */
	public static String forContext(String context) {
		if (context == null) return null;
		if (SINGLEPLAYER.equals(context)) return SINGLEPLAYER;
		if (UNKNOWN_SERVER.equals(context)) return "*";
		String host = ServerList.host(context);
		if (host.isEmpty()) return null;
		return normalize(host.indexOf(':') >= 0 ? "[" + host + "]" : host);
	}

	/**
	 * Wie genau {@code pattern} den Kontext trifft: 0 = gar nicht, sonst höher = genauer (Einzelspieler 1000,
	 * Host mit Port 600+, Host 400+, {@code *.domain} 200+, {@code *} 1).
	 *
	 * @param context {@link #SINGLEPLAYER}, {@link #UNKNOWN_SERVER} oder eine Serveradresse ({@code host[:port]})
	 */
	public static int score(String pattern, String context) {
		if (pattern == null || context == null) return 0;
		if (SINGLEPLAYER.equals(context)) return SINGLEPLAYER.equals(pattern) ? 1000 : 0;
		if (SINGLEPLAYER.equals(pattern)) return 0;
		if (pattern.equals("*")) return 1;
		if (UNKNOWN_SERVER.equals(context)) return 0;
		String host = ServerList.host(context);
		if (host.isEmpty()) return 0;
		int port = port(context);
		if (port <= 0) port = DEFAULT_PORT;
		boolean wildcard = pattern.startsWith("*.");
		String rest = wildcard ? pattern.substring(2) : pattern;
		String ph = ServerList.host(rest);
		int pp = port(rest);
		if (pp > 0 && pp != port) return 0;
		int base;
		if (wildcard) {
			if (!host.endsWith("." + ph)) return 0;
			base = 200;
		} else if (host.equals(ph)) {
			base = 400;
		} else if (host.endsWith("." + ph)) {
			base = 300;
		} else {
			return 0;
		}
		return base + (pp > 0 ? 200 : 0) + Math.min(99, ph.length());
	}

	/** Port einer Adresse: >0 = angegeben, -1 = keiner, -2 = ungültig. */
	static int port(String address) {
		if (address == null) return -1;
		String a = address.trim();
		int scheme = a.indexOf("://");
		if (scheme >= 0) a = a.substring(scheme + 3);
		int slash = a.indexOf('/');
		if (slash >= 0) a = a.substring(0, slash);
		String digits;
		if (a.startsWith("[")) {
			int end = a.indexOf(']');
			if (end < 0) return -2;
			if (end + 1 >= a.length()) return -1;
			if (a.charAt(end + 1) != ':') return -2;
			digits = a.substring(end + 2);
		} else {
			int colon = a.indexOf(':');
			if (colon < 0 || colon != a.lastIndexOf(':')) return -1;
			digits = a.substring(colon + 1);
		}
		if (digits.isEmpty() || digits.length() > 5) return -2;
		int port = 0;
		for (int i = 0; i < digits.length(); i++) {
			char c = digits.charAt(i);
			if (c < '0' || c > '9') return -2;
			port = port * 10 + (c - '0');
		}
		return port >= 1 && port <= 65535 ? port : -2;
	}

	private static boolean validHost(String host) {
		if (host.length() > 253) return false;
		for (int i = 0; i < host.length(); i++) {
			char c = host.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == ':' || c == '_';
			if (!ok) return false;
		}
		return !host.startsWith(".") && !host.contains("..");
	}
}

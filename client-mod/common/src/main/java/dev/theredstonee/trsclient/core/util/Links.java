package dev.theredstonee.trsclient.core.util;

import java.util.Locale;

/**
 * Öffnet einen http(s)-Link im Standardbrowser – nur nach Bestätigung durch den Spieler (Chat-Links werden nie
 * automatisch geöffnet). Kein Shell-Aufruf: das Programm bekommt die Adresse als einzelnes Argument; Adressen mit
 * Leer-, Anführungs- oder Steuerzeichen werden abgelehnt.
 */
public final class Links {
	private Links() {
	}

	/** Darf diese Adresse geöffnet werden? */
	public static boolean allowed(String url) {
		if (url == null || url.length() > 2048) return false;
		String lower = url.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false;
		for (int i = 0; i < url.length(); i++) {
			char c = url.charAt(i);
			if (c <= ' ' || c == '"' || c == '<' || c == '>' || c == '\\' || c == '^' || c == '`' || c == '|' || c > 126) return false;
		}
		return true;
	}

	/** false = nicht erlaubt oder kein Browser gefunden. */
	public static boolean open(String url) {
		if (!allowed(url)) return false;
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[][] commands;
		if (os.startsWith("windows")) {
			commands = new String[][]{{"rundll32", "url.dll,FileProtocolHandler", url}};
		} else if (os.contains("mac")) {
			commands = new String[][]{{"open", url}};
		} else {
			commands = new String[][]{{"xdg-open", url}, {"gio", "open", url}};
		}
		for (String[] cmd : commands) {
			try {
				Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
				p.getOutputStream().close();
				return true;
			} catch (Exception ignored) {
				// nächster Versuch
			}
		}
		return false;
	}
}

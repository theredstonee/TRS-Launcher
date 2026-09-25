package dev.theredstonee.trsclient.core.account;

import java.io.IOException;
import java.util.Locale;

/**
 * Öffnet eine Microsoft-Anmeldeseite im Standardbrowser – ohne Minecraft-Klassen (deren Hilfsfunktion
 * wechselt zwischen den Versionen Paket und Namen) und ohne Shell: nur HTTPS-Adressen der Microsoft-Anmeldung.
 */
public final class Browser {
	private Browser() {
	}

	/** Nur diese Seiten werden je geöffnet. */
	static boolean allowed(String url) {
		if (url == null || url.length() > 4096) return false;
		for (int i = 0; i < url.length(); i++) {
			char c = url.charAt(i);
			if (c <= ' ' || c == '"' || c == '<' || c == '>' || c == '\\' || c == '^' || c == '`' || c == '|' || c > 126) return false;
		}
		return url.startsWith("https://login.microsoftonline.com/") || MsAuth.trustedVerificationUri(url);
	}

	/** false = nicht erlaubt oder kein Browser gefunden. */
	public static boolean open(String url) {
		if (!allowed(url)) return false;
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[][] commands;
		if (os.startsWith("windows")) {
			commands = new String[][] {{"rundll32", "url.dll,FileProtocolHandler", url}};
		} else if (os.contains("mac")) {
			commands = new String[][] {{"open", url}};
		} else {
			commands = new String[][] {{"xdg-open", url}, {"gio", "open", url}};
		}
		for (String[] cmd : commands) {
			try {
				Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
				p.getOutputStream().close();
				return true;
			} catch (IOException | RuntimeException ignored) {
				// nächster Versuch
			}
		}
		return false;
	}
}

package dev.theredstonee.trsclient.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Öffnet eine vorhandene Datei bzw. einen Ordner mit dem System (Explorer, Finder, xdg-open) – ohne Shell und
 * ohne Minecraft-Klassen (deren Hilfsfunktion wechselt zwischen den Versionen). Nur absolute, existierende Pfade.
 */
public final class OpenPath {
	private OpenPath() {
	}

	/** false = Pfad ungültig oder kein Programm gefunden. */
	public static boolean open(Path path) {
		if (path == null || !path.isAbsolute() || !Files.exists(path)) return false;
		String p = path.toAbsolutePath().normalize().toString();
		for (int i = 0; i < p.length(); i++) {
			if (p.charAt(i) < ' ' || p.charAt(i) == '"') return false;
		}
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[][] commands;
		if (os.startsWith("windows")) {
			commands = new String[][] {Files.isDirectory(path) ? new String[] {"explorer.exe", p}
					: new String[] {"rundll32", "url.dll,FileProtocolHandler", p}};
		} else if (os.contains("mac")) {
			commands = new String[][] {{"open", p}};
		} else {
			commands = new String[][] {{"xdg-open", p}, {"gio", "open", p}};
		}
		for (String[] cmd : commands) {
			try {
				Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
				proc.getOutputStream().close();
				return true;
			} catch (IOException | RuntimeException ignored) {
				// nächster Versuch
			}
		}
		return false;
	}
}

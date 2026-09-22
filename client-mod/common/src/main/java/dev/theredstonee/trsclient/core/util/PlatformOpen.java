package dev.theredstonee.trsclient.core.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Öffnet einen Ordner im Dateimanager des Systems. Eigene Umsetzung, weil Minecrafts
 * Hilfsfunktion dafür zwischen den Versionen umgezogen ist (und AWT im Spiel headless läuft).
 */
public final class PlatformOpen {
	private PlatformOpen() {
	}

	/** Befehl zum Öffnen von {@code path} für das Betriebssystem {@code osName} (System-Property "os.name"). */
	public static List<String> command(String osName, Path path) {
		String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		String p = path.toAbsolutePath().toString();
		if (os.contains("win")) return List.of("explorer.exe", p);
		if (os.contains("mac") || os.contains("darwin")) return List.of("open", p);
		return List.of("xdg-open", p);
	}

	/** Startet den Dateimanager (wartet nicht auf ihn). */
	public static void open(Path path) throws IOException {
		new ProcessBuilder(command(System.getProperty("os.name"), path)).start();
	}
}

package dev.theredstonee.trsclient.core.clips;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verbindungsdaten zum TRS Launcher für Clips & Aufnahme.
 *
 * <p>Der Launcher schreibt vor jedem Spielstart {@code config/trsclient/clips.json}:
 * {@code {"version":1,"enabled":true,"port":51234,"token":"<64 Hex-Zeichen>"}} bzw.
 * {@code {"version":1,"enabled":false}}, wenn Clips im Launcher aus sind. Der Port gehört zu einem
 * Server, der nur auf 127.0.0.1 lauscht; das Token gilt nur für diesen einen Spielstart.
 * Fehlt die Datei, läuft das Spiel nicht über den TRS Launcher – dann gibt es keine Clips.
 */
public final class ClipConfig {
	public static final String FILE = "clips.json";
	private static final long MAX_BYTES = 4096;

	/** Zustand der Datei. */
	public enum Kind {
		/** Keine (gültige) Datei: Spiel nicht über den TRS Launcher gestartet. */
		MISSING,
		/** Clips im Launcher ausgeschaltet. */
		DISABLED,
		/** Port und Token vorhanden. */
		ENABLED
	}

	public final Kind kind;
	public final int port;
	public final String token;
	/** Änderungszeit der Datei (zum Erkennen eines neuen Spielstarts/Tokens). */
	public final long modified;

	ClipConfig(Kind kind, int port, String token, long modified) {
		this.kind = kind;
		this.port = port;
		this.token = token;
		this.modified = modified;
	}

	static final ClipConfig MISSING = new ClipConfig(Kind.MISSING, 0, null, 0);

	/** DTO der Launcher-Datei (Gson 2.2.4-tauglich: nur Felder, geboxte Typen). */
	static final class File {
		Integer version;
		Boolean enabled;
		Integer port;
		String token;
	}

	public static Path file(Path configDir) {
		return configDir.resolve("trsclient").resolve(FILE);
	}

	/** Liest und prüft die Datei streng; alles Unerwartete zählt als "fehlt". */
	public static ClipConfig load(Path configDir) {
		if (configDir == null) return MISSING;
		Path file = file(configDir);
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return MISSING;
			long modified = Files.getLastModifiedTime(file).toMillis();
			File parsed;
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				parsed = new Gson().fromJson(reader, File.class);
			}
			return parse(parsed, modified);
		} catch (IOException | RuntimeException e) {
			return MISSING;
		}
	}

	static ClipConfig parse(File parsed, long modified) {
		if (parsed == null || parsed.version == null || parsed.version < 1 || parsed.enabled == null) return MISSING;
		if (!parsed.enabled) return new ClipConfig(Kind.DISABLED, 0, null, modified);
		if (parsed.port == null || parsed.port < 1 || parsed.port > 65535 || !validToken(parsed.token)) return MISSING;
		return new ClipConfig(Kind.ENABLED, parsed.port, parsed.token, modified);
	}

	/** Genau 64 Kleinbuchstaben-Hex-Zeichen – nichts anderes wird je gesendet. */
	static boolean validToken(String token) {
		if (token == null || token.length() != 64) return false;
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
		}
		return true;
	}

	/** Gleiche Verbindungsdaten? */
	boolean sameTarget(ClipConfig other) {
		return other != null && kind == other.kind && port == other.port
				&& (token == null ? other.token == null : token.equals(other.token));
	}
}

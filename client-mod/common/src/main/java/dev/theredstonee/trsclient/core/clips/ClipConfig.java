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
 * <p>Neue Launcher (Protokoll 2) schreiben vor jedem Spielstart {@code config/trsclient/clips.json} ohne
 * Geheimnis: {@code {"version":2,"enabled":true,"port":51234}} – der Schlüssel kommt über die Umgebung
 * (siehe {@link dev.theredstonee.trsclient.core.link.LinkTarget}). Ältere Launcher (Protokoll 1) schreiben
 * {@code {"version":1,"enabled":true,"port":51234,"token":"<64 Hex-Zeichen>"}} bzw.
 * {@code {"version":1,"enabled":false}}, wenn Clips im Launcher aus sind. Der Port gehört zu einem
 * Server, der nur auf 127.0.0.1 lauscht. Fehlt die Datei, läuft das Spiel nicht über den TRS Launcher.
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
		/** Clips an (Protokoll 1: mit Port und Token, Protokoll 2: mit Port). */
		ENABLED
	}

	public final Kind kind;
	/** Port des Launchers (0 = keiner angegeben). */
	public final int port;
	/** Nur Protokoll 1. */
	public final String token;
	/** Protokollversion der Datei. */
	public final int version;
	/** Clips im Launcher eingeschaltet? */
	public final boolean clipsEnabled;
	/** Änderungszeit der Datei (zum Erkennen eines neuen Spielstarts/Tokens). */
	public final long modified;

	ClipConfig(Kind kind, int port, String token, long modified) {
		this(kind, port, token, modified, 1);
	}

	ClipConfig(Kind kind, int port, String token, long modified, int version) {
		this.kind = kind;
		this.port = port;
		this.token = token;
		this.modified = modified;
		this.version = version;
		this.clipsEnabled = kind == Kind.ENABLED;
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
		boolean portOk = parsed.port != null && parsed.port >= 1 && parsed.port <= 65535;
		if (parsed.version >= 2) {
			// Protokoll 2: kein Token in der Datei (ein trotzdem vorhandenes wird ignoriert).
			int port = portOk ? parsed.port : 0;
			return new ClipConfig(parsed.enabled ? Kind.ENABLED : Kind.DISABLED, port, null, modified, parsed.version);
		}
		if (!parsed.enabled) return new ClipConfig(Kind.DISABLED, 0, null, modified);
		if (!portOk || !validToken(parsed.token)) return MISSING;
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

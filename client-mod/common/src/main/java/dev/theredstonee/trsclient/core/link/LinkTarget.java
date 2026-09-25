package dev.theredstonee.trsclient.core.link;

import dev.theredstonee.trsclient.core.clips.ClipConfig;

import java.nio.file.Path;

/**
 * Wohin sich die Mod verbindet und womit sie sich anmeldet.
 *
 * <p>Protokoll 2 (TRS Launcher ab dieser Version): Der Launcher gibt dem Spielprozess die Umgebungsvariable
 * {@code TRS_CLIENT_LINK=2:<port>:<sid>:<schlüssel>} mit – der Schlüssel liegt also nie auf der Platte und
 * steht auch nicht in der Befehlszeile. In {@code config/trsclient/clips.json} steht nur noch
 * {@code {"version":2,"enabled":…,"port":…}}: Nach einem Neustart des Launchers lauscht er auf einem neuen
 * Port, den die Mod dort nachliest.
 *
 * <p>Protokoll 1 (ältere Launcher): Port und Einmal-Token stehen in {@code clips.json} – dann gibt es nur
 * Clips, keine Konten.
 */
public final class LinkTarget {
	public static final String ENV = "TRS_CLIENT_LINK";

	public enum Kind {
		/** Kein TRS Launcher (weder Umgebungsvariable noch Datei). */
		MISSING,
		/** Alter Launcher, Clips aus: nichts zu verbinden. */
		DISABLED,
		/** Alter Launcher: Port + Token aus clips.json. */
		V1,
		/** Neuer Launcher: Port + sid + Schlüssel aus der Umgebung. */
		V2
	}

	public final Kind kind;
	public final int port;
	/** Nur V1. */
	public final String token;
	/** Nur V2. */
	public final String sid;
	/** Nur V2 – nie loggen, nie speichern. */
	final byte[] key;
	/** Clips laut clips.json eingeschaltet? (V2: nur ein Hinweis, der Launcher meldet es selbst.) */
	public final boolean clipsEnabled;
	/** Änderungszeit von clips.json (neuer Port/neues Token nach Launcher-Neustart). */
	public final long modified;

	private LinkTarget(Kind kind, int port, String token, String sid, byte[] key, boolean clipsEnabled, long modified) {
		this.kind = kind;
		this.port = port;
		this.token = token;
		this.sid = sid;
		this.key = key;
		this.clipsEnabled = clipsEnabled;
		this.modified = modified;
	}

	static final LinkTarget MISSING = new LinkTarget(Kind.MISSING, 0, null, null, null, false, 0);

	/** Liest {@code TRS_CLIENT_LINK} streng; null = nicht gesetzt oder ungültig. */
	static LinkTarget parseEnv(String value) {
		if (value == null || value.length() > 200) return null;
		String[] parts = value.trim().split(":", -1);
		if (parts.length != 4 || !"2".equals(parts[0])) return null;
		int port;
		try {
			port = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			return null;
		}
		if (port < 1 || port > 65535 || !LinkCrypto.isHex(parts[2], 16) || !LinkCrypto.isHex(parts[3], 64)) return null;
		return new LinkTarget(Kind.V2, port, null, parts[2], LinkCrypto.unhex(parts[3]), true, 0);
	}

	/**
	 * Aktuelles Ziel. {@code env} = Wert der Umgebungsvariable (einmal beim Start gelesen), {@code preferFile} =
	 * die Verbindung zum Port aus der Umgebung klappte zuletzt nicht → Port aus clips.json versuchen.
	 */
	static LinkTarget resolve(LinkTarget env, Path configDir, boolean preferFile) {
		ClipConfig file = ClipConfig.load(configDir);
		if (env != null) {
			boolean clips = file.kind == ClipConfig.Kind.MISSING ? true : file.clipsEnabled;
			int port = env.port;
			if (preferFile && file.port > 0) port = file.port;
			return new LinkTarget(Kind.V2, port, null, env.sid, env.key, clips, file.modified);
		}
		switch (file.kind) {
			case ENABLED:
				if (file.token != null) return new LinkTarget(Kind.V1, file.port, file.token, null, null, true, file.modified);
				// Protokoll-2-Datei ohne Umgebungsvariable (z. B. Spiel neu gestartet ohne Launcher): nicht verbinden.
				return new LinkTarget(Kind.DISABLED, 0, null, null, null, true, file.modified);
			case DISABLED:
				return new LinkTarget(Kind.DISABLED, 0, null, null, null, false, file.modified);
			default:
				return MISSING;
		}
	}

	/** Gleiche Verbindungsdaten wie {@code other}? */
	boolean sameTarget(LinkTarget other) {
		return other != null && kind == other.kind && port == other.port
				&& (token == null ? other.token == null : token.equals(other.token))
				&& (sid == null ? other.sid == null : sid.equals(other.sid));
	}

	@Override
	public String toString() {
		// Nie Token oder Schlüssel ausgeben.
		return "LinkTarget{" + kind + ", port " + port + "}";
	}
}

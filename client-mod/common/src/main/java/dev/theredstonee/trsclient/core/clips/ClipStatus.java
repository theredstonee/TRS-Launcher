package dev.theredstonee.trsclient.core.clips;

/** Unveränderlicher Stand der Verbindung zum Launcher (vom Netz-Thread ersetzt, vom Spiel gelesen). */
public final class ClipStatus {
	/** Verbunden und angemeldet. */
	public final boolean connected;
	/** Launcher kann aufnehmen (Clips an, Fenster gefunden, FFmpeg bereit). */
	public final boolean available;
	/** Grund, falls nicht verfügbar ({@code starting}, {@code noWindow}, {@code ffmpeg} …) oder null. */
	public final String reason;
	/** Ringpuffer läuft. */
	public final boolean buffer;
	public final boolean recording;
	/** Beginn der normalen Aufnahme in lokaler Wanduhrzeit (ms), 0 = keine. */
	public final long recordingSince;
	/** Länge eines Sofort-Clips (Sekunden). */
	public final int clipSeconds;

	public ClipStatus(boolean connected, boolean available, String reason, boolean buffer, boolean recording,
			long recordingSince, int clipSeconds) {
		this.connected = connected;
		this.available = available;
		this.reason = reason;
		this.buffer = buffer;
		this.recording = recording;
		this.recordingSince = recordingSince;
		this.clipSeconds = clipSeconds;
	}

	public static final ClipStatus OFFLINE = new ClipStatus(false, false, null, false, false, 0, 0);

	/** Laufzeit der Aufnahme in ms (0, wenn keine läuft). */
	public long recordingMillis(long now) {
		return recording && recordingSince > 0 ? Math.max(0, now - recordingSince) : 0;
	}
}

package dev.theredstonee.trsclient.core.clips;

/** Unveränderlicher Stand der Verbindung zum Launcher (vom Netz-Thread ersetzt, vom Spiel gelesen). */
public final class ClipStatus {
	/** Verbunden und angemeldet. */
	public final boolean connected;
	/** Launcher kann aufnehmen (Clips an, Fenster gefunden, FFmpeg bereit). */
	public final boolean available;
	/** Grund, falls nicht verfügbar ({@code disabled}, {@code starting}, {@code noWindow}, {@code ffmpeg} …) oder null. */
	public final String reason;
	/** Ringpuffer läuft. */
	public final boolean buffer;
	public final boolean recording;
	/** Beginn der normalen Aufnahme in lokaler Wanduhrzeit (ms), 0 = keine. */
	public final long recordingSince;
	/** Länge eines Sofort-Clips (Sekunden). */
	public final int clipSeconds;
	/** Download-Fortschritt von FFmpeg (0–100) oder −1 (unbekannt/kein Download). */
	public final int progress;
	/** Nimmt (bzw. würde) der Launcher den Systemton auf? Unbekannt (älterer Launcher) → true. */
	public final boolean audio;
	/** Nimmt (bzw. würde) der Launcher das Mikrofon auf? */
	public final boolean mic;
	/** Der Launcher kann Clips auf Wunsch aus dem Spiel einschalten ({@code clips.enable}). */
	public final boolean canEnable;

	public ClipStatus(boolean connected, boolean available, String reason, boolean buffer, boolean recording,
			long recordingSince, int clipSeconds) {
		this(connected, available, reason, buffer, recording, recordingSince, clipSeconds, -1, true, false, false);
	}

	public ClipStatus(boolean connected, boolean available, String reason, boolean buffer, boolean recording,
			long recordingSince, int clipSeconds, int progress, boolean audio, boolean mic, boolean canEnable) {
		this.connected = connected;
		this.available = available;
		this.reason = reason;
		this.buffer = buffer;
		this.recording = recording;
		this.recordingSince = recordingSince;
		this.clipSeconds = clipSeconds;
		this.progress = progress;
		this.audio = audio;
		this.mic = mic;
		this.canEnable = canEnable;
	}

	public static final ClipStatus OFFLINE = new ClipStatus(false, false, null, false, false, 0, 0);

	/** Laufzeit der Aufnahme in ms (0, wenn keine läuft). */
	public long recordingMillis(long now) {
		return recording && recordingSince > 0 ? Math.max(0, now - recordingSince) : 0;
	}

	/** Verbunden, aber Clips sind im Launcher aus. */
	public boolean disabled() {
		return connected && !available && !recording && "disabled".equals(reason);
	}

	/** Clips sind aus und der Launcher kann sie auf Wunsch einschalten. */
	public boolean offersEnable() {
		return disabled() && canEnable;
	}
}

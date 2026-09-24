package dev.theredstonee.trsclient.core.clips;

import dev.theredstonee.trsclient.core.i18n.I18n;

/** Kurze Meldung für den Spieler ("Clip gespeichert (30 s)", Hinweise, Fehler). */
public final class ClipNotice {
	public enum Type {
		/** Sofort-Clip gespeichert. */
		CLIP_SAVED,
		/** Normale Aufnahme gespeichert. */
		RECORDING_SAVED,
		/** Normale Aufnahme läuft jetzt. */
		RECORDING_STARTED,
		/** Launcher meldet einen Fehler ({@link #code}). */
		FAILED,
		/** Hinweis der Mod selbst ({@link #code} = Schlüssel-Endung). */
		HINT
	}

	public final Type type;
	public final int seconds;
	public final String code;

	ClipNotice(Type type, int seconds, String code) {
		this.type = type;
		this.seconds = seconds;
		this.code = code;
	}

	static ClipNotice hint(String code) {
		return new ClipNotice(Type.HINT, 0, code);
	}

	/** Erfolg (grün) oder Hinweis/Fehler (gelb/rot)? */
	public boolean success() {
		return type == Type.CLIP_SAVED || type == Type.RECORDING_SAVED || type == Type.RECORDING_STARTED;
	}

	/** Übersetzter Text. */
	public String text() {
		switch (type) {
			case CLIP_SAVED:
				return I18n.tr("clips.saved.clip", duration(seconds));
			case RECORDING_SAVED:
				return I18n.tr("clips.saved.recording", duration(seconds));
			case RECORDING_STARTED:
				return I18n.tr("clips.recording.started");
			case FAILED:
				return failure(code);
			case HINT:
			default:
				return hintText(code);
		}
	}

	static String hintText(String code) {
		if ("disabled".equals(code)) return I18n.tr("clips.hint.disabled");
		if ("unreachable".equals(code)) return I18n.tr("clips.hint.unreachable");
		return I18n.tr("clips.hint.noLauncher");
	}

	static String failure(String code) {
		if ("disabled".equals(code)) return I18n.tr("clips.hint.disabled");
		if ("starting".equals(code)) return I18n.tr("clips.failed.starting");
		if ("noWindow".equals(code)) return I18n.tr("clips.failed.noWindow");
		if ("ffmpeg".equals(code)) return I18n.tr("clips.failed.ffmpeg");
		if ("noFrames".equals(code)) return I18n.tr("clips.failed.noFrames");
		if ("busy".equals(code)) return I18n.tr("clips.failed.busy");
		return I18n.tr("clips.failed.error");
	}

	/** "30 s", "1:05", "1:02:03". */
	public static String duration(int seconds) {
		int s = Math.max(0, seconds);
		if (s < 60) return I18n.tr("clips.seconds", String.valueOf(s));
		return clock(s * 1000L);
	}

	/** Uhrzeit-Form "m:ss" bzw. "h:mm:ss". */
	public static String clock(long millis) {
		long total = Math.max(0, millis / 1000);
		long h = total / 3600;
		long m = (total / 60) % 60;
		long s = total % 60;
		String ss = s < 10 ? "0" + s : String.valueOf(s);
		if (h > 0) return h + ":" + (m < 10 ? "0" + m : String.valueOf(m)) + ":" + ss;
		return m + ":" + ss;
	}
}

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
		HINT,
		/** Clips sind aus: dieselbe Taste noch einmal schaltet sie ein (mit Hinweis, was aufgenommen wird). */
		OFFER,
		/** Clips wurden gerade eingeschaltet, die Aufnahme startet. */
		ENABLED,
		/** Die Aufnahme läuft (nach dem Einschalten) – ab jetzt speichert die Taste Clips. */
		READY,
		/** Nach dem Einschalten: FFmpeg wird geladen ({@link #progress}). */
		PREPARING
	}

	public final Type type;
	public final int seconds;
	public final String code;
	/** Fortschritt (0–100) oder −1. */
	public final int progress;
	/** Betrifft die Aufnahme-Taste (sonst die Clip-Taste) – für den Tastennamen im Text. */
	public final boolean recordKey;
	/** Was aufgenommen wird (nur {@link Type#OFFER}). */
	public final boolean audio;
	public final boolean mic;

	ClipNotice(Type type, int seconds, String code) {
		this(type, seconds, code, -1, false, true, false);
	}

	ClipNotice(Type type, int seconds, String code, int progress, boolean recordKey, boolean audio, boolean mic) {
		this.type = type;
		this.seconds = seconds;
		this.code = code;
		this.progress = progress;
		this.recordKey = recordKey;
		this.audio = audio;
		this.mic = mic;
	}

	static ClipNotice hint(String code) {
		return new ClipNotice(Type.HINT, 0, code);
	}

	static ClipNotice failed(String code, int progress) {
		return new ClipNotice(Type.FAILED, 0, code, progress, false, true, false);
	}

	static ClipNotice offer(boolean recordKey, boolean audio, boolean mic) {
		return new ClipNotice(Type.OFFER, 0, null, -1, recordKey, audio, mic);
	}

	static ClipNotice ready(int seconds) {
		return new ClipNotice(Type.READY, seconds, null);
	}

	static ClipNotice preparing(int progress) {
		return new ClipNotice(Type.PREPARING, 0, null, progress, false, true, false);
	}

	/** Erfolg (grün) oder Hinweis/Fehler (gelb/rot)? */
	public boolean success() {
		return type == Type.CLIP_SAVED || type == Type.RECORDING_SAVED || type == Type.RECORDING_STARTED
				|| type == Type.ENABLED || type == Type.READY;
	}

	/** Wartet diese Meldung auf eine Antwort des Spielers (zweiter Tastendruck)? Dann länger stehen lassen. */
	public boolean sticky() {
		return type == Type.OFFER;
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
				return failure(code, progress);
			case OFFER:
				return I18n.tr("clips.offer", Clips.keyLabel(recordKey), what(audio, mic));
			case ENABLED:
				return I18n.tr("clips.enabled");
			case READY:
				return I18n.tr("clips.ready", Clips.keyLabel(false), duration(seconds));
			case PREPARING:
				return progress >= 0 ? I18n.tr("clips.preparing", String.valueOf(progress)) : I18n.tr("clips.failed.ffmpeg");
			case HINT:
			default:
				return hintText(code);
		}
	}

	/** "Spielfenster + Systemton (+ Mikrofon)" – was der Launcher aufnimmt. */
	public static String what(boolean audio, boolean mic) {
		StringBuilder sb = new StringBuilder(I18n.tr("clips.what.window"));
		if (audio) sb.append(" + ").append(I18n.tr("clips.what.audio"));
		if (mic) sb.append(" + ").append(I18n.tr("clips.what.mic"));
		return sb.toString();
	}

	static String hintText(String code) {
		if ("disabled".equals(code)) return I18n.tr("clips.hint.disabled");
		if ("unreachable".equals(code)) return I18n.tr("clips.hint.unreachable");
		if ("enableFailed".equals(code)) return I18n.tr("clips.hint.enableFailed");
		if ("unsupported".equals(code)) return I18n.tr("clips.hint.unsupported");
		if ("wait".equals(code)) return I18n.tr("clips.hint.wait");
		return I18n.tr("clips.hint.noLauncher");
	}

	static String failure(String code, int progress) {
		if ("disabled".equals(code)) return I18n.tr("clips.hint.disabled");
		if ("starting".equals(code)) return I18n.tr("clips.failed.starting");
		if ("noWindow".equals(code)) return I18n.tr("clips.failed.noWindow");
		if ("ffmpeg".equals(code)) {
			return progress >= 0 ? I18n.tr("clips.failed.ffmpegProgress", String.valueOf(progress)) : I18n.tr("clips.failed.ffmpeg");
		}
		if ("ffmpegFailed".equals(code)) return I18n.tr("clips.failed.ffmpegFailed");
		if ("encoder".equals(code)) return I18n.tr("clips.failed.encoder");
		if ("unsupported".equals(code)) return I18n.tr("clips.hint.unsupported");
		if ("noFrames".equals(code)) return I18n.tr("clips.failed.noFrames");
		if ("busy".equals(code)) return I18n.tr("clips.failed.busy");
		return I18n.tr("clips.failed.error");
	}

	/**
	 * Zustandszeile (Clips-Bildschirm): warum gerade nicht aufgenommen wird, bzw. null, wenn alles läuft oder keine
	 * Verbindung besteht.
	 */
	public static String reasonText(ClipStatus s) {
		if (s == null || !s.connected || s.available || s.recording) return null;
		if (s.reason == null || "starting".equals(s.reason)) return I18n.tr("clips.link.starting");
		return failure(s.reason, s.progress);
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

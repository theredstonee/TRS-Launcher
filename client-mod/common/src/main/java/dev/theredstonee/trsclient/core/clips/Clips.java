package dev.theredstonee.trsclient.core.clips;

import java.nio.file.Path;

/**
 * Fassade für Clips & Aufnahme – je Loader nur: {@link #init} beim Start, die zwei Tasten und
 * {@link #tick} im Client-Tick. Aufgenommen wird im Launcher; die Mod meldet nur Tastendrücke.
 */
public final class Clips {
	/** Gibt eine Meldung in der Aktionsleiste aus (je Loader). */
	public interface ActionBar {
		void show(String text);
	}

	private static Clips instance = new Clips(null);

	private final ClipLink link;
	private ClipNotice notice;
	private long noticeUntil;

	Clips(ClipLink link) {
		this.link = link;
	}

	/** Beim Start des Clients (Config-Ordner des Spiels). Fehler werden nie nach außen gereicht. */
	public static synchronized void init(Path configDir) {
		try {
			if (instance.link != null) return;
			ClipLink link = new ClipLink(configDir);
			link.start();
			instance = new Clips(link);
		} catch (RuntimeException e) {
			// Clips sind ein Zusatz – das Spiel läuft ohne weiter.
		}
	}

	public static Clips get() {
		return instance;
	}

	/** Nur für Tests: eigene Verbindung einsetzen. */
	static void set(Clips clips) {
		instance = clips;
	}

	public ClipStatus status() {
		return link == null ? ClipStatus.OFFLINE : link.status();
	}

	/** Taste "Clip speichern". */
	public void saveClip() {
		press(ClipLink.CLIP);
	}

	/** Taste "Aufnahme starten/stoppen". */
	public void toggleRecording() {
		press(ClipLink.RECORD);
	}

	private void press(String command) {
		if (link == null) {
			pendingHint = ClipNotice.hint("noLauncher");
			return;
		}
		try {
			// Senden/Datei lesen im Hintergrund – der Spiel-Thread blockiert nie.
			link.pressInBackground(command);
		} catch (RuntimeException e) {
			pendingHint = ClipNotice.hint("unreachable");
		}
	}

	/**
	 * Client-Tick: Meldungen abholen. Mit sichtbarem HUD-Element erscheinen Erfolgsmeldungen dort,
	 * Hinweise und Fehler immer auch in der Aktionsleiste (ohne HUD-Element alles dort).
	 */
	public void tick(boolean hudVisible, ActionBar actionBar) {
		long now = System.currentTimeMillis();
		try {
			if (link != null) link.tick(now);
			ClipNotice n;
			while ((n = link == null ? null : link.pollNotice()) != null) {
				show(n, now);
				if (!hudVisible || !n.success()) actionBar.show(n.text());
			}
			if (pendingHint != null) {
				ClipNotice hint = pendingHint;
				pendingHint = null;
				show(hint, now);
				actionBar.show(hint.text());
			}
		} catch (RuntimeException e) {
			// nie das Spiel stören
		}
	}

	private ClipNotice pendingHint;

	private void show(ClipNotice n, long now) {
		notice = n;
		noticeUntil = now + ClipPanel.NOTICE_MS;
	}

	/** Aktuelle Einblendung oder null. */
	ClipNotice notice(long now) {
		return notice != null && now < noticeUntil ? notice : null;
	}
}

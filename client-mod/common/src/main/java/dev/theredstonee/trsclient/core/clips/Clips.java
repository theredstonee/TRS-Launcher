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

		/** Name der belegten Taste („F9“) für Texte wie „F9 speichert die letzten 30 s“. */
		default String keyLabel(boolean record) {
			return record ? "F10" : "F9";
		}
	}

	/** Die Aktionsleiste blendet nach ~3 s aus – ein wartendes Angebot so oft erneut zeigen. */
	private static final long REPEAT_MS = 2500;

	private static Clips instance = new Clips(null);
	private static volatile ActionBar keys;

	private final ClipLink link;
	private ClipNotice notice;
	private long noticeUntil;
	private long lastActionBar;

	Clips(ClipLink link) {
		this.link = link;
	}

	/**
	 * Beim Start des Clients (Config-Ordner des Spiels): startet auch die gemeinsame Verbindung zum Launcher
	 * ({@link dev.theredstonee.trsclient.core.link.TrsLink}, auch für Konten). Fehler werden nie nach außen gereicht.
	 */
	public static synchronized void init(Path configDir) {
		try {
			if (instance.link != null) return;
			ClipLink link = new ClipLink(dev.theredstonee.trsclient.core.link.TrsLink.initShared(configDir));
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

	/** Tastenname für Meldungen (je Loader über {@link ActionBar#keyLabel}). */
	static String keyLabel(boolean record) {
		ActionBar k = keys;
		String label = null;
		try {
			if (k != null) label = k.keyLabel(record);
		} catch (RuntimeException ignored) {
			// Rückfall unten
		}
		if (label == null || label.isEmpty()) return record ? "F10" : "F9";
		return label;
	}

	public ClipStatus status() {
		return link == null ? ClipStatus.OFFLINE : link.status();
	}

	/** Ohne Verbindung: warum (kein TRS Launcher, Clips im alten Launcher aus, Launcher nicht erreichbar). */
	public String offlineText() {
		return ClipNotice.hintText(link == null ? "noLauncher" : link.offlineCode());
	}

	/** Taste "Clip speichern". */
	public void saveClip() {
		press(ClipLink.CLIP);
	}

	/** Taste "Aufnahme starten/stoppen". */
	public void toggleRecording() {
		press(ClipLink.RECORD);
	}

	/** „Jetzt einschalten“: Clips im Launcher einschalten (nur Launcher mit {@code clips.enable}). */
	public void enableClips() {
		if (link == null) {
			pendingHint = ClipNotice.hint("noLauncher");
			return;
		}
		link.enableInBackground();
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
	 * Hinweise und Fehler immer auch in der Aktionsleiste (ohne HUD-Element alles dort). Ein Angebot
	 * („noch einmal drücken = einschalten“) bleibt stehen, bis es abläuft.
	 */
	public void tick(boolean hudVisible, ActionBar actionBar) {
		long now = System.currentTimeMillis();
		keys = actionBar;
		try {
			if (link != null) link.tick(now);
			ClipNotice n;
			while ((n = link == null ? null : link.pollNotice()) != null) {
				show(n, now);
				if (!hudVisible || !n.success()) {
					actionBar.show(n.text());
					lastActionBar = now;
				}
			}
			if (pendingHint != null) {
				ClipNotice hint = pendingHint;
				pendingHint = null;
				show(hint, now);
				actionBar.show(hint.text());
				lastActionBar = now;
			}
			ClipNotice current = notice;
			if (current != null && current.sticky()) {
				if (link == null || !link.offerActive(now)) {
					// Angebot abgelaufen oder erledigt (z. B. im Launcher eingeschaltet).
					if (now < noticeUntil) noticeUntil = now;
				} else if (now - lastActionBar >= REPEAT_MS) {
					actionBar.show(current.text());
					lastActionBar = now;
				}
			}
		} catch (RuntimeException e) {
			// nie das Spiel stören
		}
	}

	private ClipNotice pendingHint;

	private void show(ClipNotice n, long now) {
		notice = n;
		noticeUntil = now + (n.sticky() ? ClipLink.OFFER_MS : ClipPanel.NOTICE_MS);
	}

	/** Aktuelle Einblendung oder null. */
	ClipNotice notice(long now) {
		return notice != null && now < noticeUntil ? notice : null;
	}

	/** Aktuelle Meldung (für den Clips-Bildschirm) oder null. */
	public ClipNotice currentNotice() {
		return notice(System.currentTimeMillis());
	}
}

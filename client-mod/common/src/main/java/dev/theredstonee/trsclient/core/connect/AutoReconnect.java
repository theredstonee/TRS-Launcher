package dev.theredstonee.trsclient.core.connect;

import java.util.List;

/**
 * Auto-Reconnect: im „Verbindung getrennt“-Bildschirm nach einem Countdown neu verbinden – nur bei harmlosen
 * Gründen (siehe {@link KickReasons}), mit „Abbrechen“ und höchstens N Versuchen je Server. Reine Zustandslogik; der
 * Loader zeigt den Countdown und verbindet, wenn {@link #due(long)} true liefert.
 */
public final class AutoReconnect {
	public enum Phase {
		/** Nichts zu tun (kein Getrennt-Bildschirm oder Modul aus). */
		IDLE,
		/** Countdown läuft. */
		COUNTDOWN,
		/** Grund verbietet Neuverbinden (Bann, Whitelist …). */
		BLOCKED,
		/** Vom Spieler abgebrochen. */
		CANCELLED,
		/** Alle Versuche verbraucht. */
		EXHAUSTED
	}

	/** Nach so langer Zeit im Spiel gilt die Verbindung als geglückt (Versuche zählen neu). */
	public static final long STABLE_MS = 30_000;

	private Phase phase = Phase.IDLE;
	private String server;
	private String reason = "";
	private KickReasons.Kind kind = KickReasons.Kind.UNKNOWN;
	private long connectAt;
	private int attempts;
	/** Server, zu dem zuletzt (automatisch) verbunden wurde – Versuche gelten je Server. */
	private String attemptsFor;
	private long joinedAt;

	/**
	 * Der Getrennt-Bildschirm ist erschienen (einmal je Bildschirm melden).
	 *
	 * @param server   Adresse des Servers ("host:port"), null = Einzelspieler/unbekannt → nichts tun
	 * @param lenient  auch bei Kicks/unbekannten Gründen
	 * @return neue Phase
	 */
	public Phase onDisconnected(String server, String reason, long now, long delayMs, int maxAttempts, boolean lenient,
			List<String> never) {
		this.reason = reason == null ? "" : reason;
		this.kind = KickReasons.classify(this.reason);
		if (server == null || server.isEmpty()) {
			phase = Phase.IDLE;
			return phase;
		}
		if (!server.equalsIgnoreCase(attemptsFor)) {
			attemptsFor = server;
			attempts = 0;
		}
		this.server = server;
		if (!KickReasons.allowed(this.reason, lenient, never)) {
			phase = Phase.BLOCKED;
		} else if (attempts >= Math.max(1, maxAttempts)) {
			phase = Phase.EXHAUSTED;
		} else {
			phase = Phase.COUNTDOWN;
			connectAt = now + Math.max(1000, delayMs);
		}
		return phase;
	}

	/** Ist der Countdown abgelaufen? Liefert genau einmal true und zählt den Versuch. */
	public boolean due(long now) {
		if (phase != Phase.COUNTDOWN || now < connectAt) return false;
		phase = Phase.IDLE;
		attempts++;
		return true;
	}

	/** Restzeit in ganzen Sekunden (aufgerundet), 0 wenn kein Countdown läuft. */
	public int secondsLeft(long now) {
		if (phase != Phase.COUNTDOWN) return 0;
		long left = connectAt - now;
		return left <= 0 ? 0 : (int) ((left + 999) / 1000);
	}

	/** „Abbrechen“ im Bildschirm. */
	public void cancel() {
		if (phase == Phase.COUNTDOWN) phase = Phase.CANCELLED;
	}

	/** Getrennt-Bildschirm geschlossen (Zurück, anderer Bildschirm): Countdown verwerfen. */
	public void screenClosed() {
		if (phase == Phase.COUNTDOWN || phase == Phase.BLOCKED || phase == Phase.CANCELLED || phase == Phase.EXHAUSTED) {
			phase = Phase.IDLE;
		}
	}

	/**
	 * Einmal je Tick, solange man in einer Welt ist: nach {@link #STABLE_MS} gilt die Verbindung als stabil und die
	 * Versuche zählen neu.
	 */
	public void inWorld(String server, long now) {
		if (server == null) return;
		if (joinedAt == 0 || !server.equalsIgnoreCase(attemptsFor)) joinedAt = now;
		if (now - joinedAt >= STABLE_MS) attempts = 0;
	}

	/** Welt verlassen (Hauptmenü): Stabilitäts-Uhr neu starten. */
	public void leftWorld() {
		joinedAt = 0;
	}

	public Phase phase() {
		return phase;
	}

	public String server() {
		return server;
	}

	public String reason() {
		return reason;
	}

	public KickReasons.Kind kind() {
		return kind;
	}

	/** Bereits verbrauchte Versuche für den aktuellen Server. */
	public int attempts() {
		return attempts;
	}
}

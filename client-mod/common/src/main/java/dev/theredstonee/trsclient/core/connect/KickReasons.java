package dev.theredstonee.trsclient.core.connect;

import dev.theredstonee.trsclient.core.chat.ChatText;

import java.util.List;
import java.util.Locale;

/**
 * Ordnet den Text im „Verbindung getrennt“-Bildschirm einer Art zu. Auto-Reconnect verbindet nur bei harmlosen
 * Gründen (Verbindung verloren, Neustart, Server voll) neu – nie bei Bann, Whitelist, Anmeldung von woanders oder
 * falscher Version. Muster sind feste Teilzeichenfolgen (englisch, deutsch, spanisch), keine regulären Ausdrücke.
 */
public final class KickReasons {
	public enum Kind {
		/** Gebannt/gesperrt – nie neu verbinden. */
		BANNED(false),
		/** Nicht auf der Whitelist – nie neu verbinden. */
		WHITELIST(false),
		/** Mit demselben Konto von woanders angemeldet – nie (sonst Ping-Pong zweier Clients). */
		DUPLICATE_LOGIN(false),
		/** Falsche Spielversion – Neuverbinden hilft nicht. */
		OUTDATED(false),
		/** Vom Team/wegen AFK gekickt – nur, wenn „alles außer Bann“ gewählt ist. */
		KICKED(false),
		/** Server startet neu / wurde geschlossen. */
		RESTART(true),
		/** Server voll. */
		FULL(true),
		/** Verbindung verloren, Zeitüberschreitung, Server nicht erreichbar. */
		CONNECTION(true),
		/** Unbekannter Grund – nur, wenn „alles außer Bann“ gewählt ist. */
		UNKNOWN(false);

		/** Gilt als harmlos (auch im vorsichtigen Modus neu verbinden). */
		public final boolean harmless;

		Kind(boolean harmless) {
			this.harmless = harmless;
		}

		/** Nie neu verbinden, egal welcher Modus. */
		public boolean never() {
			return this == BANNED || this == WHITELIST || this == DUPLICATE_LOGIN || this == OUTDATED;
		}
	}

	static final String[] BANNED = {"banned", "you are banned", "ban reason", "ban id", "blacklisted", "suspended",
			"gebannt", "gesperrt", "baneado", "vetado", "temporarily banned", "security ban"};
	static final String[] WHITELIST = {"whitelist", "white-list", "white list", "not white", "lista blanca"};
	static final String[] DUPLICATE = {"logged in from another location", "from another location",
			"duplicate_login", "already connected", "already logged in", "anderen ort", "bereits eingeloggt",
			"otra ubicación"};
	static final String[] OUTDATED = {"outdated client", "outdated server", "incompatible", "please use",
			"veraltet", "desactualizado", "unsupported version", "version mismatch"};
	static final String[] KICKED = {"kicked", "gekickt", "expulsado", "afk", "idle", "inactive", "inaktiv",
			"spam", "flying is not enabled", "illegal"};
	static final String[] RESTART = {"restart", "rebooting", "reboot", "server closed", "shutting down",
			"server stopped", "server is stopping", "proxy lost connection", "neustart", "neu gestartet",
			"wird gestoppt", "geschlossen", "reinicio", "reiniciando", "maintenance", "wartung"};
	static final String[] FULL = {"server is full", "full server", "the server is full", "server full",
			"server ist voll", "servidor lleno", "lobby is full", "queue is full"};
	static final String[] CONNECTION = {"timed out", "timeout", "connection reset", "connection lost",
			"connection refused", "lost connection", "internal exception", "end of stream", "disconnected",
			"can't connect", "cannot connect", "failed to connect", "connection closed", "unknown host",
			"network is unreachable", "no further information", "zeitüberschreitung", "verbindung verloren",
			"verbindung getrennt", "verbindung abgelehnt", "conexión perdida", "tiempo de espera"};

	private KickReasons() {
	}

	/** Art des Grundes (sichtbarer Text, Farbcodes egal). */
	public static Kind classify(String reason) {
		String t = ChatText.strip(reason == null ? "" : reason).toLowerCase(Locale.ROOT);
		if (t.trim().isEmpty()) return Kind.UNKNOWN;
		// Reihenfolge = Vorrang: „Du wurdest gebannt (Neustart in 5 Tagen)“ bleibt ein Bann.
		if (hasWord(t, "ban") || any(t, BANNED)) return Kind.BANNED;
		if (any(t, WHITELIST)) return Kind.WHITELIST;
		if (any(t, DUPLICATE)) return Kind.DUPLICATE_LOGIN;
		if (any(t, OUTDATED)) return Kind.OUTDATED;
		if (any(t, KICKED)) return Kind.KICKED;
		if (any(t, RESTART)) return Kind.RESTART;
		if (any(t, FULL)) return Kind.FULL;
		if (any(t, CONNECTION)) return Kind.CONNECTION;
		return Kind.UNKNOWN;
	}

	/**
	 * Darf bei diesem Grund neu verbunden werden?
	 *
	 * @param lenient  „alles außer Bann/Whitelist“ (auch Kicks und Unbekanntes)
	 * @param never    eigene Muster, bei denen nie neu verbunden wird (klein geschrieben)
	 */
	public static boolean allowed(String reason, boolean lenient, List<String> never) {
		if (ChatText.containsAny(reason, never)) return false;
		Kind kind = classify(reason);
		if (kind.never()) return false;
		return kind.harmless || lenient;
	}

	private static boolean any(String text, String[] needles) {
		for (String n : needles) {
			if (text.contains(n)) return true;
		}
		return false;
	}

	/** Steht {@code word} als ganzes Wort im Text? ("ban" ja, "bandwidth" nein) */
	private static boolean hasWord(String text, String word) {
		int from = 0;
		while (true) {
			int i = text.indexOf(word, from);
			if (i < 0) return false;
			boolean left = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
			int end = i + word.length();
			boolean right = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
			if (left && right) return true;
			from = i + 1;
		}
	}
}

package dev.theredstonee.trsclient.core.connect;

import dev.theredstonee.trsclient.core.chat.ChatText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Warteschlangen-Hinweis: liest die Position aus Chat, Aktionsleiste und Titeln („Position in queue: 12“) und meldet
 * einmal, wenn man (fast) dran ist bzw. weitergeleitet wird. Muster sind einfache Vorlagen mit {@code #} für die Zahl
 * (keine regulären Ausdrücke aus Benutzereingaben).
 */
public final class QueueWatcher {
	/**
	 * Standard-Vorlagen bekannter Warteschlangen-Plugins (ajQueue, 2b2t-artige Proxys, LiteBans-/Velocity-Queues …),
	 * englisch, deutsch, spanisch. Groß-/Kleinschreibung und Leerzeichen egal.
	 */
	public static final String[] DEFAULT_PATTERNS = {
			"position in queue: #",
			"position in queue #",
			"position in the queue: #",
			"position in the queue is #",
			"queue position: #",
			"queue position #",
			"you are in position #",
			"you are position #",
			"in position # of",
			"your position: #",
			"place in queue: #",
			"platz in der warteschlange: #",
			"position in der warteschlange: #",
			"du bist auf platz #",
			"warteschlange: #",
			"posición en la cola: #",
			"estás en la posición #",
	};
	/** Meldungen, dass die Warteschlange vorbei ist (man wird verbunden). */
	public static final String[] DONE_PATTERNS = {
			"connecting to the server", "connecting you to", "sending you to", "you are being sent to",
			"you have been moved to", "joining the server", "verbinde mit", "du wirst verbunden", "du wirst zu",
			"conectando al servidor",
	};

	public enum Event {
		NONE,
		/** Position hat die Schwelle erreicht. */
		ALMOST,
		/** Warteschlange vorbei (Weiterleitung). */
		DONE
	}

	private final List<Template> templates = new ArrayList<Template>();
	private String builtFrom;
	/** Zuletzt gelesene Position, -1 = gerade nicht in einer Warteschlange. */
	private int position = -1;
	private long seenAt;
	private boolean alerted;
	/** Nach so langer Zeit ohne Positionsmeldung gilt die Warteschlange als vorbei (ohne Hinweis). */
	public static final long FORGET_MS = 5 * 60_000L;

	/** Vorlagen neu bauen, wenn sich die eigenen geändert haben. */
	public void configure(String custom) {
		String key = custom == null ? "" : custom;
		if (key.equals(builtFrom)) return;
		builtFrom = key;
		templates.clear();
		for (String p : DEFAULT_PATTERNS) templates.add(Template.of(p));
		for (String p : ChatText.words(key)) {
			Template t = Template.of(p);
			if (t != null) templates.add(t);
		}
	}

	/**
	 * Eine Zeile (Chat, Aktionsleiste oder Titel) prüfen.
	 *
	 * @param threshold Hinweis, sobald die Position ≤ dieser Zahl ist (≥ 1)
	 */
	public Event onText(String text, long now, int threshold) {
		if (text == null || text.isEmpty()) return Event.NONE;
		if (builtFrom == null) configure("");
		if (position >= 0 && now - seenAt > FORGET_MS) reset();
		String plain = ChatText.strip(text).toLowerCase(Locale.ROOT);
		int found = -1;
		for (int i = 0; i < templates.size() && found < 0; i++) {
			Template t = templates.get(i);
			if (t != null) found = t.match(plain);
		}
		if (found >= 0) {
			boolean firstInQueue = position < 0;
			if (!firstInQueue && found > position + 2) alerted = false; // Warteschlange neu (z. B. anderer Server)
			position = found;
			seenAt = now;
			if (!alerted && found <= Math.max(1, threshold)) {
				alerted = true;
				return Event.ALMOST;
			}
			return Event.NONE;
		}
		if (position >= 0) {
			for (String done : DONE_PATTERNS) {
				if (plain.contains(done)) {
					reset();
					return Event.DONE;
				}
			}
		}
		return Event.NONE;
	}

	/** Welt/Server gewechselt ohne Weiterleitungs-Meldung. */
	public void reset() {
		position = -1;
		alerted = false;
	}

	/** Aktuelle Position (-1 = keine Warteschlange). */
	public int position() {
		return position;
	}

	/** Eine Vorlage: Textstücke vor/nach der Zahl, Leerzeichen flexibel. */
	static final class Template {
		private final String[] before;
		private final String[] after;

		private Template(String[] before, String[] after) {
			this.before = before;
			this.after = after;
		}

		/** "position in queue: #" → vorher ["position","in","queue:"], nachher []. null ohne "#". */
		static Template of(String pattern) {
			String p = pattern.toLowerCase(Locale.ROOT).trim();
			int hash = p.indexOf('#');
			if (hash < 0) return null;
			String[] b = tokens(p.substring(0, hash));
			String[] a = tokens(p.substring(hash + 1));
			if (b.length == 0 && a.length == 0) return null;
			return new Template(b, a);
		}

		private static String[] tokens(String s) {
			String t = s.trim();
			return t.isEmpty() ? new String[0] : t.split("\\s+");
		}

		/** Position in der (klein geschriebenen) Zeile oder -1. */
		int match(String line) {
			String[] words = line.trim().split("\\s+");
			for (int start = 0; start < words.length; start++) {
				int i = start;
				boolean ok = true;
				for (String b : before) {
					if (i >= words.length || !words[i].equals(b)) {
						ok = false;
						break;
					}
					i++;
				}
				if (!ok || i >= words.length) continue;
				int number = leadingNumber(words[i]);
				if (number < 0) continue;
				// Zahl kann an einem Wort hängen ("#3", "3.", "3/120") – der Rest nach der Zahl ist egal.
				int j = i + 1;
				for (String a : after) {
					if (j >= words.length || !words[j].startsWith(a)) {
						ok = false;
						break;
					}
					j++;
				}
				if (ok) return number;
			}
			return -1;
		}

		/** Zahl am Anfang des Worts (mit Tausender-Trennern, "#" davor erlaubt) oder -1. */
		private static int leadingNumber(String word) {
			int i = 0;
			if (i < word.length() && word.charAt(i) == '#') i++;
			long n = 0;
			int digits = 0;
			for (; i < word.length(); i++) {
				char c = word.charAt(i);
				if (c >= '0' && c <= '9') {
					n = n * 10 + (c - '0');
					digits++;
					if (n > 10_000_000) return -1;
				} else if ((c == ',' || c == '.') && digits > 0 && threeDigits(word, i + 1)) {
					// Tausender-Trenner ("1,234"): nur wenn genau drei Ziffern folgen
					continue;
				} else {
					break;
				}
			}
			return digits == 0 ? -1 : (int) n;
		}

		/** Stehen ab {@code i} genau drei Ziffern (danach Ende oder keine Ziffer)? */
		private static boolean threeDigits(String word, int i) {
			if (i + 3 > word.length()) return false;
			for (int k = i; k < i + 3; k++) {
				if (!Character.isDigit(word.charAt(k))) return false;
			}
			return i + 3 == word.length() || !Character.isDigit(word.charAt(i + 3));
		}
	}
}

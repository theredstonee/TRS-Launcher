package dev.theredstonee.trsclient.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hilfen für Chat-Text, der noch alte Farbcodes ("§a") enthalten kann – so schicken viele Server (auch Hypixel) ihre
 * Nachrichten. Alles arbeitet ohne reguläre Ausdrücke aus Benutzereingaben (kein ReDoS) und ohne Minecraft.
 */
public final class ChatText {
	/** Zeichen, das einen Farb-/Formatcode einleitet. */
	public static final char CODE = '§';
	/** Höchstens so viele Einträge je Wortliste (Filter, Hervorhebung …). */
	public static final int MAX_WORDS = 32;
	/** Einträge kürzer als das werden ignoriert (ein Buchstabe würde fast alles treffen). */
	public static final int MIN_WORD = 2;

	private ChatText() {
	}

	/** Zerlegt eine durch ";" (oder Zeilenumbruch) getrennte Liste, klein geschrieben, ohne Leer-/Doppeleinträge. */
	public static List<String> words(String text) {
		List<String> list = new ArrayList<String>();
		if (text == null) return list;
		for (String part : text.split("[;\\n]")) {
			String t = strip(part).trim().toLowerCase(Locale.ROOT);
			if (t.length() < MIN_WORD || t.length() > 64 || list.contains(t)) continue;
			list.add(t);
			if (list.size() >= MAX_WORDS) break;
		}
		return list;
	}

	/** Text ohne Farbcodes. */
	public static String strip(String text) {
		if (text == null || text.indexOf(CODE) < 0) return text == null ? "" : text;
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == CODE) {
				i++;
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/** Gehört das Zeichen zu einem Spielernamen ([A-Za-z0-9_])? Wortgrenzen richten sich danach. */
	public static boolean nameChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
	}

	/** Buchstabe/Ziffer im weiteren Sinn (auch Umlaute) – für Wortgrenzen normaler Wörter. */
	private static boolean wordChar(char c) {
		return nameChar(c) || Character.isLetterOrDigit(c);
	}

	/** Ist die Stelle i Teil eines Farbcodes ("§" selbst oder das Zeichen danach)? */
	public static boolean inCode(String raw, int i) {
		if (i < 0 || i >= raw.length()) return false;
		if (raw.charAt(i) == CODE) return true;
		return i > 0 && raw.charAt(i - 1) == CODE && !(i > 1 && raw.charAt(i - 2) == CODE);
	}

	/** Sichtbares Zeichen vor der Stelle {@code i} (Farbcodes übersprungen), sonst 0. */
	public static char visibleBefore(String raw, int i) {
		int j = i - 1;
		while (j >= 0) {
			if (inCode(raw, j)) {
				j--;
				continue;
			}
			return raw.charAt(j);
		}
		return 0;
	}

	/** Sichtbares Zeichen ab der Stelle {@code i} (Farbcodes übersprungen), sonst 0. */
	public static char visibleAt(String raw, int i) {
		int j = i;
		while (j < raw.length()) {
			if (inCode(raw, j)) {
				j++;
				continue;
			}
			return raw.charAt(j);
		}
		return 0;
	}

	/**
	 * Alle Fundstellen der Wörter im Rohtext (Farbcodes bleiben unberührt, Groß-/Kleinschreibung egal), nicht
	 * überlappend, von links nach rechts. Ein Wort trifft nur als ganzes Wort (kein "max" in "maximal").
	 *
	 * @return Paare {start, ende} (Ende exklusiv) im Rohtext
	 */
	public static List<int[]> find(String raw, List<String> words) {
		List<int[]> out = new ArrayList<int[]>();
		if (raw == null || raw.isEmpty() || words == null || words.isEmpty()) return out;
		String lower = raw.toLowerCase(Locale.ROOT);
		int i = 0;
		while (i < raw.length()) {
			int bestEnd = -1;
			if (!inCode(raw, i)) {
				for (int w = 0; w < words.size(); w++) {
					String word = words.get(w);
					int end = matchAt(raw, lower, i, word);
					if (end > bestEnd) bestEnd = end;
				}
			}
			if (bestEnd > i) {
				out.add(new int[]{i, bestEnd});
				i = bestEnd;
			} else {
				i++;
			}
		}
		return out;
	}

	/** Passt {@code word} (klein geschrieben) ab Stelle {@code start} als ganzes Wort? Liefert das Ende oder -1. */
	private static int matchAt(String raw, String lower, int start, String word) {
		if (word.isEmpty()) return -1;
		// Wortgrenze vorn (nur wenn das Wort selbst mit einem Wortzeichen beginnt).
		if (wordChar(word.charAt(0)) && wordChar(visibleBefore(raw, start))) return -1;
		int j = start;
		for (int k = 0; k < word.length(); k++) {
			while (j < raw.length() && inCode(raw, j)) j++;
			if (j >= raw.length() || lower.charAt(j) != word.charAt(k)) return -1;
			j++;
		}
		if (wordChar(word.charAt(word.length() - 1)) && wordChar(visibleAt(raw, j))) return -1;
		return j;
	}

	/** Enthält der (sichtbare) Text eines der Wörter als Teilzeichenfolge? Für Filter – ohne Wortgrenzen. */
	public static boolean containsAny(String plain, List<String> words) {
		if (plain == null || words == null || words.isEmpty()) return false;
		String lower = strip(plain).toLowerCase(Locale.ROOT);
		for (int i = 0; i < words.size(); i++) {
			if (lower.contains(words.get(i))) return true;
		}
		return false;
	}

	/**
	 * Steht die Fundstelle dort, wo der Absender einer Spieler-Nachricht steht ("&lt;Name&gt; …", "[Rang] Name: …")?
	 * Dann ist es die eigene Nachricht – kein Ping. Vor dem Treffer darf kein ':'/'&gt;' stehen, direkt danach
	 * (nach Klammern/Leerzeichen/Farbcodes) folgt ':' oder '&gt;'.
	 */
	public static boolean senderPosition(String raw, int start, int end) {
		String before = strip(raw.substring(0, Math.max(0, Math.min(start, raw.length()))));
		if (before.indexOf(':') >= 0 || before.indexOf('>') >= 0) return false;
		for (int j = end; j < raw.length(); j++) {
			if (inCode(raw, j)) continue;
			char c = raw.charAt(j);
			if (c == ':' || c == '>') return true;
			if (c == ']' || c == ')' || c == ' ' || c == '»') {
				if (c == '»') return true;
				continue;
			}
			return false;
		}
		return false;
	}

	/**
	 * Aktive Farb-/Formatcodes am Ende von {@code rawPrefix} (z. B. "§c§l") – damit ein Textstück nach einer
	 * eingeschobenen Hervorhebung wieder so aussieht wie vorher.
	 */
	public static String activeCodes(String rawPrefix) {
		String color = "";
		StringBuilder formats = new StringBuilder();
		for (int i = 0; i + 1 < rawPrefix.length(); i++) {
			if (rawPrefix.charAt(i) != CODE) continue;
			char code = Character.toLowerCase(rawPrefix.charAt(i + 1));
			i++;
			if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
				color = "" + CODE + code;
				formats.setLength(0);
			} else if (code == 'r') {
				color = "";
				formats.setLength(0);
			} else if (code >= 'k' && code <= 'o') {
				String f = "" + CODE + code;
				if (formats.indexOf(f) < 0) formats.append(f);
			}
		}
		return color + formats;
	}
}

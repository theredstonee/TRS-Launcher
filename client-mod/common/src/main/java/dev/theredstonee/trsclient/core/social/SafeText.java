package dev.theredstonee.trsclient.core.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Säubert Text anderer Spieler, bevor er im Spiel gezeichnet wird: keine Minecraft-Formatierungscodes
 * ({@code §x}), keine Steuer-, Richtungs- oder unsichtbaren Formatzeichen, begrenzte Länge. Die API säubert
 * schon (API.md §18.4) – hier gilt trotzdem: nie fremdem Text vertrauen.
 */
public final class SafeText {
	/** Größte Nachrichtenlänge laut API (Unicode-Codepunkte). */
	public static final int MAX_MESSAGE = 2000;
	public static final int MAX_NAME = 32;
	/** Links (nur zum Anzeigen/Bestätigen – nie automatisch öffnen). */
	private static final Pattern LINK = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"']{1,400}");

	private SafeText() {
	}

	/**
	 * Mehrzeiliger Nachrichtentext: {@code §} samt folgendem Zeichen entfernt, Steuerzeichen (außer Zeilenumbruch)
	 * und Richtungs-/unsichtbare Formatzeichen raus (Emoji-Verbinder ZWJ bleibt), höchstens eine Leerzeile in Folge,
	 * höchstens {@code max} Codepunkte.
	 */
	public static String message(String raw, int max) {
		if (raw == null) return null;
		StringBuilder b = new StringBuilder(Math.min(raw.length(), max + 16));
		int newlines = 0;
		int points = 0;
		for (int i = 0; i < raw.length() && points < max; ) {
			int cp = raw.codePointAt(i);
			int len = Character.charCount(cp);
			i += len;
			if (cp == 0xA7) {
				// Formatierungscode: das nächste Zeichen gehört dazu.
				if (i < raw.length()) i += Character.charCount(raw.codePointAt(i));
				continue;
			}
			if (cp == '\r') continue;
			if (cp == '\n') {
				newlines++;
				if (newlines > 2 || b.length() == 0) continue;
				b.append('\n');
				points++;
				continue;
			}
			if (cp == '\t') cp = ' ';
			if (!visible(cp)) continue;
			newlines = 0;
			b.appendCodePoint(cp);
			points++;
		}
		// Keine Leerzeilen am Ende.
		int end = b.length();
		while (end > 0 && (b.charAt(end - 1) == '\n' || b.charAt(end - 1) == ' ')) end--;
		b.setLength(end);
		return b.toString();
	}

	/** Einzeilig (Namen, Gruppennamen, Server-Beschriftungen, Vorschauen). */
	public static String line(String raw, int max) {
		if (raw == null) return null;
		String m = message(raw.replace('\n', ' ').replace('\r', ' '), max);
		return m.trim();
	}

	/** Minecraft-Name ({@code ^[A-Za-z0-9_]{1,16}$}) oder null. */
	public static String playerName(String raw) {
		if (raw == null) return null;
		String t = raw.trim();
		return t.matches("[A-Za-z0-9_]{1,16}") ? t : null;
	}

	/** Darf das Zeichen gezeichnet werden? */
	static boolean visible(int cp) {
		if (cp < 0x20 || (cp >= 0x7F && cp < 0xA0)) return false;
		// Richtungssteuerung (Bidi-Overrides/Isolates) und unsichtbare Formatzeichen.
		if (cp >= 0x202A && cp <= 0x202E) return false;
		if (cp >= 0x2066 && cp <= 0x2069) return false;
		if (cp == 0x200E || cp == 0x200F || cp == 0x061C) return false;
		if (cp == 0x200B || cp == 0x200C || cp == 0x2060 || cp == 0xFEFF || cp == 0x00AD) return false;
		if (cp >= 0xFFF9 && cp <= 0xFFFB) return false;
		if (cp >= 0xE0000 && cp <= 0xE007F) return false;
		int type = Character.getType(cp);
		if (type == Character.PRIVATE_USE || type == Character.UNASSIGNED || type == Character.SURROGATE) return false;
		return type != Character.FORMAT || cp == 0x200D;
	}

	/** Links im Text (Anfang, Ende) für die Anzeige als bestätigbarer Link. */
	public static List<int[]> links(String text) {
		List<int[]> out = new ArrayList<int[]>();
		if (text == null) return out;
		Matcher m = LINK.matcher(text);
		while (m.find() && out.size() < 20) {
			int end = m.end();
			// Satzzeichen am Ende gehören nicht zum Link.
			while (end > m.start() && ".,;:!?)]}".indexOf(text.charAt(end - 1)) >= 0) end--;
			out.add(new int[]{m.start(), end});
		}
		return out;
	}

	/** Nur http(s)-Links mit Host; sonst null. Liefert die Adresse so, wie sie im Bestätigungsdialog steht. */
	public static String safeLink(String url) {
		if (url == null || url.length() > 400) return null;
		try {
			java.net.URI u = new java.net.URI(url);
			String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) return null;
			if (u.getHost() == null || u.getHost().isEmpty()) return null;
			return u.toASCIIString();
		} catch (java.net.URISyntaxException e) {
			return null;
		}
	}

	/** Servereinladung: "host[:port]" in Kleinbuchstaben (wie im Minecraft-Feld „Serveradresse“), sonst null. */
	public static String serverAddress(String raw) {
		if (raw == null) return null;
		String t = raw.trim().toLowerCase(Locale.ROOT);
		if (t.isEmpty() || t.length() > 253 + 6) return null;
		return t.matches("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*(:[0-9]{1,5})?") ? t : null;
	}

	/** Anzahl Codepunkte. */
	public static int length(String s) {
		return s == null ? 0 : s.codePointCount(0, s.length());
	}
}

package dev.theredstonee.trsclient.core.notes;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Eine Notiz einer Welt (Gson-DTO, alle Felder einfach). Gelöschte Notizen bleiben als Grabstein
 * ({@link #deleted}, ohne Titel/Text) erhalten, damit die Löschung per Sync auf andere PCs kommt.
 *
 * <p>Konflikte löst {@link #updated} (Millisekunden): beim Abgleich gewinnt je Notiz die neuere Änderung.
 */
public final class Note {
	/** Höchstens so viele Zeichen (Codepunkte) Text. */
	public static final int MAX_TEXT = 20_000;
	/** Höchstens so viele Zeichen Titel. */
	public static final int MAX_TITLE = 64;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** 16 Kleinbuchstaben-Hex (zufällig, auf allen PCs eindeutig). */
	public String id;
	public String title = "";
	public String text = "";
	public long created;
	public long updated;
	/** Grabstein: gelöscht (Titel/Text leer). */
	public boolean deleted;

	public Note() {
	}

	/** Neue, leere Notiz. */
	public static Note create(long now) {
		Note n = new Note();
		n.id = newId();
		n.created = now;
		n.updated = now;
		return n;
	}

	public static String newId() {
		byte[] b = new byte[8];
		RANDOM.nextBytes(b);
		StringBuilder sb = new StringBuilder(16);
		for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
		return sb.toString();
	}

	/** Gültige ID (16 Hex, klein)? */
	public static boolean validId(String id) {
		if (id == null || id.length() != 16) return false;
		for (int i = 0; i < 16; i++) {
			char c = id.charAt(i);
			if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
		}
		return true;
	}

	/** Titel zum Anzeigen: eigener Titel, sonst die erste Textzeile, sonst null (= „Ohne Titel“). */
	public String displayTitle() {
		String t = title == null ? "" : title.trim();
		if (!t.isEmpty()) return t;
		String first = NoteText.firstLine(text);
		return first.isEmpty() ? null : first;
	}

	/** Kopie (Sync-Thread bekommt Kopien, nie die Objekte der Oberfläche). */
	public Note copy() {
		Note n = new Note();
		n.id = id;
		n.title = title;
		n.text = text;
		n.created = created;
		n.updated = updated;
		n.deleted = deleted;
		return n;
	}

	/** Inhalt gleich (ohne Zeiten)? */
	public boolean sameContent(Note o) {
		return o != null && deleted == o.deleted && eq(title, o.title) && eq(text, o.text);
	}

	private static boolean eq(String a, String b) {
		return (a == null ? "" : a).equals(b == null ? "" : b);
	}

	/** Zum Grabstein machen. */
	public void tombstone(long now) {
		deleted = true;
		title = "";
		text = "";
		updated = Math.max(now, updated + 1);
	}

	/**
	 * Kaputte/zu große Werte geradeziehen (geladene Datei, Sync). Null, wenn die Notiz unbrauchbar ist (keine gültige
	 * ID).
	 */
	public Note normalized() {
		if (!validId(id)) return null;
		title = clean(title, MAX_TITLE, false);
		text = clean(text, MAX_TEXT, true);
		if (deleted) {
			title = "";
			text = "";
		}
		if (created <= 0) created = updated > 0 ? updated : 1;
		if (updated <= 0) updated = created;
		return this;
	}

	/**
	 * Steuerzeichen raus (außer Zeilenumbruch im Text), Formatierungscodes {@code §x} raus, Tab → Leerzeichen,
	 * höchstens {@code max} Codepunkte.
	 */
	public static String clean(String s, int max, boolean multiline) {
		if (s == null || s.isEmpty()) return "";
		String in = s.replace("\r\n", "\n").replace('\r', '\n');
		StringBuilder out = new StringBuilder(Math.min(in.length(), max + 16));
		int count = 0;
		for (int i = 0; i < in.length() && count < max; ) {
			int cp = in.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '\t') cp = ' ';
			if (cp == '\n' && !multiline) cp = ' ';
			if (cp == 0xA7) {
				// Formatierungscode samt folgendem Zeichen verwerfen (wie im Chat-Eingabefeld).
				if (i < in.length()) i += Character.charCount(in.codePointAt(i));
				continue;
			}
			if (cp != '\n' && (cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp < 0xA0) || cp == 0x2028 || cp == 0x2029)) continue;
			out.appendCodePoint(cp);
			count++;
		}
		return out.toString();
	}

	/** Anzahl Codepunkte. */
	public static int length(String s) {
		return s == null ? 0 : s.codePointCount(0, s.length());
	}
}

package dev.theredstonee.trsclient.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Findet Koordinaten in Chat-Zeilen („x: 100 y: 64 z: -20“, „X=100, Y=64, Z=-20“, „100 64 -20“, „100, 64, -20“,
 * „100/64/-20“), damit man sie anklicken und als Wegpunkt speichern kann. Nur lokal – es wird nie etwas gesendet.
 *
 * <p>Vorsichtig bei Tripeln ohne Beschriftung: nur mit Wortgrenzen, ohne führende Nullen, mit mindestens einer Zahl
 * mit zwei Stellen oder einem Minus, keine Versionsnummern/Uhrzeiten (Punkt/Doppelpunkt sind keine Trenner) und keine
 * Datumsangaben wie „5/10/2026“. Grenzen wie Minecraft: x/z ±30 000 000, y −2048…4096.
 */
public final class ChatCoords {
	public static final int MAX_XZ = 30_000_000;
	public static final int MIN_Y = -2048;
	public static final int MAX_Y = 4096;
	/** Höchstens so viele Treffer je Zeile. */
	public static final int MAX_HITS = 5;
	/** Längere Zeilen werden nicht durchsucht (Kosten). */
	static final int MAX_LENGTH = 1024;

	private static final String NUM = "(-?(?:0|[1-9]\\d{0,7}))";
	private static final Pattern LABELED = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])x\\s*[:=]?\\s*" + NUM + "\\s*[,;/]?\\s*"
			+ "y\\s*[:=]?\\s*" + NUM + "\\s*[,;/]?\\s*z\\s*[:=]?\\s*" + NUM + "(?![\\p{L}\\p{N}_.])");
	private static final Pattern PLAIN = Pattern.compile("(?<![\\p{L}\\p{N}_.:/,#$€%-])" + NUM + "(\\s*[,/]\\s*|\\s+)" + NUM
			+ "(\\s*[,/]\\s*|\\s+)" + NUM + "(?![\\p{L}\\p{N}_%$€]|[.,:/]\\d)");
	private static final Pattern INSERTION = Pattern.compile(NUM + " " + NUM + " " + NUM);

	private ChatCoords() {
	}

	/** Ein Treffer: Textbereich [start, end) und die Blockposition. */
	public static final class Hit {
		public final int start;
		public final int end;
		public final int x;
		public final int y;
		public final int z;

		Hit(int start, int end, int x, int y, int z) {
			this.start = start;
			this.end = end;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		/** Marker für den Stil der Chat-Spanne (Einfügen per Umschalt-Klick = „x y z“). */
		public String insertion() {
			return ChatCoords.insertion(x, y, z);
		}
	}

	/** Alle Koordinaten der Zeile (höchstens {@link #MAX_HITS}, ohne Überschneidung, aufsteigend). */
	public static List<Hit> find(String text) {
		List<Hit> out = new ArrayList<Hit>();
		if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) return out;
		// Schnellprüfung: ohne drei Ziffern kann nichts passen.
		int digits = 0;
		for (int i = 0; i < text.length() && digits < 3; i++) if (Character.isDigit(text.charAt(i))) digits++;
		if (digits < 3) return out;
		Matcher m = LABELED.matcher(text);
		while (m.find() && out.size() < MAX_HITS) {
			Hit h = hit(m.start(), m.end(), m.group(1), m.group(2), m.group(3));
			if (h != null) out.add(h);
		}
		m = PLAIN.matcher(text);
		int from = 0;
		while (from < text.length() && m.find(from) && out.size() < MAX_HITS) {
			from = m.start() + 1;
			if (overlaps(out, m.start(), m.end())) continue;
			String a = m.group(1), b = m.group(3), c = m.group(5);
			if (!plausible(a, b, c, m.group(2), m.group(4))) continue;
			Hit h = hit(m.start(), m.end(), a, b, c);
			if (h == null) continue;
			out.add(h);
			from = m.end();
		}
		sort(out);
		return out;
	}

	/** Treffer mit verschobenem Bereich (z. B. Koordinaten einer Zeile im ganzen Text einer Notiz). */
	public static Hit hitAt(int start, int end, int x, int y, int z) {
		return new Hit(start, end, x, y, z);
	}

	/** Marker „x y z“. */
	public static String insertion(int x, int y, int z) {
		return x + " " + y + " " + z;
	}

	/** Marker zurücklesen: {x, y, z} oder null (fremde Einfüge-Texte, außerhalb der Grenzen). */
	public static int[] parseInsertion(String s) {
		if (s == null || s.length() > 40) return null;
		Matcher m = INSERTION.matcher(s);
		if (!m.matches()) return null;
		Hit h = hit(0, s.length(), m.group(1), m.group(2), m.group(3));
		return h == null ? null : new int[]{h.x, h.y, h.z};
	}

	private static Hit hit(int start, int end, String a, String b, String c) {
		try {
			long x = Long.parseLong(a), y = Long.parseLong(b), z = Long.parseLong(c);
			if (Math.abs(x) > MAX_XZ || Math.abs(z) > MAX_XZ || y < MIN_Y || y > MAX_Y) return null;
			return new Hit(start, end, (int) x, (int) y, (int) z);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Regeln für Tripel ohne Beschriftung. */
	private static boolean plausible(String a, String b, String c, String sep1, String sep2) {
		// Gleiche Trenner (kein „1, 2 3“-Mischmasch aus Aufzählungen).
		if (!sep1.trim().equals(sep2.trim())) return false;
		boolean strong = a.startsWith("-") || b.startsWith("-") || c.startsWith("-")
				|| digits(a) >= 2 || digits(b) >= 2 || digits(c) >= 2;
		if (!strong) return false;
		// Datum „T/M/JJJJ“ bzw. „T.M.JJJJ“ (Punkt schließt das Muster ohnehin aus).
		if (sep1.trim().equals("/")) {
			long x = Long.parseLong(a), y = Long.parseLong(b), z = Long.parseLong(c);
			if (x >= 1 && x <= 31 && y >= 1 && y <= 31 && z >= 1900 && z <= 2199) return false;
		}
		return true;
	}

	private static int digits(String s) {
		return s.startsWith("-") ? s.length() - 1 : s.length();
	}

	private static boolean overlaps(List<Hit> hits, int start, int end) {
		for (Hit h : hits) if (start < h.end && end > h.start) return true;
		return false;
	}

	private static void sort(List<Hit> hits) {
		for (int i = 1; i < hits.size(); i++) {
			Hit h = hits.get(i);
			int j = i - 1;
			while (j >= 0 && hits.get(j).start > h.start) {
				hits.set(j + 1, hits.get(j));
				j--;
			}
			hits.set(j + 1, h);
		}
	}
}

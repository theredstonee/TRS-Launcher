package dev.theredstonee.trsclient.core.notes;

import dev.theredstonee.trsclient.core.chat.ChatCoords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Aufbau des Notiz-Texts (reine Logik, getestet): Zeilen, Checklisten-Einträge ({@code [ ] Aufgabe} /
 * {@code [x] erledigt}, auch mit {@code - } davor) und Koordinaten ({@link ChatCoords}), die in der Notiz als
 * anklickbare Links erscheinen.
 */
public final class NoteText {
	/** Präfix einer neuen Checklisten-Zeile. */
	public static final String OPEN_BOX = "[ ] ";

	private NoteText() {
	}

	/** Eine Zeile des Texts: Bereich [start, end) ohne Zeilenumbruch. */
	public static final class Line {
		public final int start;
		public final int end;
		/** -1 = keine Checkliste, 0 = offen, 1 = abgehakt. */
		public final int check;
		/** Beginn des eigentlichen Inhalts (hinter dem Kästchen). */
		public final int contentStart;

		Line(int start, int end, int check, int contentStart) {
			this.start = start;
			this.end = end;
			this.check = check;
			this.contentStart = contentStart;
		}

		public boolean isCheck() {
			return check >= 0;
		}

		public boolean checked() {
			return check == 1;
		}
	}

	/** Alle Zeilen (ein leerer Text hat eine leere Zeile). */
	public static List<Line> lines(String text) {
		List<Line> out = new ArrayList<Line>();
		String t = text == null ? "" : text;
		int start = 0;
		while (true) {
			int nl = t.indexOf('\n', start);
			int end = nl < 0 ? t.length() : nl;
			out.add(line(t, start, end));
			if (nl < 0) break;
			start = nl + 1;
		}
		return out;
	}

	private static Line line(String t, int start, int end) {
		int i = start;
		while (i < end && t.charAt(i) == ' ') i++;
		if (i + 1 < end && (t.charAt(i) == '-' || t.charAt(i) == '*') && t.charAt(i + 1) == ' ') i += 2;
		if (end - i >= 3 && t.charAt(i) == '[' && t.charAt(i + 2) == ']') {
			char m = t.charAt(i + 1);
			int state = m == ' ' ? 0 : (m == 'x' || m == 'X') ? 1 : -1;
			if (state >= 0 && (i + 3 == end || t.charAt(i + 3) == ' ')) {
				int content = Math.min(end, i + 3 + (i + 3 < end ? 1 : 0));
				return new Line(start, end, state, content);
			}
		}
		return new Line(start, end, -1, start);
	}

	/** Zeile, in der {@code pos} liegt. */
	public static Line lineAt(String text, int pos) {
		String t = text == null ? "" : text;
		int p = Math.max(0, Math.min(pos, t.length()));
		int start = t.lastIndexOf('\n', p - 1) + 1;
		int nl = t.indexOf('\n', p);
		return line(t, start, nl < 0 ? t.length() : nl);
	}

	/** Kästchen der Zeile {@code index} umschalten; unveränderter Text, wenn die Zeile keine Checkliste ist. */
	public static String toggle(String text, int index) {
		List<Line> lines = lines(text);
		if (index < 0 || index >= lines.size()) return text;
		Line l = lines.get(index);
		if (!l.isCheck()) return text;
		int box = text.indexOf('[', l.start);
		if (box < 0 || box + 2 >= l.end) return text;
		char mark = l.checked() ? ' ' : 'x';
		return text.substring(0, box + 1) + mark + text.substring(box + 2);
	}

	/**
	 * Zeile mit dem Cursor zur Checkliste machen bzw. wieder normal (Knopf „Checkliste“). Rückgabe: {neuer Text,
	 * neue Cursorposition}.
	 */
	public static Object[] toggleChecklistLine(String text, int cursor) {
		String t = text == null ? "" : text;
		Line l = lineAt(t, cursor);
		if (l.isCheck()) {
			int box = t.indexOf('[', l.start);
			int cut = Math.min(l.end, box + 3 + (box + 3 < l.end && t.charAt(box + 3) == ' ' ? 1 : 0));
			int removed = cut - l.start;
			String out = t.substring(0, l.start) + t.substring(cut);
			return new Object[] {out, Integer.valueOf(Math.max(l.start, cursor - removed))};
		}
		String out = t.substring(0, l.start) + OPEN_BOX + t.substring(l.start);
		return new Object[] {out, Integer.valueOf(cursor + OPEN_BOX.length())};
	}

	/**
	 * Enter in einer Checklisten-Zeile: neue Zeile beginnt wieder mit einem Kästchen; Enter in einem leeren Eintrag
	 * beendet die Liste (Kästchen weg). Null = normaler Zeilenumbruch.
	 */
	public static Object[] enterInChecklist(String text, int cursor) {
		String t = text == null ? "" : text;
		Line l = lineAt(t, cursor);
		if (!l.isCheck() || cursor < l.contentStart) return null;
		if (t.substring(l.contentStart, l.end).trim().isEmpty()) {
			String out = t.substring(0, l.start) + t.substring(l.end);
			return new Object[] {out, Integer.valueOf(l.start)};
		}
		String insert = "\n" + OPEN_BOX;
		String out = t.substring(0, cursor) + insert + t.substring(cursor);
		return new Object[] {out, Integer.valueOf(cursor + insert.length())};
	}

	/** {erledigt, gesamt} der Checklisten-Einträge. */
	public static int[] progress(String text) {
		int done = 0, total = 0;
		if (text == null || text.indexOf('[') < 0) return new int[] {0, 0};
		for (Line l : lines(text)) {
			if (!l.isCheck()) continue;
			total++;
			if (l.checked()) done++;
		}
		return new int[] {done, total};
	}

	/** Erste nicht leere Zeile (ohne Kästchen), gekürzt auf 80 Zeichen. */
	public static String firstLine(String text) {
		if (text == null) return "";
		for (Line l : lines(text)) {
			String s = text.substring(l.contentStart, l.end).trim();
			if (!s.isEmpty()) return s.length() > 80 ? s.substring(0, 80) : s;
		}
		return "";
	}

	/** Koordinaten einer Zeile (Bereiche relativ zum Textanfang). */
	public static List<ChatCoords.Hit> coords(String text, Line line) {
		if (text == null || line.end <= line.contentStart) return Collections.emptyList();
		String s = text.substring(line.contentStart, line.end);
		List<ChatCoords.Hit> raw = ChatCoords.find(s);
		if (raw.isEmpty()) return raw;
		List<ChatCoords.Hit> out = new ArrayList<ChatCoords.Hit>(raw.size());
		for (ChatCoords.Hit h : raw) out.add(shift(h, line.contentStart));
		return out;
	}

	private static ChatCoords.Hit shift(ChatCoords.Hit h, int by) {
		return ChatCoords.hitAt(h.start + by, h.end + by, h.x, h.y, h.z);
	}

	/** Text für „Meine Position einfügen“: {@code x: 12, y: 64, z: -300}. */
	public static String position(int x, int y, int z) {
		return "x: " + x + ", y: " + y + ", z: " + z;
	}

	/** Passt die Notiz zur Suche (Titel oder Text enthalten alle Wörter, ohne Groß/Klein)? */
	public static boolean matches(Note n, String query) {
		if (query == null) return true;
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return true;
		String hay = ((n.title == null ? "" : n.title) + "\n" + (n.text == null ? "" : n.text)).toLowerCase(Locale.ROOT);
		for (String word : q.split("\\s+")) {
			if (!word.isEmpty() && hay.indexOf(word) < 0) return false;
		}
		return true;
	}
}

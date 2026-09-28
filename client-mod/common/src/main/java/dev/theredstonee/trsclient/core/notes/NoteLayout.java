package dev.theredstonee.trsclient.core.notes;

import dev.theredstonee.trsclient.core.chat.ChatCoords;
import dev.theredstonee.trsclient.core.ui.social.ChatLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Umbruch einer Notiz in Anzeigezeilen (Notiz-Seite und HUD): Checklisten-Einträge bekommen vorn Platz für das
 * Kästchen, ihre Folgezeilen rücken ein; Koordinaten sind je Anzeigezeile als Bereiche abgelegt.
 */
public final class NoteLayout {
	/** Breite des Kästchens samt Abstand. */
	public static final int BOX_W = 11;

	private NoteLayout() {
	}

	/** Eine Anzeigezeile: Textbereich [start, end) der Zeile {@code line}. */
	public static final class Row {
		public final int line;
		public final int start;
		public final int end;
		/** Kästchen vor dieser Zeile: -1 = keins, 0 = offen, 1 = abgehakt (nur in der ersten Zeile eines Eintrags). */
		public final int check;
		/** Einrückung in Pixeln (Checklisten-Einträge). */
		public final int indent;
		/** Checklisten-Eintrag (auch Folgezeilen) – für gedämpfte Farbe abgehakter Einträge. */
		public final boolean checkedItem;
		/** Koordinaten, die in diese Zeile fallen (Bereiche auf die Zeile gekürzt). */
		public final List<ChatCoords.Hit> coords;

		Row(int line, int start, int end, int check, int indent, boolean checkedItem, List<ChatCoords.Hit> coords) {
			this.line = line;
			this.start = start;
			this.end = end;
			this.check = check;
			this.indent = indent;
			this.checkedItem = checkedItem;
			this.coords = coords;
		}
	}

	/** Alle Anzeigezeilen des Texts bei {@code width} Pixeln. */
	public static List<Row> layout(String text, int width, ChatLayout.Measure m) {
		List<Row> out = new ArrayList<Row>();
		String t = text == null ? "" : text;
		List<NoteText.Line> lines = NoteText.lines(t);
		for (int li = 0; li < lines.size(); li++) {
			NoteText.Line l = lines.get(li);
			int from = l.isCheck() ? l.contentStart : l.start;
			int indent = l.isCheck() ? BOX_W : 0;
			List<ChatCoords.Hit> hits = NoteText.coords(t, l);
			String part = t.substring(from, l.end);
			List<int[]> wrapped = ChatLayout.wrap(m, part, Math.max(8, width - indent));
			if (wrapped.isEmpty()) wrapped = Collections.singletonList(new int[] {0, 0});
			for (int i = 0; i < wrapped.size(); i++) {
				int s = from + wrapped.get(i)[0];
				int e = from + wrapped.get(i)[1];
				out.add(new Row(li, s, e, i == 0 ? l.check : -1, indent, l.checked(), clip(hits, s, e)));
			}
		}
		return out;
	}

	private static List<ChatCoords.Hit> clip(List<ChatCoords.Hit> hits, int s, int e) {
		if (hits.isEmpty()) return Collections.emptyList();
		List<ChatCoords.Hit> out = null;
		for (ChatCoords.Hit h : hits) {
			int a = Math.max(h.start, s), b = Math.min(h.end, e);
			if (b <= a) continue;
			if (out == null) out = new ArrayList<ChatCoords.Hit>(2);
			out.add(ChatCoords.hitAt(a, b, h.x, h.y, h.z));
		}
		return out == null ? Collections.<ChatCoords.Hit>emptyList() : out;
	}
}

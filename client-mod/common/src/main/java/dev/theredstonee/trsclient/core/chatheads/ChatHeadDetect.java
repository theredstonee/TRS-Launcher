package dev.theredstonee.trsclient.core.chatheads;

import dev.theredstonee.trsclient.core.chat.ChatText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 *
 * Based on Chat Heads by dzwdz (https://github.com/dzwdz/chat_heads), modified for the TRS Client.
 */

/**
 * Sucht den Absender einer Chat-Zeile in der Tab-Liste. Der Ablauf (Wortgrenzen, Codepoints, frühester Treffer)
 * folgt {@code ChatHeads.scanForPlayerName}. Zusätzlich muss der Name in {@code <…>} stehen oder ein Trennzeichen
 * ({@code : » > - |}) bzw. „whispers“ folgen – und Flüstern an sich selbst liefert {@link Hit#self}.
 */
public final class ChatHeadDetect {
	private static final int[] WHISPERS = points("whispers");
	private static final int[] YOU_WHISPER = points("You whisper");

	private ChatHeadDetect() {
	}

	/** Ein Treffer. {@link #name} ist die Schreibweise aus der Tab-Liste. */
	public static final class Hit {
		public final String name;
		public final boolean self;
		/** Start im Text ohne Farbcodes, in UTF-16-Einheiten. */
		public final int index;

		public Hit(String name, boolean self, int index) {
			this.name = name;
			this.self = self;
			this.index = index;
		}
	}

	/**
	 * Frühester passender Tab-Name, sonst die eigene Flüster-Form, sonst null.
	 * Groß-/Kleinschreibung wie im Profil. Namen, die mit {@code |slot_} beginnen, werden übersprungen.
	 */
	public static Hit find(String raw, List<String> names) {
		if (raw == null) return null;
		String plain = ChatText.strip(raw);
		if (plain.isEmpty()) return null;
		int[] text = points(plain);
		int[] units = unitStarts(plain);
		Hit named = earliestName(text, units, names);
		Hit self = earliestSelf(text, units);
		if (named == null) return self;
		if (self == null) return named;
		return self.index < named.index ? self : named;
	}

	private static Hit earliestName(int[] text, int[] units, List<String> names) {
		if (names == null || names.isEmpty() || text.length == 0) return null;
		Map<Integer, List<int[]>> byFirst = new HashMap<Integer, List<int[]>>();
		Map<Integer, String> spelling = new HashMap<Integer, String>();
		int id = 0;
		for (int n = 0; n < names.size(); n++) {
			String name = names.get(n);
			if (name == null || name.isEmpty() || name.startsWith("|slot_")) continue;
			int[] seq = points(ChatText.strip(name));
			if (seq.length == 0) continue;
			spelling.put(Integer.valueOf(id), name);
			seq = withId(seq, id++);
			List<int[]> bucket = byFirst.get(Integer.valueOf(seq[0]));
			if (bucket == null) {
				bucket = new ArrayList<int[]>();
				byFirst.put(Integer.valueOf(seq[0]), bucket);
			}
			bucket.add(seq);
		}
		for (List<int[]> bucket : byFirst.values()) {
			Collections.sort(bucket, LONGEST_FIRST);
		}

		boolean insideWord = false;
		for (int i = 0; i < text.length; i++) {
			int c = text[i];
			if (insideWord && isWordCharacter(c)) continue;
			List<int[]> bucket = byFirst.get(Integer.valueOf(c));
			if (bucket != null) {
				for (int b = 0; b < bucket.size(); b++) {
					int[] named = bucket.get(b);
					int len = named.length - 1;
					if (i + len > text.length) continue;
					boolean endsAsWord = isWordCharacter(named[len - 1]);
					boolean followedByWord = i + len < text.length && isWordCharacter(text[i + len]);
					if (endsAsWord && followedByWord) continue;
					if (!containsAt(text, i, named, len)) continue;
					if (!accepted(text, i, i + len)) continue;
					String written = spelling.get(Integer.valueOf(named[len]));
					return new Hit(written, false, units[i]);
				}
			}
			insideWord = isWordCharacter(c);
		}
		return null;
	}

	/** „You whisper …“ und „[me -&gt; …]“. */
	private static Hit earliestSelf(int[] text, int[] units) {
		int best = -1;
		boolean insideWord = false;
		for (int i = 0; i < text.length; i++) {
			int c = text[i];
			if (!insideWord || !isWordCharacter(c)) {
				if (matchesYouWhisper(text, i) || matchesMeArrow(text, i)) {
					best = i;
					break;
				}
			}
			insideWord = isWordCharacter(c);
		}
		if (best < 0) return null;
		return new Hit(null, true, units[best]);
	}

	private static boolean matchesYouWhisper(int[] text, int i) {
		if (!containsAt(text, i, YOU_WHISPER, YOU_WHISPER.length)) return false;
		int after = i + YOU_WHISPER.length;
		return after >= text.length || !isWordCharacter(text[after]);
	}

	/** {@code [me ->} mit beliebigen Leerzeichen um den Pfeil. */
	private static boolean matchesMeArrow(int[] text, int i) {
		if (i + 3 > text.length || text[i] != '[') return false;
		int j = i + 1;
		if (j >= text.length || text[j] != 'm' || j + 1 >= text.length || text[j + 1] != 'e') return false;
		j += 2;
		if (j < text.length && isWordCharacter(text[j])) return false;
		while (j < text.length && text[j] == ' ') j++;
		return j < text.length && text[j] == '-';
	}

	/** In {@code <Name>}, oder danach ein Trennzeichen bzw. „whispers“. */
	private static boolean accepted(int[] text, int start, int end) {
		if (start > 0 && text[start - 1] == '<' && end < text.length && text[end] == '>') return true;
		int i = end;
		while (i < text.length && text[i] == ' ') i++;
		if (i >= text.length) return false;
		int c = text[i];
		if (c == ':' || c == '»' || c == '>' || c == '-' || c == '|') return true;
		if (!containsAt(text, i, WHISPERS, WHISPERS.length)) return false;
		int after = i + WHISPERS.length;
		return after >= text.length || !isWordCharacter(text[after]);
	}

	/** Wie Chat Heads: Buchstabe, Ziffer, Unterstrich oder ein Zeichen mit Zahlenwert. */
	static boolean isWordCharacter(int codePoint) {
		return Character.isLetterOrDigit(codePoint) || codePoint == '_' || Character.getNumericValue(codePoint) != -1;
	}

	private static boolean containsAt(int[] sequence, int start, int[] sub, int length) {
		if (start < 0 || length < 0 || start + length > sequence.length) return false;
		for (int j = 0; j < length; j++) {
			if (sequence[start + j] != sub[j]) return false;
		}
		return true;
	}

	private static int[] withId(int[] seq, int id) {
		int[] out = new int[seq.length + 1];
		System.arraycopy(seq, 0, out, 0, seq.length);
		out[seq.length] = id;
		return out;
	}

	private static int[] points(String text) {
		int count = text.codePointCount(0, text.length());
		int[] out = new int[count];
		int n = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			out[n++] = cp;
			i += Character.charCount(cp);
		}
		return out;
	}

	private static int[] unitStarts(String text) {
		int count = text.codePointCount(0, text.length());
		int[] out = new int[count];
		int n = 0;
		for (int i = 0; i < text.length(); ) {
			out[n++] = i;
			i += Character.charCount(text.codePointAt(i));
		}
		return out;
	}

	private static final Comparator<int[]> LONGEST_FIRST = new Comparator<int[]>() {
		@Override
		public int compare(int[] a, int[] b) {
			return b.length - a.length;
		}
	};
}

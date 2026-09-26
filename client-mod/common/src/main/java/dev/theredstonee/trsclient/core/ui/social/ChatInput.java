package dev.theredstonee.trsclient.core.ui.social;

import dev.theredstonee.trsclient.core.social.SafeText;
import dev.theredstonee.trsclient.core.ui.UiKey;

/**
 * Mehrzeiliges Eingabefeld des Chats: Zeilenumbrüche (Umschalt+Enter), Einfügen, Cursor, höchstens
 * {@link SafeText#MAX_MESSAGE} Codepunkte. Formatierungszeichen ({@code §}) und Steuerzeichen werden nie übernommen.
 */
public final class ChatInput {
	private final StringBuilder text = new StringBuilder();
	private final int max;
	private int cursor;
	private boolean focused;

	public ChatInput(int maxCodePoints) {
		this.max = Math.max(1, maxCodePoints);
	}

	public String text() {
		return text.toString();
	}

	public boolean isEmpty() {
		return text.toString().trim().isEmpty();
	}

	public int cursor() {
		return cursor;
	}

	public boolean focused() {
		return focused;
	}

	public void setFocused(boolean f) {
		focused = f;
	}

	public void clear() {
		text.setLength(0);
		cursor = 0;
	}

	public void setText(String value) {
		clear();
		insert(value == null ? "" : value);
	}

	private int length() {
		return text.codePointCount(0, text.length());
	}

	/** Text an der Cursorposition einfügen (Einfügen aus der Zwischenablage, Tippen). Rückgabe: etwas übernommen. */
	public boolean insert(String raw) {
		if (raw == null || raw.isEmpty()) return false;
		String clean = raw.replace("\r\n", "\n").replace('\r', '\n');
		StringBuilder ok = new StringBuilder();
		int room = max - length();
		for (int i = 0; i < clean.length() && room > 0; ) {
			int cp = clean.codePointAt(i);
			int n = Character.charCount(cp);
			if (cp == 0xA7) {
				// Formatierungscode samt folgendem Zeichen verwerfen.
				i += n;
				if (i < clean.length()) i += Character.charCount(clean.codePointAt(i));
				continue;
			}
			i += n;
			if (cp == '\t') cp = ' ';
			if (cp != '\n' && (cp < 0x20 || cp == 0x7F)) continue;
			ok.appendCodePoint(cp);
			room--;
		}
		if (ok.length() == 0) return false;
		text.insert(Math.min(cursor, text.length()), ok);
		cursor = Math.min(text.length(), cursor + ok.length());
		return true;
	}

	/** Ein getipptes Zeichen. */
	public boolean type(char c) {
		if (c < ' ' || c == 127 || c == '§') return false;
		if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) {
			// Surrogat-Hälften einzeln (manche Versionen liefern Emoji so): trotzdem übernehmen.
			if (length() >= max) return false;
			text.insert(Math.min(cursor, text.length()), c);
			cursor++;
			return true;
		}
		return insert(String.valueOf(c));
	}

	public void newline() {
		insert("\n");
	}

	/** Taste verarbeiten; true = verbraucht. {@code paste} liefert den Inhalt der Zwischenablage. */
	public boolean key(UiKey key, String paste) {
		switch (key) {
			case BACKSPACE:
				if (cursor > 0) {
					int start = text.offsetByCodePoints(cursor, -1);
					text.delete(start, cursor);
					cursor = start;
				}
				return true;
			case DELETE:
				if (cursor < text.length()) text.delete(cursor, text.offsetByCodePoints(cursor, 1));
				return true;
			case LEFT:
				if (cursor > 0) cursor = text.offsetByCodePoints(cursor, -1);
				return true;
			case RIGHT:
				if (cursor < text.length()) cursor = text.offsetByCodePoints(cursor, 1);
				return true;
			case HOME: {
				int nl = text.lastIndexOf("\n", cursor - 1);
				cursor = nl < 0 ? 0 : nl + 1;
				return true;
			}
			case END: {
				int nl = text.indexOf("\n", cursor);
				cursor = nl < 0 ? text.length() : nl;
				return true;
			}
			case SELECT_ALL:
				cursor = text.length();
				return true;
			case PASTE:
				insert(paste);
				return true;
			default:
				return false;
		}
	}
}

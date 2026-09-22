package dev.theredstonee.trsclient.core.ui;

/**
 * Einzeiliges Eingabefeld (Suche, Profilnamen) – eigener Code, weil Vanilla-Textfelder
 * in jeder Minecraft-Version anders angelegt und gezeichnet werden.
 */
public final class TextInput {
	private final StringBuilder text = new StringBuilder();
	private final int maxLength;
	private int cursor;
	private boolean focused;

	public TextInput(int maxLength) {
		this.maxLength = Math.max(1, maxLength);
	}

	public String text() {
		return text.toString();
	}

	public void setText(String value) {
		text.setLength(0);
		if (value != null) {
			for (int i = 0; i < value.length() && text.length() < maxLength; i++) {
				char c = value.charAt(i);
				if (allowed(c)) text.append(c);
			}
		}
		cursor = text.length();
	}

	public boolean isEmpty() {
		return text.length() == 0;
	}

	public int cursor() {
		return cursor;
	}

	public boolean focused() {
		return focused;
	}

	public void setFocused(boolean focused) {
		this.focused = focused;
		if (focused) cursor = text.length();
	}

	public void clear() {
		text.setLength(0);
		cursor = 0;
	}

	/** Zeichen einfügen; liefert true, wenn es übernommen wurde. */
	public boolean type(char c) {
		if (!allowed(c) || text.length() >= maxLength) return false;
		text.insert(Math.min(cursor, text.length()), c);
		cursor = Math.min(cursor + 1, text.length());
		return true;
	}

	/** Taste verarbeiten; liefert true, wenn sie verbraucht wurde. */
	public boolean key(UiKey key) {
		switch (key) {
			case BACKSPACE:
				if (cursor > 0) {
					text.deleteCharAt(cursor - 1);
					cursor--;
				}
				return true;
			case DELETE:
				if (cursor < text.length()) text.deleteCharAt(cursor);
				return true;
			case LEFT:
				cursor = Math.max(0, cursor - 1);
				return true;
			case RIGHT:
				cursor = Math.min(text.length(), cursor + 1);
				return true;
			case HOME:
				cursor = 0;
				return true;
			case END:
				cursor = text.length();
				return true;
			default:
				return false;
		}
	}

	/** Druckbares Zeichen? (Steuerzeichen und Formatierungszeichen werden abgelehnt.) */
	public static boolean allowed(char c) {
		return c >= ' ' && c != 127 && c != '§';
	}
}

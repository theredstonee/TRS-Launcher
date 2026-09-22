package dev.theredstonee.trsclient.ui;

import dev.theredstonee.trsclient.compat.Mc;
import net.minecraft.client.gui.Font;

/**
 * Einfaches Textfeld im TRS-Stil. Bewusst selbst gezeichnet statt Vanilla-Widget:
 * Aufbau und Eingabe der Vanilla-Felder haben sich zwischen 1.14.4 und 1.19.4 mehrfach geändert,
 * diese Variante braucht nur {@link Gfx} und die Zeichen-/Tasten-Ereignisse des Bildschirms.
 */
public final class TextField {
	/** Tastencodes (GLFW), in allen Versionen gleich. */
	private static final int KEY_BACKSPACE = 259;
	private static final int KEY_DELETE = 261;
	private static final int KEY_RIGHT = 262;
	private static final int KEY_LEFT = 263;
	private static final int KEY_HOME = 268;
	private static final int KEY_END = 269;
	private static final int KEY_V = 86;
	private static final int MOD_CONTROL = 0x0002;

	private final int maxLength;
	private String value = "";
	private int cursor;
	private boolean focused = true;

	public TextField(String value, int maxLength) {
		this.maxLength = Math.max(1, maxLength);
		setValue(value);
	}

	public String value() {
		return value;
	}

	public void setValue(String text) {
		value = text == null ? "" : (text.length() > maxLength ? text.substring(0, maxLength) : text);
		cursor = value.length();
	}

	public boolean focused() {
		return focused;
	}

	public void setFocused(boolean focused) {
		this.focused = focused;
	}

	public void draw(Gfx g, Font font, int x, int y, int w, int h, String placeholder) {
		g.fill(x, y, x + w, y + h, Brand.SURFACE);
		Brand.outline(g, x, y, w, h, focused ? Brand.AMBER : Brand.BORDER);
		int textY = y + (h - 8) / 2;
		if (value.isEmpty() && !focused) {
			g.text(font, Gfx.trim(font, placeholder, w - 8), x + 4, textY, Brand.TEXT_DIM, false);
			return;
		}
		String shown = Gfx.trim(font, value, w - 8);
		g.text(font, shown, x + 4, textY, Brand.TEXT, false);
		if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = x + 4 + font.width(value.substring(0, Math.min(cursor, shown.length())));
			g.fill(cx, textY - 1, cx + 1, textY + 9, Brand.TEXT);
		}
	}

	/** Zeichen einfügen; true = verbraucht. */
	public boolean onChar(char character) {
		if (!focused || character < ' ' || character == 127) return false;
		if (value.length() >= maxLength) return true;
		value = value.substring(0, cursor) + character + value.substring(cursor);
		cursor++;
		return true;
	}

	/** Sondertasten (Backspace, Pfeile, Strg+V); true = verbraucht. */
	public boolean onKey(int key, int modifiers) {
		if (!focused) return false;
		switch (key) {
			case KEY_BACKSPACE:
				if (cursor > 0) {
					value = value.substring(0, cursor - 1) + value.substring(cursor);
					cursor--;
				}
				return true;
			case KEY_DELETE:
				if (cursor < value.length()) value = value.substring(0, cursor) + value.substring(cursor + 1);
				return true;
			case KEY_LEFT:
				if (cursor > 0) cursor--;
				return true;
			case KEY_RIGHT:
				if (cursor < value.length()) cursor++;
				return true;
			case KEY_HOME:
				cursor = 0;
				return true;
			case KEY_END:
				cursor = value.length();
				return true;
			case KEY_V:
				if ((modifiers & MOD_CONTROL) != 0) {
					paste();
					return true;
				}
				return false;
			default:
				return false;
		}
	}

	private void paste() {
		String clip = Mc.clipboard().replace('\n', ' ').replace('\r', ' ');
		int free = maxLength - value.length();
		if (free <= 0 || clip.isEmpty()) return;
		if (clip.length() > free) clip = clip.substring(0, free);
		value = value.substring(0, cursor) + clip + value.substring(cursor);
		cursor += clip.length();
	}
}

package dev.theredstonee.trsclient.ui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * Einfaches Textfeld im TRS-Stil. Bewusst selbst gezeichnet statt Vanilla-{@code GuiTextField}:
 * so braucht es nur {@link Brand} und die Tasten-/Zeichen-Ereignisse von {@code keyTyped}.
 * Tastencodes sind LWJGL-2-Codes.
 */
public final class TextField {
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

	public void draw(FontRenderer font, int x, int y, int w, int h, String placeholder) {
		Brand.rect(x, y, w, h, Brand.SURFACE);
		Brand.outline(x, y, w, h, focused ? Brand.AMBER : Brand.BORDER);
		int textY = y + (h - 8) / 2;
		if (value.isEmpty() && !focused) {
			Brand.text(font, font.trimStringToWidth(placeholder, w - 8), x + 4, textY, Brand.TEXT_DIM, false);
			return;
		}
		String shown = font.trimStringToWidth(value, w - 8);
		Brand.text(font, shown, x + 4, textY, Brand.TEXT, false);
		if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = x + 4 + font.getStringWidth(value.substring(0, Math.min(cursor, shown.length())));
			Brand.fill(cx, textY - 1, cx + 1, textY + 9, Brand.TEXT);
		}
	}

	/** Zeichen einfügen; true = verbraucht. */
	public boolean onChar(char character) {
		if (!focused || character < ' ' || character == 127 || character == '§') return false;
		if (value.length() >= maxLength) return true;
		value = value.substring(0, cursor) + character + value.substring(cursor);
		cursor++;
		return true;
	}

	/** Sondertasten (Rücktaste, Pfeile, Strg+V); true = verbraucht. */
	public boolean onKey(int key) {
		if (!focused) return false;
		// 1.7.10 hat noch kein GuiScreen.isKeyComboCtrlV – daher von Hand geprüft.
		if (key == Keyboard.KEY_V && GuiScreen.isCtrlKeyDown()) {
			paste();
			return true;
		}
		switch (key) {
			case Keyboard.KEY_BACK:
				if (cursor > 0) {
					value = value.substring(0, cursor - 1) + value.substring(cursor);
					cursor--;
				}
				return true;
			case Keyboard.KEY_DELETE:
				if (cursor < value.length()) value = value.substring(0, cursor) + value.substring(cursor + 1);
				return true;
			case Keyboard.KEY_LEFT:
				if (cursor > 0) cursor--;
				return true;
			case Keyboard.KEY_RIGHT:
				if (cursor < value.length()) cursor++;
				return true;
			case Keyboard.KEY_HOME:
				cursor = 0;
				return true;
			case Keyboard.KEY_END:
				cursor = value.length();
				return true;
			default:
				return false;
		}
	}

	private void paste() {
		String clip = GuiScreen.getClipboardString();
		if (clip == null) return;
		clip = clip.replace('\n', ' ').replace('\r', ' ');
		int free = maxLength - value.length();
		if (free <= 0) return;
		if (clip.length() > free) clip = clip.substring(0, free);
		value = value.substring(0, cursor) + clip + value.substring(cursor);
		cursor += clip.length();
	}
}

package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;

/**
 * Freier Text (z. B. Auto-GG-Nachricht, Text-Hotkeys). Wird im Menü als Zeile mit
 * Eingabefeld angezeigt; der Wert landet in {@link ModuleConfig#texts}.
 */
public final class TextSetting extends Setting {
	/** Obergrenze für alle Textfelder (Chat-Nachrichten sind kürzer). */
	public static final int MAX_LENGTH = 200;

	private final String defaultValue;
	private final int maxLength;
	/** Platzhalter, wenn der Text leer ist (nur Anzeige). */
	private final String placeholder;
	private String value;

	public TextSetting(String key, String label, String defaultValue, int maxLength, String placeholder) {
		super(key, label);
		this.defaultValue = defaultValue == null ? "" : defaultValue;
		this.maxLength = Math.max(1, maxLength);
		this.placeholder = placeholder == null ? "" : placeholder;
		this.value = this.defaultValue;
	}

	public TextSetting(String key, String label, String defaultValue) {
		this(key, label, defaultValue, 100, "");
	}

	public String get() {
		return value;
	}

	/** Setzt den Text (auf die Maximallänge gekürzt, null → leer). */
	public void set(String text) {
		if (text == null) {
			value = "";
			return;
		}
		value = text.length() > maxLength ? text.substring(0, maxLength) : text;
	}

	public int maxLength() {
		return maxLength;
	}

	public String placeholder() {
		return placeholder;
	}

	/** Anzeige im Menü: der Text oder der Platzhalter. */
	public String display() {
		return value.isEmpty() ? placeholder : value;
	}

	public boolean isEmpty() {
		return value.isEmpty();
	}

	@Override
	public void read(ModuleConfig config) {
		String v = config.texts.get(key());
		set(v == null ? defaultValue : v);
	}

	@Override
	public void write(ModuleConfig config) {
		config.texts.put(key(), value);
	}

	@Override
	public void reset() {
		value = defaultValue;
	}
}

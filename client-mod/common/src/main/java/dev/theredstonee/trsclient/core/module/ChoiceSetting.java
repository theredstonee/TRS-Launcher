package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;
import dev.theredstonee.trsclient.core.i18n.I18n;

/**
 * Auswahl aus festen Optionen (Klick = nächste Option). Gespeichert wird die Options-ID,
 * damit sich Anzeigenamen ändern dürfen. Die Optionen sind die Konstanten eines Enums,
 * das {@link Option} implementiert.
 */
public final class ChoiceSetting<E extends Enum<E> & ChoiceSetting.Option> extends Setting {
	/** Eine wählbare Option; {@link #label()} ist der englische Rückfall-Name. */
	public interface Option {
		String label();
	}

	private final E[] options;
	/** Übersetzungsschlüssel je Option ("setting.<modul>.<key>.<option>"). */
	private String[] optionKeys;
	private final E defaultValue;
	private E value;

	public ChoiceSetting(String key, String label, Class<E> type, E defaultValue) {
		super(key, label);
		this.options = type.getEnumConstants();
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public E get() {
		return value;
	}

	public void set(E value) {
		this.value = value == null ? defaultValue : value;
	}

	/** Nächste ({@code steps} > 0) bzw. vorherige Option, rundherum. */
	public void cycle(int steps) {
		int n = options.length;
		value = options[Math.floorMod(value.ordinal() + steps, n)];
	}

	public void cycle() {
		cycle(1);
	}

	/** Anzahl der Optionen (für die Auswahlliste im Menü). */
	public int size() {
		return options.length;
	}

	@Override
	protected void onBound() {
		optionKeys = new String[options.length];
		for (int i = 0; i < options.length; i++) {
			optionKeys[i] = labelKey() + "." + options[i].name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** Übersetzungsschlüssel der Option {@code i} (null, solange ungebunden). */
	public String optionKey(int i) {
		return optionKeys == null ? null : optionKeys[i];
	}

	/** Anzeigename der Option {@code i} in der aktiven Sprache. */
	public String optionLabel(int i) {
		return optionKeys == null ? options[i].label() : I18n.trOr(optionKeys[i], options[i].label());
	}

	/** Index der aktuellen Option. */
	public int index() {
		return value.ordinal();
	}

	/** Wählt die Option {@code i} (außerhalb des Bereichs → unverändert). */
	public void setIndex(int i) {
		if (i >= 0 && i < options.length) value = options[i];
	}

	public String display() {
		return optionLabel(value.ordinal());
	}

	@Override
	public void read(ModuleConfig config) {
		value = parse(config.choices.get(key()));
	}

	@Override
	public void write(ModuleConfig config) {
		config.choices.put(key(), value.name().toLowerCase(java.util.Locale.ROOT));
	}

	@Override
	public void reset() {
		value = defaultValue;
	}

	private E parse(String id) {
		if (id == null) return defaultValue;
		for (E e : options) {
			if (e.name().equalsIgnoreCase(id)) return e;
		}
		return defaultValue;
	}
}

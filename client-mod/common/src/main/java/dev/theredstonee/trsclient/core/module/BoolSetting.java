package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;

/** Ein/Aus-Option. */
public final class BoolSetting extends Setting {
	private final boolean defaultValue;
	private boolean value;
	/** Sperre (z. B. vom Server erzwungen): solange sie greift, gilt {@link #lockedValue} statt des eigenen Werts. */
	private java.util.function.BooleanSupplier lock;
	private boolean lockedValue;

	public BoolSetting(String key, String label, boolean defaultValue) {
		super(key, label);
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	/** Wirksamer Wert (bei aktiver Sperre der erzwungene). */
	public boolean get() {
		return lock != null && locked() ? lockedValue : value;
	}

	/**
	 * Wert sperren, solange {@code when} true liefert (das Menü zeigt den Schalter dann ausgegraut). Der eigene Wert
	 * bleibt gespeichert und gilt wieder, sobald die Sperre endet.
	 */
	public BoolSetting lock(java.util.function.BooleanSupplier when, boolean value) {
		this.lock = when;
		this.lockedValue = value;
		return this;
	}

	/** Greift gerade eine Sperre? */
	public boolean locked() {
		if (lock == null) return false;
		try {
			return lock.getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	public void set(boolean value) {
		this.value = value;
	}

	/** Umschalten (bei aktiver Sperre ohne Wirkung). */
	public void toggle() {
		if (locked()) return;
		value = !value;
	}

	@Override
	public void read(ModuleConfig config) {
		Boolean v = config.flags.get(key());
		value = v != null ? v : defaultValue;
	}

	@Override
	public void write(ModuleConfig config) {
		config.flags.put(key(), value);
	}

	@Override
	public void reset() {
		value = defaultValue;
	}
}

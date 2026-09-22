package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;

/** Ein/Aus-Option. */
public final class BoolSetting extends Setting {
	private final boolean defaultValue;
	private boolean value;

	public BoolSetting(String key, String label, boolean defaultValue) {
		super(key, label);
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public boolean get() {
		return value;
	}

	public void set(boolean value) {
		this.value = value;
	}

	public void toggle() {
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

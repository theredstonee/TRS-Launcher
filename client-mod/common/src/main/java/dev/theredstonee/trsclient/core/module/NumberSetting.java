package dev.theredstonee.trsclient.core.module;

import dev.theredstonee.trsclient.core.config.ModuleConfig;

import java.util.Locale;

/** Zahlenwert mit Bereich und Schrittweite (z. B. Skalierung, Zoom-Faktor). */
public final class NumberSetting extends Setting {
	private final double min;
	private final double max;
	private final double step;
	private final double defaultValue;
	/** Präfix für die Anzeige, z. B. "×". */
	private final String prefix;
	private double value;

	public NumberSetting(String key, String label, double defaultValue, double min, double max, double step, String prefix) {
		super(key, label);
		this.min = min;
		this.max = max;
		this.step = step;
		this.defaultValue = defaultValue;
		this.prefix = prefix;
		this.value = defaultValue;
	}

	public double get() {
		return value;
	}

	public float getFloat() {
		return (float) value;
	}

	public void set(double v) {
		value = clamp(v);
	}

	/** Um {@code steps} Schritte erhöhen/verringern (auf Schrittweite gerundet). */
	public void nudge(int steps) {
		set(Math.round((value + steps * step) / step) * step);
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	/** Anzeige, z. B. "×4.0" oder "1.25". */
	public String display() {
		return prefix + String.format(Locale.ROOT, step < 0.1 ? "%.2f" : "%.1f", value);
	}

	@Override
	public void read(ModuleConfig config) {
		Double v = config.numbers.get(key());
		value = v == null || v.isNaN() ? defaultValue : clamp(v);
	}

	@Override
	public void write(ModuleConfig config) {
		config.numbers.put(key(), value);
	}

	@Override
	public void reset() {
		value = defaultValue;
	}

	private double clamp(double v) {
		return Math.max(min, Math.min(max, v));
	}
}

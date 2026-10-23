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
	/** Einheit hinter dem Wert, z. B. "%". */
	private final String suffix;
	private double value;
	/** Vom Server erzwungener Wert bzw. Grenzen (NaN = keine). */
	private double serverValue = Double.NaN;
	private double serverMin = Double.NaN;
	private double serverMax = Double.NaN;

	public NumberSetting(String key, String label, double defaultValue, double min, double max, double step, String prefix) {
		this(key, label, defaultValue, min, max, step, prefix, "");
	}

	public NumberSetting(String key, String label, double defaultValue, double min, double max, double step,
			String prefix, String suffix) {
		super(key, label);
		this.suffix = suffix == null ? "" : suffix;
		this.min = min;
		this.max = max;
		this.step = step;
		this.defaultValue = defaultValue;
		this.prefix = prefix;
		this.value = defaultValue;
	}

	/** Wirksamer Wert (bei einer Server-Regel der erzwungene bzw. geklemmte). */
	public double get() {
		double forced = serverValue;
		if (forced == forced) return forced;
		double v = value;
		double lo = serverMin;
		double hi = serverMax;
		if (lo == lo && v < lo) v = lo;
		if (hi == hi && v > hi) v = hi;
		return v;
	}

	/** Der eigene (gespeicherte) Wert – ohne Server-Regel. */
	public double own() {
		return value;
	}

	public float getFloat() {
		return (float) get();
	}

	public int getInt() {
		return (int) Math.round(get());
	}

	// --- Server-Regeln ---

	/** Wert vom Server erzwingen (auf den eigenen Bereich geklemmt). */
	public void serverForce(double v) {
		if (Double.isNaN(v) || Double.isInfinite(v)) return;
		serverValue = clamp(v);
	}

	/**
	 * Grenzen vom Server (NaN = offen). Sie werden auf den eigenen Bereich gelegt; liegen sie ganz außerhalb, gilt die
	 * nächste Kante.
	 */
	public void serverRange(double lo, double hi) {
		serverMin = Double.isNaN(lo) || Double.isInfinite(lo) ? Double.NaN : clamp(lo);
		serverMax = Double.isNaN(hi) || Double.isInfinite(hi) ? Double.NaN : clamp(hi);
		if (serverMin == serverMin && serverMax == serverMax && serverMin > serverMax) serverMax = serverMin;
	}

	/** Untere Server-Grenze (NaN = keine). */
	public double serverMin() {
		return serverMin;
	}

	/** Obere Server-Grenze (NaN = keine). */
	public double serverMax() {
		return serverMax;
	}

	@Override
	public boolean serverLocked() {
		return serverValue == serverValue || serverMin == serverMin || serverMax == serverMax;
	}

	@Override
	public boolean serverFixed() {
		return serverValue == serverValue;
	}

	@Override
	public void clearServerLock() {
		serverValue = Double.NaN;
		serverMin = Double.NaN;
		serverMax = Double.NaN;
	}

	public void set(double v) {
		value = clamp(v);
	}

	/** Um {@code steps} Schritte erhöhen/verringern (auf Schrittweite gerundet). */
	public void nudge(int steps) {
		set(Math.round((get() + steps * step) / step) * step);
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	public double step() {
		return step;
	}

	/** Lage des Werts im Bereich, 0..1 (für Schieberegler). */
	public double fraction() {
		return max > min ? (get() - min) / (max - min) : 0;
	}

	/** Setzt den Wert aus einer Reglerposition 0..1, auf die Schrittweite gerundet. */
	public void setFraction(double f) {
		double f2 = Math.max(0, Math.min(1, f));
		double v = min + f2 * (max - min);
		if (step > 0) v = min + Math.round((v - min) / step) * step;
		set(v);
	}

	public double defaultValue() {
		return defaultValue;
	}

	/** Anzeige, z. B. "×4.0", "1.25" oder bei ganzzahliger Schrittweite "3". */
	public String display() {
		return format(get());
	}

	/** Ein Wert in der Anzeige dieser Einstellung (Präfix, Nachkommastellen, Einheit). */
	public String format(double v) {
		String fmt = step >= 1 ? "%.0f" : step < 0.1 ? "%.2f" : "%.1f";
		return prefix + String.format(Locale.ROOT, fmt, v) + suffix;
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

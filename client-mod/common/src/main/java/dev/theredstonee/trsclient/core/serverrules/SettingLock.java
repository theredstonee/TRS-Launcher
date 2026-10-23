package dev.theredstonee.trsclient.core.serverrules;

/**
 * Regel des Servers für EINE Einstellung (Teil von {@link ModuleLock}): {@code {"value":x}} erzwingt einen Wert
 * (Schalter: Boolean, Zahl: Double, Auswahl: Enum-Name als String), {@code {"min":a,"max":b}} klemmt eine Zahl.
 * Ein fester Wert hat Vorrang vor Grenzen.
 */
public final class SettingLock {
	/** Erzwungener Wert: {@link Boolean}, {@link Double} oder {@link String}; null = keiner. */
	public final Object value;
	/** Untere/obere Grenze (NaN = offen). */
	public final double min;
	public final double max;

	SettingLock(Object value, double min, double max) {
		this.value = value;
		this.min = min;
		this.max = max;
	}

	/** Hat die Regel überhaupt eine Wirkung? */
	boolean empty() {
		return value == null && Double.isNaN(min) && Double.isNaN(max);
	}

	@Override
	public String toString() {
		return value != null ? "value=" + value : "min=" + min + ",max=" + max;
	}
}

package dev.theredstonee.trsclient.core.zoom;

/**
 * Zoom-Zustand mit weichem Übergang. Wird einmal pro Frame aktualisiert;
 * {@link #factor()} liefert den aktuellen Divisor für das Sichtfeld (1 = kein Zoom).
 */
public final class ZoomState {
	public static final double MIN_LEVEL = 1.5;
	public static final double MAX_LEVEL = 50.0;
	/** Faktor je Mausrad-Raste. */
	public static final double SCROLL_STEP = 1.2;
	/** Geschwindigkeit des Übergangs (größer = schneller). */
	private static final double SMOOTH_SPEED = 14.0;

	private boolean active;
	/** Zielstufe während der aktuellen Zoom-Betätigung (per Mausrad änderbar). */
	private double level = 1.0;
	private double current = 1.0;
	private long lastNanos = -1L;

	/**
	 * Aktualisiert den Zustand.
	 * @param active     Zoom-Taste gedrückt (und Modul aktiv)
	 * @param baseFactor eingestellter Zoom-Faktor
	 * @param smooth     weicher Übergang an/aus
	 * @param nowNanos   aktuelle Zeit ({@link System#nanoTime()})
	 */
	public void update(boolean active, double baseFactor, boolean smooth, long nowNanos) {
		if (active && !this.active) {
			level = clampLevel(baseFactor);
		}
		this.active = active;
		double target = active ? level : 1.0;

		double dt = lastNanos < 0 ? 0 : (nowNanos - lastNanos) / 1_000_000_000.0;
		lastNanos = nowNanos;
		if (!smooth) {
			current = target;
			return;
		}
		dt = Math.min(Math.max(dt, 0), 0.1);
		current += (target - current) * (1.0 - Math.exp(-dt * SMOOTH_SPEED));
		if (Math.abs(target - current) < 0.001) current = target;
	}

	/** Mausrad während des Zooms: positive Werte zoomen hinein. */
	public void scroll(double amount) {
		if (!active || amount == 0) return;
		level = clampLevel(amount > 0 ? level * SCROLL_STEP : level / SCROLL_STEP);
	}

	public boolean isActive() {
		return active;
	}

	/** Aktueller FOV-Divisor (≥ 1). */
	public double factor() {
		return current;
	}

	/** Zielstufe (für Anzeige/Tests). */
	public double level() {
		return level;
	}

	private static double clampLevel(double v) {
		return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, v));
	}
}

package dev.theredstonee.trsclient.core.render;

/**
 * Helligkeit/Gamma des Moduls „Gamma“ (früher „Fullbright“). Rechnet den eingestellten Prozentwert
 * (100–1000 %) in den Gamma-Wert um, den die Lightmap benutzt: 100 % → 1.0 (Vanilla-Maximum),
 * 1000 % → 16.0 (Fullbright). Dazwischen linear.
 */
public final class Brightness {
	/** Kleinster/größter Prozentwert des Reglers. */
	public static final double MIN_PERCENT = 100.0;
	public static final double MAX_PERCENT = 1000.0;
	/** Prozentwert, der voller Helligkeit (Fullbright) entspricht. */
	public static final double FULL_PERCENT = 1000.0;

	private Brightness() {
	}

	/** Gamma-Wert für die Lightmap aus dem Prozentwert (geklemmt auf 100–1000 %). */
	public static double gamma(double percent) {
		double p = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
		return 1.0 + (p - 100.0) / 900.0 * 15.0;
	}
}

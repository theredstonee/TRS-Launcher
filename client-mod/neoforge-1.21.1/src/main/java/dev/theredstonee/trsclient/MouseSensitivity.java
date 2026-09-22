package dev.theredstonee.trsclient;

/**
 * Umrechnung der Maus-Empfindlichkeit für "Maus verlangsamen" beim Zoomen.
 * Vanilla ({@code MouseHandler#turnPlayer}) dreht um {@code Δ · (0.6·s + 0.2)³ · 8};
 * NeoForge lässt über {@code CalculatePlayerTurnEvent} nur {@code s} ändern.
 * Damit die Drehung genau durch {@code divisor} geteilt wird, muss gelten
 * {@code (0.6·s' + 0.2) = (0.6·s + 0.2) / ∛divisor}.
 */
public final class MouseSensitivity {
	private MouseSensitivity() {
	}

	/** Empfindlichkeit, bei der die Drehung {@code divisor}-mal langsamer ist als mit {@code sensitivity}. */
	public static double divided(double sensitivity, double divisor) {
		if (divisor <= 1.0) return sensitivity;
		double base = sensitivity * 0.6 + 0.2;
		return (base / Math.cbrt(divisor) - 0.2) / 0.6;
	}
}

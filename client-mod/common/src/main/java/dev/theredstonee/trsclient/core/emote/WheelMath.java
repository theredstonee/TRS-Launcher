package dev.theredstonee.trsclient.core.emote;

/**
 * Geometrie des Emote-Rads (Bildschirmkoordinaten, y nach unten): Plätze gleichmäßig im Kreis, Platz 0 oben,
 * dann im Uhrzeigersinn. Gewählt wird der Platz, in dessen Richtung die Maus vom Mittelpunkt aus zeigt – die
 * Entfernung spielt keine Rolle, nur innerhalb der toten Zone in der Mitte ist nichts gewählt.
 */
public final class WheelMath {
	private WheelMath() {
	}

	/** Winkel von Platz {@code i} (Bogenmaß, 0 = oben, im Uhrzeigersinn). */
	public static double angle(int i, int n) {
		return n <= 0 ? 0 : 2 * Math.PI * i / n;
	}

	/** Mittelpunkt x von Platz {@code i} bei Radius {@code r}. */
	public static int slotX(int cx, double r, int i, int n) {
		return (int) Math.round(cx + r * Math.sin(angle(i, n)));
	}

	/** Mittelpunkt y von Platz {@code i} bei Radius {@code r}. */
	public static int slotY(int cy, double r, int i, int n) {
		return (int) Math.round(cy - r * Math.cos(angle(i, n)));
	}

	/**
	 * Platz in Richtung ({@code dx}, {@code dy}) vom Mittelpunkt, oder −1 innerhalb von {@code deadZone} bzw. ohne
	 * Plätze.
	 */
	public static int select(double dx, double dy, int n, double deadZone) {
		if (n <= 0 || dx * dx + dy * dy < deadZone * deadZone) return -1;
		double a = Math.atan2(dx, -dy);
		if (a < 0) a += 2 * Math.PI;
		double step = 2 * Math.PI / n;
		return (int) Math.floor((a + step / 2) / step) % n;
	}

	/**
	 * Radius des Rads für einen Bildschirm {@code width}×{@code height} (GUI-Pixel): 30 % der kürzeren Seite,
	 * aber mindestens so groß, dass {@code n} Plätze der Größe {@code slot} nicht überlappen, und höchstens so, dass
	 * das Rad samt Plätzen auf den Bildschirm passt.
	 */
	public static int radius(int width, int height, int n, int slot) {
		int shortSide = Math.min(width, height);
		double min = n <= 1 ? slot : (slot + 6) / (2 * Math.sin(Math.PI / n));
		double r = Math.max(shortSide * 0.30, min);
		double max = shortSide / 2.0 - slot / 2.0 - 14;
		return (int) Math.round(Math.max(24, Math.min(r, max)));
	}
}

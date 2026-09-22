package dev.theredstonee.trsclient.core.input;

/**
 * Teilt ganzzahlige Maus-Deltas durch einen Divisor, ohne kleine Bewegungen zu verlieren:
 * der Nachkommarest wird ins nächste Frame übernommen. (Nur 1.8.9 – dort sind die Deltas int.)
 */
public final class MouseScaler {
	private double restX;
	private double restY;
	private int outX;
	private int outY;

	/** Skaliert ein Delta-Paar; Ergebnis über {@link #x()}/{@link #y()}. Divisor ≤ 1 = unverändert. */
	public void scale(int dx, int dy, double divisor) {
		if (!(divisor > 1.0)) {
			restX = 0;
			restY = 0;
			outX = dx;
			outY = dy;
			return;
		}
		restX += dx / divisor;
		restY += dy / divisor;
		outX = (int) restX;
		outY = (int) restY;
		restX -= outX;
		restY -= outY;
	}

	public int x() {
		return outX;
	}

	public int y() {
		return outY;
	}
}

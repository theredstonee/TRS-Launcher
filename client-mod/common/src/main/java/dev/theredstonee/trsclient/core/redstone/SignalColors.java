package dev.theredstonee.trsclient.core.redstone;

/**
 * Farben der Signalstärke: 0 grau, 1 dunkelrot bis 15 hellrot – wie leuchtender Redstone-Staub,
 * aber mit sichtbarem Unterschied zwischen jeder Stufe. Feste Tabelle, keine Rechnung je Frame.
 */
public final class SignalColors {
	/** Stufe 0 (aus). */
	public static final int OFF = 0xFF8C8C8C;
	private static final int LOW = 0xFF6E1616;
	private static final int HIGH = 0xFFFF4A40;
	private static final int[] TABLE = new int[16];
	private static final String[] DIGITS = new String[16];

	static {
		TABLE[0] = OFF;
		for (int level = 1; level <= 15; level++) {
			float t = (level - 1) / 14f;
			// leicht beschleunigt, damit die hohen Stufen heller auseinanderliegen
			TABLE[level] = lerp(LOW, HIGH, (float) Math.pow(t, 0.85));
		}
		for (int i = 0; i < 16; i++) DIGITS[i] = Integer.toString(i);
	}

	private SignalColors() {
	}

	/** ARGB-Farbe (deckend) für Stärke {@code level}; Werte außerhalb 0–15 werden begrenzt. */
	public static int color(int level) {
		return TABLE[Math.max(0, Math.min(15, level))];
	}

	/** "0" … "15" ohne neue Strings je Frame. */
	public static String digits(int level) {
		return DIGITS[Math.max(0, Math.min(15, level))];
	}

	static int lerp(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int r = Math.round(ar + (br - ar) * t);
		int g = Math.round(ag + (bg - ag) * t);
		int bl = Math.round(ab + (bb - ab) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}
}

package dev.theredstonee.trsclient.core.minigames;

/**
 * Zufallsfolge der Minispiele (Mulberry32), Bit für Bit wie {@code mulberry} in {@code app/utils/minigames/stapler.ts}.
 * JavaScript rechnet das Ergebnis vorzeichenlos ({@code >>> 0}), hier steckt derselbe Wert in einem {@code int}. Darum
 * teilt {@link #below} vorzeichenlos und nie mit {@code %}.
 */
public final class Mulberry {
	private int a;

	public Mulberry(int seed) {
		this.a = seed;
	}

	/** Nächster Wert als Bitmuster eines vorzeichenlosen 32-Bit-Werts. */
	public int next() {
		a += 0x6D2B79F5;
		int t = (a ^ (a >>> 15)) * (1 | a);
		t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
		return t ^ (t >>> 14);
	}

	/** {@code rng() % n} aus JavaScript. */
	public int below(int n) {
		return Integer.remainderUnsigned(next(), n);
	}
}

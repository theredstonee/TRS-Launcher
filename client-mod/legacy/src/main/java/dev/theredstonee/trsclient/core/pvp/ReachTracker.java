package dev.theredstonee.trsclient.core.pvp;

import java.util.Locale;

/**
 * Entfernung des letzten Treffers (Auge des Spielers → getroffener Punkt).
 * Reine Anzeige: die Reichweite selbst wird nicht verändert.
 */
public final class ReachTracker {
	private double distance = -1;
	private long time;

	/** Trefferentfernung in Blöcken merken. */
	public void record(double blocks, long nowMs) {
		if (blocks < 0 || Double.isNaN(blocks)) return;
		this.distance = blocks;
		this.time = nowMs;
	}

	/** Letzte Entfernung (< 0 = noch keine). */
	public double distance() {
		return distance;
	}

	public long time() {
		return time;
	}

	/** Noch anzuzeigen? {@code holdMs} ≤ 0 = dauerhaft. */
	public boolean valid(long nowMs, long holdMs) {
		if (distance < 0) return false;
		return holdMs <= 0 || nowMs - time <= holdMs;
	}

	public void reset() {
		distance = -1;
		time = 0;
	}

	/** "3.04 m" mit {@code decimals} Nachkommastellen. */
	public static String format(double blocks, int decimals) {
		int d = Math.max(0, Math.min(3, decimals));
		return String.format(Locale.ROOT, "%." + d + "f m", blocks);
	}
}

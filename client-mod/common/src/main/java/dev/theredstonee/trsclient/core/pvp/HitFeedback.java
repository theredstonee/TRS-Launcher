package dev.theredstonee.trsclient.core.pvp;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;
import dev.theredstonee.trsclient.core.ui.Canvas;

/**
 * Treffer-Feedback, nur Anzeige: Hitmarker am Fadenkreuz, wenn ein eigener Schlag trifft (das Ziel zeigt die
 * Schadens-Animation), optionaler eigener Ton und mehr Kritisch-/Schärfe-Partikel. Nichts davon ändert Angriffe oder
 * schickt Pakete.
 */
public final class HitFeedback {
	/** Ton beim Treffer (Vanilla-Klänge; Namen je Version in {@code core.qol.QolSound}). */
	public enum Sound implements ChoiceSetting.Option {
		OFF("Off"),
		ORB("Experience orb"),
		ARROW("Arrow hit"),
		HAT("Note block: hat"),
		PLING("Note block: pling"),
		CRIT("Critical hit");

		private final String label;

		Sound(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	private long hitAt;
	/** Kritischer Treffer (rote Farbe am Hitmarker). */
	private boolean crit;
	private int pendingSound;

	/** Ein eigener Treffer wurde bestätigt. */
	public void onHit(long now, boolean critical) {
		hitAt = now;
		crit = critical;
		pendingSound++;
	}

	/** Soll jetzt ein Treffer-Ton gespielt werden? (einmal je Treffer, höchstens einer je Tick) */
	public boolean takeSound() {
		if (pendingSound <= 0) return false;
		pendingSound = 0;
		return true;
	}

	/** Deckkraft des Hitmarkers 0–1 (voll, dann weich aus). */
	public float alpha(long now, long durationMs) {
		if (hitAt == 0) return 0f;
		long age = now - hitAt;
		long d = Math.max(50, durationMs);
		if (age < 0 || age >= d) return 0f;
		float t = age / (float) d;
		return t < 0.5f ? 1f : 1f - (t - 0.5f) * 2f;
	}

	public boolean critical() {
		return crit;
	}

	public void reset() {
		hitAt = 0;
		pendingSound = 0;
	}

	/** Wie viele Partikel-Emitter insgesamt je Vanilla-Emitter (1 = unverändert, höchstens 5). */
	public static int particleCopies(double multiplier) {
		int m = (int) Math.round(multiplier);
		return Math.max(1, Math.min(5, m));
	}

	/**
	 * Zeichnet den Hitmarker (vier kurze Diagonalen) um den Mittelpunkt (cx, cy). Größe = Länge je Strich in Pixeln.
	 */
	public static void draw(Canvas c, int cx, int cy, int size, int argb, float alpha) {
		if (alpha <= 0.02f) return;
		int a = (int) (((argb >>> 24) & 0xFF) * alpha);
		int color = (a << 24) | (argb & 0xFFFFFF);
		int shadow = ((int) (a * 0.6f) << 24);
		int gap = 3;
		int len = Math.max(2, Math.min(12, size));
		for (int i = gap; i < gap + len; i++) {
			// Schatten eine Stufe versetzt, dann der Strich
			dot(c, cx + i + 1, cy + i + 1, shadow);
			dot(c, cx - i + 1, cy + i + 1, shadow);
			dot(c, cx + i + 1, cy - i + 1, shadow);
			dot(c, cx - i + 1, cy - i + 1, shadow);
		}
		for (int i = gap; i < gap + len; i++) {
			dot(c, cx + i, cy + i, color);
			dot(c, cx - i, cy + i, color);
			dot(c, cx + i, cy - i, color);
			dot(c, cx - i, cy - i, color);
		}
	}

	private static void dot(Canvas c, int x, int y, int argb) {
		c.fill(x, y, x + 1, y + 1, argb);
	}
}

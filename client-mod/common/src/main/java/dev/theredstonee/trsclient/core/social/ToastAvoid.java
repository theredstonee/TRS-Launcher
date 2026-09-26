package dev.theredstonee.trsclient.core.social;

/**
 * Ausweichen vor den Vanilla-Toasts (Rezepte, Tutorial-Hinweise, Fortschritte, System-Meldungen), die Minecraft
 * ebenfalls oben rechts zeichnet: aus der belegten Höhe wird ein Versatz nach unten, dem der TRS-Stapel weich folgt.
 *
 * <p>Alles ohne Speicheranforderung je Bild: nur primitive Felder und Rechnungen.
 */
public final class ToastAvoid {
	/** Höhe eines Vanilla-Toast-Platzes (alle Versionen: 32 GUI-Pixel). */
	public static final int SLOT_HEIGHT = 32;
	/** Zeitkonstante der Annäherung: nach ~3·TAU (≈ 0,27 s) ist der Stapel praktisch angekommen. */
	static final float TAU_MS = 90f;
	/** Längere Pause ohne Zeichnen (kein TRS-Toast sichtbar): beim nächsten Mal direkt an die richtige Stelle. */
	static final long SNAP_AFTER_MS = 250;

	private float offset;
	private long lastMs = Long.MIN_VALUE;

	/**
	 * Nächster Versatz (GUI-Pixel nach unten) für dieses Bild. {@code target} = gewünschter Versatz; das Ergebnis
	 * nähert sich ihm exponentiell (unabhängig von der Bildrate) und rastet unter einem halben Pixel ein.
	 */
	public float step(float target, long nowMs) {
		long last = lastMs;
		lastMs = nowMs;
		if (last == Long.MIN_VALUE || nowMs - last > SNAP_AFTER_MS || nowMs < last) {
			offset = target;
			return offset;
		}
		long dt = nowMs - last;
		if (dt > 0) {
			float k = 1f - (float) Math.exp(-dt / TAU_MS);
			offset += (target - offset) * k;
		}
		if (Math.abs(target - offset) < 0.5f) offset = target;
		return offset;
	}

	/** Aktueller Versatz ohne Fortschreiten (Tests/Selbsttest). */
	public float offset() {
		return offset;
	}

	/** Zurücksetzen (z. B. andere Ecke): nächstes {@link #step} springt direkt. */
	public void reset() {
		lastMs = Long.MIN_VALUE;
		offset = 0;
	}

	/**
	 * Versatz für den TRS-Stapel, dessen oberste Karte bei {@code margin} beginnt: unter die Vanilla-Toasts
	 * ({@code vanillaBottom} = deren Unterkante, 0 = keine) mit {@code gap} Abstand – sonst 0.
	 */
	public static int targetOffset(int vanillaBottom, int margin, int gap) {
		if (vanillaBottom <= 0) return 0;
		return Math.max(0, vanillaBottom + gap - margin);
	}

	/** Unterkante von {@code slots} belegten Vanilla-Plätzen ab oben (Plätze 0..slots-1). */
	public static int slotsBottom(int slots) {
		return slots <= 0 ? 0 : slots * SLOT_HEIGHT;
	}

	/**
	 * Unterkante des Erfolgs-Fensters bis 1.11.2 ({@code GuiAchievement#updateAchievementWindow}) – es fährt
	 * senkrecht von oben herein und wieder hinaus: 3 s Laufzeit, dauerhaft (Tutorial „Inventar öffnen“) bleibt es
	 * nach dem Hereinfahren stehen. {@code elapsedMs} = Zeit seit Anzeige in Minecrafts Systemzeit.
	 */
	public static int achievementBottom(long elapsedMs, boolean permanent) {
		double t = elapsedMs / 3000.0;
		if (!permanent) {
			if (t < 0 || t > 1) return 0;
		} else if (t > 0.5) {
			t = 0.5;
		}
		double s = t * 2;
		if (s > 1) s = 2 - s;
		s *= 4;
		s = 1 - s;
		if (s < 0) s = 0;
		s *= s;
		s *= s;
		int y = -(int) (s * 36);
		return Math.max(0, y + SLOT_HEIGHT);
	}
}

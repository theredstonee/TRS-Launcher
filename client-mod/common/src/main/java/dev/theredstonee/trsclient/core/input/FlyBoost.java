package dev.theredstonee.trsclient.core.input;

/**
 * Flug-Boost im Kreativmodus: multipliziert die Fluggeschwindigkeit der Spielfigur, solange
 * gewünscht, und stellt danach den vorherigen Wert wieder her. Setzt der Server zwischendurch
 * eine neue Geschwindigkeit (Fähigkeiten-Paket), gilt die als neuer Grundwert.
 */
public final class FlyBoost {
	private float base = Float.NaN;
	private float boosted = Float.NaN;

	/**
	 * @param current aktuelle Fluggeschwindigkeit
	 * @param want    Boost gewünscht?
	 * @param factor  Faktor (größer 1, sonst kein Boost)
	 * @return die Fluggeschwindigkeit, die jetzt gelten soll
	 */
	public float apply(float current, boolean want, double factor) {
		if (want && factor > 1.0) {
			if (Float.isNaN(boosted) || current != boosted) {
				// Erster Boost-Tick oder der Server hat die Geschwindigkeit inzwischen geändert.
				base = current;
			}
			boosted = (float) (base * factor);
			return boosted;
		}
		if (!Float.isNaN(boosted)) {
			float result = current == boosted ? base : current;
			reset();
			return result;
		}
		return current;
	}

	public boolean active() {
		return !Float.isNaN(boosted);
	}

	/** Spielfigur weg (Welt verlassen, Respawn): nichts mehr zurückzusetzen. */
	public void reset() {
		base = Float.NaN;
		boosted = Float.NaN;
	}
}

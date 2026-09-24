package dev.theredstonee.trsclient.core.util;

/**
 * Setzt eine Vanilla-Option vorübergehend (z. B. die filmische Kamera beim Zoomen) und stellt
 * danach genau den Wert wieder her, den der Spieler vorher hatte.
 */
public final class FlagOverride {
	private boolean applied;
	private boolean saved;

	/**
	 * @param current aktueller Wert der Option
	 * @param want    soll die Option gerade erzwungen eingeschaltet sein?
	 * @return der Wert, den die Option jetzt haben soll
	 */
	public boolean update(boolean current, boolean want) {
		if (want && !applied) {
			saved = current;
			applied = true;
			return true;
		}
		if (!want && applied) {
			applied = false;
			return saved;
		}
		return applied || current;
	}

	public boolean applied() {
		return applied;
	}
}

package dev.theredstonee.trsclient.core.input;

/**
 * Umschalt-Logik für Toggle-Sprint/-Schleichen: jeder Tastendruck schaltet um.
 * Ist das Modul aus oder vanillas eigene Umschalt-Option aktiv, bleibt der Zustand aus.
 */
public final class ToggleState {
	private boolean active;

	/**
	 * Pro Tick aufrufen.
	 *
	 * @param presses     Anzahl neuer Tastendrücke seit dem letzten Tick
	 * @param enabled     Modul eingeschaltet?
	 * @param blocked     z. B. Vanilla-Umschaltung aktiv oder ein Menü offen – Drücke zählen nicht
	 * @return ob die Taste jetzt als gehalten gelten soll
	 */
	public boolean update(int presses, boolean enabled, boolean blocked) {
		if (!enabled) {
			active = false;
			return false;
		}
		if (!blocked && (presses & 1) == 1) active = !active;
		return active;
	}

	public boolean active() {
		return active;
	}

	/** Direkt setzen (Selbsttest). */
	public void set(boolean active) {
		this.active = active;
	}

	/** Zurücksetzen (z. B. beim Verlassen der Welt). */
	public void reset() {
		active = false;
	}
}

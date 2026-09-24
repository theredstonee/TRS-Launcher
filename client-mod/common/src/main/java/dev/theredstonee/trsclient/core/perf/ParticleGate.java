package dev.theredstonee.trsclient.core.perf;

/**
 * Entscheidet, ob ein neuer Partikel entstehen darf: bestimmte Arten aus (Explosion, Regen-Spritzer,
 * Rauch), nur ein Anteil aller Partikel („Menge“) und eine Obergrenze gleichzeitig sichtbarer Partikel.
 * Die Menge wird gleichmäßig ausgedünnt (Fehler-Akkumulator statt Zufall): bei 50 % jeder zweite.
 */
public final class ParticleGate {
	/** Art eines Partikels (der Loader ordnet die Minecraft-Typen zu). */
	public enum Kind {
		OTHER, EXPLOSION, RAIN, SMOKE
	}

	private double credit;

	/**
	 * @param kind       Art des neuen Partikels
	 * @param count      aktuell vorhandene Partikel (≤ 0 = unbekannt)
	 * @param limit      Obergrenze (≤ 0 = keine)
	 * @param amount     Anteil 0..1, der entstehen darf (1 = alle)
	 * @param hideKind   diese Art ist ausgeschaltet
	 */
	public boolean allow(Kind kind, int count, int limit, double amount, boolean hideKind) {
		if (hideKind) return false;
		if (limit > 0 && count >= limit) return false;
		if (amount >= 0.999) return true;
		if (amount <= 0) return false;
		credit += amount;
		if (credit >= 1.0) {
			credit -= 1.0;
			return true;
		}
		return false;
	}

	public void reset() {
		credit = 0;
	}
}

package dev.theredstonee.trsclient.core.pvp;

/**
 * Zählt aufeinanderfolgende Treffer ("Combo"). Ein Schlag zählt erst, wenn das Ziel
 * kurz danach wirklich Schaden nimmt; eigener Schaden oder eine Pause beenden die Combo.
 * Rein zählend – es wird nichts automatisiert.
 */
public final class ComboTracker {
	/** Ein Schlag zählt nur, wenn das Ziel innerhalb dieser Zeit Schaden nimmt. */
	public static final long CONFIRM_MS = 500;

	private int combo;
	private int best;
	private long lastHit;
	private int pendingTarget = Integer.MIN_VALUE;
	private long pendingTime;
	private int comboTarget = Integer.MIN_VALUE;

	/** Spieler hat zugeschlagen (Ziel-ID des Angriffs). */
	public void onAttack(int entityId, long nowMs) {
		pendingTarget = entityId;
		pendingTime = nowMs;
	}

	/**
	 * Das Ziel hat Schaden genommen. Zählt nur, wenn es das zuletzt angegriffene Ziel ist
	 * und der Schlag noch nicht zu lange her ist.
	 *
	 * @return true, wenn die Combo hochgezählt wurde
	 */
	public boolean onTargetHurt(int entityId, long nowMs) {
		if (entityId != pendingTarget || nowMs - pendingTime > CONFIRM_MS) return false;
		pendingTarget = Integer.MIN_VALUE;
		// Ein Wechsel des Ziels beginnt eine neue Combo.
		if (entityId != comboTarget) combo = 0;
		comboTarget = entityId;
		combo++;
		lastHit = nowMs;
		if (combo > best) best = combo;
		return true;
	}

	/** Der Spieler selbst wurde getroffen → Combo vorbei. */
	public void onSelfHurt() {
		combo = 0;
		pendingTarget = Integer.MIN_VALUE;
	}

	/** Beendet die Combo nach {@code timeoutMs} ohne Treffer. */
	public void tick(long nowMs, long timeoutMs) {
		if (combo > 0 && timeoutMs > 0 && nowMs - lastHit > timeoutMs) combo = 0;
	}

	public int combo() {
		return combo;
	}

	/** Höchste Combo seit dem letzten {@link #reset()}. */
	public int best() {
		return best;
	}

	public long lastHit() {
		return lastHit;
	}

	public void reset() {
		combo = 0;
		best = 0;
		lastHit = 0;
		pendingTarget = Integer.MIN_VALUE;
		comboTarget = Integer.MIN_VALUE;
	}
}

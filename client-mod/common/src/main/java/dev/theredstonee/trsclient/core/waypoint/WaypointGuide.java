package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.i18n.I18n;

/**
 * Ablauf rund um Tod und Ziel, für alle Versionen gleich: Beim Tod wird ein Todespunkt angelegt (begrenzte Anzahl),
 * nach dem Wiederbeleben führt der Weg zurück – der Todespunkt wird zum Ziel. Beim Erreichen endet die Führung.
 * Die Bäume melden nur je Tick Position und Lebenszustand.
 */
public final class WaypointGuide {
	/** Näher als das (Blöcke, waagerecht + senkrecht) gilt ein Ziel als erreicht. */
	static final double ARRIVED = 4.0;

	private boolean wasDead;

	/** Was im Tick passiert ist – die Bäume zeigen dazu die Meldung. */
	public static final class Event {
		/** Text für die Aktionsleiste oder null. */
		public final String message;
		/** Etwas wurde geändert und muss gespeichert werden. */
		public final boolean changed;

		Event(String message, boolean changed) {
			this.message = message;
			this.changed = changed;
		}
	}

	private static final Event NOTHING = new Event(null, false);

	/** Beim Verlassen der Welt. */
	public void reset() {
		wasDead = false;
	}

	/**
	 * @param dead       Spieler ist tot
	 * @param record     Todespunkte setzen (Einstellung und Modul)
	 * @param keep       so viele behalten
	 * @param wayBack    nach dem Tod zum Ziel machen
	 */
	public Event tick(WaypointStore store, String worldKey, double x, double y, double z, String dimension, boolean dead, boolean record,
			int keep, boolean wayBack) {
		boolean justDied = dead && !wasDead;
		wasDead = dead;
		if (justDied && record) {
			Waypoint death = store.addDeath(worldKey, floor(x), floor(y), floor(z), dimension, 0xE0281E, keep);
			if (wayBack) store.setTarget(worldKey, death, true);
			return new Event(I18n.tr("waypoint.deathSet"), true);
		}
		if (dead) return NOTHING;
		Waypoint target = store.target(worldKey);
		if (target != null && arrived(target, x, y, z, dimension)) {
			store.setTarget(worldKey, null, false);
			return new Event(I18n.tr("waypoint.arrived", target.name), true);
		}
		return NOTHING;
	}

	static boolean arrived(Waypoint target, double x, double y, double z, String dimension) {
		return target.inDimension(dimension) && target.distanceTo(x, y, z) <= ARRIVED;
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}
}

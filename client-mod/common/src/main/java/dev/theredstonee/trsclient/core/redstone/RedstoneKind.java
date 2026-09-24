package dev.theredstonee.trsclient.core.redstone;

/** Art eines Redstone-Bauteils – bestimmt, welche Werte die Signalstärke-Anzeige zeigt. */
public enum RedstoneKind {
	/** Kein Redstone-Bauteil. */
	NONE,
	DUST,
	REPEATER,
	COMPARATOR,
	TORCH,
	LEVER,
	BUTTON,
	/** Druckplatte (Wägeplatten mit Stärke 0–15, sonst an/aus). */
	PLATE,
	PISTON,
	LAMP,
	OBSERVER,
	DAYLIGHT,
	REDSTONE_BLOCK,
	/** Andere Signalquelle (Zielblock, Sculk-Sensor, Blitzableiter, Haken …). */
	SOURCE,
	/** Verbraucher mit "angesteuert"-Zustand (Tür, Falltür, Notenblock, Werfer, Trichter, Schiene …). */
	CONSUMER,
	/** Behälter – Komparator-Ausgabe aus dem Inhalt (falls bekannt). */
	CONTAINER,
	/** Block mit Komparator-Ausgabe aus seinem Zustand (Komposter, Kuchen, Kessel …). */
	ANALOG;

	/** Zeigt die Anzeige die empfangene Stärke (Verbraucher) statt einer Ausgabe? */
	public boolean receives() {
		return this == PISTON || this == LAMP || this == CONSUMER;
	}
}

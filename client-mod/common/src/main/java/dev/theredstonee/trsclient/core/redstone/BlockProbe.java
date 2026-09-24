package dev.theredstonee.trsclient.core.redstone;

/**
 * Was der Client über einen Block weiß – gefüllt vom versionsabhängigen Adapter
 * ({@link RedstoneWorld#probe}). Veränderliches Objekt, wird je Abfrage wiederverwendet.
 */
public final class BlockProbe {
	public RedstoneKind kind = RedstoneKind.NONE;
	/** Stärke-Eigenschaft 0–15 (Staub, Wägeplatte, Tageslichtsensor, Zielblock …) oder -1. */
	public int power = -1;
	/** An/aus-Zustand (POWERED, LIT, TRIGGERED, ausgefahren …) – nur gültig mit {@link #hasPowered}. */
	public boolean powered;
	public boolean hasPowered;
	/** Verstärker: Verzögerung 1–4 (Redstone-Ticks). */
	public int delay;
	/** Verstärker: von der Seite verriegelt. */
	public boolean locked;
	/** Komparator: Subtrahieren statt Vergleichen. */
	public boolean subtract;
	/** Kolben: ausgefahren. */
	public boolean extended;
	/** Verstärker/Komparator: Richtung zum Eingang ({@link Dir}), sonst -1. */
	public int facing = -1;
	/** Der Block ist ein Behälter (Komparator liest den Inhalt). */
	public boolean container;
	/** Komparator-Ausgabe aus dem Blockzustand (Komposter, Kuchen …) oder -1. */
	public int analog = -1;
	/** Stärkstes Signal, das der Block von seinen Nachbarn bekommt, oder -1. */
	public int received = -1;

	public void clear() {
		kind = RedstoneKind.NONE;
		power = -1;
		powered = false;
		hasPowered = false;
		delay = 0;
		locked = false;
		subtract = false;
		extended = false;
		facing = -1;
		container = false;
		analog = -1;
		received = -1;
	}

	public void copyFrom(BlockProbe o) {
		kind = o.kind;
		power = o.power;
		powered = o.powered;
		hasPowered = o.hasPowered;
		delay = o.delay;
		locked = o.locked;
		subtract = o.subtract;
		extended = o.extended;
		facing = o.facing;
		container = o.container;
		analog = o.analog;
		received = o.received;
	}

	/** Setzt den an/aus-Zustand. */
	public BlockProbe powered(boolean on) {
		hasPowered = true;
		powered = on;
		return this;
	}

	/** Gleicher Zustand (für "hat sich etwas geändert?"). */
	public boolean sameAs(BlockProbe o) {
		return kind == o.kind && power == o.power && powered == o.powered && hasPowered == o.hasPowered
				&& delay == o.delay && locked == o.locked && subtract == o.subtract && extended == o.extended
				&& facing == o.facing && container == o.container && analog == o.analog && received == o.received;
	}
}

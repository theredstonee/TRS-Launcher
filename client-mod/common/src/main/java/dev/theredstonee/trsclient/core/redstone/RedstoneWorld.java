package dev.theredstonee.trsclient.core.redstone;

/**
 * Lesezugriff auf die Blöcke, die der Client ohnehin kennt – je Minecraft-Version ein kleiner
 * Adapter ({@code compat/RedstoneProbe}). Alle Methoden sind reine Abfragen des Blockzustands
 * bzw. der Vanilla-Signalberechnung; nichts wird an den Server geschickt.
 * Nicht geladene Chunks verhalten sich wie Luft.
 */
public interface RedstoneWorld {
	/** Komparator-Ausgabe: der Block hat keine. */
	int NO_ANALOG = -1;
	/** Komparator-Ausgabe: Behälter – der Inhalt ist dem Client nicht bekannt. */
	int CONTAINER_ANALOG = -2;

	/** Füllt {@code out} für den Block an (x, y, z); false = kein Redstone-Bauteil / nicht geladen. */
	boolean probe(int x, int y, int z, BlockProbe out);

	/** Anzeigename des Blocks (in der Sprache des Spiels). Nur für die Anzeige, nicht je Frame. */
	String name(int x, int y, int z);

	/** Stärke des Redstone-Staubs an (x, y, z) oder -1, wenn dort kein Staub liegt. */
	int dustPower(int x, int y, int z);

	/** Signal, das der Block an (x, y, z) in Richtung {@code dir} abgibt (Vanilla {@code getSignal}). */
	int signal(int x, int y, int z, int dir);

	/**
	 * Seiteneingang eines Komparators: Signal des Blocks an (x, y, z) in Richtung {@code dir},
	 * wenn er eine Signalquelle ist (Redstone-Block 15, Staub seine Stärke, sonst das direkte Signal).
	 */
	int sideSignal(int x, int y, int z, int dir);

	/** Komparator-Ausgabe des Blocks: ≥ 0, {@link #NO_ANALOG} oder {@link #CONTAINER_ANALOG}. */
	int analog(int x, int y, int z);

	/** Leitet der Block Redstone (voller, fester Block)? */
	boolean conductor(int x, int y, int z);

	/** Verdeckt der Block die Sicht vollständig? (für "nur sichtbare" im Welt-Overlay) */
	boolean opaque(int x, int y, int z);
}

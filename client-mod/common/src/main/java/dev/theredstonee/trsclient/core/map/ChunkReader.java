package dev.theredstonee.trsclient.core.map;

/**
 * Lesezugriff auf einen geladenen Chunk – die einzige versionsabhängige Stelle der Karte (je Loader/Version eine
 * kleine Umsetzung {@code compat/MapSampler}). Alles Weitere (Oberfläche suchen, Wasser, Höhlenschnitt, Farben)
 * rechnet {@link ColumnScanner} in {@code core}.
 *
 * <p>Nur aus dem Spiel-Thread aufrufen. Gelesen werden ausschließlich Chunks, die das Spiel ohnehin geladen hat –
 * keine Server-Anfragen, nichts jenseits der Sichtweite.
 */
public interface ChunkReader {
	/**
	 * Merker in {@link #block}: der Block ist Luft. Unsichtbare technische Blöcke (Barriere, Licht-Block,
	 * Strukturleere) melden die Umsetzungen ebenfalls als Luft – die Karte schaut durch sie hindurch.
	 */
	int AIR = 1 << 24;
	/**
	 * Merker in {@link #block}: voller, lichtundurchlässiger Block (verdeckt, was darunter liegt). Nur solche
	 * Blöcke zählen als Dach über dem Spieler – Laub, Glas, Zäune usw. nicht.
	 */
	int OPAQUE = 1 << 25;

	/** Ist der Chunk geladen? */
	boolean isLoaded(int chunkX, int chunkZ);

	/** Wählt den Chunk für die folgenden Aufrufe; false, wenn er nicht (mehr) da ist. */
	boolean open(int chunkX, int chunkZ);

	/** Unterste Bauhöhe der Welt. */
	int minY();

	/** Oberkante der Spalte: y über dem höchsten Nicht-Luft-Block (lokale Koordinaten 0..15). */
	int top(int localX, int localZ);

	/**
	 * Kartenfarbe (0xRRGGBB, 0 = keine, z. B. Luft/Glas) des Blocks, dazu {@link #AIR} bei Luft (auch Barriere,
	 * Licht-Block, Strukturleere) und {@link #OPAQUE} bei vollen, undurchsichtigen Blöcken.
	 */
	int block(int localX, int y, int localZ);

	/**
	 * Besteht der 16 Blöcke hohe Abschnitt, in dem {@code y} liegt, nur aus Luft? Erlaubt der Abtastung, leere
	 * Abschnitte (Leere-Welten mit Barriere-Boden, hohe Luft über Lobbys) in einem Schritt zu überspringen.
	 * Im Zweifel false.
	 */
	default boolean sectionEmpty(int y) {
		return false;
	}

	/** Tönungsfarbe (Biom-Gras, Laub, Wasser …) des Blocks als 0xRRGGBB oder -1. */
	int tint(int localX, int y, int localZ);
}

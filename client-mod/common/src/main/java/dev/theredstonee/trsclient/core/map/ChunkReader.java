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
	/** Merker in {@link #block}: der Block ist Luft. */
	int AIR = 1 << 24;

	/** Ist der Chunk geladen? */
	boolean isLoaded(int chunkX, int chunkZ);

	/** Wählt den Chunk für die folgenden Aufrufe; false, wenn er nicht (mehr) da ist. */
	boolean open(int chunkX, int chunkZ);

	/** Unterste Bauhöhe der Welt. */
	int minY();

	/** Oberkante der Spalte: y über dem höchsten Nicht-Luft-Block (lokale Koordinaten 0..15). */
	int top(int localX, int localZ);

	/** Kartenfarbe (0xRRGGBB, 0 = keine, z. B. Luft/Glas) des Blocks, dazu {@link #AIR} bei Luft. */
	int block(int localX, int y, int localZ);

	/** Tönungsfarbe (Biom-Gras, Laub, Wasser …) des Blocks als 0xRRGGBB oder -1. */
	int tint(int localX, int y, int localZ);
}

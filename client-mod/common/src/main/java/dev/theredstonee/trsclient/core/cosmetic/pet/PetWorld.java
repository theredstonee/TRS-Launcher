package dev.theredstonee.trsclient.core.cosmetic.pet;

/**
 * Was die Begleiter am Boden ({@link PetSim}) von der Welt wissen müssen – je Minecraft-Version ein kleiner Adapter
 * (Block-Zustand, Kollisionsform, Flüssigkeit, Licht). Nur geladene Chunks; alles im Spiel-/Render-Thread.
 *
 * <p>{@link #block} liefert Merker und die Oberkante der Kollisionsform in Sechzehnteln: {@code flags | (top16 << 8)}
 * mit {@code top16} = Oberkante relativ zur Blockunterseite (0…24, Zaun 24, Stufe 8, Schneeschicht 2·(Schichten−1)).
 */
public interface PetWorld {
	/** Hat eine Kollisionsform (man steht darauf / läuft dagegen). */
	int SOLID = 1;
	/** Enthält Wasser (Quelle, fließend, geflutet). */
	int WATER = 2;
	/** Rutschig: Eis aller Art, Schnee (Block, Schicht, Pulverschnee) – dort rutschen Pinguine öfter. */
	int SLIPPERY = 4;
	/** Gefährlich (Lava, Feuer, Kaktus, Magma …): wie eine Wand, nie betreten. */
	int DANGER = 8;
	/** Chunk nicht geladen – wie fester Boden auf der aktuellen Höhe behandeln (nie ins Leere fallen). */
	int UNLOADED = 16;

	/** Merker | (Oberkante in Sechzehnteln &lt;&lt; 8) des Blocks; 0 = Luft. */
	int block(int x, int y, int z);

	/** Licht am Block, gepackt wie Minecraft: {@code (himmel << 20) | (block << 4)} (je 0…15). */
	int light(int x, int y, int z);

	/** Oberkante der Kollisionsform eines {@link #block}-Werts in Blöcken (0…1,5). */
	static double top(int code) {
		return ((code >>> 8) & 0xFF) / 16.0;
	}

	/** {@link #block}-Wert aus Merkern und Oberkante (Blöcke, wird auf Sechzehntel gerundet). */
	static int code(int flags, double top) {
		int t = (int) Math.round(Math.max(0, Math.min(1.5, top)) * 16);
		return (flags & 0xFF) | (t << 8);
	}

	/** Licht packen ({@code sky}, {@code block} je 0…15). */
	static int packLight(int sky, int block) {
		return (Math.max(0, Math.min(15, sky)) << 20) | (Math.max(0, Math.min(15, block)) << 4);
	}
}

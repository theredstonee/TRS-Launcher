package dev.theredstonee.trsclient.core.creator;

/**
 * Bildbearbeitung für Fan-Symbole: der Meilenstein-Rahmen wird beim Hochladen fest ins Bild gezeichnet – so braucht
 * jede Minecraft-Version nur „Textur als Quadrat zeichnen“, egal ob in der Tabliste oder über dem Kopf.
 */
public final class SymbolArt {
	private SymbolArt() {
	}

	/** Rahmenstärke in Pixeln für ein Symbol der Kantenlänge {@code size} (16 px → 1, 64 px → 4). */
	public static int border(int size) {
		return Math.max(1, Math.round(size / 16f));
	}

	/** Kantenlänge mit Rahmen (ohne Meilenstein unverändert). */
	public static int framedSize(int size, Milestone milestone) {
		return milestone == null ? size : size + 2 * border(size);
	}

	/**
	 * Bild mit dünnem Rahmen in Meilenstein-Farbe ({@code (s + 2b)²}). Ohne Meilenstein kommt das Bild unverändert
	 * zurück. Der Rahmen hat eine helle Innenkante (Glanz) – so hebt er sich auch von dunklen Symbolen ab.
	 */
	public static int[] framed(int[] px, int size, Milestone milestone) {
		if (milestone == null) return px;
		int b = border(size);
		int n = size + 2 * b;
		int[] out = new int[n * n];
		int color = milestone.color;
		int light = lighten(color, 0.45f);
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				boolean ring = x < b || y < b || x >= n - b || y >= n - b;
				if (ring) {
					// Obere/linke Außenkante etwas heller (Licht von oben links), Rest volle Farbe.
					boolean shine = b > 1 && (x == 0 || y == 0);
					out[y * n + x] = shine ? light : color;
				} else {
					out[y * n + x] = px[(y - b) * size + (x - b)];
				}
			}
		}
		return out;
	}

	/** Farbe zu Weiß hin aufhellen ({@code t} 0…1), Deckkraft bleibt. */
	static int lighten(int argb, float t) {
		int a = argb >>> 24;
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		r = Math.round(r + (255 - r) * t);
		g = Math.round(g + (255 - g) * t);
		b = Math.round(b + (255 - b) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}

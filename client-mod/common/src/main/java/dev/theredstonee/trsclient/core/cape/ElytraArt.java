package dev.theredstonee.trsclient.core.cape;

/**
 * Elytra-Look aus dem Umhang-Motiv: Viele TRS-Umhänge (Team-/Code-Umhänge, Studio-Umhänge, alte Uploads) haben im
 * Elytra-Bereich der Textur nichts Brauchbares – leer, Reste oder einfarbig rot. Deshalb wird der Elytra-Bereich jedes
 * Bildes beim Laden (Textur-Thread, einmal je Bild) aus der Außenseite des Umhangs neu erzeugt – in voller Auflösung,
 * je Animationsbild, ohne Kosten pro Tick. Rechnet wie der Launcher ({@code app/utils/capeImage.ts},
 * {@code fillElytraFromFront}): gleiche Zeilenverteilung, Abdunklung 3/5 bzw. 4/5, Bereich vorher geleert.
 *
 * <p><b>Aufteilung</b> (Vanilla-Elytra, Texturraum 64×32, Flügel = Quader {@code texOffs(22,0)}, 10×20×2, beide Flügel
 * nutzen denselben Bereich, das Modell spiegelt den rechten selbst). Koordinaten in Einheiten, Faktor s:
 * <pre>
 *   außen  (36, 2) 10×20  O                     – +z-Fläche, vom Rücken weg (dort malt auch Mojang die Flügel hin)
 *   innen  (24, 2) 10×20  O gespiegelt, ⌊c·3/5⌋ – zum Rücken
 *   Schulterkante (34, 2) 2×20  O(0, y), ⌊c·4/5⌋  (grenzt an außen u 36 und innen u 34)
 *   Endkante      (22, 2) 2×20  O(10s−1, y), ⌊c·4/5⌋ (grenzt an außen u 46 und innen u 24)
 *   oben   (24, 0) 10×2   O(10s−1−x, 0), ⌊c·4/5⌋
 *   unten  (34, 0) 10×2   O(10s−1−x, 20s−1), ⌊c·4/5⌋
 * </pre>
 * Auf der Außenseite liegt u = 36 an der Schulter, u = 46 am Flügelende; von hinten zeigt der linke Flügel das Motiv
 * also genau wie der Umhang, der rechte spiegelbildlich – symmetrisch. Kanten, oben und unten nehmen die Farbe der
 * angrenzenden Stelle der Außenseite (daher die gespiegelten Spalten oben/unten).
 *
 * <p><b>Motiv</b> = Außenseite des Umhangs (u 1..11, v 1..17, also 10×16 Einheiten). Löcher (Alpha &lt; 128) bekommen
 * die Farbe des nächstgelegenen deckenden Pixels, alles wird voll deckend (die Elytra wird mit Ausschnitt gezeichnet –
 * halbe Deckkraft gäbe Löcher); ganz ohne deckendes Pixel wird es grau (64,64,64). Die Höhe 16 → 20 wird per
 * „nächster Nachbar“ gestreckt: Zielzeile y nimmt Quellzeile ⌊y·16/20⌋ – jede vierte Quellzeile kommt doppelt,
 * gleichmäßig verteilt. Bei HD-Umhängen (Faktor Vielfaches von 4) wird so jedes Motiv-Pixel exakt gleich hoch
 * (z. B. 8 → 10 Zeilen): die Pixel-Kunst bleibt scharf und das ganze Motiv sichtbar (kein Beschnitt).
 */
public final class ElytraArt {
	/** Ab dieser Deckkraft zählt ein Pixel als Motiv; alles darunter ist ein Loch. */
	static final int OPAQUE = 128;
	/** Farbe, falls die Außenseite gar nichts Deckendes hat (wie im Launcher). */
	static final int FALLBACK = 0xFF404040;

	private ElytraArt() {
	}

	/** Erzeugt den Elytra-Bereich in allen Bildern eines Umhangs (im Textur-Thread, vor dem Hochladen). */
	public static void apply(CapeFrames frames) {
		if (frames == null) return;
		for (int[] px : frames.frames) {
			if (px != null) apply(px, frames.width, frames.height);
		}
	}

	/**
	 * Erzeugt den Elytra-Bereich eines Bildes ({@code w × h}, ARGB, Seitenverhältnis 2:1, Breite ≥ 64) aus dessen
	 * Umhang-Außenseite. Andere Bereiche bleiben unverändert.
	 */
	public static void apply(int[] px, int w, int h) {
		if (px == null || w < 64 || h * 2 != w || px.length < w * h) return;
		// 1. Motiv: Außenseite des Umhangs, Löcher gefüllt, voll deckend.
		int sx0 = p(1, w);
		int sy0 = p(1, w);
		int fw = p(11, w) - sx0;
		int fh = p(17, w) - sy0;
		int[] face = new int[fw * fh];
		for (int y = 0; y < fh; y++) System.arraycopy(px, (sy0 + y) * w + sx0, face, y * fw, fw);
		if (!fill(face, fw, fh)) java.util.Arrays.fill(face, FALLBACK);

		// 2. Außenseite O: 10×20, Zeile y = Motivzeile ⌊y·16/20⌋, Spalten 1:1 (bei krummen Größen nächster Nachbar).
		int ox0 = p(36, w);
		int oy0 = p(2, w);
		int ow = p(46, w) - ox0;
		int oh = p(22, w) - oy0;
		int[] outer = new int[ow * oh];
		for (int y = 0; y < oh; y++) {
			int sy = (int) ((long) y * fh / oh);
			for (int x = 0; x < ow; x++) outer[y * ow + x] = face[sy * fw + (int) ((long) x * fw / ow)];
		}

		// 3. Bereich leeren, dann die sechs Flächen.
		int rx0 = p(22, w);
		int rx1 = p(46, w);
		for (int y = 0; y < oy0 + oh; y++) java.util.Arrays.fill(px, y * w + rx0, y * w + rx1, 0);
		for (int y = 0; y < oh; y++) System.arraycopy(outer, y * ow, px, (oy0 + y) * w + ox0, ow);
		// Innenseite: gespiegelt (gleiche Stelle des Flügels → gleiche Farbe) und dunkler.
		int ix0 = p(24, w);
		int iw = p(34, w) - ix0;
		for (int y = 0; y < oh; y++) {
			int row = (oy0 + y) * w;
			for (int x = 0; x < iw; x++) px[row + ix0 + x] = shade(outer[y * ow + mirror(x, iw, ow)], 3, 5);
		}
		// Kanten: an der Schulter die erste, am Flügelende die letzte Spalte der Außenseite.
		int ex0 = p(34, w);
		int ew = p(36, w) - ex0;
		int wx0 = p(22, w);
		int ww = p(24, w) - wx0;
		for (int y = 0; y < oh; y++) {
			int row = (oy0 + y) * w;
			int shoulder = shade(outer[y * ow], 4, 5);
			int end = shade(outer[y * ow + ow - 1], 4, 5);
			for (int x = 0; x < ew; x++) px[row + ex0 + x] = shoulder;
			for (int x = 0; x < ww; x++) px[row + wx0 + x] = end;
		}
		// Oben/unten: oberste bzw. unterste Zeile der Außenseite, Spalte u = x0 + j ↔ Spalte (letzte − j).
		int tx0 = p(24, w);
		int tw = p(34, w) - tx0;
		int bx0 = p(34, w);
		int bw = p(44, w) - bx0;
		for (int y = 0; y < oy0; y++) {
			int row = y * w;
			for (int x = 0; x < tw; x++) px[row + tx0 + x] = shade(outer[mirror(x, tw, ow)], 4, 5);
			for (int x = 0; x < bw; x++) px[row + bx0 + x] = shade(outer[(oh - 1) * ow + mirror(x, bw, ow)], 4, 5);
		}
	}

	/** Texturkoordinate {@code u} (64er-Raster) → Pixel bei Bildbreite {@code w} (gerundet). */
	static int p(int u, int w) {
		return (u * w + 32) / 64;
	}

	/** Spalte {@code x} einer gespiegelten Fläche der Breite {@code n} → Spalte der Außenseite (Breite {@code ow}). */
	private static int mirror(int x, int n, int ow) {
		return ow - 1 - Math.min(ow - 1, (int) ((long) x * ow / n));
	}

	/**
	 * Füllt Löcher (Alpha &lt; {@link #OPAQUE}) mit der Farbe des nächstgelegenen deckenden Pixels (Breitensuche von allen
	 * deckenden Pixeln gleichzeitig) und macht alles voll deckend.
	 *
	 * @return false, wenn es gar kein deckendes Pixel gibt (dann bleibt {@code face} unverändert)
	 */
	static boolean fill(int[] face, int w, int h) {
		int n = w * h;
		if (n == 0) return false;
		int opaque = 0;
		for (int i = 0; i < n; i++) if ((face[i] >>> 24) >= OPAQUE) opaque++;
		if (opaque == 0) return false;
		if (opaque < n) {
			spread(face, w, n);
		}
		for (int i = 0; i < n; i++) face[i] |= 0xFF000000;
		return true;
	}

	/** Breitensuche: jedes Loch bekommt die Farbe des nächsten deckenden Pixels (4er-Nachbarschaft). */
	private static void spread(int[] face, int w, int n) {
		int[] queue = new int[n];
		boolean[] done = new boolean[n];
		int head = 0;
		int tail = 0;
		for (int i = 0; i < n; i++) {
			if ((face[i] >>> 24) >= OPAQUE) {
				done[i] = true;
				queue[tail++] = i;
			}
		}
		while (head < tail) {
			int i = queue[head++];
			int c = face[i];
			int x = i % w;
			if (x > 0) tail = visit(face, done, queue, tail, i - 1, c);
			if (x < w - 1) tail = visit(face, done, queue, tail, i + 1, c);
			if (i >= w) tail = visit(face, done, queue, tail, i - w, c);
			if (i + w < n) tail = visit(face, done, queue, tail, i + w, c);
		}
	}

	private static int visit(int[] face, boolean[] done, int[] queue, int tail, int j, int c) {
		if (done[j]) return tail;
		done[j] = true;
		face[j] = c;
		queue[tail] = j;
		return tail + 1;
	}

	/** Farbe mal {@code num/den} (je Kanal abgerundet, wie im Launcher), voll deckend. */
	static int shade(int c, int num, int den) {
		int r = ((c >> 16) & 0xFF) * num / den;
		int g = ((c >> 8) & 0xFF) * num / den;
		int b = (c & 0xFF) * num / den;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}

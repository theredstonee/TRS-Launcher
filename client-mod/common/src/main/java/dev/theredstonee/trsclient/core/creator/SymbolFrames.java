package dev.theredstonee.trsclient.core.creator;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.online.SymbolInfo;

import java.io.IOException;

/**
 * Ein Fan-Symbol, zerlegt in seine quadratischen Einzelbilder (ARGB). Die Datei ist ein senkrechter Streifen
 * {@code s × (s·n)} mit {@code s} 16…64 und {@code n} 1…8 (API.md §35.1). Stimmt die Angabe der API nicht mit der Datei
 * überein, gewinnt die Datei – aber nie mehr Bilder, als die API verspricht.
 */
public final class SymbolFrames {
	/** Kantenlänge eines Bildes in Pixeln. */
	public final int size;
	/** [Bild][Pixel], ARGB zeilenweise. */
	public final int[][] frames;

	SymbolFrames(int size, int[][] frames) {
		this.size = size;
		this.frames = frames;
	}

	public int count() {
		return frames.length;
	}

	/** Dekodiert und prüft einen Symbol-Streifen; {@code info} darf null sein (dann zählt nur die Datei). */
	public static SymbolFrames decode(byte[] png, SymbolInfo info) throws IOException {
		long maxPixels = (long) SymbolInfo.MAX_SIZE * SymbolInfo.MAX_SIZE * SymbolInfo.MAX_FRAMES;
		PngDecoder.Image image = PngDecoder.decode(png, maxPixels);
		if (image == null) throw new IOException("Symbol leer");
		int s = image.width;
		if (s < SymbolInfo.MIN_SIZE || s > SymbolInfo.MAX_SIZE) throw new IOException("Symbol-Breite " + s);
		if (image.height % s != 0) throw new IOException("Symbol-Höhe " + image.height);
		int count = image.height / s;
		if (count < 1 || count > SymbolInfo.MAX_FRAMES) throw new IOException("Bildzahl " + count);
		if (info != null && info.frames > 0) count = Math.min(count, info.frames);
		int[][] frames = new int[count][];
		for (int f = 0; f < count; f++) {
			int[] px = new int[s * s];
			System.arraycopy(image.argb, f * s * s, px, 0, s * s);
			frames[f] = px;
		}
		return new SymbolFrames(s, frames);
	}
}

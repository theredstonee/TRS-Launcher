package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.online.CapeInfo;

import java.io.IOException;

/**
 * Ein Umhang, zerlegt in seine Einzelbilder (je {@code 64·scale × 32·scale}, ARGB). Jedes Bild wird eine
 * eigene Textur – so passen die Vanilla-UVs (Umhang wie Elytra) unverändert, egal wie viele Bilder.
 */
public final class CapeFrames {
	public final int width;
	public final int height;
	/** [Bild][Pixel], ARGB zeilenweise. */
	public final int[][] frames;

	CapeFrames(int width, int height, int[][] frames) {
		this.width = width;
		this.height = height;
		this.frames = frames;
	}

	/**
	 * Teilt die PNG in Bilder auf. Maßgeblich ist das Seitenverhältnis 2:1 je Bild; stimmt die
	 * Bildzahl der API nicht mit der Höhe überein, gewinnt die Höhe (höchstens 64 Bilder).
	 */
	public static CapeFrames split(PngDecoder.Image image, CapeInfo info) throws IOException {
		int w = image.width;
		int frameH = w / 2;
		if (w < 64 || w > 64 * CapeInfo.MAX_SCALE || w % 64 != 0 || frameH == 0) throw new IOException("Umhang-Breite " + w);
		if (image.height % frameH != 0) throw new IOException("Umhang-Höhe " + image.height);
		int count = image.height / frameH;
		if (count < 1 || count > 64) throw new IOException("Bildzahl " + count);
		if (info != null && info.frames != count) {
			// Die API verspricht etwas anderes als die Datei – der Datei glauben, aber nie mehr Bilder als geliefert.
			count = Math.min(count, Math.max(1, info.frames));
		}
		int[][] frames = new int[count][];
		for (int f = 0; f < count; f++) {
			int[] px = new int[w * frameH];
			System.arraycopy(image.argb, f * w * frameH, px, 0, w * frameH);
			frames[f] = px;
		}
		return new CapeFrames(w, frameH, frames);
	}

	public int count() {
		return frames.length;
	}
}

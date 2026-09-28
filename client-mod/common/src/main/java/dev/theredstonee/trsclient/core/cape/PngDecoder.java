package dev.theredstonee.trsclient.core.cape;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Kleiner PNG-Dekoder (ohne AWT/ImageIO – das kann auf macOS mit LWJGL hängen): alle Farbtypen (Graustufen,
 * RGB, Palette, Graustufen+Alpha, RGBA) in allen Bittiefen (1/2/4/8/16), durchsichtige Farbe (tRNS) und
 * Interlacing (Adam7). Das deckt die Umhänge der TRS API ab und auch Texturen aus Resource Packs (die Karte
 * mittelt Blocktexturen). Ergebnis: ARGB-Pixel.
 */
public final class PngDecoder {
	/** Größter Umhang: Faktor 8 (512×256) mit 64 Bildern. */
	private static final long MAX_PIXELS = 512L * 256 * 64;

	private PngDecoder() {
	}

	/** Dekodiertes Bild. */
	public static final class Image {
		public final int width;
		public final int height;
		/** ARGB, zeilenweise. */
		public final int[] argb;

		public Image(int width, int height, int[] argb) {
			this.width = width;
			this.height = height;
			this.argb = argb;
		}
	}

	public static Image decode(byte[] png) throws IOException {
		return decode(png, MAX_PIXELS);
	}

	/**
	 * Wie {@link #decode(byte[])}, aber mit eigener Pixel-Grenze (große Bildschirmfotos: 5K/Ultrawide liegen über der
	 * Standardgrenze von 8,4 MP).
	 */
	public static Image decode(byte[] png, long maxPixels) throws IOException {
		if (png == null || png.length < 33) throw new IOException("keine PNG");
		byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
		for (int i = 0; i < 8; i++) {
			if (png[i] != sig[i]) throw new IOException("keine PNG-Signatur");
		}
		int pos = 8;
		int width = 0;
		int height = 0;
		int depth = 8;
		int colorType = -1;
		int interlace = 0;
		int[] palette = null;
		int[] paletteAlpha = null;
		// Durchsichtige Farbe bei Graustufen/RGB ohne Alpha (tRNS) in der Bittiefe der Datei; -1 = keine.
		int transGray = -1;
		long transRgb = -1;
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		boolean end = false;
		while (!end && pos + 8 <= png.length) {
			int len = readInt(png, pos);
			String type = new String(png, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
			int data = pos + 8;
			if (len < 0 || data + len + 4 > png.length) throw new IOException("Chunk zu lang");
			switch (type) {
				case "IHDR":
					width = readInt(png, data);
					height = readInt(png, data + 4);
					depth = png[data + 8] & 0xFF;
					colorType = png[data + 9] & 0xFF;
					interlace = png[data + 12] & 0xFF;
					if (depth != 1 && depth != 2 && depth != 4 && depth != 8 && depth != 16) {
						throw new IOException("Bittiefe " + depth);
					}
					if (interlace > 1) throw new IOException("Interlacing " + interlace);
					if (width <= 0 || height <= 0 || (long) width * height > maxPixels) {
						throw new IOException("Bildgröße ungültig");
					}
					break;
				case "PLTE":
					palette = new int[len / 3];
					for (int i = 0; i < palette.length; i++) {
						int o = data + i * 3;
						palette[i] = ((png[o] & 0xFF) << 16) | ((png[o + 1] & 0xFF) << 8) | (png[o + 2] & 0xFF);
					}
					break;
				case "tRNS":
					if (colorType == 0 && len >= 2) {
						transGray = ((png[data] & 0xFF) << 8) | (png[data + 1] & 0xFF);
					} else if (colorType == 2 && len >= 6) {
						transRgb = ((long) (((png[data] & 0xFF) << 8) | (png[data + 1] & 0xFF)) << 32)
								| ((long) (((png[data + 2] & 0xFF) << 8) | (png[data + 3] & 0xFF)) << 16)
								| (((png[data + 4] & 0xFF) << 8) | (png[data + 5] & 0xFF));
					} else {
						paletteAlpha = new int[len];
						for (int i = 0; i < len; i++) paletteAlpha[i] = png[data + i] & 0xFF;
					}
					break;
				case "IDAT":
					idat.write(png, data, len);
					break;
				case "IEND":
					end = true;
					break;
				default:
					break;
			}
			pos = data + len + 4;
		}
		if (width == 0 || colorType < 0) throw new IOException("IHDR fehlt");
		int channels;
		switch (colorType) {
			case 0: channels = 1; break;
			case 2: channels = 3; break;
			case 3: channels = 1; break;
			case 4: channels = 2; break;
			case 6: channels = 4; break;
			default: throw new IOException("Farbtyp " + colorType);
		}
		if (colorType == 3 && palette == null) throw new IOException("Palette fehlt");
		if (colorType == 3 && depth == 16) throw new IOException("Palette mit 16 Bit");
		if ((colorType == 2 || colorType == 4 || colorType == 6) && depth < 8) throw new IOException("Bittiefe " + depth);
		int bitsPerPixel = channels * depth;
		// Abstand für die Filter: ganze Bytes je Pixel, mindestens 1.
		int bpp = Math.max(1, bitsPerPixel / 8);
		// Durchgänge: ohne Interlacing einer, sonst Adam7 (Start x, Start y, Schritt x, Schritt y).
		int[][] passes = interlace == 0 ? new int[][] {{0, 0, 1, 1}}
				: new int[][] {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
		long expected = 0;
		for (int[] p : passes) {
			long pw = passSize(width, p[0], p[2]), ph = passSize(height, p[1], p[3]);
			if (pw == 0 || ph == 0) continue;
			expected += ((pw * bitsPerPixel + 7) / 8 + 1) * ph;
		}
		if (expected > Integer.MAX_VALUE - 16) throw new IOException("Bild zu groß");
		byte[] raw = inflate(idat.toByteArray(), (int) expected);
		int[] out = new int[width * height];
		int maxSample = (1 << depth) - 1;
		int off = 0;
		for (int[] p : passes) {
			int pw = passSize(width, p[0], p[2]), ph = passSize(height, p[1], p[3]);
			if (pw == 0 || ph == 0) continue;
			int rowBytes = (pw * bitsPerPixel + 7) / 8;
			byte[] prev = new byte[rowBytes];
			byte[] line = new byte[rowBytes];
			for (int row = 0; row < ph; row++) {
				int filter = raw[off] & 0xFF;
				System.arraycopy(raw, off + 1, line, 0, rowBytes);
				off += rowBytes + 1;
				unfilter(filter, line, prev, bpp);
				int y = p[1] + row * p[3];
				for (int col = 0; col < pw; col++) {
					int x = p[0] + col * p[2];
					int a;
					int r;
					int g;
					int b;
					switch (colorType) {
						case 0: {
							int v = sample(line, col, 0, 1, depth);
							r = g = b = to8(v, depth, maxSample);
							a = v == transGray ? 0 : 255;
							break;
						}
						case 2: {
							int rv = sample(line, col, 0, 3, depth);
							int gv = sample(line, col, 1, 3, depth);
							int bv = sample(line, col, 2, 3, depth);
							r = to8(rv, depth, maxSample);
							g = to8(gv, depth, maxSample);
							b = to8(bv, depth, maxSample);
							long key = ((long) rv << 32) | ((long) gv << 16) | bv;
							a = key == transRgb ? 0 : 255;
							break;
						}
						case 3: {
							int idx = sample(line, col, 0, 1, depth);
							int rgb = idx < palette.length ? palette[idx] : 0;
							r = (rgb >> 16) & 0xFF;
							g = (rgb >> 8) & 0xFF;
							b = rgb & 0xFF;
							a = paletteAlpha != null && idx < paletteAlpha.length ? paletteAlpha[idx] : 255;
							break;
						}
						case 4:
							r = g = b = to8(sample(line, col, 0, 2, depth), depth, maxSample);
							a = to8(sample(line, col, 1, 2, depth), depth, maxSample);
							break;
						default:
							r = to8(sample(line, col, 0, 4, depth), depth, maxSample);
							g = to8(sample(line, col, 1, 4, depth), depth, maxSample);
							b = to8(sample(line, col, 2, 4, depth), depth, maxSample);
							a = to8(sample(line, col, 3, 4, depth), depth, maxSample);
							break;
					}
					out[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
				}
				byte[] t = prev;
				prev = line;
				line = t;
			}
		}
		return new Image(width, height, out);
	}

	/** Pixel eines Durchgangs in einer Richtung (Start, Schritt); 0 = Durchgang leer. */
	private static int passSize(int size, int start, int step) {
		return size <= start ? 0 : (size - start + step - 1) / step;
	}

	/** Abtastwert (Kanal {@code ch} von {@code channels}) des Pixels {@code px} einer Zeile in Bittiefe {@code depth}. */
	private static int sample(byte[] line, int px, int ch, int channels, int depth) {
		if (depth == 8) return line[px * channels + ch] & 0xFF;
		if (depth == 16) {
			int o = (px * channels + ch) * 2;
			return ((line[o] & 0xFF) << 8) | (line[o + 1] & 0xFF);
		}
		// 1/2/4 Bit: nur Graustufen/Palette (ein Kanal), höchstwertige Bits zuerst.
		int bit = px * depth;
		int v = line[bit >> 3] & 0xFF;
		int shift = 8 - depth - (bit & 7);
		return (v >> shift) & ((1 << depth) - 1);
	}

	/** Abtastwert auf 0..255 umrechnen. */
	private static int to8(int v, int depth, int maxSample) {
		if (depth == 8) return v;
		if (depth == 16) return v >> 8;
		return v * 255 / maxSample;
	}

	private static void unfilter(int filter, byte[] line, byte[] prev, int bpp) throws IOException {
		int n = line.length;
		switch (filter) {
			case 0:
				return;
			case 1:
				for (int i = bpp; i < n; i++) line[i] = (byte) (line[i] + line[i - bpp]);
				return;
			case 2:
				for (int i = 0; i < n; i++) line[i] = (byte) (line[i] + prev[i]);
				return;
			case 3:
				for (int i = 0; i < n; i++) {
					int left = i >= bpp ? line[i - bpp] & 0xFF : 0;
					line[i] = (byte) (line[i] + ((left + (prev[i] & 0xFF)) >> 1));
				}
				return;
			case 4:
				for (int i = 0; i < n; i++) {
					int a = i >= bpp ? line[i - bpp] & 0xFF : 0;
					int b = prev[i] & 0xFF;
					int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
					int p = a + b - c;
					int pa = Math.abs(p - a);
					int pb = Math.abs(p - b);
					int pc = Math.abs(p - c);
					int pred = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
					line[i] = (byte) (line[i] + pred);
				}
				return;
			default:
				throw new IOException("Filter " + filter);
		}
	}

	private static byte[] inflate(byte[] data, int expected) throws IOException {
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(data);
			byte[] out = new byte[expected];
			int total = 0;
			while (total < expected && !inflater.finished()) {
				int n = inflater.inflate(out, total, expected - total);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
				total += n;
			}
			if (total != expected) throw new IOException("Bilddaten unvollständig");
			return out;
		} catch (DataFormatException e) {
			throw new IOException("Bilddaten kaputt", e);
		} finally {
			inflater.end();
		}
	}

	private static int readInt(byte[] b, int o) {
		return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
	}
}

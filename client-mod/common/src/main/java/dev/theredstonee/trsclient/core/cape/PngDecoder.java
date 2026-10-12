package dev.theredstonee.trsclient.core.cape;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Kleiner PNG-Dekoder (ohne AWT/ImageIO – das kann auf macOS mit LWJGL hängen): alle Farbtypen (Graustufen,
 * RGB, Palette, Graustufen+Alpha, RGBA) in allen Bittiefen (1/2/4/8/16), durchsichtige Farbe (tRNS) und
 * Interlacing (Adam7). Das deckt die Umhänge der TRS API ab und auch Texturen aus Resource Packs (die Karte
 * mittelt Blocktexturen). Ergebnis: ARGB-Pixel.
 *
 * <p>Die Bilddaten werden Zeile für Zeile direkt aus der Datei entpackt (kein Rohdaten-Puffer in Bildgröße). Wer das
 * ganze Bild nicht braucht (Umhänge: Einzelbilder, verkleinert, nur das erste Bild), dekodiert über einen {@link Sink}.
 */
public final class PngDecoder {
	/** Standardgrenze: größter Umhang, Faktor 16 (1024×512 je Bild) mit 32 Bildern = 16,8 MP. */
	public static final long MAX_PIXELS = 1024L * 512 * 32;

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

	/**
	 * Empfänger der Bildzeilen beim Dekodieren: So muss niemand das ganze Bild (oder die entpackten Rohdaten) auf einmal
	 * im Speicher halten – Umhänge schreiben direkt in ihre Einzelbilder.
	 */
	public interface Sink {
		/** Kopf gelesen und geprüft; kommt genau einmal vor der ersten Zeile. */
		void begin(int width, int height, boolean interlaced) throws IOException;

		/**
		 * Pixel einer Zeile: {@code px[0..n)} gehören an {@code (x0 + i·dx, y)}. Ohne Interlacing kommen die Zeilen
		 * vollständig ({@code x0 = 0, dx = 1}) und von oben nach unten. {@code px} wird danach wiederverwendet.
		 *
		 * @return false = der Rest wird nicht gebraucht, Dekodieren hier beenden
		 */
		boolean row(int y, int x0, int dx, int[] px, int n) throws IOException;
	}

	public static Image decode(byte[] png) throws IOException {
		return decode(png, MAX_PIXELS);
	}

	/**
	 * Wie {@link #decode(byte[])}, aber mit eigener Pixel-Grenze (große Bildschirmfotos: 5K/Ultrawide liegen über der
	 * Standardgrenze von 16,8 MP).
	 */
	public static Image decode(byte[] png, long maxPixels) throws IOException {
		final Image[] result = new Image[1];
		decode(png, maxPixels, new Sink() {
			private int[] out;
			private int width;

			@Override
			public void begin(int w, int h, boolean interlaced) {
				width = w;
				out = new int[w * h];
				result[0] = new Image(w, h, out);
			}

			@Override
			public boolean row(int y, int x0, int dx, int[] px, int n) {
				int base = y * width + x0;
				if (dx == 1) {
					System.arraycopy(px, 0, out, base, n);
				} else {
					for (int i = 0; i < n; i++) out[base + i * dx] = px[i];
				}
				return true;
			}
		});
		return result[0];
	}

	/**
	 * Dekodiert zeilenweise in {@code sink}. Entpackt wird Zeile für Zeile – Speicher braucht nur, was der Empfänger
	 * behält (plus zwei Zeilen).
	 */
	public static void decode(byte[] png, long maxPixels, Sink sink) throws IOException {
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
		// IDAT-Abschnitte nur merken (Start, Länge) – entpackt wird später direkt aus der Datei.
		IdatStream idat = new IdatStream(png);
		boolean end = false;
		while (!end && pos + 8 <= png.length) {
			int len = readInt(png, pos);
			String type = new String(png, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
			int data = pos + 8;
			if (len < 0 || (long) data + len + 4 > png.length) throw new IOException("Chunk zu lang");
			switch (type) {
				case "IHDR":
					if (len < 13) throw new IOException("IHDR zu kurz");
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
					idat.add(data, len);
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
		if (((long) width * bitsPerPixel + 7) / 8 > Integer.MAX_VALUE - 16) throw new IOException("Bild zu groß");
		// Abstand für die Filter: ganze Bytes je Pixel, mindestens 1.
		int bpp = Math.max(1, bitsPerPixel / 8);
		// Durchgänge: ohne Interlacing einer, sonst Adam7 (Start x, Start y, Schritt x, Schritt y).
		int[][] passes = interlace == 0 ? new int[][] {{0, 0, 1, 1}}
				: new int[][] {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
		sink.begin(width, height, interlace != 0);
		int maxSample = (1 << depth) - 1;
		int[] px = new int[width];
		byte[] filter = new byte[1];
		try {
			for (int[] p : passes) {
				int pw = passSize(width, p[0], p[2]), ph = passSize(height, p[1], p[3]);
				if (pw == 0 || ph == 0) continue;
				int rowBytes = (int) (((long) pw * bitsPerPixel + 7) / 8);
				byte[] prev = new byte[rowBytes];
				byte[] line = new byte[rowBytes];
				for (int row = 0; row < ph; row++) {
					idat.read(filter, 1);
					idat.read(line, rowBytes);
					unfilter(filter[0] & 0xFF, line, prev, bpp);
					if (colorType == 6 && depth == 8) {
						// Häufigster Fall (RGBA, 8 Bit): ohne Umweg über die Abtastwerte.
						for (int col = 0, o = 0; col < pw; col++, o += 4) {
							px[col] = ((line[o + 3] & 0xFF) << 24) | ((line[o] & 0xFF) << 16) | ((line[o + 1] & 0xFF) << 8)
									| (line[o + 2] & 0xFF);
						}
					} else {
						for (int col = 0; col < pw; col++) {
							px[col] = pixel(line, col, colorType, depth, maxSample, palette, paletteAlpha, transGray, transRgb);
						}
					}
					if (!sink.row(p[1] + row * p[3], p[0], p[2], px, pw)) return;
					byte[] t = prev;
					prev = line;
					line = t;
				}
			}
		} finally {
			idat.end();
		}
	}

	/** Ein Pixel einer entfilterten Zeile als ARGB. */
	private static int pixel(byte[] line, int col, int colorType, int depth, int maxSample, int[] palette,
			int[] paletteAlpha, int transGray, long transRgb) {
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
		return (a << 24) | (r << 16) | (g << 8) | b;
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

	/** Entpackt die IDAT-Abschnitte direkt aus der Datei, Stück für Stück (ohne sie vorher zusammenzukopieren). */
	private static final class IdatStream {
		private final byte[] png;
		private int[] offsets = new int[8];
		private int[] lengths = new int[8];
		private int count;
		private int next;
		private Inflater inflater;

		IdatStream(byte[] png) {
			this.png = png;
		}

		void add(int offset, int length) {
			if (count == offsets.length) {
				offsets = Arrays.copyOf(offsets, count * 2);
				lengths = Arrays.copyOf(lengths, count * 2);
			}
			offsets[count] = offset;
			lengths[count] = length;
			count++;
		}

		/** Liest genau {@code len} entpackte Bytes; zu kurze Daten → IOException. */
		void read(byte[] buf, int len) throws IOException {
			if (inflater == null) inflater = new Inflater();
			int total = 0;
			while (total < len) {
				if (inflater.finished()) throw new IOException("Bilddaten unvollständig");
				if (inflater.needsInput()) {
					if (next >= count) throw new IOException("Bilddaten unvollständig");
					inflater.setInput(png, offsets[next], lengths[next]);
					next++;
					continue;
				}
				int n;
				try {
					n = inflater.inflate(buf, total, len - total);
				} catch (DataFormatException e) {
					throw new IOException("Bilddaten kaputt", e);
				}
				if (n == 0 && !inflater.needsInput() && !inflater.finished()) throw new IOException("Bilddaten kaputt");
				total += n;
			}
		}

		void end() {
			if (inflater != null) inflater.end();
		}
	}

	private static int readInt(byte[] b, int o) {
		return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
	}
}

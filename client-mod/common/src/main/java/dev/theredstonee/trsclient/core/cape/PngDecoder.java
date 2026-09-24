package dev.theredstonee.trsclient.core.cape;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Kleiner PNG-Dekoder (ohne AWT/ImageIO – das kann auf macOS mit LWJGL hängen): 8 Bit je Kanal,
 * Graustufen, RGB, Palette (+tRNS), Graustufen+Alpha und RGBA, ohne Interlacing. Das deckt die
 * Umhänge der TRS API ab (die API kodiert jedes Bild als RGBA neu). Ergebnis: ARGB-Pixel.
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
		if (png == null || png.length < 33) throw new IOException("keine PNG");
		byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
		for (int i = 0; i < 8; i++) {
			if (png[i] != sig[i]) throw new IOException("keine PNG-Signatur");
		}
		int pos = 8;
		int width = 0;
		int height = 0;
		int colorType = -1;
		int[] palette = null;
		int[] paletteAlpha = null;
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
					int depth = png[data + 8] & 0xFF;
					colorType = png[data + 9] & 0xFF;
					int interlace = png[data + 12] & 0xFF;
					if (depth != 8 || interlace != 0) throw new IOException("nur 8 Bit ohne Interlacing");
					if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
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
					paletteAlpha = new int[len];
					for (int i = 0; i < len; i++) paletteAlpha[i] = png[data + i] & 0xFF;
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
		int stride = width * channels;
		byte[] raw = inflate(idat.toByteArray(), (stride + 1) * height);
		int[] out = new int[width * height];
		byte[] prev = new byte[stride];
		byte[] line = new byte[stride];
		for (int y = 0; y < height; y++) {
			int base = y * (stride + 1);
			int filter = raw[base] & 0xFF;
			System.arraycopy(raw, base + 1, line, 0, stride);
			unfilter(filter, line, prev, channels);
			for (int x = 0; x < width; x++) {
				int o = x * channels;
				int a;
				int r;
				int g;
				int b;
				switch (colorType) {
					case 0:
						r = g = b = line[o] & 0xFF;
						a = 255;
						break;
					case 2:
						r = line[o] & 0xFF;
						g = line[o + 1] & 0xFF;
						b = line[o + 2] & 0xFF;
						a = 255;
						break;
					case 3: {
						int idx = line[o] & 0xFF;
						int rgb = idx < palette.length ? palette[idx] : 0;
						r = (rgb >> 16) & 0xFF;
						g = (rgb >> 8) & 0xFF;
						b = rgb & 0xFF;
						a = paletteAlpha != null && idx < paletteAlpha.length ? paletteAlpha[idx] : 255;
						break;
					}
					case 4:
						r = g = b = line[o] & 0xFF;
						a = line[o + 1] & 0xFF;
						break;
					default:
						r = line[o] & 0xFF;
						g = line[o + 1] & 0xFF;
						b = line[o + 2] & 0xFF;
						a = line[o + 3] & 0xFF;
						break;
				}
				out[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
			}
			byte[] t = prev;
			prev = line;
			line = t;
		}
		return new Image(width, height, out);
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

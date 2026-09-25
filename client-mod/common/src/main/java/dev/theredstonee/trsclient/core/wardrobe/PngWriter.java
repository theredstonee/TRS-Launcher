package dev.theredstonee.trsclient.core.wardrobe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Schreibt ARGB-Pixel als PNG (8 Bit RGBA, ohne Metadaten) – ohne AWT, damit es in jeder Minecraft-Version gleich
 * läuft. Farben durchsichtiger Pixel bleiben erhalten (wie die TRS API sie speichert).
 */
public final class PngWriter {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	private PngWriter() {
	}

	public static byte[] write(int width, int height, int[] argb) {
		if (width <= 0 || height <= 0 || argb == null || argb.length < width * height) {
			throw new IllegalArgumentException("Bild " + width + "×" + height);
		}
		byte[] raw = new byte[height * (1 + width * 4)];
		int p = 0;
		for (int y = 0; y < height; y++) {
			raw[p++] = 0; // Filter: keiner
			for (int x = 0; x < width; x++) {
				int c = argb[y * width + x];
				raw[p++] = (byte) (c >> 16);
				raw[p++] = (byte) (c >> 8);
				raw[p++] = (byte) c;
				raw[p++] = (byte) (c >>> 24);
			}
		}
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		ByteArrayOutputStream z = new ByteArrayOutputStream();
		try {
			deflater.setInput(raw);
			deflater.finish();
			byte[] buf = new byte[8192];
			while (!deflater.finished()) {
				int n = deflater.deflate(buf);
				z.write(buf, 0, n);
			}
		} finally {
			deflater.end();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(z.size() + 64);
		out.write(SIGNATURE, 0, SIGNATURE.length);
		byte[] ihdr = new byte[13];
		int32(ihdr, 0, width);
		int32(ihdr, 4, height);
		ihdr[8] = 8; // Bittiefe
		ihdr[9] = 6; // RGBA
		chunk(out, "IHDR", ihdr);
		chunk(out, "IDAT", z.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] len = new byte[4];
		int32(len, 0, data.length);
		out.write(len, 0, 4);
		byte[] t = type.getBytes(StandardCharsets.US_ASCII);
		out.write(t, 0, 4);
		out.write(data, 0, data.length);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data);
		byte[] c = new byte[4];
		int32(c, 0, (int) crc.getValue());
		out.write(c, 0, 4);
	}

	private static void int32(byte[] b, int o, int v) {
		b[o] = (byte) (v >>> 24);
		b[o + 1] = (byte) (v >>> 16);
		b[o + 2] = (byte) (v >>> 8);
		b[o + 3] = (byte) v;
	}
}

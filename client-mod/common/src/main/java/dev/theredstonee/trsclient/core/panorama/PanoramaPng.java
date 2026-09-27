package dev.theredstonee.trsclient.core.panorama;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Schreibt große Bilder als PNG (8 Bit RGB, deckend) direkt in eine Datei – zeilenweise, ohne das ganze Rohbild im
 * Speicher und ohne AWT (gleich in allen Minecraft-Versionen). Filter „Sub“ je Zeile, mittlere Kompression: ein
 * 4096×2048-Panorama ist so in wenigen Sekunden geschrieben.
 */
public final class PanoramaPng {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final int IDAT_CHUNK = 1 << 16;

	private PanoramaPng() {
	}

	public static void write(Path file, int width, int height, int[] argb) throws IOException {
		if (width <= 0 || height <= 0 || argb == null || argb.length < width * height) {
			throw new IllegalArgumentException("Bild " + width + "×" + height);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
			raw.write(SIGNATURE);
			byte[] ihdr = new byte[13];
			int32(ihdr, 0, width);
			int32(ihdr, 4, height);
			ihdr[8] = 8; // Bittiefe
			ihdr[9] = 2; // RGB
			chunk(raw, "IHDR", ihdr, ihdr.length);
			IdatStream idat = new IdatStream(raw);
			Deflater deflater = new Deflater(5);
			try (DeflaterOutputStream z = new DeflaterOutputStream(idat, deflater, 1 << 16)) {
				byte[] row = new byte[1 + width * 3];
				for (int y = 0; y < height; y++) {
					row[0] = 1; // Filter „Sub“
					int pr = 0, pg = 0, pb = 0;
					int p = 1;
					for (int x = 0; x < width; x++) {
						int c = argb[y * width + x];
						int r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
						row[p++] = (byte) (r - pr);
						row[p++] = (byte) (g - pg);
						row[p++] = (byte) (b - pb);
						pr = r;
						pg = g;
						pb = b;
					}
					z.write(row);
				}
			} finally {
				deflater.end();
			}
			idat.flushChunk();
			chunk(raw, "IEND", new byte[0], 0);
		}
		try {
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Sammelt die gepackten Daten und schreibt sie als IDAT-Blöcke von höchstens 64 KiB. */
	private static final class IdatStream extends OutputStream {
		private final OutputStream out;
		private final byte[] buf = new byte[IDAT_CHUNK];
		private int n;

		IdatStream(OutputStream out) {
			this.out = out;
		}

		@Override
		public void write(int b) throws IOException {
			if (n == buf.length) flushChunk();
			buf[n++] = (byte) b;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			while (len > 0) {
				if (n == buf.length) flushChunk();
				int k = Math.min(len, buf.length - n);
				System.arraycopy(b, off, buf, n, k);
				n += k;
				off += k;
				len -= k;
			}
		}

		void flushChunk() throws IOException {
			if (n == 0) return;
			chunk(out, "IDAT", buf, n);
			n = 0;
		}

		@Override
		public void close() {
			// Die Datei schließt der Aufrufer (IEND folgt noch).
		}
	}

	private static void chunk(OutputStream out, String type, byte[] data, int len) throws IOException {
		byte[] head = new byte[8];
		int32(head, 0, len);
		byte[] t = type.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(t, 0, head, 4, 4);
		out.write(head);
		out.write(data, 0, len);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data, 0, len);
		byte[] c = new byte[4];
		int32(c, 0, (int) crc.getValue());
		out.write(c);
	}

	private static void int32(byte[] b, int o, int v) {
		b[o] = (byte) (v >>> 24);
		b[o + 1] = (byte) (v >>> 16);
		b[o + 2] = (byte) (v >>> 8);
		b[o + 3] = (byte) v;
	}
}

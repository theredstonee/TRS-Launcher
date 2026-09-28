package dev.theredstonee.trsclient.core.map;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Schreibt ein PNG (8 Bit RGBA, unerkundet = durchsichtig) Zeile für Zeile direkt in eine Datei – das ganze Bild
 * liegt nie im Speicher (eine 16384²-Karte wären sonst 1 GB). Ohne AWT, gleich in allen Minecraft-Versionen.
 * Erst in eine {@code .tmp}-Datei, {@link #finish()} benennt sie um; {@link #close()} ohne finish löscht sie.
 */
public final class MapPng implements Closeable {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	private static final int IDAT_CHUNK = 1 << 16;

	private final Path file;
	private final Path tmp;
	private final int width;
	private final int height;
	private final OutputStream raw;
	private final IdatStream idat;
	private final Deflater deflater;
	private final DeflaterOutputStream z;
	private final byte[] row;
	private int rows;
	private boolean done;

	public MapPng(Path file, int width, int height) throws IOException {
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("Bild " + width + "×" + height);
		this.file = file;
		this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
		this.width = width;
		this.height = height;
		Files.createDirectories(file.toAbsolutePath().getParent());
		raw = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
		raw.write(SIGNATURE);
		byte[] ihdr = new byte[13];
		int32(ihdr, 0, width);
		int32(ihdr, 4, height);
		ihdr[8] = 8; // Bittiefe
		ihdr[9] = 6; // RGBA
		chunk(raw, "IHDR", ihdr, ihdr.length);
		idat = new IdatStream(raw);
		deflater = new Deflater(6);
		z = new DeflaterOutputStream(idat, deflater, 1 << 16);
		row = new byte[1 + width * 4];
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** Nächste Zeile (ARGB, {@code width} Werte ab {@code off}). */
	public void writeRow(int[] argb, int off) throws IOException {
		if (rows >= height) throw new IllegalStateException("zu viele Zeilen");
		row[0] = 1; // Filter „Sub“: Differenz zum linken Nachbarn – packt Kartenflächen gut
		int pr = 0, pg = 0, pb = 0, pa = 0;
		int p = 1;
		for (int x = 0; x < width; x++) {
			int c = argb[off + x];
			int a = c >>> 24, r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
			if (a == 0) r = g = b = 0; // durchsichtig immer gleich (packt besser)
			row[p++] = (byte) (r - pr);
			row[p++] = (byte) (g - pg);
			row[p++] = (byte) (b - pb);
			row[p++] = (byte) (a - pa);
			pr = r;
			pg = g;
			pb = b;
			pa = a;
		}
		z.write(row);
		rows++;
	}

	/** Alle Zeilen geschrieben: Datei abschließen und an ihren Platz legen. */
	public void finish() throws IOException {
		if (rows != height) throw new IllegalStateException("Zeilen " + rows + "/" + height);
		z.finish();
		deflater.end();
		idat.flushChunk();
		chunk(raw, "IEND", new byte[0], 0);
		raw.close();
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
		done = true;
	}

	@Override
	public void close() {
		if (done) return;
		deflater.end();
		try {
			raw.close();
		} catch (IOException e) {
			// egal – Datei wird gelöscht
		}
		try {
			Files.deleteIfExists(tmp);
		} catch (IOException e) {
			// bleibt als .tmp liegen
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
			// Die Datei schließt MapPng (IEND folgt noch).
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

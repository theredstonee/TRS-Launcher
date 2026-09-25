package dev.theredstonee.trsclient.core.map;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Kompaktes Dateiformat eines Kartenbereichs ({@code r.<x>.<z>.trsm}): Kopf, Übersicht (32×32, gepackt) und die
 * Daten als getrennte Ebenen (Merker, Rot, Grün, Blau, Höhe als Differenz) – so packt Deflate typischerweise auf
 * 10–30 KB je 128×128 Blöcke. Die Übersicht steht vorn, damit die herausgezoomte Weltkarte nur den Anfang liest.
 */
public final class RegionCodec {
	static final int MAGIC = 0x54524D31; // "TRM1"
	static final int VERSION = 1;
	private static final int MAX_PACKED = 4 << 20;

	private RegionCodec() {
	}

	/** Gelesener Bereich. */
	public static final class Decoded {
		public final int rx;
		public final int rz;
		public final int[] summary;
		public final int[] pixels;
		public final short[] heights;

		Decoded(int rx, int rz, int[] summary, int[] pixels, short[] heights) {
			this.rx = rx;
			this.rz = rz;
			this.summary = summary;
			this.pixels = pixels;
			this.heights = heights;
		}
	}

	/** Schreibt Pixel/Höhen (Kopien) und die Übersicht (oder null). */
	public static byte[] encode(int rx, int rz, int[] pixels, short[] heights, int[] summary) throws IOException {
		byte[] planes = new byte[MapRegion.AREA * 6];
		int a = MapRegion.AREA;
		short prev = 0;
		for (int i = 0; i < a; i++) {
			int p = pixels[i];
			planes[i] = (byte) (p >>> 24);
			planes[a + i] = (byte) (p >> 16);
			planes[2 * a + i] = (byte) (p >> 8);
			planes[3 * a + i] = (byte) p;
			short d = (short) (heights[i] - prev);
			prev = heights[i];
			planes[4 * a + i] = (byte) (d >> 8);
			planes[5 * a + i] = (byte) d;
		}
		byte[] packedData = deflate(planes);
		byte[] packedSummary = new byte[0];
		if (summary != null && summary.length == MapRegion.SUMMARY * MapRegion.SUMMARY) {
			byte[] raw = new byte[summary.length * 4];
			for (int i = 0; i < summary.length; i++) {
				int c = summary[i];
				raw[i * 4] = (byte) (c >>> 24);
				raw[i * 4 + 1] = (byte) (c >> 16);
				raw[i * 4 + 2] = (byte) (c >> 8);
				raw[i * 4 + 3] = (byte) c;
			}
			packedSummary = deflate(raw);
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(packedData.length + packedSummary.length + 32);
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(MAGIC);
		out.writeByte(VERSION);
		out.writeInt(rx);
		out.writeInt(rz);
		out.writeInt(packedSummary.length);
		out.write(packedSummary);
		out.writeInt(packedData.length);
		out.write(packedData);
		out.flush();
		return bytes.toByteArray();
	}

	/** Liest nur Kopf und Übersicht (null = keine Übersicht gespeichert). */
	public static int[] readSummary(InputStream stream) throws IOException {
		DataInputStream in = new DataInputStream(stream);
		header(in);
		in.readInt();
		in.readInt();
		return summary(in);
	}

	/** Liest alles. */
	public static Decoded decode(InputStream stream) throws IOException {
		DataInputStream in = new DataInputStream(stream);
		header(in);
		int rx = in.readInt();
		int rz = in.readInt();
		int[] summary = summary(in);
		int len = in.readInt();
		if (len <= 0 || len > MAX_PACKED) throw new IOException("Datenlänge " + len);
		byte[] packed = new byte[len];
		in.readFully(packed);
		int a = MapRegion.AREA;
		byte[] planes = inflate(packed, a * 6);
		int[] pixels = new int[a];
		short[] heights = new short[a];
		short prev = 0;
		for (int i = 0; i < a; i++) {
			pixels[i] = ((planes[i] & 0xFF) << 24) | ((planes[a + i] & 0xFF) << 16) | ((planes[2 * a + i] & 0xFF) << 8)
					| (planes[3 * a + i] & 0xFF);
			short d = (short) (((planes[4 * a + i] & 0xFF) << 8) | (planes[5 * a + i] & 0xFF));
			prev = (short) (prev + d);
			heights[i] = prev;
		}
		return new Decoded(rx, rz, summary, pixels, heights);
	}

	private static void header(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) throw new IOException("keine TRS-Karte");
		int version = in.readUnsignedByte();
		if (version != VERSION) throw new IOException("Kartenversion " + version);
	}

	private static int[] summary(DataInputStream in) throws IOException {
		int len = in.readInt();
		if (len < 0 || len > MAX_PACKED) throw new IOException("Übersichtslänge " + len);
		if (len == 0) return null;
		byte[] packed = new byte[len];
		in.readFully(packed);
		int n = MapRegion.SUMMARY * MapRegion.SUMMARY;
		byte[] raw = inflate(packed, n * 4);
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			out[i] = ((raw[i * 4] & 0xFF) << 24) | ((raw[i * 4 + 1] & 0xFF) << 16) | ((raw[i * 4 + 2] & 0xFF) << 8)
					| (raw[i * 4 + 3] & 0xFF);
		}
		return out;
	}

	private static byte[] deflate(byte[] raw) {
		Deflater deflater = new Deflater(6);
		try {
			deflater.setInput(raw);
			deflater.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
			byte[] buf = new byte[16384];
			while (!deflater.finished()) {
				int n = deflater.deflate(buf);
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		} finally {
			deflater.end();
		}
	}

	private static byte[] inflate(byte[] packed, int expected) throws IOException {
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(packed);
			byte[] out = new byte[expected];
			int off = 0;
			while (off < expected) {
				int n = inflater.inflate(out, off, expected - off);
				if (n == 0) {
					if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break;
				}
				off += n;
			}
			if (off != expected) throw new IOException("Karte beschädigt (" + off + "/" + expected + ")");
			return out;
		} catch (DataFormatException e) {
			throw new IOException("Karte beschädigt", e);
		} finally {
			inflater.end();
		}
	}
}

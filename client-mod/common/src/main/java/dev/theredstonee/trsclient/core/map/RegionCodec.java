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
 *
 * <p>Fassung 2 (nur Karte v3, {@link MapPreview}, eigener Ordner {@code maps-v3}): nach der 32×32-Übersicht eine
 * 64×64-Übersicht, nach den Daten optional Wassertiefe und Grundfarbe als weitere Ebenen. Gelesen werden beide
 * Fassungen; Fassung 1 schreibt weiter, wer die Vorschau nicht an hat (alter Ordner {@code maps}).
 */
public final class RegionCodec {
	static final int MAGIC = 0x54524D31; // "TRM1"
	static final int VERSION = 1;
	/** Fassung mit Wassertiefe/Grund und 64×64-Übersicht (Karte v3). */
	static final int VERSION_V3 = 2;
	static final int SUMMARY64 = 64;
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
		/** Fassung der Datei (1 oder 2). */
		public int version = VERSION;
		/** Nur Fassung 2: Wassertiefe/Grundfarbe (null = kein Wasser gespeichert) und 64×64-Übersicht (null = keine). */
		public byte[] depth;
		public int[] floor;
		public int[] summary64;

		Decoded(int rx, int rz, int[] summary, int[] pixels, short[] heights) {
			this.rx = rx;
			this.rz = rz;
			this.summary = summary;
			this.pixels = pixels;
			this.heights = heights;
		}
	}

	/**
	 * Fassung 2 (Karte v3): wie {@link #encode}, dazu 64×64-Übersicht und Wasser-Ebenen (Tiefe, Grund-RGB).
	 *
	 * @param depth Wassertiefe je Pixel oder null
	 * @param floor Grundfarbe je Pixel oder null
	 * @param summary64 64×64 ARGB oder null
	 */
	public static byte[] encodeV3(int rx, int rz, int[] pixels, short[] heights, byte[] depth, int[] floor, int[] summary,
			int[] summary64) throws IOException {
		byte[] packedData = deflate(planes(pixels, heights));
		byte[] packedSummary = packArgb(summary, MapRegion.SUMMARY * MapRegion.SUMMARY);
		byte[] packedSummary64 = packArgb(summary64, SUMMARY64 * SUMMARY64);
		byte[] packedWater = new byte[0];
		if (depth != null && floor != null) {
			int a = MapRegion.AREA;
			byte[] w = new byte[a * 4];
			boolean any = false;
			for (int i = 0; i < a; i++) {
				int d = depth[i] & 0xFF;
				if (d != 0) any = true;
				int f = floor[i];
				w[i] = (byte) d;
				w[a + i] = (byte) (f >> 16);
				w[2 * a + i] = (byte) (f >> 8);
				w[3 * a + i] = (byte) f;
			}
			if (any) packedWater = deflate(w);
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(packedData.length + packedSummary.length + packedSummary64.length
				+ packedWater.length + 40);
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(MAGIC);
		out.writeByte(VERSION_V3);
		out.writeInt(rx);
		out.writeInt(rz);
		out.writeInt(packedSummary.length);
		out.write(packedSummary);
		out.writeInt(packedSummary64.length);
		out.write(packedSummary64);
		out.writeInt(packedData.length);
		out.write(packedData);
		out.writeInt(packedWater.length);
		out.write(packedWater);
		out.flush();
		return bytes.toByteArray();
	}

	private static byte[] packArgb(int[] argb, int n) {
		if (argb == null || argb.length != n) return new byte[0];
		byte[] raw = new byte[n * 4];
		for (int i = 0; i < n; i++) {
			int c = argb[i];
			raw[i * 4] = (byte) (c >>> 24);
			raw[i * 4 + 1] = (byte) (c >> 16);
			raw[i * 4 + 2] = (byte) (c >> 8);
			raw[i * 4 + 3] = (byte) c;
		}
		return deflate(raw);
	}

	private static byte[] planes(int[] pixels, short[] heights) {
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
		return planes;
	}

	/** Liest nur die 64×64-Übersicht (Fassung 2; null bei Fassung 1 oder ohne Übersicht). */
	public static int[] readSummary64(InputStream stream) throws IOException {
		DataInputStream in = new DataInputStream(stream);
		int version = header(in);
		if (version < VERSION_V3) return null;
		in.readInt();
		in.readInt();
		skipBlock(in);
		return summary(in, SUMMARY64);
	}

	private static void skipBlock(DataInputStream in) throws IOException {
		int len = in.readInt();
		if (len < 0 || len > MAX_PACKED) throw new IOException("Blocklänge " + len);
		int left = len;
		while (left > 0) {
			int n = in.skipBytes(left);
			if (n <= 0) throw new IOException("Datei zu kurz");
			left -= n;
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

	/** Liest alles (Fassung 1 oder 2). */
	public static Decoded decode(InputStream stream) throws IOException {
		DataInputStream in = new DataInputStream(stream);
		int version = header(in);
		int rx = in.readInt();
		int rz = in.readInt();
		int[] summary = summary(in);
		int[] summary64 = version >= VERSION_V3 ? summary(in, SUMMARY64) : null;
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
		Decoded d = new Decoded(rx, rz, summary, pixels, heights);
		d.version = version;
		d.summary64 = summary64;
		if (version >= VERSION_V3) {
			int wlen = in.readInt();
			if (wlen < 0 || wlen > MAX_PACKED) throw new IOException("Wasserlänge " + wlen);
			if (wlen > 0) {
				byte[] pw = new byte[wlen];
				in.readFully(pw);
				byte[] w = inflate(pw, a * 4);
				d.depth = new byte[a];
				d.floor = new int[a];
				System.arraycopy(w, 0, d.depth, 0, a);
				for (int i = 0; i < a; i++) {
					d.floor[i] = ((w[a + i] & 0xFF) << 16) | ((w[2 * a + i] & 0xFF) << 8) | (w[3 * a + i] & 0xFF);
				}
			}
		}
		return d;
	}

	/** Prüft Kennung und Fassung (1 oder 2) und liefert die Fassung. */
	private static int header(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) throw new IOException("keine TRS-Karte");
		int version = in.readUnsignedByte();
		if (version != VERSION && version != VERSION_V3) throw new IOException("Kartenversion " + version);
		return version;
	}

	private static int[] summary(DataInputStream in) throws IOException {
		return summary(in, MapRegion.SUMMARY);
	}

	private static int[] summary(DataInputStream in, int side) throws IOException {
		int len = in.readInt();
		if (len < 0 || len > MAX_PACKED) throw new IOException("Übersichtslänge " + len);
		if (len == 0) return null;
		byte[] packed = new byte[len];
		in.readFully(packed);
		int n = side * side;
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

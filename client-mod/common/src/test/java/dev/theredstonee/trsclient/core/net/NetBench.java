package dev.theredstonee.trsclient.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Mikro-Benchmark der Netzwerk-Optimierung: Verarbeitungszeit je Paket vorher (so wie Vanilla es macht) und nachher
 * (TRS). Läuft nur auf Wunsch: {@code -Dtrsclient.test.netbench=true} bzw. als {@code main} (auch unter Java 8).
 *
 * <p>„Vanilla“ ist hier eine Nachbildung der Arbeitsschritte (gleiche Kopien/Allokationen), nicht Mojangs Code:
 * <ul>
 *   <li>Entschlüsseln (alle Versionen): Bytes in ein Feld kopieren, {@code Cipher.update} (JDK-CFB8) in einen Heap-Puffer.</li>
 *   <li>Entpacken (1.8.9–1.20.4): {@code new byte[]} für die Eingabe, {@code new byte[]} fürs Ergebnis,
 *   {@code Unpooled.wrappedBuffer}.</li>
 *   <li>Packen (alle Versionen): {@code new byte[]} je Paket, Ausgabe über ein 8-KB-Zwischenfeld.</li>
 * </ul>
 */
public class NetBench {
	static final ByteBufAllocator ALLOC = PooledByteBufAllocator.DEFAULT;

	public static void main(String[] args) throws Exception {
		System.out.println(run(Integer.getInteger("netbench.millis", 1500)));
	}

	@Test
	void benchmark() throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean("trsclient.test.netbench"), "nur mit -Dtrsclient.test.netbench=true");
		System.out.println(run(1500));
	}

	/** Ergebnis-Tabelle (µs je Paket). */
	public static String run(int millisPerCase) throws Exception {
		StringBuilder out = new StringBuilder();
		out.append(String.format(Locale.ROOT, "NetBench – Java %s, %d ms je Fall%n", System.getProperty("java.version"), millisPerCase));
		byte[] key = new byte[16];
		new Random(1).nextBytes(key);

		int[] cryptSizes = {32, 1024, 16_384, 65_536};
		out.append("Entschlüsseln (µs/Paket)     Vanilla        TRS   Faktor\n");
		for (int size : cryptSizes) {
			byte[] data = new byte[size];
			new Random(size).nextBytes(data);
			final ByteBuf in = ALLOC.directBuffer(size);
			in.writeBytes(data);
			final Cipher jdk = cipher(key);
			final byte[][] heapIn = {new byte[0]};
			double vanilla = measure(millisPerCase, new Case() {
				@Override
				public void run() throws Exception {
					in.readerIndex(0);
					int n = in.readableBytes();
					if (heapIn[0].length < n) heapIn[0] = new byte[n];
					in.readBytes(heapIn[0], 0, n);
					ByteBuf dst = ALLOC.heapBuffer(jdk.getOutputSize(n));
					dst.writerIndex(jdk.update(heapIn[0], 0, n, dst.array(), dst.arrayOffset()));
					dst.release();
				}
			});
			allocVanilla = lastAlloc;
			final FastCfb8 fast = new FastCfb8(key, key);
			double trs = measure(millisPerCase, new Case() {
				@Override
				public void run() throws Exception {
					in.readerIndex(0);
					int total = in.readableBytes();
					ByteBuf dst = ALLOC.heapBuffer(total);
					byte[] target = dst.array();
					int at = dst.arrayOffset();
					byte[] h = fast.history();
					int left = total;
					while (left > 0) {
						int n = Math.min(FastCfb8.CHUNK, left);
						in.readBytes(h, 16, n);
						fast.decryptChunk(n, target, at);
						at += n;
						left -= n;
					}
					dst.writerIndex(total);
					dst.release();
				}
			});
			in.release();
			out.append(row(size + " B", vanilla, trs));
		}

		out.append("Entpacken 1.8.9–1.20.4 (µs)  Vanilla        TRS   Faktor\n");
		int[] zipSizes = {300, 2048, 16_384, 65_536, 200_000};
		for (int size : zipSizes) {
			byte[] data = NetCodecsTest.packetLike(size, size);
			byte[] z = NetCodecsTest.zlib(data);
			final int declared = size;
			final ByteBuf in = ALLOC.directBuffer(z.length);
			in.writeBytes(z);
			final Inflater vanillaInflater = new Inflater();
			double vanilla = measure(millisPerCase, new Case() {
				@Override
				public void run() throws Exception {
					in.readerIndex(0);
					byte[] compressed = new byte[in.readableBytes()];
					in.readBytes(compressed);
					vanillaInflater.setInput(compressed);
					byte[] result = new byte[declared];
					vanillaInflater.inflate(result);
					ByteBuf b = Unpooled.wrappedBuffer(result);
					vanillaInflater.reset();
					b.release();
				}
			});
			allocVanilla = lastAlloc;
			final NetCodecs.Inflate ours = new NetCodecs.Inflate();
			double trs = measure(millisPerCase, new Case() {
				@Override
				public void run() throws Exception {
					in.readerIndex(0);
					ours.inflate(ALLOC, in, declared, true).release();
				}
			});
			in.release();
			out.append(row(size + " B", vanilla, trs));
		}

		out.append("Packen (µs/Paket)            Vanilla        TRS   Faktor\n");
		for (int size : new int[]{300, 4096, 32_768}) {
			byte[] data = NetCodecsTest.packetLike(size, size + 1);
			final ByteBuf in = ALLOC.directBuffer(size);
			in.writeBytes(data);
			final Deflater vd = new Deflater();
			final byte[] encodeBuf = new byte[8192];
			double vanilla = measure(millisPerCase, new Case() {
				@Override
				public void run() {
					in.readerIndex(0);
					ByteBuf outBuf = ALLOC.directBuffer(256);
					int n = in.readableBytes();
					byte[] bs = new byte[n];
					in.readBytes(bs);
					vd.setInput(bs, 0, n);
					vd.finish();
					while (!vd.finished()) outBuf.writeBytes(encodeBuf, 0, vd.deflate(encodeBuf));
					vd.reset();
					outBuf.release();
				}
			});
			allocVanilla = lastAlloc;
			final NetCodecs.Deflate ours = new NetCodecs.Deflate();
			double trs = measure(millisPerCase, new Case() {
				@Override
				public void run() {
					in.readerIndex(0);
					ByteBuf outBuf = ALLOC.directBuffer(256);
					ours.deflate(in, outBuf);
					outBuf.release();
				}
			});
			in.release();
			out.append(row(size + " B", vanilla, trs));
		}
		return out.toString();
	}

	interface Case {
		void run() throws Exception;
	}

	static Cipher cipher(byte[] key) throws Exception {
		Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
		c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
		return c;
	}

	/** Zuletzt gemessene Allokation je Aufruf in Bytes (-1 = unbekannt). */
	static double lastAlloc = -1;

	static long allocated() {
		try {
			java.lang.management.ThreadMXBean b = java.lang.management.ManagementFactory.getThreadMXBean();
			if (b instanceof com.sun.management.ThreadMXBean) {
				return ((com.sun.management.ThreadMXBean) b).getThreadAllocatedBytes(Thread.currentThread().getId());
			}
		} catch (Throwable ignored) {
			// keine HotSpot-JVM
		}
		return -1;
	}

	/** Median von 5 Durchgängen (µs je Aufruf) nach kurzem Aufwärmen; Allokation je Aufruf in {@link #lastAlloc}. */
	static double measure(int millis, Case c) throws Exception {
		long warm = System.nanoTime() + millis * 400_000L;
		while (System.nanoTime() < warm) c.run();
		double[] runs = new double[5];
		for (int r = 0; r < runs.length; r++) {
			long end = System.nanoTime() + millis * 200_000L;
			long n = 0;
			long t0 = System.nanoTime();
			long t1;
			do {
				c.run();
				n++;
				t1 = System.nanoTime();
			} while (t1 < end);
			runs[r] = (t1 - t0) / 1000.0 / n;
		}
		Arrays.sort(runs);
		long a0 = allocated();
		for (int i = 0; i < 200; i++) c.run();
		long a1 = allocated();
		lastAlloc = a0 < 0 || a1 < 0 ? -1 : (a1 - a0) / 200.0;
		return runs[runs.length / 2];
	}

	static String row(String label, double vanilla, double trs) {
		return String.format(Locale.ROOT, "  %-26s %9.2f %10.2f   %5.2fx   alloc %9.0f -> %7.0f B%n", label, vanilla, trs,
				vanilla / trs, allocVanilla, lastAlloc);
	}

	static double allocVanilla = -1;
}

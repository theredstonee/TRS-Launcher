package dev.theredstonee.trsclient.core.hosting.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zuverlässige Schicht über einem simulierten, verlustbehafteten, umsortierenden Netz mit virtueller Uhr. */
class RudpTest {
	/** Simuliertes Netz: Verzögerung mit Streuung (sortiert um), Verlust, Verdopplung. */
	static final class Wire {
		final Random rnd;
		final double loss;
		final double dup;
		final int minDelay;
		final int jitter;
		final List<long[]> meta = new ArrayList<long[]>();
		final List<byte[]> packets = new ArrayList<byte[]>();
		long delivered;
		long dropped;

		Wire(long seed, double loss, double dup, int minDelay, int jitter) {
			this.rnd = new Random(seed);
			this.loss = loss;
			this.dup = dup;
			this.minDelay = minDelay;
			this.jitter = jitter;
		}

		void put(byte[] b, int len, long now) {
			if (rnd.nextDouble() < loss) {
				dropped++;
				return;
			}
			int copies = rnd.nextDouble() < dup ? 2 : 1;
			for (int i = 0; i < copies; i++) {
				meta.add(new long[] { now + minDelay + rnd.nextInt(jitter + 1) });
				packets.add(Arrays.copyOf(b, len));
			}
		}

		void deliver(Rudp to, long now) {
			Iterator<long[]> m = meta.iterator();
			Iterator<byte[]> p = packets.iterator();
			while (m.hasNext()) {
				long[] at = m.next();
				byte[] pkt = p.next();
				if (at[0] <= now) {
					m.remove();
					p.remove();
					delivered++;
					to.input(pkt, 0, pkt.length, now);
				}
			}
		}
	}

	static final class Collector implements Rudp.Receiver {
		final ByteArrayOutputStream got = new ByteArrayOutputStream();
		String closed;

		@Override
		public void data(byte[] b, int off, int len) {
			got.write(b, off, len);
		}

		@Override
		public void closed(String reason) {
			closed = reason;
		}
	}

	static final class Pair {
		long now = 1000;
		final Wire ab;
		final Wire ba;
		final Collector ca = new Collector();
		final Collector cb = new Collector();
		final Rudp a;
		final Rudp b;

		Pair(Wire ab, Wire ba) {
			this.ab = ab;
			this.ba = ba;
			a = new Rudp(new Rudp.Output() {
				@Override
				public void send(byte[] s, int len) {
					Pair.this.ab.put(s, len, now);
				}
			}, ca, now);
			b = new Rudp(new Rudp.Output() {
				@Override
				public void send(byte[] s, int len) {
					Pair.this.ba.put(s, len, now);
				}
			}, cb, now);
		}

		/** Simuliert bis Bedingung oder Zeitlimit (virtuelle ms). */
		long run(long maxMs, java.util.function.BooleanSupplier done) {
			long start = now;
			while (now - start < maxMs) {
				now += 1;
				ab.deliver(b, now);
				ba.deliver(a, now);
				if (now % 5 == 0) {
					a.tick(now);
					b.tick(now);
				}
				if (done.getAsBoolean()) return now - start;
			}
			return -1;
		}
	}

	static byte[] random(int n, long seed) {
		byte[] d = new byte[n];
		new Random(seed).nextBytes(d);
		return d;
	}

	/** In zufälligen Stücken schreiben (wie Minecraft-Pakete). */
	static void writeChunks(Rudp r, byte[] data, long seed) {
		Random rnd = new Random(seed);
		int off = 0;
		while (off < data.length) {
			int n = Math.min(data.length - off, 1 + rnd.nextInt(3000));
			assertTrue(r.send(data, off, n));
			off += n;
		}
	}

	@Test
	void perfectNetworkDeliversInOrder() {
		Pair p = new Pair(new Wire(1, 0, 0, 10, 0), new Wire(2, 0, 0, 10, 0));
		final byte[] data = random(300_000, 3);
		writeChunks(p.a, data, 4);
		final Collector cb = p.cb;
		assertTrue(p.run(20_000, () -> cb.got.size() == data.length) > 0);
		assertArrayEquals(data, cb.got.toByteArray());
		assertEquals(0, p.a.retransmits());
	}

	@Test
	void lossyReorderingDuplicatingNetworkBothDirections() {
		// 10 % Verlust, 3 % doppelt, 20–80 ms Laufzeit (stark umsortiert) – in beide Richtungen gleichzeitig.
		Pair p = new Pair(new Wire(11, 0.10, 0.03, 20, 60), new Wire(12, 0.10, 0.03, 20, 60));
		final byte[] ab = random(1_000_000, 13);
		final byte[] ba = random(250_000, 14);
		writeChunks(p.a, ab, 15);
		writeChunks(p.b, ba, 16);
		final Collector ca = p.ca;
		final Collector cb = p.cb;
		long took = p.run(120_000, () -> cb.got.size() == ab.length && ca.got.size() == ba.length);
		assertTrue(took > 0, "nicht fertig geworden: " + cb.got.size() + "/" + ab.length);
		assertArrayEquals(ab, cb.got.toByteArray());
		assertArrayEquals(ba, ca.got.toByteArray());
		assertTrue(p.a.retransmits() > 0);
		assertFalse(p.a.isClosed());
		// ~1 MB bei 10 % Verlust und ~100 ms RTT: deutlich unter einer Minute (virtuell).
		assertTrue(took < 60_000, "zu langsam: " + took + " ms");
	}

	@Test
	void heavyLossStillCompletes() {
		Pair p = new Pair(new Wire(21, 0.30, 0, 5, 30), new Wire(22, 0.30, 0, 5, 30));
		final byte[] data = random(120_000, 23);
		writeChunks(p.a, data, 24);
		final Collector cb = p.cb;
		assertTrue(p.run(120_000, () -> cb.got.size() == data.length) > 0);
		assertArrayEquals(data, cb.got.toByteArray());
	}

	@Test
	void finArrivesAfterAllDataAndClosesPeer() {
		Pair p = new Pair(new Wire(31, 0.15, 0, 10, 40), new Wire(32, 0.15, 0, 10, 40));
		final byte[] data = random(80_000, 33);
		writeChunks(p.a, data, 34);
		p.a.finish();
		assertFalse(p.a.send(new byte[] { 1 }, 0, 1));
		final Collector cb = p.cb;
		assertTrue(p.run(60_000, () -> cb.closed != null) > 0);
		assertArrayEquals(data, cb.got.toByteArray());
		assertEquals("closed by peer", cb.closed);
		final Rudp a = p.a;
		assertTrue(p.run(10_000, a::drained) >= 0);
	}

	@Test
	void silentPeerTimesOut() {
		Pair p = new Pair(new Wire(41, 1.0, 0, 10, 0), new Wire(42, 1.0, 0, 10, 0));
		p.a.send(new byte[100], 0, 100);
		final Collector ca = p.ca;
		long took = p.run(Rudp.DEAD_MS + 2000, () -> ca.closed != null);
		assertTrue(took >= Rudp.DEAD_MS - 10, "zu früh: " + took);
		assertEquals("timeout", ca.closed);
	}

	@Test
	void keepalivesKeepAnIdleConnectionAlive() {
		Pair p = new Pair(new Wire(51, 0.2, 0, 10, 10), new Wire(52, 0.2, 0, 10, 10));
		final Collector ca = p.ca;
		assertEquals(-1, p.run(Rudp.DEAD_MS * 2, () -> ca.closed != null));
		assertFalse(p.a.isClosed());
		assertFalse(p.b.isClosed());
	}

	@Test
	void garbageAndShortSegmentsAreIgnored() {
		Pair p = new Pair(new Wire(61, 0, 0, 1, 0), new Wire(62, 0, 0, 1, 0));
		p.b.input(new byte[] { 9, 9, 9 }, 0, 3, p.now);
		byte[] bad = new byte[40];
		bad[0] = 77;
		p.b.input(bad, 0, bad.length, p.now);
		assertEquals(0, p.cb.got.size());
		assertFalse(p.b.isClosed());
	}

	@Test
	void backlogLimitCloses() {
		Pair p = new Pair(new Wire(71, 1.0, 0, 1, 0), new Wire(72, 1.0, 0, 1, 0));
		byte[] big = new byte[8 * 1024 * 1024];
		boolean ok = true;
		for (int i = 0; i < 10 && ok; i++) ok = p.a.send(big, 0, big.length);
		assertFalse(ok);
		assertEquals("backlog", p.ca.closed);
	}

	@Test
	void sequenceDiffSurvivesWraparound() {
		assertEquals(1, Rudp.diff(Integer.MIN_VALUE, Integer.MAX_VALUE));
		assertEquals(-1, Rudp.diff(Integer.MAX_VALUE, Integer.MIN_VALUE));
	}
}

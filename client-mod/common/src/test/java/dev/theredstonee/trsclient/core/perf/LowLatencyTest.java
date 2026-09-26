package dev.theredstonee.trsclient.core.perf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowLatencyTest {
	/** GPU-Attrappe: Zäune werden erst erreicht, wenn der Test sie freigibt. */
	static class FakeGpu implements LowLatency.Gpu {
		long next = 1;
		final Set<Long> signaled = new HashSet<Long>();
		final Set<Long> live = new HashSet<Long>();
		final List<Long> awaited = new ArrayList<Long>();
		boolean supported = true;

		@Override
		public boolean supported() {
			return supported;
		}

		@Override
		public long fence() {
			long f = next++;
			live.add(f);
			return f;
		}

		@Override
		public boolean await(long fence, long timeoutNanos) {
			awaited.add(fence);
			signaled.add(fence);
			return true;
		}

		@Override
		public boolean signaled(long fence) {
			return signaled.contains(fence);
		}

		@Override
		public void delete(long fence) {
			live.remove(fence);
		}
	}

	@Test
	void balancedWaitsForThePreviousFrameMaximumForTheCurrent() {
		FakeGpu gpu = new FakeGpu();
		LowLatency ll = new LowLatency(gpu);
		ll.frameStart(LowLatency.Mode.BALANCED, false); // Zaun 1, noch nichts zum Warten
		assertTrue(gpu.awaited.isEmpty());
		ll.frameStart(LowLatency.Mode.BALANCED, false); // Zaun 2 → auf 1 warten
		assertEquals(Long.valueOf(1), gpu.awaited.get(0));
		ll.frameStart(LowLatency.Mode.MAXIMUM, false); // Zaun 3 → auf 3 warten
		assertEquals(Long.valueOf(3), gpu.awaited.get(1));
		assertTrue(gpu.live.size() <= LowLatency.RING, "Zäune werden aufgeräumt");
	}

	@Test
	void offDoesNothingUnlessMeasuring() {
		FakeGpu gpu = new FakeGpu();
		LowLatency ll = new LowLatency(gpu);
		assertEquals(0, ll.frameStart(null, false));
		assertEquals(1, gpu.next, "kein Zaun ohne Modus");
		// Messen: Zäune ohne Warten, Warteschlange wird gezählt.
		ll.frameStart(null, true);
		ll.frameStart(null, true);
		ll.frameStart(null, true);
		assertTrue(gpu.awaited.isEmpty());
		LowLatency.Stats s = ll.stats();
		assertEquals(3, s.frames);
		// Kein Zaun wurde erreicht: bei Bild 2 hing 1, bei Bild 3 hingen 2.
		assertEquals(1.0, s.avgQueued, 1e-9);
		ll.frameStart(null, false);
		assertTrue(gpu.live.isEmpty(), "Aus: alle Zäune gelöscht");
	}

	@Test
	void unsupportedOrBrokenGpuIsSkipped() {
		FakeGpu gpu = new FakeGpu();
		gpu.supported = false;
		LowLatency ll = new LowLatency(gpu);
		assertFalse(ll.available());
		assertEquals(0, ll.frameStart(LowLatency.Mode.MAXIMUM, true));
		LowLatency broken = new LowLatency(new FakeGpu() {
			@Override
			public long fence() {
				throw new IllegalStateException("kein Kontext");
			}
		});
		broken.frameStart(LowLatency.Mode.MAXIMUM, false);
		assertFalse(broken.available());
	}
}

package dev.theredstonee.trsclient.core.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingMeterTest {
	private static PingMeter.Snapshot snap(PingMeter m, long now) {
		return m.snapshot(null, now, 0);
	}

	/** Ein Messzyklus: senden (wenn fällig), Antwort nach rtt. */
	private static long cycle(PingMeter m, long now, long rtt, long interval) {
		assertTrue(m.shouldSend(now, interval, true), "fällig bei " + now);
		m.sent(now);
		assertTrue(m.pong(now, now + rtt));
		return now + interval;
	}

	@Test
	void sendsAtMostEveryIntervalAndNeverFasterThanOncePerSecond() {
		PingMeter m = new PingMeter();
		assertTrue(m.shouldSend(0, 2000, true));
		m.sent(0);
		assertFalse(m.shouldSend(100, 2000, true), "Anfrage noch offen");
		m.pong(0, 30);
		assertFalse(m.shouldSend(1500, 2000, true));
		assertTrue(m.shouldSend(2000, 2000, true));
		m.sent(2000);
		m.pong(2000, 2040);
		// Zu kleines Intervall wird auf 1 s angehoben.
		assertFalse(m.shouldSend(2500, 100, true));
		assertTrue(m.shouldSend(3000, 100, true));
		// Version ohne eigene Anfragen: nie senden.
		PingMeter old = new PingMeter();
		assertFalse(old.shouldSend(0, 2000, false));
	}

	@Test
	void averageJitterAndHistory() {
		PingMeter m = new PingMeter();
		long t = 0;
		long[] rtts = {40, 44, 40, 44, 40, 44};
		for (long r : rtts) t = cycle(m, t, r, 2000);
		PingMeter.Snapshot s = snap(m, t);
		assertEquals(PingMeter.Source.ACTIVE, s.source);
		assertEquals(44, s.current);
		assertEquals(42.0, s.average, 1e-9);
		assertEquals(4.0, s.jitter, 1e-9);
		assertEquals(40, s.min);
		assertEquals(44, s.max);
		assertEquals(6, s.historyCount);
		assertEquals(0.0, s.timeoutPercent, 1e-9);
	}

	@Test
	void timeoutsAreCountedAndShownInTheGraph() {
		PingMeter m = new PingMeter();
		long t = cycle(m, 0, 50, 2000);
		t = cycle(m, t, 50, 2000);
		assertTrue(m.shouldSend(t, 2000, true));
		m.sent(t);
		// Keine Antwort: nach 5 s verloren.
		assertFalse(m.shouldSend(t + 4999, 2000, true));
		m.shouldSend(t + PingMeter.TIMEOUT_MS, 2000, true);
		PingMeter.Snapshot s = snap(m, t + 5000);
		assertEquals(1, s.timeouts);
		assertEquals(100.0 / 3, s.timeoutPercent, 1e-9);
		assertEquals(-1, s.history[s.historyCount - 1]);
		// Späte Antwort zählt nicht mehr.
		assertFalse(m.pong(t, t + 6000));
	}

	@Test
	void serversThatNeverAnswerFallBackToTheServerValue() {
		PingMeter m = new PingMeter();
		long t = 0;
		for (int i = 0; i < PingMeter.GIVE_UP_AFTER; i++) {
			assertTrue(m.shouldSend(t, 1000, true));
			m.sent(t);
			t += PingMeter.TIMEOUT_MS;
			m.shouldSend(t, 1000, true);
		}
		assertFalse(m.shouldSend(t + 10_000, 1000, true), "keine weiteren Anfragen");
		m.server(87, t);
		PingMeter.Snapshot s = snap(m, t);
		assertEquals(PingMeter.Source.SERVER, s.source);
		assertEquals(87, s.current);
	}

	@Test
	void oldVersionsUseThePlayerListValue() {
		PingMeter m = new PingMeter();
		m.shouldSend(0, 2000, false);
		m.server(55, 0);
		m.server(56, 500);
		m.server(58, 2100);
		PingMeter.Snapshot s = snap(m, 2100);
		assertEquals(PingMeter.Source.SERVER, s.source);
		assertEquals(58, s.current);
		assertEquals(2, s.historyCount, "höchstens alle 2 s ein Verlaufspunkt");
		assertEquals(-1, s.timeoutPercent, 1e-9);
	}

	@Test
	void spikesAreDetectedAgainstTheUsualValue() {
		PingMeter m = new PingMeter();
		m.spikeThreshold(100);
		long t = 0;
		for (int i = 0; i < 8; i++) t = cycle(m, t, 40, 2000);
		assertFalse(snap(m, t).spike);
		t = cycle(m, t, 300, 2000);
		PingMeter.Snapshot s = snap(m, t - 2000 + 300);
		assertTrue(s.spike);
		assertEquals(300, s.spikeValue);
		assertFalse(snap(m, t + 5000).spike, "Hinweis verschwindet nach ein paar Sekunden");
	}

	@Test
	void tpsFromTimePackets() {
		TpsEstimator e = new TpsEstimator();
		long ns = 0;
		for (int i = 0; i <= 5; i++) {
			e.add(i * 20L, ns);
			ns += 1_000_000_000L;
		}
		assertEquals(20.0, e.tps(ns - 1_000_000_000L), 1e-6);
		// Server läuft halb so schnell: 20 Ticks brauchen 2 s.
		TpsEstimator slow = new TpsEstimator();
		ns = 0;
		for (int i = 0; i <= 5; i++) {
			slow.add(i * 20L, ns);
			ns += 2_000_000_000L;
		}
		assertEquals(10.0, slow.tps(ns - 2_000_000_000L), 1e-6);
		assertTrue(Double.isNaN(slow.tps(ns + 10_000_000_000L)), "zu alt");
		assertTrue(Double.isNaN(new TpsEstimator().tps(0)));
	}

	@Test
	void keepAliveJitterOnlyWithTimestampIds() {
		KeepAliveJitter k = new KeepAliveJitter();
		// Server schickt alle 15 s seine Uhr; Laufzeit schwankt um ±10 ms.
		long serverClock = 1_000_000;
		double[] delay = {50, 60, 50, 60, 50};
		for (int i = 0; i < delay.length; i++) {
			long id = serverClock + i * 15_000L;
			long arrivalNs = (long) ((id + 777_000 + delay[i]) * 1e6);
			k.add(id, arrivalNs);
		}
		assertEquals(10.0, k.jitter(), 1e-6);
		// Zufällige Kennungen: kein Wert.
		KeepAliveJitter r = new KeepAliveJitter();
		for (int i = 0; i < 6; i++) r.add(i % 2 == 0 ? 17 : 999_999_999L, i * 15_000_000_000L);
		assertEquals(-1, r.jitter(), 1e-9);
	}
}

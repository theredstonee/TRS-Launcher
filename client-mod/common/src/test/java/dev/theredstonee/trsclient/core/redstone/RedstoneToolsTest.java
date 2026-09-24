package dev.theredstonee.trsclient.core.redstone;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redstone-Werkzeuge: Komparator-Rechnung, Takt-Messung, Farbverlauf, Staub-Cache, Anzeige. */
class RedstoneToolsTest {
	@AfterEach
	void english() {
		I18n.use("en");
	}

	// --- Komparator ---

	@Test
	void containerSignalMatchesVanilla() {
		int[] counts = new int[27];
		int[] max = new int[27];
		java.util.Arrays.fill(max, 64);
		assertEquals(0, ComparatorMath.containerSignal(counts, max, 27), "leer = 0");
		counts[0] = 1;
		assertEquals(1, ComparatorMath.containerSignal(counts, max, 27), "ein Gegenstand = 1");
		java.util.Arrays.fill(counts, 64);
		assertEquals(15, ComparatorMath.containerSignal(counts, max, 27), "voll = 15");

		// Trichter (5 Plätze): 23 Gegenstände = 2, 46 = 3, ein voller Stapel = 3 (bekannte Tabellenwerte)
		int[] hopper = new int[5];
		int[] hopperMax = {64, 64, 64, 64, 64};
		hopper[0] = 22;
		assertEquals(1, ComparatorMath.containerSignal(hopper, hopperMax, 5));
		hopper[0] = 23;
		assertEquals(2, ComparatorMath.containerSignal(hopper, hopperMax, 5));
		hopper[0] = 46;
		assertEquals(3, ComparatorMath.containerSignal(hopper, hopperMax, 5));
		hopper[0] = 64;
		assertEquals(3, ComparatorMath.containerSignal(hopper, hopperMax, 5));

		// nicht stapelbare Gegenstände zählen wie volle Stapel
		int[] one = {1, 0, 0, 0, 0};
		int[] swordMax = {1, 64, 64, 64, 64};
		assertEquals(3, ComparatorMath.containerSignal(one, swordMax, 5));
		// Enderperlen (16er Stapel)
		int[] pearls = {16, 16, 16, 16, 16};
		int[] pearlMax = {16, 16, 16, 16, 16};
		assertEquals(15, ComparatorMath.containerSignal(pearls, pearlMax, 5));
	}

	@Test
	void comparatorComparesAndSubtracts() {
		assertEquals(12, ComparatorMath.output(false, 12, 5));
		assertEquals(7, ComparatorMath.output(true, 12, 5));
		assertEquals(0, ComparatorMath.output(false, 12, 13), "Seite stärker = aus");
		assertEquals(0, ComparatorMath.output(true, 12, 13));
		assertEquals(12, ComparatorMath.output(false, 12, 12), "gleich stark = durch");
		assertEquals(0, ComparatorMath.output(true, 12, 12));
		assertEquals(0, ComparatorMath.output(false, 0, 0));
	}

	@Test
	void comparatorOutputFromTheWorld() {
		// Komparator bei (0,0,0) schaut nach Norden: Eingang z-1, Seiten x-1 / x+1.
		FakeWorld w = new FakeWorld().comparator(0, 0, 0, Dir.NORTH, false, true).dust(0, 0, -1, 12).dust(1, 0, 0, 5);
		BlockProbe p = new BlockProbe();
		assertTrue(w.probe(0, 0, 0, p));
		ContainerMemory memory = new ContainerMemory();
		assertEquals(12, ComparatorMath.output(w, 0, 0, 0, p, memory));
		p.subtract = true;
		assertEquals(7, ComparatorMath.output(w, 0, 0, 0, p, memory));
		w.dust(-1, 0, 0, 14);
		assertEquals(0, ComparatorMath.output(w, 0, 0, 0, p, memory), "linke Seite 14 > 12");
	}

	@Test
	void comparatorReadsContainersOnlyWhenKnown() {
		FakeWorld w = new FakeWorld().comparator(0, 0, 0, Dir.NORTH, false, true).container(0, 0, -1);
		BlockProbe p = new BlockProbe();
		w.probe(0, 0, 0, p);
		ContainerMemory memory = new ContainerMemory();
		assertEquals(-1, ComparatorMath.output(w, 0, 0, 0, p, memory), "Inhalt unbekannt");
		memory.put(0, 0, -1, 9, 1);
		assertEquals(9, ComparatorMath.output(w, 0, 0, 0, p, memory));

		// durch einen festen Block hindurch (Komparator liest den Behälter dahinter)
		FakeWorld through = new FakeWorld().comparator(0, 0, 0, Dir.NORTH, false, true).solid(0, 0, -1).analog(0, 0, -2, 6);
		through.probe(0, 0, 0, p);
		assertEquals(6, ComparatorMath.output(through, 0, 0, 0, p, memory));
		// Zustandsbasierte Ausgabe direkt davor (Komposter, Kuchen …)
		FakeWorld cake = new FakeWorld().comparator(0, 0, 0, Dir.NORTH, false, true).analog(0, 0, -1, 14);
		assertEquals(14, ComparatorMath.output(cake, 0, 0, 0, p, memory));
	}

	@Test
	void comparatorChainsAreFollowed() {
		// Zwei Komparatoren hintereinander (beide nach Norden): der hintere liest Staub 10.
		FakeWorld w = new FakeWorld()
				.comparator(0, 0, 0, Dir.NORTH, false, true)
				.comparator(0, 0, -1, Dir.NORTH, true, true)
				.dust(0, 0, -2, 10)
				.dust(1, 0, -1, 3);
		BlockProbe p = new BlockProbe();
		w.probe(0, 0, 0, p);
		assertEquals(7, ComparatorMath.output(w, 0, 0, 0, p, new ContainerMemory()), "10 - 3 vom vorderen Komparator");
	}

	@Test
	void containerMemoryForgetsTheOldest() {
		ContainerMemory m = new ContainerMemory();
		for (int i = 0; i < ContainerMemory.MAX + 10; i++) m.put(i, 0, 0, i % 16, i);
		assertEquals(ContainerMemory.MAX, m.size());
		assertEquals(-1, m.get(0, 0, 0), "ältester fällt heraus");
		assertEquals((ContainerMemory.MAX + 9) % 16, m.get(ContainerMemory.MAX + 9, 0, 0));
		assertTrue(m.current(ContainerMemory.MAX + 9, 0, 0, ContainerMemory.MAX + 10));
		assertFalse(m.current(ContainerMemory.MAX + 9, 0, 0, ContainerMemory.MAX + 100));
	}

	// --- Takt ---

	@Test
	void measuresASquareWave() {
		FrequencyMeter m = new FrequencyMeter();
		// Periode 8 Spiel-Ticks (4 Redstone-Ticks), 4 Ticks an
		for (long t = 0; t < 100; t++) m.sample(t, (t % 8) < 4 ? 15 : 0);
		assertEquals(8.0, m.periodTicks(99, 100), 1e-9);
		assertEquals(2.5, m.hertz(99, 100), 1e-9);
		assertEquals(5.0, m.changesPerSecond(99, 100), 1e-9);
		assertEquals(4, m.lastPulseTicks());
		assertEquals(15, m.level(0), "t = 99: 99 % 8 = 3 < 4 → an");
		assertEquals(0, m.level(4), "t = 95: 95 % 8 = 7 → aus");
	}

	@Test
	void measuresFastAndSlowClocks() {
		FrequencyMeter fast = new FrequencyMeter();
		for (long t = 0; t < 60; t++) fast.sample(t, (t % 2) == 0 ? 15 : 0); // 2 Ticks = 10 Hz
		assertEquals(10.0, fast.hertz(59, 60), 1e-9);
		assertEquals(1, fast.lastPulseTicks());

		FrequencyMeter slow = new FrequencyMeter();
		for (long t = 0; t < 200; t++) slow.sample(t, (t % 40) < 20 ? 7 : 0); // 2 s Periode
		assertEquals(0.5, slow.hertz(199, 200), 1e-9);
		assertEquals(0.0, slow.hertz(199, 40), 1e-9, "Fenster zu kurz für zwei Flanken");
	}

	@Test
	void aStoppedClockHasNoFrequency() {
		FrequencyMeter m = new FrequencyMeter();
		for (long t = 0; t < 40; t++) m.sample(t, (t % 8) < 4 ? 15 : 0);
		for (long t = 40; t < 100; t++) m.sample(t, 0);
		assertEquals(0.0, m.hertz(99, 100), 1e-9, "seit 60 Ticks keine Flanke mehr");
		assertTrue(m.risesWithin(99, 100) > 0);
		assertEquals(0, m.risesWithin(99, 40));
	}

	@Test
	void fillsSmallGapsAndRestartsOnLargeOnes() {
		FrequencyMeter m = new FrequencyMeter();
		m.sample(0, 15);
		m.sample(3, 0); // Lücke 1..2 → 15
		assertEquals(0, m.level(0));
		assertEquals(15, m.level(1));
		assertEquals(15, m.level(2));
		assertEquals(4, m.samples());
		m.sample(3, 15); // gleicher Tick zählt nicht
		assertEquals(0, m.level(0));
		m.sample(500, 15); // große Lücke → Neustart
		assertEquals(1, m.samples());
		assertEquals(-1, m.level(1));
		m.sample(400, 0); // Rücksprung → Neustart
		assertEquals(1, m.samples());
	}

	@Test
	void historyIsARingBuffer() {
		FrequencyMeter m = new FrequencyMeter();
		for (long t = 0; t < FrequencyMeter.HISTORY * 3; t++) m.sample(t, (int) (t % 16));
		long last = FrequencyMeter.HISTORY * 3 - 1;
		for (int ago = 0; ago < FrequencyMeter.HISTORY; ago++) {
			assertEquals((int) ((last - ago) % 16), m.level(ago));
		}
		assertEquals(-1, m.level(FrequencyMeter.HISTORY));
	}

	// --- Farben ---

	@Test
	void signalColorsRunFromGreyToBrightRed() {
		int zero = SignalColors.color(0);
		int r0 = (zero >> 16) & 0xFF, g0 = (zero >> 8) & 0xFF, b0 = zero & 0xFF;
		assertEquals(r0, g0, "0 ist grau");
		assertEquals(g0, b0, "0 ist grau");
		int lastRed = -1;
		int lastBrightness = -1;
		for (int level = 1; level <= 15; level++) {
			int c = SignalColors.color(level);
			assertEquals(0xFF, c >>> 24, "deckend");
			int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
			assertTrue(r > g + 40 && r > b + 40, "Stufe " + level + " ist rot");
			assertTrue(r > lastRed, "Rot steigt mit der Stärke (" + level + ")");
			assertTrue(r + g + b > lastBrightness, "Helligkeit steigt (" + level + ")");
			lastRed = r;
			lastBrightness = r + g + b;
		}
		assertEquals(0xFF, (SignalColors.color(15) >> 16) & 0xFF, "15 = hellstes Rot");
		assertEquals(SignalColors.color(15), SignalColors.color(99), "begrenzt");
		assertEquals(SignalColors.color(0), SignalColors.color(-3));
		Set<Integer> distinct = new HashSet<Integer>();
		for (int level = 0; level <= 15; level++) distinct.add(SignalColors.color(level));
		assertEquals(16, distinct.size(), "jede Stufe hat ihre eigene Farbe");
		assertEquals("15", SignalColors.digits(15));
		assertTrue(SignalColors.digits(7) == SignalColors.digits(7), "keine neuen Strings je Frame");
	}

	// --- Cache ---

	@Test
	void cacheFindsDustWithinTheRadiusOverSeveralTicks() {
		FakeWorld w = new FakeWorld();
		for (int x = -3; x <= 3; x++) w.dust(x, 0, 2, Math.abs(x) * 2);
		w.dust(20, 0, 0, 15); // außerhalb
		SignalCache cache = new SignalCache(100, 64);
		cache.tick(w, 0, 0, 0, 4, 0.5, 1.6, 0.5, false);
		assertTrue(cache.size() < 7, "ein Tick reicht bei kleinem Budget nicht");
		for (int i = 0; i < 10; i++) cache.tick(w, 0, 0, 0, 4, 0.5, 1.6, 0.5, false);
		assertEquals(7, cache.size());
		assertTrue(cache.passComplete());
		for (SignalCache.Entry e : cache.entries()) {
			assertEquals(Math.abs(e.x) * 2, e.power);
			assertTrue(e.visible);
		}
	}

	@Test
	void cacheFollowsPowerChangesAndRemovals() {
		FakeWorld w = new FakeWorld().dust(1, 0, 1, 3).dust(2, 0, 1, 2);
		SignalCache cache = new SignalCache(10000, 64);
		cache.tick(w, 0, 0, 0, 4, 0.5, 1.6, 0.5, false);
		assertEquals(2, cache.size());
		w.dust(1, 0, 1, 15);
		w.dust.remove(FakeWorld.key(2, 0, 1));
		cache.tick(w, 0, 0, 0, 4, 0.5, 1.6, 0.5, false);
		assertEquals(1, cache.size());
		assertEquals(15, cache.entries().get(0).power);
		// Spieler läuft weg → Staub fällt aus dem Radius
		cache.tick(w, 30, 0, 0, 4, 30.5, 1.6, 0.5, false);
		assertEquals(0, cache.size());
	}

	@Test
	void cacheReadsOnlyItsBudgetPerTick() {
		FakeWorld w = new FakeWorld();
		SignalCache cache = new SignalCache(500, 0);
		cache.tick(w, 0, 0, 0, 16, 0.5, 1.6, 0.5, false);
		assertEquals(500, w.dustReads, "Radius 16 = 35 937 Blöcke, aber nur 500 je Tick");
	}

	@Test
	void cacheHidesDustBehindWalls() {
		FakeWorld w = new FakeWorld().dust(0, 0, 5, 9).dust(2, 0, 0, 4);
		for (int x = -2; x <= 2; x++) for (int y = -1; y <= 3; y++) w.solid(x, y, 3);
		SignalCache cache = new SignalCache(10000, 64);
		cache.tick(w, 0, 1, 0, 8, 0.5, 1.6, 0.5, true);
		assertEquals(2, cache.size());
		for (SignalCache.Entry e : cache.entries()) {
			if (e.z == 5) assertFalse(e.visible, "hinter der Wand");
			else assertTrue(e.visible, "frei sichtbar");
		}
		cache.tick(w, 0, 1, 0, 8, 0.5, 1.6, 0.5, false);
		for (SignalCache.Entry e : cache.entries()) assertTrue(e.visible, "Sichtprüfung aus");
	}

	@Test
	void cacheClampsTheRadiusAndClears() {
		FakeWorld w = new FakeWorld().dust(0, 0, 18, 1).dust(0, 0, 3, 1);
		SignalCache cache = new SignalCache(100000, 0);
		cache.tick(w, 0, 0, 0, 99, 0.5, 1.6, 0.5, false);
		assertEquals(1, cache.size(), "höchstens Radius 16");
		cache.tick(w, 0, 0, 0, 1, 0.5, 1.6, 0.5, false);
		assertEquals(1, cache.size(), "mindestens Radius 4");
		cache.clear();
		assertEquals(0, cache.size());
	}

	@Test
	void lineOfSight() {
		FakeWorld w = new FakeWorld().solid(0, 0, 2);
		assertFalse(LineOfSight.clear(w, 0.5, 0.5, 0.5, 0.5, 0.5, 4.5));
		assertTrue(LineOfSight.clear(w, 0.5, 0.5, 0.5, 2.5, 0.5, 4.5));
		assertTrue(LineOfSight.clear(w, 0.5, 0.5, 0.5, 0.5, 0.5, 2.5), "Zielblock selbst zählt nicht");
		assertTrue(LineOfSight.clear(w, -3.5, 5.5, -3.5, -3.2, 5.2, -3.1), "gleicher Block");
		assertTrue(LineOfSight.clear(w, 0.5, 0.5, -0.5, 0.5, 0.5, -6.5), "negative Richtung frei");
		w.solid(0, 0, -4);
		assertFalse(LineOfSight.clear(w, 0.5, 0.5, -0.5, 0.5, 0.5, -6.5), "negative Richtung verdeckt");
	}

	// --- Anzeige ---

	@Test
	void readoutDescribesComponents() {
		I18n.use("en");
		RedstoneReadout r = new RedstoneReadout();
		BlockProbe p = new BlockProbe();
		p.kind = RedstoneKind.REPEATER;
		p.delay = 3;
		p.locked = true;
		p.powered(true);
		r.describe("Redstone Repeater", p, -1, -1, false);
		assertTrue(r.valid);
		assertEquals(15, r.signal);
		assertEquals("Delay: 3 ticks", r.details.get(0));
		assertEquals("Locked", r.details.get(1));

		p.clear();
		p.kind = RedstoneKind.COMPARATOR;
		p.subtract = true;
		p.powered(true);
		r.describe("Redstone Comparator", p, 6, -1, false);
		assertEquals(6, r.signal);
		assertEquals("Output", r.signalLabel);
		assertEquals("Mode: Subtract", r.details.get(0));

		p.clear();
		p.kind = RedstoneKind.PISTON;
		p.extended = true;
		p.received = 13;
		r.describe("Piston", p, -1, -1, false);
		assertEquals(13, r.signal);
		assertEquals("Input", r.signalLabel);
		assertEquals("Extended", r.details.get(0));

		p.clear();
		p.kind = RedstoneKind.CONTAINER;
		p.container = true;
		r.describe("Chest", p, -1, -1, false);
		assertFalse(r.valid, "Truhe mit unbekanntem Inhalt wird nicht gezeigt");
		r.describe("Chest", p, -1, 4, false);
		assertTrue(r.valid);
		assertEquals(4, r.signal);
		assertEquals("As last opened", r.details.get(0));
		r.describe("Chest", p, -1, 4, true);
		assertTrue(r.details.isEmpty());

		p.clear();
		p.kind = RedstoneKind.CONSUMER;
		p.container = true;
		p.powered(false);
		p.received = 0;
		r.describe("Hopper", p, -1, 5, true);
		assertEquals("Not powered", r.details.get(0));
		assertEquals("Comparator: 5", r.details.get(1));

		I18n.use("de");
		p.clear();
		p.kind = RedstoneKind.REPEATER;
		p.delay = 1;
		r.describe("Verstärker", p, -1, -1, false);
		assertEquals("Verzögerung: 1 Tick", r.details.get(0));
		assertEquals(0, r.signal);
	}

	@Test
	void toolsMeasureTheComponentYouLookedAt() {
		TrsModules modules = new TrsModules();
		modules.redstoneOverlay.setEnabled(true);
		RedstoneTools tools = new RedstoneTools(modules);
		FakeWorld w = new FakeWorld().dust(0, 0, 0, 0);
		Object level = new Object();
		for (int t = 0; t < 60; t++) {
			w.dust(0, 0, 0, (t % 6) < 3 ? 15 : 0);
			boolean look = t < 5; // danach schaut der Spieler weg
			tools.tick(w, level, look, 0, 0, 0, 0.5, 1.6, 3.5);
		}
		assertTrue(tools.clockVisible(), "misst nach dem Wegschauen weiter");
		assertEquals("DUST", tools.clockName());
		assertEquals("3.33 Hz", tools.clockLines().get(0));
		assertEquals("Period: 3 rt (6 gt)", tools.clockLines().get(1));
		assertEquals("On for 1.5 rt", tools.clockLines().get(2));
		assertFalse(tools.readout().valid, "nichts angeschaut");
		assertEquals(1, tools.cache().size(), "Overlay-Cache läuft");

		modules.redstoneClockKeep.set(false);
		tools.tick(w, level, false, 0, 0, 0, 0.5, 1.6, 3.5);
		assertFalse(tools.clockVisible(), "ohne 'weitermessen' endet die Messung");

		tools.tick(w, level, true, 0, 0, 0, 0.5, 1.6, 3.5);
		assertTrue(tools.readout().valid);
		assertEquals("DUST", tools.readout().name);
		tools.tick(w, new Object(), true, 0, 0, 0, 0.5, 1.6, 3.5);
		assertEquals(1, tools.meter().samples(), "Weltwechsel beginnt neu");
		modules.redstoneOverlay.setEnabled(false);
		tools.tick(w, level, false, 0, 0, 0, 0.5, 1.6, 3.5);
		assertEquals(0, tools.cache().size(), "Overlay aus = Cache leer");
	}

	@Test
	void ticksAreFormattedAsRedstoneTicks() {
		I18n.use("en");
		assertEquals("4", RedstoneTools.ticks(4.0));
		assertEquals("1.5", RedstoneTools.ticks(1.5));
		assertNotEquals("1.50", RedstoneTools.ticks(1.5));
		I18n.use("de");
		assertEquals("1,5", RedstoneTools.ticks(1.5));
	}
}

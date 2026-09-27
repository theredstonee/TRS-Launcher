package dev.theredstonee.trsclient.core.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Koordinaten im Chat erkennen (nur lokal, „Als Wegpunkt speichern“). */
class ChatCoordsTest {
	private static int[] one(String text) {
		List<ChatCoords.Hit> hits = ChatCoords.find(text);
		assertEquals(1, hits.size(), "Treffer in: " + text);
		ChatCoords.Hit h = hits.get(0);
		return new int[]{h.x, h.y, h.z};
	}

	private static void none(String text) {
		assertTrue(ChatCoords.find(text).isEmpty(), "kein Treffer erwartet in: " + text);
	}

	@Test
	void labeledForms() {
		assertArrayEquals(new int[]{100, 64, -20}, one("Base bei x: 100 y: 64 z: -20!"));
		assertArrayEquals(new int[]{100, 64, -20}, one("X=100, Y=64, Z=-20"));
		assertArrayEquals(new int[]{-5, 70, 3}, one("x -5 y 70 z 3"));
		assertArrayEquals(new int[]{1, 2, 3}, one("komm zu x:1 y:2 z:3"));
		assertArrayEquals(new int[]{12, -60, 7}, one("X: 12 / Y: -60 / Z: 7"));
	}

	@Test
	void plainTriples() {
		assertArrayEquals(new int[]{100, 64, -20}, one("100 64 -20"));
		assertArrayEquals(new int[]{100, 64, -20}, one("treffen wir uns bei 100, 64, -20 ok?"));
		assertArrayEquals(new int[]{100, 64, -20}, one("100/64/-20"));
		assertArrayEquals(new int[]{-1234567, 80, 29999999}, one("weit weg: -1234567 80 29999999"));
		assertArrayEquals(new int[]{10, 20, 30}, one("10 20 30"));
		assertArrayEquals(new int[]{0, 64, 0}, one("Spawn ist 0 64 0"));
	}

	@Test
	void rejectsVersionsTimesDatesAndScores() {
		none("Wir spielen auf 1.21.11 und 1.8.9");
		none("Um 12:30:45 geht's los");
		none("am 5/10/2026 um acht");
		none("Punkte: 3 2 1");
		none("IP 192.168.0.1");
		none("1, 2 3");
		none("01 64 20");
		none("x: 100 y: 64");
		none("123456789 64 20");
		none("preis 100 64 20% rabatt");
		none("100 64 -20.5");
		none("");
		none(null);
	}

	@Test
	void respectsWorldLimits() {
		none("30000001 64 0");
		none("0 5000 0");
		none("0 -2049 0");
		assertArrayEquals(new int[]{0, 4096, 0}, one("0 4096 0"));
		assertArrayEquals(new int[]{0, -2048, 0}, one("x: 0 y: -2048 z: 0"));
	}

	@Test
	void findsSeveralWithoutOverlap() {
		List<ChatCoords.Hit> hits = ChatCoords.find("A: x: 1 y: 2 z: 3, B: 100 64 -20 und C: 5/60/7");
		assertEquals(3, hits.size());
		assertEquals(1, hits.get(0).x);
		assertEquals(100, hits.get(1).x);
		assertEquals(5, hits.get(2).x);
		assertTrue(hits.get(0).end <= hits.get(1).start);
		String text = "B: 100 64 -20";
		ChatCoords.Hit h = ChatCoords.find(text).get(0);
		assertEquals("100 64 -20", text.substring(h.start, h.end));
	}

	@Test
	void insertionMarkerRoundTrip() {
		assertEquals("100 64 -20", ChatCoords.insertion(100, 64, -20));
		assertArrayEquals(new int[]{100, 64, -20}, ChatCoords.parseInsertion("100 64 -20"));
		assertNull(ChatCoords.parseInsertion("Theredstonee"));
		assertNull(ChatCoords.parseInsertion("/tp 1 2 3"));
		assertNull(ChatCoords.parseInsertion("1 9999 3"));
		assertNull(ChatCoords.parseInsertion(null));
	}

	@Test
	void longLinesAreSkipped() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 300; i++) b.append("wort ");
		b.append("100 64 -20");
		none(b.toString());
	}
}

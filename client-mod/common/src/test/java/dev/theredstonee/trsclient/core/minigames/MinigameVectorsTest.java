package dev.theredstonee.trsclient.core.minigames;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zentrale Abnahme der Minispiele: Alle Testpartien aus dem Launcher ({@code tests/fixtures/minigames-vectors.json}, hier
 * als Kopie) müssen auf den Takt genau dasselbe Ergebnis liefern wie die TypeScript-Fassung.
 */
class MinigameVectorsTest {
	private static JsonArray vectors() throws IOException {
		try (InputStream in = MinigameVectorsTest.class.getResourceAsStream("/minigames/minigames-vectors.json")) {
			assertNotNull(in, "Testpartien fehlen");
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return new JsonParser().parse(reader).getAsJsonArray();
			}
		}
	}

	private static int[] ints(JsonArray array) {
		int[] out = new int[array.size()];
		for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsInt();
		return out;
	}

	@Test
	void everyRecordedRunGivesTheSameResult() throws IOException {
		JsonArray all = vectors();
		assertTrue(all.size() >= 28, "zu wenige Testpartien: " + all.size());
		for (int i = 0; i < all.size(); i++) {
			JsonElement element = all.get(i);
			JsonObject v = element.getAsJsonObject();
			String game = v.get("game").getAsString();
			long seed = v.get("seed").getAsLong();
			int[] inputs = ints(v.getAsJsonArray("inputs"));
			String name = "Partie " + i + " " + game + " Startwert " + seed;
			assertTrue(Replay.validInputs(game, inputs), name + ": ungültige Eingaben");
			Replay.Result result = Replay.replay(game, seed, inputs);
			assertTrue(result.finished, name + ": nicht beendet");
			assertEquals(v.get("score").getAsLong(), result.score, name + ": score");
			assertEquals(v.get("ticks").getAsInt(), result.ticks, name + ": ticks");
			assertEquals(v.get("hash").getAsLong(), result.hash, name + ": hash");
			assertArrayEquals(inputs, result.inputs, name + ": aufgezeichnete Eingaben");
		}
	}

	/** Reihen dürfen in beliebiger Reihenfolge abgefragt werden, das Bild schaut voraus. */
	@Test
	void lorenRowsDoNotDependOnTheOrderOfQueries() {
		Loren forward = new Loren(0xFEDCBA98L);
		for (int i = 0; i < 400; i++) forward.row(i);
		Loren backward = new Loren(0xFEDCBA98L);
		for (int i = 399; i >= 0; i--) backward.row(i);
		for (int i = 0; i < 400; i++) {
			assertArrayEquals(forward.row(i).o, backward.row(i).o, "Hindernisse Reihe " + i);
			assertArrayEquals(forward.row(i).item, backward.row(i).item, "Gegenstände Reihe " + i);
			assertArrayEquals(forward.row(i).pow, backward.row(i).pow, "Antrieb Reihe " + i);
		}
	}

	@Test
	void inputListsAreChecked() {
		assertTrue(Replay.validInputs(Replay.STAPLER, new int[] {0, 255}));
		assertTrue(Replay.validInputs(Replay.LOREN, new int[] {12, 31}));
		assertEquals(false, Replay.validInputs(Replay.LOREN, new int[] {12, 32}));
		assertEquals(false, Replay.validInputs(Replay.STAPLER, new int[] {-1, 1}));
		assertEquals(false, Replay.validInputs(Replay.STAPLER, new int[] {1}));
	}
}

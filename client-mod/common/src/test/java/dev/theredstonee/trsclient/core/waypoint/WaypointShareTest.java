package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.social.Chat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wegpunkte teilen: Weltkennung, Dimensionen, Karten bauen, Welt vergleichen, übernehmen. */
class WaypointShareTest {
	@Test
	void worldIdIsFirst16HexOfSha256() throws Exception {
		byte[] d = MessageDigest.getInstance("SHA-256").digest("sp:Neue Welt".getBytes(StandardCharsets.UTF_8));
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i] & 0xFF));
		assertEquals(b.toString(), WaypointShare.worldId("sp:Neue Welt"));
		assertEquals(16, WaypointShare.worldId("sp:x").length());
		assertTrue(WaypointShare.worldId("sp:x").matches("[0-9a-f]{16}"));
	}

	@Test
	void serverAddressesCompareWithoutDefaultPort() {
		assertEquals("play.example.net", WaypointShare.normalizeServer("Play.Example.NET:25565"));
		assertEquals("play.example.net:25566", WaypointShare.normalizeServer("play.example.net:25566"));
		assertEquals("a.b", WaypointShare.normalizeServer("a.b."));
		assertEquals("", WaypointShare.normalizeServer(null));
	}

	@Test
	void dimensionsMapBetweenLegacyAndNamespaced() {
		assertEquals("minecraft:overworld", WaypointShare.canonicalDimension("dim0"));
		assertEquals("minecraft:the_nether", WaypointShare.canonicalDimension("dim-1"));
		assertEquals("minecraft:the_end", WaypointShare.canonicalDimension("dim1"));
		assertEquals("legacy:dim7", WaypointShare.canonicalDimension("dim7"));
		assertEquals("minecraft:overworld", WaypointShare.canonicalDimension("minecraft:overworld"));
		assertEquals("minecraft:overworld", WaypointShare.canonicalDimension("DimensionType{minecraft:overworld}"));
		assertEquals("mymod:sky/world", WaypointShare.canonicalDimension("mymod:sky/world"));
		assertNull(WaypointShare.canonicalDimension(""));
		assertNull(WaypointShare.canonicalDimension(null));
		assertEquals("dim-1", WaypointShare.localDimension("minecraft:the_nether", "dim0"));
		assertEquals("dim7", WaypointShare.localDimension("legacy:dim7", "dim0"));
		assertEquals("minecraft:the_end", WaypointShare.localDimension("minecraft:the_end", "minecraft:overworld"));
		assertEquals("mymod:x", WaypointShare.localDimension("mymod:x", "dim0"));
	}

	@Test
	void cardsCarryServerAddressOrOnlyAWorldId() {
		Chat.Waypoint s = WaypointShare.card("Base", 100, 64, -20, "minecraft:overworld", "mp:play.example.net:25565", 0xE0281E);
		assertNotNull(s);
		assertEquals("play.example.net", s.address);
		assertNull(s.worldId);
		Chat.Waypoint w = WaypointShare.card("Haus", 1, 2, 3, "dim-1", "sp:Meine Welt", -1);
		assertNotNull(w);
		assertNull(w.address);
		assertEquals(WaypointShare.worldId("sp:Meine Welt"), w.worldId);
		assertEquals("minecraft:the_nether", w.dimension);
		assertEquals(-1, w.color);
		// Kein Weltname in der Karte.
		assertTrue(!w.worldId.contains("Meine"));
		assertNull(WaypointShare.card("X", 1, 2, 3, "minecraft:overworld", "", 0));
		assertNull(WaypointShare.card("X", 1, 2, 3, "", "sp:a", 0));
		assertNull(WaypointShare.card("X", 40_000_000, 2, 3, "minecraft:overworld", "sp:a", 0));
	}

	@Test
	void matchChecksWorldThenDimension() {
		Chat.Waypoint s = WaypointShare.card("Base", 1, 2, 3, "minecraft:overworld", "mp:play.example.net", 0);
		assertEquals(WaypointShare.Result.OK, WaypointShare.match(s, "mp:play.example.net:25565", "minecraft:overworld"));
		assertEquals(WaypointShare.Result.OTHER_DIMENSION, WaypointShare.match(s, "mp:play.example.net", "minecraft:the_nether"));
		assertEquals(WaypointShare.Result.OTHER_SERVER, WaypointShare.match(s, "mp:other.net", "minecraft:overworld"));
		assertEquals(WaypointShare.Result.OTHER_SERVER, WaypointShare.match(s, "sp:Welt", "minecraft:overworld"));
		assertEquals(WaypointShare.Result.NO_WORLD, WaypointShare.match(s, "", "minecraft:overworld"));
		Chat.Waypoint w = WaypointShare.card("Haus", 1, 2, 3, "dim0", "sp:Welt", 0);
		assertEquals(WaypointShare.Result.OK, WaypointShare.match(w, "sp:Welt", "dim0"));
		assertEquals(WaypointShare.Result.OK, WaypointShare.match(w, "sp:Welt", "minecraft:overworld"));
		assertEquals(WaypointShare.Result.OTHER_WORLD, WaypointShare.match(w, "sp:Andere", "dim0"));
		assertEquals(WaypointShare.Result.OTHER_WORLD, WaypointShare.match(w, "mp:a.b", "dim0"));
	}

	@Test
	void adoptAddsOnceInLocalDimensionSpelling(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("wp.json"));
		Chat.Waypoint w = WaypointShare.card("Portal", 10, 70, -5, "minecraft:the_nether", "sp:Welt", 0x3DDC84);
		assertEquals(WaypointShare.Result.ADDED, WaypointShare.adopt(store, "sp:Welt", "dim0", w));
		assertEquals(1, store.all("sp:Welt").size());
		Waypoint p = store.all("sp:Welt").get(0);
		assertEquals("Portal", p.name);
		assertEquals("dim-1", p.dimension);
		assertEquals(0x3DDC84, p.color);
		assertEquals(WaypointShare.Result.ALREADY, WaypointShare.adopt(store, "sp:Welt", "dim0", w));
		assertEquals(1, store.all("sp:Welt").size());
		assertEquals(WaypointShare.Result.OTHER_WORLD, WaypointShare.adopt(store, "sp:Andere", "dim0", w));
		assertEquals(0, store.all("sp:Andere").size());
	}
}

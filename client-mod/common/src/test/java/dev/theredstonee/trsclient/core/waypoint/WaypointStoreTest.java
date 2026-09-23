package dev.theredstonee.trsclient.core.waypoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointStoreTest {
	@Test
	void savesAndLoadsPerWorld(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("trsclient-waypoints.json");
		WaypointStore store = new WaypointStore(file);
		store.load();
		String world = WaypointStore.serverKey("Play.Example.NET");
		assertEquals("mp:play.example.net", world);
		store.add(world, new Waypoint("Basis", 10, 64, -20, "minecraft:overworld", 0x3DDC84));
		store.add(world, new Waypoint("Nether-Tor", 1, 30, 2, "minecraft:the_nether", 0xE0281E));
		assertTrue(store.dirty());
		store.saveIfDirty();
		assertFalse(store.dirty());

		WaypointStore again = new WaypointStore(file);
		again.load();
		assertEquals(2, again.all(world).size());
		List<Waypoint> overworld = again.visible(world, "minecraft:overworld");
		assertEquals(1, overworld.size());
		assertEquals("Basis", overworld.get(0).name);
	}

	@Test
	void deathWaypointReplacesPrevious(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("w.json"));
		String world = WaypointStore.singleplayerKey("Testwelt");
		store.add(world, new Waypoint("Basis", 0, 64, 0, "minecraft:overworld", 0xFFFFFF));
		store.setDeath(world, 5, 60, 5, "minecraft:overworld", 0xE0281E);
		store.setDeath(world, 9, 61, 9, "minecraft:overworld", 0xE0281E);
		int deaths = 0;
		for (Waypoint w : store.all(world)) {
			if (w.death) {
				deaths++;
				assertEquals(9, w.x);
			}
		}
		assertEquals(1, deaths);
		assertEquals(2, store.all(world).size());
	}

	@Test
	void brokenFileFallsBackToEmpty(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("kaputt.json");
		Files.write(file, "{ kein json".getBytes(StandardCharsets.UTF_8));
		WaypointStore store = new WaypointStore(file);
		store.load();
		assertEquals(0, store.worldCount());
	}

	@Test
	void limitsEntriesAndNormalizes(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("w.json"));
		String world = WaypointStore.singleplayerKey("x");
		for (int i = 0; i < WaypointStore.MAX_PER_WORLD + 5; i++) {
			store.add(world, new Waypoint("P" + i, i, 64, 0, "", 0xFFFFFF));
		}
		assertEquals(WaypointStore.MAX_PER_WORLD, store.all(world).size());
		assertEquals("P5", store.all(world).get(0).name, "die ältesten fallen raus");

		Waypoint w = new Waypoint("  ", 0, 0, 0, null, 0x1FFFFFF);
		w.normalized();
		assertEquals("Waypoint", w.name);
		assertEquals(0xFFFFFF, w.color);
		assertTrue(w.inDimension("minecraft:overworld"), "ohne Dimension gilt überall");
	}

	@Test
	void removeAndDistance(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("w.json"));
		String world = WaypointStore.singleplayerKey("x");
		Waypoint w = store.add(world, new Waypoint("A", 3, 64, 4, "", 0xFFFFFF));
		assertEquals(5.0, w.distanceTo(3.5, 64.5, -0.5), 1e-9);
		assertTrue(store.remove(world, w));
		assertFalse(store.remove(world, w));
		assertEquals(0, store.all(world).size());
	}
}

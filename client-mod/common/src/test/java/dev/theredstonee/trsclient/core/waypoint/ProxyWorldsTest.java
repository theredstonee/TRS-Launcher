package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.map.MapDisk;
import dev.theredstonee.trsclient.core.waypoint.live.LiveWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProxyWorldsTest {
	@Test
	void identityAndPersistence(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("proxy.json");
		ProxyWorlds worlds = ProxyWorlds.open(file);
		assertEquals(WaypointStore.serverKey("Proxy.Example"), worlds.resolve("Proxy.Example", null));
		worlds.select("Proxy.Example", "Survival");
		String manual = worlds.resolve("proxy.example", null);
		assertTrue(manual.matches("proxy:[0-9a-f]{64}"));
		assertEquals(manual, worlds.resolve(" PROXY.EXAMPLE ", null));
		assertNotEquals(manual, worlds.resolve("other.example", "Survival"));
		String automatic = worlds.resolve("proxy.example", "Survival");
		assertNotEquals(manual, automatic);
		assertNotEquals(automatic, worlds.resolve("proxy.example", "Creative"));
		assertNotEquals(automatic, worlds.resolve("other.example", "Survival"));
		assertEquals(manual, worlds.resolve("proxy.example", "\u0000"));
		assertTrue(worlds.resolve("proxy.example", new String(new char[128]).replace('\0', 'a')).startsWith("proxy:"));
		ProxyWorlds reloaded = ProxyWorlds.open(file);
		assertEquals(manual, reloaded.resolve("proxy.example", null));
		assertEquals("Survival", reloaded.selected("proxy.example"));
		reloaded.select("proxy.example", "Creative");
		assertNotEquals(manual, reloaded.resolve("proxy.example", null));
		reloaded.select("proxy.example", null);
		assertEquals("mp:proxy.example", reloaded.resolve("proxy.example", null));
		assertEquals(2, ProxyWorlds.open(file).profiles("proxy.example").size());
	}

	@Test
	void brokenFileIsNeverOverwritten(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("proxy.json");
		byte[] broken = "{ broken".getBytes(StandardCharsets.UTF_8);
		Files.write(file, broken);
		ProxyWorlds worlds = ProxyWorlds.open(file);
		assertFalse(worlds.writable());
		assertEquals("mp:proxy", worlds.resolve("proxy", null));
		assertThrows(IOException.class, () -> worlds.select("proxy", "world"));
		assertArrayEquals(broken, Files.readAllBytes(file));
	}

	@Test
	void resetRetainsOldMapAndWaypointsAndSharingDoesNotTreatProfilesAsServers(@TempDir Path dir) throws IOException {
		ProxyWorlds worlds = ProxyWorlds.open(dir.resolve("proxy.json"));
		WaypointStore store = new WaypointStore(dir.resolve("waypoints.json"));
		String old = worlds.resolve("play.example.net", null);
		store.add(old, new Waypoint("Old", 1, 2, 3, "minecraft:overworld", 0));
		worlds.select("play.example.net", "Survival");
		String profile = worlds.resolve("play.example.net", null);
		store.add(profile, new Waypoint("New", 1, 2, 3, "minecraft:overworld", 0));
		assertNotEquals(MapDisk.safeName(old), MapDisk.safeName(profile));
		assertNull(WaypointShare.card("New", 1, 2, 3, "minecraft:overworld", profile, 0));
		assertNull(LiveWorld.fromStoreKey(profile));
		worlds.select("play.example.net", null);
		assertEquals(old, worlds.resolve("play.example.net", null));
		assertEquals("Old", store.all(old).get(0).name);
		assertEquals("New", store.all(profile).get(0).name);
	}

	@Test
	void rejectsInvalidNamesAndBounds(@TempDir Path dir) throws IOException {
		ProxyWorlds worlds = ProxyWorlds.open(dir.resolve("proxy.json"));
		assertThrows(IllegalArgumentException.class, () -> worlds.select("proxy", " "));
		assertThrows(IllegalArgumentException.class, () -> worlds.select("proxy", "\u00a7a"));
		for (int index = 0; index < ProxyWorlds.MAX_PROFILES; index++) worlds.select("proxy", "profile" + index);
		assertThrows(IllegalArgumentException.class, () -> worlds.select("proxy", "overflow"));
		assertEquals(ProxyWorlds.MAX_PROFILES, ProxyWorlds.open(dir.resolve("proxy.json")).profiles("proxy").size());
	}
}
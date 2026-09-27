package dev.theredstonee.trsclient.core.keys;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Suche in der Tastenbelegung: Zerlegen der Eingabe, Tastennamen, Maus, Mods, Konflikte, unbelegt. */
class KeySearchTest {
	private static KeySearch.Entry e(String name, String nameKey, String cat, String catKey, String code, String label) {
		return new KeySearch.Entry(name, nameKey, cat, catKey, code, label, null);
	}

	private static List<KeySearch.Entry> sample() {
		return new ArrayList<KeySearch.Entry>(Arrays.asList(
				e("Springen", "key.jump", "Bewegung", "key.categories.movement", "key.keyboard.space", "Leertaste"),
				e("Schleichen", "key.sneak", "Bewegung", "key.categories.movement", "key.keyboard.left.shift", "Linke Umschalttaste"),
				e("Schnellleiste 2", "key.hotbar.2", "Schnellleiste", "key.categories.inventory", "key.keyboard.2", "2"),
				e("Zoom", "key.trsclient.zoom", "TRS Client", "key.categories.trsclient", "key.keyboard.c", "C"),
				e("Sodium-Menü", "key.sodium.menu", "Sodium", "sodium", "key.keyboard.2", "2"),
				e("Angreifen", "key.attack", "Spielsteuerung", "key.categories.gameplay", "key.mouse.left", "Linke Maustaste"),
				e("Emote-Rad", "key.trsclient.emotes", "TRS Client", "key.categories.trsclient", "key.mouse.4", "Maustaste 4"),
				e("Panorama", "key.trsclient.panorama", "TRS Client", "key.categories.trsclient", "key.keyboard.unknown", "Nicht belegt")));
	}

	private static List<String> names(String query) {
		List<String> out = new ArrayList<String>();
		for (KeySearch.Entry x : KeySearch.filter(sample(), query)) out.add(x.name);
		return out;
	}

	@Test
	void emptyQueryShowsAll() {
		assertEquals(8, names("").size());
		assertEquals(8, names("   ").size());
	}

	@Test
	void freeTextSearchesNameCategoryAndKey() {
		assertEquals(Arrays.asList("Springen", "Schleichen"), names("bewegung"));
		assertEquals(Arrays.asList("Zoom"), names("zoom"));
		assertEquals(Arrays.asList("Springen"), names("leer"));
		assertEquals(Arrays.asList("Zoom", "Emote-Rad", "Panorama"), names("trs"));
	}

	@Test
	void keyFilterMatchesCodeAndLabel() {
		assertEquals(Arrays.asList("Schnellleiste 2", "Sodium-Menü"), names("key:2"));
		assertEquals(Arrays.asList("Zoom"), names("key:C"));
		assertEquals(Arrays.asList("Springen"), names("key:space"));
		assertEquals(Arrays.asList("Springen"), names("taste:leertaste"));
		assertEquals(Arrays.asList("Schleichen"), names("key:leftshift"));
		assertEquals(Arrays.asList("Schleichen"), names("key:left.shift"));
	}

	@Test
	void mouseButtons() {
		assertEquals(Arrays.asList("Angreifen", "Emote-Rad"), names("mouse"));
		assertEquals(Arrays.asList("Emote-Rad"), names("key:maus4"));
		assertEquals(Arrays.asList("Emote-Rad"), names("key:mouse4"));
		assertEquals(Arrays.asList("Emote-Rad"), names("key:m4"));
		assertEquals(Arrays.asList("Angreifen"), names("key:mouse1"));
		assertEquals(Arrays.asList("Angreifen", "Emote-Rad"), names("key:maus"));
	}

	@Test
	void modConflictAndUnbound() {
		assertEquals(Arrays.asList("Sodium-Menü"), names("mod:sodium"));
		assertEquals(Arrays.asList("Zoom", "Emote-Rad", "Panorama"), names("mod:trsclient"));
		assertEquals(Arrays.asList("Schnellleiste 2", "Sodium-Menü"), names("conflict"));
		assertEquals(Arrays.asList("Schnellleiste 2", "Sodium-Menü"), names("konflikt"));
		assertEquals(Arrays.asList("Panorama"), names("unbound"));
		assertEquals(Arrays.asList("Panorama"), names("unbelegt"));
		// Unbelegte Aktionen sind nie ein Konflikt, auch wenn mehrere unbelegt sind.
		List<KeySearch.Entry> all = sample();
		all.add(e("Freelook", "key.trsclient.freelook", "TRS Client", "key.categories.trsclient", "key.keyboard.unknown", ""));
		KeySearch.markConflicts(all);
		assertFalse(all.get(all.size() - 1).conflict());
	}

	@Test
	void debugCombosAreNoConflicts() {
		List<KeySearch.Entry> all = new ArrayList<KeySearch.Entry>(Arrays.asList(
				e("Links", "key.left", "Bewegung", "key.categories.movement", "key.keyboard.a", "A"),
				e("Chunks neu laden", "key.debug.reloadChunk", "Debug", "minecraft:debug", "key.keyboard.a", "A"),
				e("Emote-Rad", "key.trsclient.emoteWheel", "TRS Client", "key.categories.trsclient", "key.keyboard.g", "G"),
				e("Schnellaktionen", "key.quickActions", "Verschiedenes", "key.categories.misc", "key.keyboard.g", "G")));
		KeySearch.markConflicts(all);
		assertFalse(all.get(0).conflict());
		assertFalse(all.get(1).conflict());
		assertTrue(all.get(2).conflict());
		assertTrue(all.get(3).conflict());
	}

	@Test
	void combinesFilters() {
		assertEquals(Arrays.asList("Emote-Rad"), names("mouse mod:trsclient"));
		assertEquals(Arrays.asList("Sodium-Menü"), names("key:2 mod:sodium"));
		assertTrue(names("key:2 unbound").isEmpty());
		assertTrue(names("gibtsnicht").isEmpty());
	}

	@Test
	void queryForPressedKey() {
		assertEquals("key:2", KeySearch.queryFor("key.keyboard.2"));
		assertEquals("key:mouse4", KeySearch.queryFor("key.mouse.4"));
		assertEquals("key:mouse2", KeySearch.queryFor("key.mouse.right"));
		assertEquals("key:left.shift", KeySearch.queryFor("key.keyboard.left.shift"));
		assertEquals(Arrays.asList("Schleichen"), names(KeySearch.queryFor("key.keyboard.left.shift")));
		assertEquals("", KeySearch.queryFor("key.keyboard.unknown"));
	}
}

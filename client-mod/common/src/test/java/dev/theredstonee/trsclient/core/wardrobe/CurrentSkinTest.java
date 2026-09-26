package dev.theredstonee.trsclient.core.wardrobe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Karte „Aktueller Skin“: Vergleichswert, Quelle, keine Dopplung mit der Bibliothek, „getragen“ ≠ ausgewählt. */
class CurrentSkinTest {
	private static WardrobeService.State state(List<WardrobeService.Skin> skins, int[] active, boolean slim, String account) {
		WardrobeService.Active a = active == null ? new WardrobeService.Active(null, false, null, account)
				: new WardrobeService.Active(active, slim, CurrentSkin.lookKey(active, slim), account);
		return new WardrobeService.State(skins, WardrobeDoc.EMPTY, null, null, null, null,
				Collections.<String, WardrobeService.CapeArt>emptyMap(), a, WardrobeService.Task.NONE, null, new Object[0], false,
				false, true, null, 1);
	}

	private static WardrobeService.Skin skin(String id, int[] px, boolean slim) {
		return new WardrobeService.Skin(id, id, slim, px, false, false);
	}

	private static int[] colored(int argb) {
		int[] px = SkinEditor.blankTemplate(false);
		px[8 * 64 + 8] = argb; // Gesicht
		return px;
	}

	@Test
	void lookKeyComparesWhatMinecraftShowsPlusArmModel() {
		int[] a = colored(0xFF112233);
		assertEquals(CurrentSkin.lookKey(a, false), CurrentSkin.lookKey(Arrays.copyOf(a, a.length), false));
		assertEquals(64, CurrentSkin.lookKey(a, false).length(), "SHA-256 hex");
		assertNotEquals(CurrentSkin.lookKey(a, false), CurrentSkin.lookKey(a, true), "andere Armform = anderer Skin");
		assertNotEquals(CurrentSkin.lookKey(a, false), CurrentSkin.lookKey(colored(0xFF112234), false));
		// Grundebene wird deckend gezeigt: gleicher Farbwert mit Alpha 0 sieht gleich aus
		int[] transparent = Arrays.copyOf(a, a.length);
		transparent[8 * 64 + 8] = 0x00112233;
		assertEquals(CurrentSkin.lookKey(a, false), CurrentSkin.lookKey(transparent, false));
		assertNull(CurrentSkin.lookKey(null, false));
		assertNull(CurrentSkin.lookKey(new int[10], false));
	}

	@Test
	void profileWinsOnlyForTheSignedInAccount() {
		int[] profile = colored(0xFFAA0000);
		int[] menu = colored(0xFF00AA00);
		WardrobeService.State s = state(Collections.<WardrobeService.Skin>emptyList(), profile, true, "acc");
		CurrentSkin fresh = CurrentSkin.resolve(s, true, menu, null, false, true);
		assertEquals(CurrentSkin.Source.PROFILE, fresh.source);
		assertSame(profile, fresh.pixels);
		assertTrue(fresh.slim);
		// Nach einem Kontowechsel (Stand noch vom alten Konto): Menü-Skin des neuen Kontos
		CurrentSkin stale = CurrentSkin.resolve(s, false, menu, null, false, true);
		assertEquals(CurrentSkin.Source.LOOK, stale.source);
		assertSame(menu, stale.pixels);
		assertFalse(stale.slim);
		// Profil ohne eigenen Skin (Standard-Skin) → Menü-Skin; Offline/Dev → Standard-Skin
		WardrobeService.State none = state(Collections.<WardrobeService.Skin>emptyList(), null, false, "acc");
		assertEquals(CurrentSkin.Source.DEFAULT, CurrentSkin.resolve(none, true, menu, null, false, false).source);
		// Zwischengespeicherter Vergleichswert wird übernommen
		assertEquals("k", CurrentSkin.resolve(none, true, menu, "k", false, false).look);
		// Gar nichts bekannt: nur Textur – weder speichern noch Zwilling
		CurrentSkin unknown = CurrentSkin.resolve(none, true, null, null, false, false);
		assertNull(unknown.pixels);
		assertNull(unknown.look);
		assertNull(unknown.twin);
		assertFalse(unknown.saveable());
	}

	@Test
	void identicalLibrarySkinIsMarkedNotDuplicated() {
		int[] worn = colored(0xFF123456);
		List<WardrobeService.Skin> lib = new ArrayList<WardrobeService.Skin>();
		lib.add(skin("aaaaaaaaaaaa", colored(0xFF000001), false));
		lib.add(skin("bbbbbbbbbbbb", worn, true)); // gleiche Pixel, andere Armform
		lib.add(skin("cccccccccccc", Arrays.copyOf(worn, worn.length), false));
		lib.add(skin("dddddddddddd", Arrays.copyOf(worn, worn.length), false)); // zweite Kopie
		WardrobeService.State s = state(lib, worn, false, "acc");
		CurrentSkin cur = CurrentSkin.resolve(s, true, null, null, false, false);
		assertEquals("cccccccccccc", cur.twin, "erster gleicher Skin");
		assertFalse(cur.saveable(), "schon in der Bibliothek → nicht noch einmal speichern");
		assertFalse(cur.sameAs(lib.get(0)));
		assertFalse(cur.sameAs(lib.get(1)), "andere Armform ist nicht „= aktuell“");
		assertTrue(cur.sameAs(lib.get(2)));
		assertTrue(cur.sameAs(lib.get(3)), "jede gleiche Kopie bekommt den Hinweis");
		assertFalse(cur.sameAs(null));
		// Ohne Zwilling speicherbar
		WardrobeService.State alone = state(lib.subList(0, 2), worn, false, "acc");
		CurrentSkin c2 = CurrentSkin.resolve(alone, true, null, null, false, false);
		assertNull(c2.twin);
		assertTrue(c2.saveable());
		assertEquals(CurrentSkin.twin(lib, cur.look), cur.twin);
		assertNull(CurrentSkin.twin(lib, null));
	}

	@Test
	void selectionIdIsNeverALibraryId() {
		assertFalse(SkinFiles.validId(CurrentSkin.ID));
		WardrobeService.State s = state(Collections.singletonList(skin("aaaaaaaaaaaa", colored(1), false)), null, false, "acc");
		assertNull(s.skin(CurrentSkin.ID), "Auswahl „Aktueller Skin“ ist kein Bibliotheks-Skin");
	}
}

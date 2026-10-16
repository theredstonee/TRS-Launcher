package dev.theredstonee.trsclient.core.creator;

import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Profil-Abzeichen vor dem Namen: Auswahl, eingebautes Bild als Rückfall, Bild der API ersetzt es. */
class EventBadgesTest {
	private static final class Backend implements CapeTextures.Backend<String> {
		final List<String> uploads = new ArrayList<String>();

		@Override
		public String upload(String name, int width, int height, int[] argb) {
			uploads.add(name + " " + width + "x" + height);
			return name;
		}

		@Override
		public void release(String texture) {
		}
	}

	@Test
	void pumpkinHunterWinsOverHalloween() {
		assertEquals("pumpkinhunter2026", EventBadges.pick(Arrays.asList("halloween2026", "pumpkinhunter2026")));
		assertEquals("halloween2026", EventBadges.pick(Arrays.asList("founder", "halloween2026")));
		assertNull(EventBadges.pick(Collections.singletonList("founder")));
		assertNull(EventBadges.pick(null));
	}

	@Test
	void builtinFirstThenTheApiImage() {
		Backend backend = new Backend();
		final EventBadges.Done[] pending = new EventBadges.Done[1];
		final int[] requests = new int[1];
		EventBadges<String> badges = new EventBadges<String>(backend, new EventBadges.Loader() {
			@Override
			public void load(String badgeId, EventBadges.Done done, Runnable failed) {
				requests[0]++;
				pending[0] = done;
			}
		});
		TextureRef builtin = badges.texture("halloween2026", 0);
		assertNotNull(builtin);
		assertEquals(72, builtin.width);
		assertEquals(64, builtin.height);
		assertEquals(9, EventBadges.widthFor(builtin, 8));
		assertSame(builtin, badges.texture("halloween2026", 1), "nur einmal hochladen");
		assertEquals(1, requests[0], "nur eine Anfrage, solange sie läuft");
		pending[0].image(8, 8, new int[64]);
		TextureRef remote = badges.texture("halloween2026", 2);
		assertEquals(8, remote.width);
		assertEquals(Arrays.asList("badge/halloween2026 72x64", "badge/halloween2026_api 8x8"), backend.uploads);
		// Unbekanntes Abzeichen ohne eingebautes Bild: erst mit dem Bild der API.
		assertNull(badges.texture("unknown_badge", 3));
		// Kaputtes Bild wird verworfen.
		pending[0].image(9999, 1, new int[9999]);
		assertNull(badges.texture("unknown_badge", 4));
	}
}

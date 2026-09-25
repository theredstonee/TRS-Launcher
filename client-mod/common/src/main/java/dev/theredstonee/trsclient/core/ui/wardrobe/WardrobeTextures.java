package dev.theredstonee.trsclient.core.ui.wardrobe;

import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Texturen der Garderobe (Skins, Umhänge, Editor): jedes Bild wird genau einmal hochgeladen und danach nur noch
 * gezeichnet; geändert wird nur, wenn sich das Pixel-Array ändert. Höchstens {@link #UPLOADS_PER_FRAME} neue Bilder je
 * Bild, damit das Öffnen mit vielen Skins nicht ruckelt. Nur Render-Thread.
 */
final class WardrobeTextures {
	static final int UPLOADS_PER_FRAME = 6;

	private static final class Slot {
		TextureRef ref;
		Object source;
		int stamp;
	}

	private final Map<String, Slot> slots = new HashMap<String, Slot>();
	private int budget;

	/** Zu Beginn jedes Bildes. */
	void frame() {
		budget = UPLOADS_PER_FRAME;
	}

	/**
	 * Textur für {@code key} (Name {@code [a-z0-9_/.-]}); lädt hoch, wenn {@code pixels} (Identität) oder {@code stamp}
	 * neu sind. null, solange noch nicht hochgeladen oder ohne Textur-Unterstützung.
	 */
	TextureRef get(String key, int[] pixels, int width, int height, int stamp) {
		Slot s = slots.get(key);
		if (s != null && s.source == pixels && s.stamp == stamp && s.ref != null) return s.ref;
		Textures.Store store = Textures.store();
		if (store == null || pixels == null || !Textures.validName(key)) return s == null ? null : s.ref;
		boolean urgent = s != null && s.ref != null; // Ersetzen (Editor) nie aufschieben
		if (!urgent && budget <= 0) return null;
		if (!urgent) budget--;
		if (s == null) {
			s = new Slot();
			slots.put(key, s);
		}
		if (s.ref != null && (s.ref.width != width || s.ref.height != height)) {
			store.release(s.ref);
			s.ref = null;
		}
		TextureRef ref = store.upload(key, width, height, pixels);
		s.ref = ref;
		s.source = pixels;
		s.stamp = stamp;
		return ref;
	}

	/** Alles freigeben (beim Schließen). */
	void releaseAll() {
		Textures.Store store = Textures.store();
		for (Iterator<Slot> it = slots.values().iterator(); it.hasNext(); ) {
			Slot s = it.next();
			if (store != null && s.ref != null) store.release(s.ref);
			it.remove();
		}
	}
}

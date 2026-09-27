package dev.theredstonee.trsclient.core.tooltip;

import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Texturen der Karten-Vorschau: je Karten-ID eine 128×128-Textur, neu hochgeladen nur, wenn sich die Kartendaten
 * geändert haben. Höchstens {@link #MAX} Karten gleichzeitig (älteste werden freigegeben). Nur im Render-Thread.
 */
public final class MapPreviews {
	static final int MAX = 6;
	/** Papierfarbe für leere Stellen (wie die Vanilla-Karte). */
	public static final int PAPER = 0xFFD8C6A0;

	private static final class Entry {
		String name;
		TextureRef texture;
		long hash;
	}

	private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<Integer, Entry>(16, 0.75f, true);
	private int[] buffer;
	private int uploads;

	/** Textur der Karte oder null (keine Texturen möglich). */
	public TextureRef texture(int id, byte[] colors) {
		Textures.Store store = Textures.store();
		if (store == null || colors == null) return null;
		long hash = MapPalette.hash(colors);
		Entry e = entries.get(id);
		if (e != null && e.hash == hash && e.texture != null) return e.texture;
		buffer = MapPalette.toArgb(colors, PAPER, buffer);
		if (e == null) {
			e = new Entry();
			e.name = "tooltip/map" + (uploads++);
			entries.put(id, e);
		}
		// Gleicher Name + gleiche Größe = Pixel werden an Ort und Stelle ersetzt.
		TextureRef tex = store.upload(e.name, 128, 128, buffer);
		if (tex == null) {
			entries.remove(id);
			return null;
		}
		e.texture = tex;
		e.hash = hash;
		evict(store);
		return tex;
	}

	private void evict(Textures.Store store) {
		Iterator<Map.Entry<Integer, Entry>> it = entries.entrySet().iterator();
		while (entries.size() > MAX && it.hasNext()) {
			Entry old = it.next().getValue();
			if (old.texture != null) store.release(old.texture);
			it.remove();
		}
	}

	/** Alles freigeben (Welt verlassen). */
	public void clear() {
		Textures.Store store = Textures.store();
		for (Entry e : entries.values()) {
			if (store != null && e.texture != null) store.release(e.texture);
		}
		entries.clear();
	}

	int size() {
		return entries.size();
	}
}

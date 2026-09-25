package dev.theredstonee.trsclient.core.clips;

import dev.theredstonee.trsclient.core.cape.PngDecoder;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Vorschaubilder von Bildschirmfotos: Datei lesen und PNG dekodieren + verkleinern im Hintergrund, Hochladen
 * als Textur im Render-Thread (höchstens {@link #UPLOADS_PER_FRAME} je Bild), Zwischenspeicher mit fester Größe
 * (älteste Textur wird freigegeben). Ohne Textur-Unterstützung der Version liefert {@link #get} immer null.
 */
public final class Thumbnails {
	static final int UPLOADS_PER_FRAME = 2;
	static final long MAX_FILE_BYTES = 48L * 1024 * 1024;

	private static final class Slot {
		TextureRef ref;
		boolean failed;
		long modified;
		int width;
		int height;
	}

	private static final class Decoded {
		final String key;
		final long modified;
		final int width;
		final int height;
		final int[] argb;

		Decoded(String key, long modified, int width, int height, int[] argb) {
			this.key = key;
			this.modified = modified;
			this.width = width;
			this.height = height;
			this.argb = argb;
		}
	}

	private static int counter;

	private final Executor worker;
	private final int maxW;
	private final int maxH;
	private final int capacity;
	private final String prefix;
	private int uploads;
	/** Nur Render-Thread; Zugriffsreihenfolge = LRU. */
	private final LinkedHashMap<String, Slot> cache = new LinkedHashMap<>(16, 0.75f, true);
	private final ConcurrentLinkedQueue<Decoded> done = new ConcurrentLinkedQueue<>();

	public Thumbnails(Executor worker, int maxW, int maxH, int capacity) {
		this.worker = worker;
		this.maxW = maxW;
		this.maxH = maxH;
		this.capacity = Math.max(1, capacity);
		synchronized (Thumbnails.class) {
			this.prefix = "trs_thumbs/" + (counter++) + "_";
		}
	}

	/** Seitenverhältnis (Breite/Höhe) des geladenen Bildes, 0 = noch unbekannt. */
	public float aspect(Path path) {
		Slot s = cache.get(path.toString());
		return s == null || s.height == 0 ? 0f : (float) s.width / s.height;
	}

	/** Textur des Bildes oder null (lädt noch / geht nicht). Render-Thread. */
	public TextureRef get(Path path, long modified) {
		if (Textures.store() == null || path == null) return null;
		String key = path.toString();
		Slot s = cache.get(key);
		if (s != null && s.modified == modified) return s.failed ? null : s.ref;
		if (s == null) {
			s = new Slot();
			cache.put(key, s);
			trim();
		} else if (s.ref != null) {
			Textures.store().release(s.ref);
			s.ref = null;
		}
		s.modified = modified;
		s.failed = false;
		schedule(path, key, modified);
		return null;
	}

	/** Gibt es für den Pfad schon ein Ergebnis (Bild oder Fehler)? */
	public boolean settled(Path path) {
		Slot s = cache.get(path.toString());
		return s != null && (s.failed || s.ref != null);
	}

	private void schedule(final Path path, final String key, final long modified) {
		try {
			worker.execute(new Runnable() {
				@Override
				public void run() {
					done.add(decode(path, key, modified));
				}
			});
		} catch (RuntimeException e) {
			done.add(new Decoded(key, modified, 0, 0, null));
		}
	}

	Decoded decode(Path path, String key, long modified) {
		try {
			if (Files.size(path) > MAX_FILE_BYTES) return new Decoded(key, modified, 0, 0, null);
			PngDecoder.Image img = PngDecoder.decode(Files.readAllBytes(path));
			float scale = Math.min(1f, Math.min(maxW / (float) img.width, maxH / (float) img.height));
			int w = Math.max(1, Math.round(img.width * scale));
			int h = Math.max(1, Math.round(img.height * scale));
			return new Decoded(key, modified, w, h, downscale(img.argb, img.width, img.height, w, h));
		} catch (Exception | OutOfMemoryError e) {
			return new Decoded(key, modified, 0, 0, null);
		}
	}

	/** Einmal je Bild im Render-Thread: fertige Bilder hochladen. */
	public void frame() {
		Textures.Store store = Textures.store();
		for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
			Decoded d = done.poll();
			if (d == null) return;
			Slot s = cache.get(d.key);
			if (s == null || s.modified != d.modified) continue;
			if (d.argb == null || store == null) {
				s.failed = true;
				continue;
			}
			TextureRef ref = store.upload(prefix + (uploads++),
					d.width, d.height, d.argb);
			if (ref == null) {
				s.failed = true;
			} else {
				s.ref = ref;
				s.width = d.width;
				s.height = d.height;
			}
		}
	}

	private void trim() {
		Textures.Store store = Textures.store();
		Iterator<Map.Entry<String, Slot>> it = cache.entrySet().iterator();
		while (cache.size() > capacity && it.hasNext()) {
			Slot s = it.next().getValue();
			if (s.ref != null && store != null) store.release(s.ref);
			it.remove();
		}
	}

	/** Alle Texturen freigeben (Bildschirm geschlossen). Render-Thread. */
	public void releaseAll() {
		Textures.Store store = Textures.store();
		for (Slot s : cache.values()) {
			if (s.ref != null && store != null) store.release(s.ref);
		}
		cache.clear();
		done.clear();
	}

	/** Flächenmittel (Box-Filter) auf {@code tw}×{@code th}; Alpha wird auf deckend gesetzt. */
	static int[] downscale(int[] src, int sw, int sh, int tw, int th) {
		if (tw == sw && th == sh) {
			int[] out = src.clone();
			for (int i = 0; i < out.length; i++) out[i] |= 0xFF000000;
			return out;
		}
		int[] out = new int[tw * th];
		for (int y = 0; y < th; y++) {
			int y0 = y * sh / th;
			int y1 = Math.max(y0 + 1, (y + 1) * sh / th);
			for (int x = 0; x < tw; x++) {
				int x0 = x * sw / tw;
				int x1 = Math.max(x0 + 1, (x + 1) * sw / tw);
				long r = 0, g = 0, b = 0;
				int n = 0;
				// Große Bilder: höchstens 4×4 Stichproben je Zielpixel (schnell genug, sieht ruhig aus).
				int stepY = Math.max(1, (y1 - y0) / 4);
				int stepX = Math.max(1, (x1 - x0) / 4);
				for (int yy = y0; yy < y1; yy += stepY) {
					int row = yy * sw;
					for (int xx = x0; xx < x1; xx += stepX) {
						int p = src[row + xx];
						r += (p >> 16) & 0xFF;
						g += (p >> 8) & 0xFF;
						b += p & 0xFF;
						n++;
					}
				}
				out[y * tw + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
			}
		}
		return out;
	}
}

package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.online.CapeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Umhang-Texturen im Spiel: lädt jeden Umhang einmal (über den {@link Loader}, im Spiel TrsOnline#loadCape), lädt die
 * Einzelbilder als eigene Texturen hoch (höchstens {@link #UPLOADS_PER_CALL} je Aufruf und {@link #UPLOADS_PER_TICK}
 * je Tick, damit nichts ruckelt)
 * und liefert das Bild, das gerade dran ist. Nicht benutzte Umhänge werden nach {@link #IDLE_MS} freigegeben.
 *
 * <p>Nur aus dem Spiel-/Render-Thread benutzen.
 *
 * @param <T> Textur-Kennung der jeweiligen Minecraft-Version (z. B. ResourceLocation)
 */
public final class CapeTextures<T> {
	static final int UPLOADS_PER_CALL = 8;
	/**
	 * Höchstens so viele Bilder je Tick insgesamt (über alle Spieler und Bilder): das Hochladen passiert
	 * mitten im Zeichnen – verteilt auf mehrere Ticks ruckelt es nicht, wenn viele Umhänge auf einmal kommen.
	 */
	static final int UPLOADS_PER_TICK = 4;
	static final long IDLE_MS = 3 * 60_000L;
	static final long RETRY_MS = 5 * 60_000L;

	/** Hochladen/Freigeben einer Textur in der jeweiligen Version. */
	public interface Backend<T> {
		/** Lädt ein Bild hoch; {@code name} ist eindeutig und nur [a-z0-9_/.-]. */
		T upload(String name, int width, int height, int[] argb);

		void release(T texture);
	}

	/** Lädt einen Umhang im Hintergrund; genau einer der Rückrufe kommt später im Spiel-Thread. */
	public interface Loader {
		void load(CapeInfo cape, java.util.function.Consumer<CapeFrames> done, Runnable failed);
	}

	private static final class Slot<T> {
		final CapeInfo info;
		final String name;
		CapeFrames pending;
		List<T> textures = new ArrayList<>();
		boolean requested;
		boolean ready;
		long retryAt;
		long lastUsed;

		Slot(CapeInfo info, String name) {
			this.info = info;
			this.name = name;
		}
	}

	private final Backend<T> backend;
	private final Loader loader;
	private final Map<String, Slot<T>> slots = new HashMap<>();
	private int serial;
	private int budget = UPLOADS_PER_TICK;
	private long lastCleanup;

	public CapeTextures(Backend<T> backend, Loader loader) {
		this.backend = backend;
		this.loader = loader;
	}

	/** Textur des aktuellen Bildes, oder null, solange sie (noch) nicht da ist. */
	public T texture(CapeInfo cape, long now) {
		if (cape == null) return null;
		Slot<T> slot = slots.get(cape.key());
		if (slot == null) {
			slot = new Slot<>(cape, "capes/" + cape.id.replaceAll("[^a-z0-9_-]", "") + "_" + (serial++));
			slots.put(cape.key(), slot);
		}
		slot.lastUsed = now;
		if (!slot.ready) {
			advance(slot, now);
			if (!slot.ready) return null;
		}
		int n = slot.textures.size();
		return slot.textures.get(Math.min(n - 1, cape.frameAt(now) % n));
	}

	private void advance(Slot<T> slot, long now) {
		if (slot.pending == null) {
			if (!slot.requested && now >= slot.retryAt) {
				slot.requested = true;
				Slot<T> s = slot;
				loader.load(slot.info, frames -> s.pending = frames, () -> {
					s.requested = false;
					s.retryAt = System.currentTimeMillis() + RETRY_MS;
				});
			}
			return;
		}
		CapeFrames frames = slot.pending;
		int calls = UPLOADS_PER_CALL;
		while (calls-- > 0 && budget > 0 && slot.textures.size() < frames.count()) {
			budget--;
			int i = slot.textures.size();
			T tex = backend.upload(slot.name + "/" + i, frames.width, frames.height, frames.frames[i]);
			if (tex == null) {
				// Hochladen fehlgeschlagen: später erneut versuchen.
				releaseAll(slot);
				slot.pending = null;
				slot.requested = false;
				slot.retryAt = now + RETRY_MS;
				return;
			}
			slot.textures.add(tex);
		}
		if (slot.textures.size() == frames.count()) {
			slot.ready = true;
			slot.pending = null;
		}
	}

	/** Lange nicht benutzte Umhänge freigeben (einmal pro Sekunde aus dem Tick). */
	public void cleanup(long now) {
		budget = UPLOADS_PER_TICK;
		if (now - lastCleanup < 1000) return;
		lastCleanup = now;
		Iterator<Slot<T>> it = slots.values().iterator();
		while (it.hasNext()) {
			Slot<T> slot = it.next();
			if (now - slot.lastUsed > IDLE_MS) {
				releaseAll(slot);
				it.remove();
			}
		}
	}

	private void releaseAll(Slot<T> slot) {
		for (T t : slot.textures) backend.release(t);
		slot.textures = new ArrayList<>();
		slot.ready = false;
	}

	int size() {
		return slots.size();
	}
}

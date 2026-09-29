package dev.theredstonee.trsclient.core.cosmetic.v2;

import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.online.HatInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * v2-Kosmetik im Spiel: lädt jedes Teil einmal (über den {@link Loader}, im Spiel {@code TrsOnline#loadCosmetic}),
 * lädt die Einzelbilder von Grundtextur und Leucht-Schicht als eigene Texturen hoch (höchstens {@link #UPLOADS_PER_TICK}
 * je Tick – nichts ruckelt) und gibt nicht benutzte Teile nach {@link #IDLE_MS} frei. Dazu eine gemeinsame Hof-Textur.
 *
 * <p>Nur aus dem Spiel-/Render-Thread benutzen.
 *
 * @param <T> Textur-Kennung der Version (ResourceLocation/Identifier)
 */
public final class CosmeticAssets<T> {
	static final int UPLOADS_PER_TICK = 4;
	static final long IDLE_MS = 3 * 60_000L;
	static final long RETRY_MS = 5 * 60_000L;

	/** Lädt ein Teil im Hintergrund; genau einer der Rückrufe kommt später im Spiel-Thread. */
	public interface Loader {
		void load(HatInfo hat, java.util.function.Consumer<CosmeticV2Cache.Loaded> done, Runnable failed);
	}

	/** Ein fertig hochgeladenes Teil. */
	public static final class Entry<T> {
		public final CosmeticV2 model;
		final List<T> base = new ArrayList<T>();
		final List<T> glow = new ArrayList<T>();

		Entry(CosmeticV2 model) {
			this.model = model;
		}

		/** Grundtextur zur Wanduhr. */
		public T base(long now) {
			int n = base.size();
			return base.get(Math.min(n - 1, CosmeticV2Renderer.frameAt(now, n, model.frameTimeMs)));
		}

		/** Leucht-Schicht zur Wanduhr oder null. */
		public T glow(long now) {
			int n = glow.size();
			if (n == 0) return null;
			return glow.get(Math.min(n - 1, CosmeticV2Renderer.frameAt(now, n, model.glowFrameTimeMs)));
		}
	}

	private static final class Slot<T> {
		final HatInfo hat;
		final String name;
		CosmeticV2Cache.Loaded pending;
		Entry<T> entry;
		boolean requested;
		boolean ready;
		long retryAt;
		long lastUsed;

		Slot(HatInfo hat, String name) {
			this.hat = hat;
			this.name = name;
		}
	}

	private final CapeTextures.Backend<T> backend;
	private final Loader loader;
	private final Map<String, Slot<T>> slots = new HashMap<String, Slot<T>>();
	private int serial;
	private int budget = UPLOADS_PER_TICK;
	private long lastCleanup;
	private T halo;
	private boolean haloFailed;

	public CosmeticAssets(CapeTextures.Backend<T> backend, Loader loader) {
		this.backend = backend;
		this.loader = loader;
	}

	/** Fertiges Teil oder null, solange es (noch) nicht da ist (Laden/Hochladen läuft dann an). */
	public Entry<T> get(HatInfo hat, long now) {
		if (hat == null || !hat.v2()) return null;
		Slot<T> slot = slots.get(hat.key());
		if (slot == null) {
			slot = new Slot<T>(hat, "cosmetics/" + hat.id.replaceAll("[^a-z0-9_]", "") + "_" + (serial++));
			slots.put(hat.key(), slot);
		}
		slot.lastUsed = now;
		if (!slot.ready) {
			advance(slot, now);
			if (!slot.ready) return null;
		}
		return slot.entry;
	}

	/** Hof-Textur (Graustufen für additives Zeichnen), einmal hochgeladen; null, wenn das nicht ging. */
	public T halo() {
		if (halo == null && !haloFailed) {
			halo = backend.upload("cosmetics/halo", V2Images.HALO_SIZE, V2Images.HALO_SIZE, V2Images.haloAdditive());
			haloFailed = halo == null;
		}
		return halo;
	}

	private void advance(Slot<T> slot, long now) {
		if (slot.pending == null) {
			if (!slot.requested && now >= slot.retryAt) {
				slot.requested = true;
				final Slot<T> s = slot;
				loader.load(slot.hat, loaded -> s.pending = loaded, () -> {
					s.requested = false;
					s.retryAt = System.currentTimeMillis() + RETRY_MS;
				});
			}
			return;
		}
		CosmeticV2Cache.Loaded l = slot.pending;
		if (slot.entry == null) slot.entry = new Entry<T>(l.model);
		Entry<T> e = slot.entry;
		int w = l.model.pixelWidth();
		int h = l.model.pixelHeight();
		int total = l.base.length + (l.glow == null ? 0 : l.glow.length);
		while (budget > 0 && e.base.size() + e.glow.size() < total) {
			budget--;
			boolean isBase = e.base.size() < l.base.length;
			int i = isBase ? e.base.size() : e.glow.size();
			T tex = backend.upload(slot.name + (isBase ? "/b" : "/g") + i, w, h, isBase ? l.base[i] : l.glow[i]);
			if (tex == null) {
				releaseAll(slot);
				slot.pending = null;
				slot.requested = false;
				slot.retryAt = now + RETRY_MS;
				return;
			}
			(isBase ? e.base : e.glow).add(tex);
		}
		if (e.base.size() + e.glow.size() == total) {
			slot.ready = true;
			slot.pending = null;
		}
	}

	/** Lange nicht benutzte Teile freigeben (einmal je Tick aufrufen). */
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
		if (slot.entry != null) {
			for (T t : slot.entry.base) backend.release(t);
			for (T t : slot.entry.glow) backend.release(t);
		}
		slot.entry = null;
		slot.ready = false;
	}

	int size() {
		return slots.size();
	}
}

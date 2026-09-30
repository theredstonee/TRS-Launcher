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

	/**
	 * Ein fertig hochgeladenes Teil: je Bild die Texturen aller Entfernungs-Stufen ({@link V2Images#levels}). Stufe 0
	 * (volle Auflösung) ist sofort da, die kleineren Stufen kommen danach im selben Upload-Budget dazu – bis dahin wird
	 * die nächstgrößere vorhandene Stufe genommen.
	 */
	public static final class Entry<T> implements V2Hat.Lod<T> {
		public final CosmeticV2 model;
		/** [Stufe] → Texturen je Bild. */
		final List<List<T>> base = new ArrayList<List<T>>();
		final List<List<T>> glow = new ArrayList<List<T>>();

		Entry(CosmeticV2 model, int levels) {
			this.model = model;
			for (int l = 0; l <= levels; l++) {
				base.add(new ArrayList<T>());
				glow.add(new ArrayList<T>());
			}
		}

		/** Grundtextur zur Wanduhr (volle Auflösung). */
		public T base(long now) {
			return base(now, 0);
		}

		/** Leucht-Schicht zur Wanduhr oder null (volle Auflösung). */
		public T glow(long now) {
			return glow(now, 0);
		}

		@Override
		public T base(long now, int level) {
			List<T> l = level(base, level);
			int n = l.size();
			return l.get(Math.min(n - 1, CosmeticV2Renderer.frameAt(now, n, model.frameTimeMs)));
		}

		@Override
		public T glow(long now, int level) {
			List<T> l = level(glow, level);
			int n = l.size();
			if (n == 0) return null;
			return l.get(Math.min(n - 1, CosmeticV2Renderer.frameAt(now, n, model.glowFrameTimeMs)));
		}

		/** Gewünschte Stufe, wenn vollständig hochgeladen, sonst die nächstgrößere (Stufe 0 ist immer vollständig). */
		private List<T> level(List<List<T>> levels, int level) {
			int want = Math.max(0, Math.min(levels.size() - 1, level));
			int n0 = levels.get(0).size();
			for (int l = want; l > 0; l--) {
				if (levels.get(l).size() == n0) return levels.get(l);
			}
			return levels.get(0);
		}

		int uploaded() {
			int n = 0;
			for (List<T> l : base) n += l.size();
			for (List<T> l : glow) n += l.size();
			return n;
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
		if (!slot.ready || slot.pending != null) {
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
		int levels = l.levels();
		if (slot.entry == null) slot.entry = new Entry<T>(l.model, levels);
		Entry<T> e = slot.entry;
		int w = l.model.pixelWidth();
		int h = l.model.pixelHeight();
		int glowCount = l.glow == null ? 0 : l.glow.length;
		int perLevel = l.base.length + glowCount;
		int total = perLevel * (levels + 1);
		// Reihenfolge: Stufe 0 (Grund, dann Leuchten), dann Stufe 1, 2, …
		while (budget > 0 && e.uploaded() < total) {
			budget--;
			int k = e.uploaded();
			int level = k / perLevel;
			int i = k % perLevel;
			boolean isBase = i < l.base.length;
			int frame = isBase ? i : i - l.base.length;
			int[] px = isBase ? l.baseLevels[frame][level] : l.glowLevels[frame][level];
			T tex = backend.upload(slot.name + (isBase ? "/b" : "/g") + frame + (level == 0 ? "" : "_l" + level),
					w >> level, h >> level, px);
			if (tex == null) {
				if (level > 0) {
					// kleinere Stufen sind nur Zugabe: dann eben ohne sie weiter
					slot.pending = null;
					return;
				}
				releaseAll(slot);
				slot.pending = null;
				slot.requested = false;
				slot.retryAt = now + RETRY_MS;
				return;
			}
			(isBase ? e.base : e.glow).get(level).add(tex);
		}
		if (e.uploaded() >= perLevel) slot.ready = true;
		if (e.uploaded() >= total) slot.pending = null;
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
			for (List<T> l : slot.entry.base) for (T t : l) backend.release(t);
			for (List<T> l : slot.entry.glow) for (T t : l) backend.release(t);
		}
		slot.entry = null;
		slot.ready = false;
	}

	int size() {
		return slots.size();
	}
}

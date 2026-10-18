package dev.theredstonee.trsclient.core.cape;

import dev.theredstonee.trsclient.core.online.CapeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Umhang-Texturen im Spiel: lädt jeden Umhang einmal (über den {@link Loader}, im Spiel TrsOnline#loadCape), lädt die
 * Einzelbilder als eigene Texturen hoch (höchstens {@link #UPLOADS_PER_CALL} je Aufruf, {@link #UPLOADS_PER_TICK}
 * Bilder und {@link #PIXELS_PER_TICK} Pixel je Tick, damit nichts ruckelt)
 * und liefert das Bild, das gerade dran ist. Nicht benutzte Umhänge werden nach {@link #IDLE_MS} freigegeben.
 *
 * <p><b>Speicher-Budget:</b> Ein HD-Umhang mit 32 Bildern zu 1024×512 belegt 64 MB Texturspeicher (und im Spiel noch
 * einmal so viel Arbeitsspeicher, solange er hochgeladen wird). Alle Umhänge zusammen bleiben deshalb unter
 * {@link #DEFAULT_BUDGET}: Reicht der Platz nicht, werden zuerst Umhänge freigegeben, die gerade niemand sieht (am
 * längsten nicht gesehen zuerst); reicht es dann immer noch nicht, wird der neue Umhang verkleinert geladen (halbe
 * Kantenlänge je Stufe, Kastenfilter). Der eigene Umhang kommt immer in voller Qualität. Wird später wieder Platz frei,
 * werden verkleinerte, sichtbare Umhänge nach und nach in besserer Qualität nachgeladen.
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
	/** Höchstens so viele Pixel je Tick: ein HD-Bild 1024×512 oder vier 512×256 (das erste Bild darf immer). */
	static final int PIXELS_PER_TICK = 1024 * 512;
	static final long IDLE_MS = 3 * 60_000L;
	static final long RETRY_MS = 5 * 60_000L;
	/** So lange nach dem letzten Zeichnen gilt ein Umhang als „zu sehen“ und wird fürs Budget nicht freigegeben. */
	static final long SEEN_MS = 2_000L;
	/** So oft wird geprüft, ob ein verkleinerter Umhang jetzt in besserer Qualität passt. */
	static final long UPGRADE_CHECK_MS = 5_000L;
	/** Texturspeicher aller Umhänge zusammen (RGBA-Bytes; 384 MB = sechs volle HD-Umhänge mit 32 Bildern). */
	public static final long DEFAULT_BUDGET = 384L * 1024 * 1024;

	/** Hochladen/Freigeben einer Textur in der jeweiligen Version. */
	public interface Backend<T> {
		/** Lädt ein Bild hoch; {@code name} ist eindeutig und nur [a-z0-9_/.-]. */
		T upload(String name, int width, int height, int[] argb);

		/**
		 * Wie {@link #upload(String, int, int, int[])}; {@code smooth} = echter HD-Umhang ({@link CapeFrames#smooth}):
		 * weich filtern (linear + Mipmaps) statt scharfer Pixel. Ohne eigene Umsetzung wie bisher scharf.
		 */
		default T upload(String name, int width, int height, int[] argb, boolean smooth) {
			return upload(name, width, height, argb);
		}

		void release(T texture);
	}

	/** Lädt einen Umhang im Hintergrund; genau einer der Rückrufe kommt später im Spiel-Thread. */
	public interface Loader {
		/** @param downscale gewünschte Verkleinerung (1 = volle Qualität, 2, 4 …; siehe {@link CapeFrames#decode}) */
		void load(CapeInfo cape, int downscale, java.util.function.Consumer<CapeFrames> done, Runnable failed);
	}

	private static final class Slot<T> {
		final CapeInfo info;
		final String name;
		/** Fertig dekodiert, wird gerade hochgeladen (in {@link #loading}). */
		CapeFrames pending;
		/** Angezeigte Bilder (fertig). */
		List<T> textures = new ArrayList<>();
		/** Bilder in Arbeit; ersetzen {@link #textures}, sobald alle da sind (so bleibt beim Nachladen das alte Bild). */
		List<T> loading = new ArrayList<>();
		boolean requested;
		boolean ready;
		/** Eigener Umhang: immer volle Qualität, nie fürs Budget freigegeben. */
		boolean own;
		/** Größe der Einzelbilder (nach dem Hochladen). */
		int width;
		int height;
		/** Verkleinerung der angezeigten Bilder. */
		int downscale = 1;
		/** Speicher der angezeigten Bilder. */
		long bytes;
		/** Vorgemerkter Speicher für das, was gerade geladen/hochgeladen wird. */
		long reserved;
		long retryAt;
		long lastUsed;
		/** Bei der letzten Anfrage gewünschte Verkleinerung. */
		int requestedDownscale = 1;

		Slot(CapeInfo info, String name) {
			this.info = info;
			this.name = name;
		}
	}

	private final Backend<T> backend;
	private final Loader loader;
	private final long budgetBytes;
	private final Map<String, Slot<T>> slots = new HashMap<>();
	private int serial;
	private int budget = UPLOADS_PER_TICK;
	private int pixelBudget = PIXELS_PER_TICK;
	private long lastCleanup;
	private long lastUpgradeCheck;

	public CapeTextures(Backend<T> backend, Loader loader) {
		this(backend, loader, DEFAULT_BUDGET);
	}

	/** @param budgetBytes Texturspeicher aller Umhänge (RGBA-Bytes); {@link Long#MAX_VALUE} = unbegrenzt */
	public CapeTextures(Backend<T> backend, Loader loader, long budgetBytes) {
		this.backend = backend;
		this.loader = loader;
		this.budgetBytes = budgetBytes;
	}

	/** Textur des aktuellen Bildes, oder null, solange sie (noch) nicht da ist. */
	public T texture(CapeInfo cape, long now) {
		return texture(cape, now, false);
	}

	/**
	 * Textur des aktuellen Bildes, oder null, solange sie (noch) nicht da ist.
	 *
	 * @param own Umhang des eigenen Spielers (volle Qualität, auch wenn das Budget knapp ist)
	 */
	public T texture(CapeInfo cape, long now, boolean own) {
		if (cape == null) return null;
		Slot<T> slot = slots.get(cape.key());
		if (slot == null) {
			slot = new Slot<>(cape, "capes/" + cape.id.replaceAll("[^a-z0-9_-]", "") + "_" + (serial++));
			slots.put(cape.key(), slot);
		}
		slot.lastUsed = now;
		if (own && !slot.own) {
			slot.own = true;
			// Schon verkleinert geladen (z. B. als fremder Umhang gesehen)? Gleich in voller Qualität nachladen.
			if (slot.ready && slot.downscale > 1 && !slot.requested && slot.pending == null) request(slot, now, 1);
		}
		if (slot.pending != null || !slot.ready) {
			advance(slot, now);
			if (!slot.ready) return null;
		}
		int n = slot.textures.size();
		return slot.textures.get(Math.min(n - 1, cape.frameAt(now) % n));
	}

	/** Breite der Bilder eines fertig geladenen Umhangs (0 = noch nicht da). */
	public int width(CapeInfo cape) {
		Slot<T> slot = cape == null ? null : slots.get(cape.key());
		return slot != null && slot.ready ? slot.width : 0;
	}

	/** Höhe der Bilder eines fertig geladenen Umhangs (0 = noch nicht da). */
	public int height(CapeInfo cape) {
		Slot<T> slot = cape == null ? null : slots.get(cape.key());
		return slot != null && slot.ready ? slot.height : 0;
	}

	/** Verkleinerung eines fertig geladenen Umhangs (1 = volle Qualität, 0 = noch nicht da). */
	int downscale(CapeInfo cape) {
		Slot<T> slot = cape == null ? null : slots.get(cape.key());
		return slot != null && slot.ready ? slot.downscale : 0;
	}

	private void advance(Slot<T> slot, long now) {
		if (slot.pending == null) {
			if (!slot.ready && !slot.requested && now >= slot.retryAt) {
				request(slot, now, chooseDownscale(slot, now));
			}
			return;
		}
		CapeFrames frames = slot.pending;
		int calls = UPLOADS_PER_CALL;
		while (calls-- > 0 && budget > 0 && pixelBudget > 0 && slot.loading.size() < frames.count()) {
			budget--;
			pixelBudget -= frames.width * frames.height;
			int i = slot.loading.size();
			T tex = backend.upload(slot.name + "/" + slot.requestedDownscale + "/" + i, frames.width, frames.height,
					frames.frames[i], frames.smooth);
			if (tex == null) {
				// Hochladen fehlgeschlagen: später erneut versuchen (ein schon angezeigter Umhang bleibt).
				release(slot.loading);
				slot.loading = new ArrayList<>();
				slot.pending = null;
				slot.requested = false;
				slot.reserved = 0;
				slot.retryAt = now + RETRY_MS;
				return;
			}
			// Pixel sind jetzt in der Textur – Arbeitsspeicher sofort freigeben.
			frames.frames[i] = null;
			slot.loading.add(tex);
		}
		if (slot.loading.size() == frames.count()) {
			release(slot.textures);
			slot.textures = slot.loading;
			slot.loading = new ArrayList<>();
			slot.width = frames.width;
			slot.height = frames.height;
			slot.downscale = frames.downscale;
			slot.bytes = frames.bytes();
			slot.reserved = 0;
			slot.ready = true;
			slot.requested = false;
			slot.pending = null;
		}
	}

	private void request(Slot<T> slot, long now, int downscale) {
		slot.requested = true;
		slot.requestedDownscale = downscale;
		slot.reserved = CapeFrames.bytes(slot.info.scale, slot.info.frames, downscale);
		Slot<T> s = slot;
		loader.load(slot.info, downscale, frames -> {
			s.pending = frames;
			s.reserved = frames.bytes();
		}, () -> {
			s.requested = false;
			s.reserved = 0;
			s.retryAt = System.currentTimeMillis() + RETRY_MS;
		});
	}

	/**
	 * Verkleinerung für einen neuen Umhang: volle Qualität, wenn sie ins Budget passt (dafür werden ungesehene Umhänge
	 * freigegeben, am längsten ungesehene zuerst), sonst so lange halbieren, bis er passt (höchstens bis 64×32).
	 */
	int chooseDownscale(Slot<T> slot, long now) {
		if (budgetBytes == Long.MAX_VALUE) return 1;
		int scale = slot.info.scale;
		long full = CapeFrames.bytes(scale, slot.info.frames, 1);
		long free = budgetBytes - usedExcept(slot);
		if (full > free) {
			evictUnseen(now, full - free, slot);
			free = budgetBytes - usedExcept(slot);
		}
		if (slot.own || full <= free) return 1;
		int k = 1;
		while (CapeFrames.bytes(scale, slot.info.frames, k) > free && k * 2 <= scale) k *= 2;
		return k;
	}

	/** Belegter Speicher aller Umhänge außer {@code except} (angezeigt + vorgemerkt). */
	private long usedExcept(Slot<T> except) {
		long used = 0;
		for (Slot<T> s : slots.values()) {
			if (s != except) used += s.bytes + s.reserved;
		}
		return used;
	}

	/** Belegter Speicher aller Umhänge (angezeigt + vorgemerkt). */
	long usedBytes() {
		return usedExcept(null);
	}

	/** Gibt ungesehene Umhänge frei (am längsten ungesehene zuerst), bis {@code need} Bytes frei geworden sind. */
	private void evictUnseen(long now, long need, Slot<T> except) {
		List<Slot<T>> candidates = new ArrayList<>();
		for (Slot<T> s : slots.values()) {
			if (s != except && !s.own && now - s.lastUsed > SEEN_MS && s.bytes + s.reserved > 0) candidates.add(s);
		}
		if (candidates.isEmpty()) return;
		Collections.sort(candidates, new Comparator<Slot<T>>() {
			@Override
			public int compare(Slot<T> a, Slot<T> b) {
				return Long.compare(a.lastUsed, b.lastUsed);
			}
		});
		long freed = 0;
		for (Slot<T> s : candidates) {
			if (freed >= need) break;
			freed += s.bytes + s.reserved;
			releaseAll(s);
			slots.remove(s.info.key());
		}
	}

	/** Lange nicht benutzte Umhänge freigeben (einmal pro Sekunde aus dem Tick). */
	public void cleanup(long now) {
		budget = UPLOADS_PER_TICK;
		pixelBudget = PIXELS_PER_TICK;
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
		if (now - lastUpgradeCheck >= UPGRADE_CHECK_MS) {
			lastUpgradeCheck = now;
			upgradeOne(now);
		}
	}

	/**
	 * Ist wieder Platz im Budget, wird ein sichtbarer, verkleinert geladener Umhang in besserer Qualität nachgeladen
	 * (einer nach dem anderen; das alte Bild bleibt, bis das neue fertig ist).
	 */
	private void upgradeOne(long now) {
		if (budgetBytes == Long.MAX_VALUE && !anyDownscaled()) return;
		Slot<T> best = null;
		for (Slot<T> s : slots.values()) {
			if (s.requested || s.pending != null) return; // schon etwas in Arbeit
			if (!s.ready || s.downscale <= 1 || now - s.lastUsed > SEEN_MS || now < s.retryAt) continue;
			if (best == null || (s.own && !best.own) || (s.own == best.own && s.lastUsed > best.lastUsed)) best = s;
		}
		if (best == null) return;
		// Während des Nachladens belegt der Umhang beides: die angezeigten Bilder und die neuen.
		long free = budgetBytes == Long.MAX_VALUE ? Long.MAX_VALUE : budgetBytes - usedBytes();
		int k = 1;
		while (k < best.downscale && !best.own && CapeFrames.bytes(best.info.scale, best.info.frames, k) > free) k *= 2;
		if (k < best.downscale) request(best, now, k);
	}

	private boolean anyDownscaled() {
		for (Slot<T> s : slots.values()) if (s.ready && s.downscale > 1) return true;
		return false;
	}

	private void releaseAll(Slot<T> slot) {
		release(slot.textures);
		release(slot.loading);
		slot.textures = new ArrayList<>();
		slot.loading = new ArrayList<>();
		slot.ready = false;
		slot.bytes = 0;
		slot.reserved = 0;
		slot.pending = null;
	}

	private void release(List<T> textures) {
		for (T t : textures) backend.release(t);
	}

	int size() {
		return slots.size();
	}
}

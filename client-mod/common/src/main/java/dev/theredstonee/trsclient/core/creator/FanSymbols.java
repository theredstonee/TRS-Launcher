package dev.theredstonee.trsclient.core.creator;

import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.online.FanInfo;
import dev.theredstonee.trsclient.core.online.SymbolInfo;
import dev.theredstonee.trsclient.core.ui.TextureRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Texturen der Fan-Symbole im Spiel: jedes Symbol (je Meilenstein, der Rahmen ist eingezeichnet) wird einmal geladen
 * ({@link Loader}, im Spiel {@code TrsOnline#loadSymbol}), jedes Bild als eigene Textur hochgeladen und das gerade
 * passende Bild geliefert. Nicht benutzte Symbole werden nach {@link #IDLE_MS} freigegeben.
 *
 * <p>Symbole sind klein (höchstens 66×66 × 8 Bilder) – ein Speicher-Budget wie bei Umhängen braucht es nicht, nur eine
 * Obergrenze für die Zahl gleichzeitig geladener Symbole. Nur aus dem Spiel-/Render-Thread benutzen.
 *
 * @param <T> Textur-Kennung der jeweiligen Minecraft-Version
 */
public final class FanSymbols<T> {
	static final long IDLE_MS = 3 * 60_000L;
	static final long RETRY_MS = 5 * 60_000L;
	/** Höchstens so viele Symbole gleichzeitig (ein Server voller Fans verschiedener Creator). */
	static final int MAX_SLOTS = 96;
	/** Höchstens so viele neue Symbole je Tick hochladen. */
	static final int UPLOADS_PER_TICK = 4;

	/** Lädt ein Symbol im Hintergrund; genau einer der Rückrufe kommt später im Spiel-Thread. */
	public interface Loader {
		void load(SymbolInfo symbol, Consumer<SymbolFrames> done, Runnable failed);
	}

	private static final class Slot<T> {
		final SymbolInfo info;
		final Milestone milestone;
		final String name;
		List<TextureRef> textures = new ArrayList<>();
		SymbolFrames pending;
		boolean requested;
		boolean ready;
		long retryAt;
		long lastUsed;

		Slot(SymbolInfo info, Milestone milestone, String name) {
			this.info = info;
			this.milestone = milestone;
			this.name = name;
		}
	}

	private final CapeTextures.Backend<T> backend;
	private final Loader loader;
	private final Map<String, Slot<T>> slots = new HashMap<>();
	private int serial;
	private int budget = UPLOADS_PER_TICK;
	private long lastCleanup;

	public FanSymbols(CapeTextures.Backend<T> backend, Loader loader) {
		this.backend = backend;
		this.loader = loader;
	}

	static String key(SymbolInfo info, Milestone milestone) {
		return info.sha256 + "|" + (milestone == null ? "-" : milestone.name());
	}

	/**
	 * Textur des aktuellen Bildes des Symbols (mit Meilenstein-Rahmen) oder null, solange sie (noch) nicht da ist.
	 * {@link TextureRef#id} ist die Textur der Version, Breite = Höhe = Kantenlänge mit Rahmen.
	 */
	@SuppressWarnings("unchecked")
	public TextureRef texture(FanInfo fan, long now) {
		if (fan == null || fan.symbol == null) return null;
		String key = key(fan.symbol, fan.milestone);
		Slot<T> slot = slots.get(key);
		if (slot == null) {
			if (slots.size() >= MAX_SLOTS && !evictOldest(now)) return null;
			slot = new Slot<>(fan.symbol, fan.milestone,
					"fan/" + fan.symbol.sha256.substring(0, 12) + "_" + (fan.milestone == null ? "n" : fan.milestone.name().toLowerCase(java.util.Locale.ROOT)) + "_" + (serial++));
			slots.put(key, slot);
		}
		slot.lastUsed = now;
		if (!slot.ready) {
			advance(slot, now);
			if (!slot.ready) return null;
		}
		int n = slot.textures.size();
		return slot.textures.get(slot.info.frameAt(now, n));
	}

	/** Ist das Symbol schon zeichenbar (ohne es anzufordern)? */
	public boolean ready(FanInfo fan) {
		if (fan == null || fan.symbol == null) return false;
		Slot<T> slot = slots.get(key(fan.symbol, fan.milestone));
		return slot != null && slot.ready;
	}

	private void advance(Slot<T> slot, long now) {
		if (slot.pending == null) {
			if (!slot.requested && now >= slot.retryAt) request(slot);
			return;
		}
		if (budget <= 0) return;
		budget--;
		SymbolFrames frames = slot.pending;
		slot.pending = null;
		int size = SymbolArt.framedSize(frames.size, slot.milestone);
		List<TextureRef> out = new ArrayList<>(frames.count());
		for (int i = 0; i < frames.count(); i++) {
			int[] px = SymbolArt.framed(frames.frames[i], frames.size, slot.milestone);
			T tex = backend.upload(slot.name + "/" + i, size, size, px);
			if (tex == null) {
				for (TextureRef t : out) release(t);
				slot.requested = false;
				slot.retryAt = now + RETRY_MS;
				return;
			}
			out.add(new TextureRef(tex, size, size));
		}
		slot.textures = out;
		slot.ready = true;
		slot.requested = false;
	}

	private void request(final Slot<T> slot) {
		slot.requested = true;
		loader.load(slot.info, new Consumer<SymbolFrames>() {
			@Override
			public void accept(SymbolFrames frames) {
				slot.pending = frames;
			}
		}, new Runnable() {
			@Override
			public void run() {
				slot.requested = false;
				slot.retryAt = System.currentTimeMillis() + RETRY_MS;
			}
		});
	}

	@SuppressWarnings("unchecked")
	private void release(TextureRef t) {
		try {
			backend.release((T) t.id);
		} catch (RuntimeException ignored) {
			// Freigeben ist nie kritisch
		}
	}

	private void releaseAll(Slot<T> slot) {
		for (TextureRef t : slot.textures) release(t);
		slot.textures = new ArrayList<>();
		slot.ready = false;
	}

	/** Am längsten nicht benutztes Symbol freigeben (Obergrenze erreicht); false = alle gerade in Benutzung. */
	private boolean evictOldest(long now) {
		String oldest = null;
		long at = Long.MAX_VALUE;
		for (Map.Entry<String, Slot<T>> e : slots.entrySet()) {
			Slot<T> s = e.getValue();
			if (now - s.lastUsed > 1000 && s.lastUsed < at && !s.requested) {
				at = s.lastUsed;
				oldest = e.getKey();
			}
		}
		if (oldest == null) return false;
		releaseAll(slots.remove(oldest));
		return true;
	}

	/** Einmal je Tick: Upload-Budget zurücksetzen, lange nicht benutzte Symbole freigeben (einmal je Sekunde). */
	public void cleanup(long now) {
		budget = UPLOADS_PER_TICK;
		if (now - lastCleanup < 1000) return;
		lastCleanup = now;
		Iterator<Slot<T>> it = slots.values().iterator();
		while (it.hasNext()) {
			Slot<T> slot = it.next();
			if (now - slot.lastUsed > IDLE_MS && !slot.requested) {
				releaseAll(slot);
				it.remove();
			}
		}
	}

	/** Alles freigeben (Konto gewechselt). */
	public void clear() {
		for (Slot<T> s : slots.values()) releaseAll(s);
		slots.clear();
	}

	int size() {
		return slots.size();
	}
}

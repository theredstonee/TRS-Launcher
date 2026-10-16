package dev.theredstonee.trsclient.core.creator;

import dev.theredstonee.trsclient.core.cape.CapeTextures;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.halloween.Sprites;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Profil-Abzeichen vor dem Namen (Halloween 2026: {@code halloween2026} = Mond mit Fledermaus, {@code pumpkinhunter2026}
 * = Kürbis). Wie die Fan-Symbole ein Bild an der Stelle eines unsichtbaren Platzhalters; Bild von
 * {@code GET /v1/badges/{id}.png} ({@link Loader}), bis dahin (oder wenn es nicht lädt) das eingebaute Pixel-Bild
 * ({@link Sprites#forBadge}). Unbekannte Abzeichen ohne eingebautes Bild werden erst gezeigt, wenn ihr Bild da ist.
 *
 * <p>Nur aus dem Spiel-/Render-Thread benutzen.
 *
 * @param <T> Textur-Kennung der jeweiligen Minecraft-Version
 */
public final class EventBadges<T> {
	/** Abzeichen, die vor dem Namen erscheinen – in dieser Reihenfolge der Vorrang (nur eins wird gezeigt). */
	public static final String[] SHOWN = {"pumpkinhunter2026", "halloween2026"};
	static final long RETRY_MS = 10 * 60_000L;
	/** Größtes Bild der API (Pixel ×8: höchstens 32×32 Sprite-Pixel). */
	static final int MAX_SIDE = 256;

	/** Lädt das Bild im Hintergrund; genau einer der Rückrufe kommt später im Spiel-Thread. */
	public interface Loader {
		void load(String badgeId, Done done, Runnable failed);
	}

	/** Fertiges Bild (ARGB). */
	public interface Done {
		void image(int width, int height, int[] argb);
	}

	private static final class Slot {
		TextureRef builtin;
		TextureRef remote;
		boolean requested;
		long retryAt;
		int[] pending;
		int pendingW;
		int pendingH;
	}

	private final CapeTextures.Backend<T> backend;
	private final Loader loader;
	private final Map<String, Slot> slots = new HashMap<String, Slot>();

	public EventBadges(CapeTextures.Backend<T> backend, Loader loader) {
		this.backend = backend;
		this.loader = loader;
	}

	/** Welches Abzeichen zeigt ein Spieler mit diesen Profil-Abzeichen? (Kürbisjäger vor Halloween 2026) null = keins. */
	public static String pick(List<String> badges) {
		if (badges == null || badges.isEmpty()) return null;
		for (String id : SHOWN) {
			if (badges.contains(id)) return id;
		}
		return null;
	}

	/**
	 * Textur des Abzeichens {@code id} (API-Bild, sonst eingebautes) oder null (unbekannt und noch nicht geladen).
	 * {@link TextureRef#width}/{@link TextureRef#height} = Bildmaße (Seitenverhältnis fürs Zeichnen).
	 */
	@SuppressWarnings("unchecked")
	public TextureRef texture(String id, long now) {
		if (id == null) return null;
		Slot s = slots.get(id);
		if (s == null) {
			if (slots.size() > 16) return null;
			s = new Slot();
			slots.put(id, s);
		}
		if (s.pending != null) {
			int[] px = s.pending;
			s.pending = null;
			T tex = backend.upload("badge/" + id + "_api", s.pendingW, s.pendingH, px);
			if (tex != null) s.remote = new TextureRef(tex, s.pendingW, s.pendingH);
		}
		if (s.remote != null) return s.remote;
		if (!s.requested && now >= s.retryAt && loader != null) request(id, s);
		if (s.builtin == null) {
			Sprites.Sprite sprite = Sprites.forBadge(id);
			if (sprite == null) return null;
			int scale = 8;
			T tex = backend.upload("badge/" + id, sprite.w * scale, sprite.h * scale, Sprites.image(sprite, scale));
			if (tex == null) return null;
			s.builtin = new TextureRef(tex, sprite.w * scale, sprite.h * scale);
		}
		return s.builtin;
	}

	private void request(final String id, final Slot s) {
		s.requested = true;
		loader.load(id, new Done() {
			@Override
			public void image(int width, int height, int[] argb) {
				s.requested = false;
				if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE || argb == null || argb.length != width * height) {
					s.retryAt = System.currentTimeMillis() + RETRY_MS;
					return;
				}
				s.pendingW = width;
				s.pendingH = height;
				s.pending = argb;
			}
		}, new Runnable() {
			@Override
			public void run() {
				s.requested = false;
				s.retryAt = System.currentTimeMillis() + RETRY_MS;
			}
		});
	}

	/** Alles freigeben (Konto gewechselt). */
	@SuppressWarnings("unchecked")
	public void clear() {
		for (Slot s : slots.values()) {
			release(s.builtin);
			release(s.remote);
		}
		slots.clear();
	}

	@SuppressWarnings("unchecked")
	private void release(TextureRef t) {
		if (t == null) return;
		try {
			backend.release((T) t.id);
		} catch (RuntimeException ignored) {
			// nie kritisch
		}
	}

	/** Breite des Abzeichens in Schrift-Pixeln bei Höhe {@code size} (Seitenverhältnis des Bildes, mind. 1). */
	public static int widthFor(TextureRef tex, int size) {
		if (tex == null || tex.height <= 0) return size;
		return Math.max(1, Math.round(size * (float) tex.width / tex.height));
	}

	int size() {
		return slots.size();
	}
}

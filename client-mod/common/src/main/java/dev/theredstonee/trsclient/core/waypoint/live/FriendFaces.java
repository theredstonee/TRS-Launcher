package dev.theredstonee.trsclient.core.waypoint.live;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.map.MapSprites;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Köpfe der Freunde für Karte und HUD: Gesicht + Hut aus {@link FaceCache} (Skin über Mojang, im Hintergrund) als
 * kleine 8×8-Textur ({@link Textures.Store}); ohne Texturen Pixel für Pixel ({@link Faces}). Dazu der Blickrichtungs-Pfeil.
 * Nur Spiel-/Render-Thread.
 */
public final class FriendFaces {
	/** Goldton der Freunde (wie die Freundes-Hervorhebung der Karte). */
	public static final int FRIEND = 0xFFFFC23D;
	private static final int MAX = 64;

	private static final class Face {
		TextureRef ref;
		boolean done;
	}

	private static final Map<String, Face> CACHE = new LinkedHashMap<String, Face>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Face> eldest) {
			if (size() <= MAX) return false;
			Textures.Store store = Textures.store();
			if (store != null && eldest.getValue().ref != null) store.release(eldest.getValue().ref);
			return true;
		}
	};

	private FriendFaces() {
	}

	/** Gesichtspixel (128 ARGB) oder null, solange geladen wird / es keins gibt. */
	public static int[] pixels(String uuid) {
		return uuid == null ? null : FaceCache.shared("TRS-Client").face(uuid, null);
	}

	/** Gesicht als Textur oder null (lädt noch, keine Texturen in dieser Version). */
	public static TextureRef texture(String uuid) {
		if (uuid == null) return null;
		Face e = CACHE.get(uuid);
		if (e != null && e.done) return e.ref;
		int[] face = pixels(uuid);
		if (face == null || face.length < 128) return null;
		Textures.Store store = Textures.store();
		if (e == null) {
			e = new Face();
			CACHE.put(uuid, e);
		}
		e.done = true;
		if (store == null) return null;
		int[] argb = new int[64];
		for (int i = 0; i < 64; i++) {
			int hat = face[64 + i];
			argb[i] = (hat >>> 24) >= 0x80 ? hat | 0xFF000000 : face[i] | 0xFF000000;
		}
		e.ref = store.upload("wpshare/face-" + uuid, 8, 8, argb);
		return e.ref;
	}

	/**
	 * Kopf mit dunklem Rand (Mitte x/y, Kantenlänge {@code size}); {@code alpha} 0…255 (verblasst, wenn die Position alt
	 * ist). Fehlt das Gesicht, ein Platzhalter in Freundesfarbe.
	 */
	public static void head(Canvas c, String uuid, String name, float x, float y, float size, int alpha) {
		float half = size / 2f;
		int frame = ColorMath.withAlpha(0xFF101014, Math.min(0xE0, alpha));
		c.fill(Math.round(x - half - 1), Math.round(y - half - 1), Math.round(x + half + 1), Math.round(y + half + 1), frame);
		TextureRef ref = c.images() ? texture(uuid) : null;
		if (ref != null) {
			c.push();
			c.translate(x - half, y - half);
			c.scale(size / 8f, size / 8f);
			c.image(ref, 0, 0, 8, 8, (alpha << 24) | 0xFFFFFF);
			c.pop();
			return;
		}
		int[] face = pixels(uuid);
		if (face != null && alpha >= 0xF0) {
			int px = Math.max(1, Math.round(size / 8f));
			Faces.draw(c, face, uuid, name, Math.round(x - 4 * px), Math.round(y - 4 * px), px, false);
			return;
		}
		// Noch kein Gesicht (lädt, Mojang nicht erreichbar): goldenes Feld mit Anfangsbuchstaben.
		c.fill(Math.round(x - half), Math.round(y - half), Math.round(x + half), Math.round(y + half),
				ColorMath.withAlpha(FRIEND, alpha));
		if (size >= 9 && name != null && !name.isEmpty()) {
			String letter = name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
			c.text(letter, Math.round(x - c.textWidth(letter) / 2f + 0.5f), Math.round(y - 3.5f), ColorMath.withAlpha(0xFF2A1A05, alpha), false);
		}
	}

	/**
	 * Blickrichtung als kleiner Pfeil am Rand des Kopfes. {@code rot} = Drehung im Bildschirm (0 = nach oben),
	 * wie der eigene Pfeil der Karte.
	 */
	public static void facing(Canvas c, MapSprites sprites, float x, float y, float size, float rot, int alpha, double scale) {
		double dx = Math.sin(rot), dy = -Math.cos(rot);
		float r = size / 2f + 3f;
		sprites.draw(c, MapSprites.ARROW, x + (float) (dx * r), y + (float) (dy * r), 7f, rot,
				ColorMath.withAlpha(FRIEND, alpha), scale);
	}

	/** Für Tests/Kontowechsel: Texturen freigeben. */
	public static void clear() {
		Textures.Store store = Textures.store();
		for (Face e : CACHE.values()) {
			if (store != null && e.ref != null) store.release(e.ref);
		}
		CACHE.clear();
	}
}

package dev.theredstonee.trsclient.core.minigames;

import dev.theredstonee.trsclient.core.ui.Affine;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.TextureRef;
import dev.theredstonee.trsclient.core.ui.Textures;

/** Ein Pixelbild als Textur: Gleicher Name und gleiche Größe ersetzen die Pixel an Ort und Stelle. Nur im Render-Thread. */
final class PixelFrame {
	private final String name;
	final int width;
	final int height;
	private TextureRef ref;

	PixelFrame(String name, int width, int height) {
		this.name = name;
		this.width = width;
		this.height = height;
	}

	/** Lädt die Pixel hoch; false, wenn es in dieser Version nicht geht. */
	boolean upload(int[] pixels) {
		Textures.Store store = Textures.store();
		if (store == null) return false;
		ref = store.upload(name, width, height, pixels);
		return ref != null;
	}

	boolean ready() {
		return ref != null;
	}

	/** Ganzes Bild ins Rechteck. */
	void draw(Canvas c, int x, int y, int w, int h) {
		if (ref != null) Affine.image(c, ref, x, y, w, h, 0f, 0f, width, height, 0xFFFFFFFF);
	}

	/** Ausschnitt ins Rechteck. */
	void draw(Canvas c, int x, int y, int w, int h, int u, int v, int tw, int th) {
		if (ref != null) Affine.image(c, ref, x, y, w, h, u, v, tw, th, 0xFFFFFFFF);
	}

	void release() {
		Textures.Store store = Textures.store();
		if (store != null && ref != null) store.release(ref);
		ref = null;
	}
}

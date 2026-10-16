package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.halloween.Sprites;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kürbis-Suche (13 Kürbisse in Launcher, Website und TRS Client; Vertrag „Pumpkin hunt“). Der Client versteckt die
 * vier {@code client-*}-Kürbisse: ein kleiner 12×12-Pixel-Kürbis, ~55 % deckend, wächst beim Überfahren, platzt beim
 * Klick (Toast „Kürbis gefunden! n/13“); schon gefundene werden nicht gezeichnet.
 *
 * <p>Die Bildschirme rufen nur {@link #draw} auf – ob der Kürbis gezeigt wird (Event aktiv, Zustand geladen, noch nicht
 * gefunden), entscheidet diese Klasse.
 */
public final class Pumpkins {
	/** Alle 13 IDs in der Reihenfolge der Hinweise. */
	public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
			"launcher-titlebar", "launcher-settings", "launcher-skins", "launcher-social", "launcher-logs",
			"web-home", "web-features", "web-roadmap", "web-creator",
			"client-title", "client-wardrobe", "client-hud", "client-modules"));
	public static final String CLIENT_TITLE = "client-title";
	public static final String CLIENT_WARDROBE = "client-wardrobe";
	public static final String CLIENT_HUD = "client-hud";
	public static final String CLIENT_MODULES = "client-modules";
	public static final int TOTAL = 13;
	/** Kantenlänge des Kürbisses (Sprite-Pixel = GUI-Pixel). */
	public static final int SIZE = 12;
	/** Dauer der Platz-Animation. */
	static final float POP_SECONDS = 0.8f;

	/** Zustand je Kürbis im Bild: Überfahren (0..1) und Platz-Animation. */
	private static final class Anim {
		float hover;
		float pop = -1;
		long last;
	}

	private static final Map<String, Anim> ANIM = new HashMap<String, Anim>();

	private Pumpkins() {
	}

	/** Ist {@code id} eine der 13 Kürbis-IDs? */
	public static boolean valid(String id) {
		return id != null && ALL.contains(id);
	}

	/**
	 * Zeichnet den Kürbis {@code id} mit seiner Mitte bei ({@code cx}, {@code cy}) und meldet einen Klick in {@code hits},
	 * falls er gerade sichtbar ist. Holt dabei bei Bedarf den Zustand der Suche (einmal, im Hintergrund).
	 *
	 * @return true, wenn etwas gezeichnet wurde
	 */
	public static boolean draw(Canvas c, Hits hits, final String id, int cx, int cy, int mouseX, int mouseY) {
		if (!valid(id) || !Halloween.active()) return false;
		HalloweenService service = HalloweenService.get();
		service.ensure();
		Anim a = ANIM.get(id);
		boolean popping = a != null && a.pop >= 0;
		if (!popping && !service.pumpkinVisible(id)) return false;
		if (a == null) {
			a = new Anim();
			ANIM.put(id, a);
		}
		long now = System.nanoTime();
		float dt = a.last == 0 ? 0f : Math.min(0.1f, (now - a.last) / 1e9f);
		a.last = now;
		int half = SIZE / 2;
		boolean over = !popping && mouseX >= cx - half - 1 && mouseX < cx + half + 1 && mouseY >= cy - half - 1 && mouseY < cy + half + 1;
		a.hover = over ? Math.min(1f, a.hover + dt / 0.3f) : Math.max(0f, a.hover - dt / 0.3f);
		float scale = 1f + 0.25f * smooth(a.hover);
		float alpha = 0.55f + 0.45f * a.hover;
		if (popping) {
			a.pop += dt;
			float p = Math.min(1f, a.pop / POP_SECONDS);
			// Wie @keyframes popout: kurz größer, dann klein und weg.
			scale = p < 0.35f ? 1.25f + p / 0.35f * 0.35f : 1.6f * (1f - (p - 0.35f) / 0.65f);
			alpha = 1f - Math.max(0f, (p - 0.3f) / 0.7f);
			if (p >= 1f) {
				a.pop = -1;
				return false;
			}
		}
		c.push();
		c.translate(cx, cy);
		c.scale(Math.max(0.05f, scale));
		Sprites.draw(c, Sprites.PUMPKIN, -half, -half, 1, alpha);
		c.pop();
		if (!popping && hits != null) {
			final Anim anim = a;
			hits.add(cx - half - 1, cy - half - 1, SIZE + 2, SIZE + 2, new Runnable() {
				@Override
				public void run() {
					if (anim.pop >= 0) return;
					anim.pop = 0f;
					HalloweenService.get().findPumpkin(id);
				}
			});
		}
		return true;
	}

	/** Nur Tests/Autotest: Animation zurücksetzen. */
	static void resetAnimations() {
		ANIM.clear();
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}
}

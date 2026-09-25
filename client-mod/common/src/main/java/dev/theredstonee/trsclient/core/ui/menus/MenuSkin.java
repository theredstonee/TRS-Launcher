package dev.theredstonee.trsclient.core.ui.menus;

import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.FadeCanvas;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.PixelFont;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.title.CircuitScene;

import java.util.List;

/**
 * Redstone-Stil für Vanilla-Menüs (Pause, Serverliste, Ladebildschirme, Optionen, Weltenliste). Zeichnet nur
 * Flächen – Hintergrund, Kopf-/Fußleiste, Listen-Mulde, Knopfflächen, Serverkarten, Ping-Anzeige,
 * Ladeanzeige – die Beschriftungen und Bedienung bleiben die von Minecraft (und damit die anderer Mods).
 * Versionsunabhängig; die Loader-Varianten rufen es aus ihren Hooks auf.
 *
 * <p>Vanilla schreibt weiß auf die Knöpfe; darum sind die Flächen hier immer dunkel (auch im hellen Theme),
 * nur Akzent, Staub und Lampen kommen aus dem {@link Theme}.
 */
public final class MenuSkin {
	/** Höhe der Kopfleiste (Vanilla-Titel steht bei y = 15…20). */
	public static final int HEADER = 32;

	private static final CircuitScene SCENE = new CircuitScene();
	private static final long START = System.nanoTime();
	/** Mittlere Zeichenzeit des Hintergrunds (µs, gleitend) – für den Selbsttest. */
	private static float avgMicros;

	private static final int STONE = 0xFF2E2B2E;
	private static final int STONE_HOVER = 0xFF3B3437;
	private static final int STONE_OFF = 0xFF201E20;
	private static final int EDGE = 0xFF0E0C0D;
	private static final int BEVEL_LIGHT = 0xFF57504F;
	private static final int BEVEL_DARK = 0xFF151213;

	private MenuSkin() {
	}

	private static float seconds() {
		return (System.nanoTime() - START) / 1_000_000_000f;
	}

	/** Sekunden seit dem Start (Animationen). */
	public static float time() {
		return seconds();
	}

	public static float averageMicros() {
		return avgMicros;
	}

	private static void measure(long start) {
		float us = (System.nanoTime() - start) / 1000f;
		avgMicros = avgMicros == 0 ? us : avgMicros + (us - avgMicros) * 0.05f;
	}

	// --- Hintergrund ---

	/**
	 * Hintergrund eines Menüs. Ohne Welt: die Redstone-Schaltung des Startbildschirms; in der Welt: dunkler,
	 * halbdurchsichtiger Schleier mit glimmender Staubkante (die Welt bleibt sichtbar).
	 *
	 * @param headerH Höhe der Kopfleiste (0 = keine)
	 * @param footerTop obere Kante der Fußleiste (≥ height = keine)
	 */
	public static void background(Canvas c, int width, int height, boolean inWorld, int headerH, int footerTop) {
		long start = System.nanoTime();
		Theme t = Theme.get();
		float time = seconds();
		if (inWorld) {
			c.fill(0, 0, width, height, 0x9A0A0708);
			// Glimmen von unten wie bei einer Redstone-Leitung am Boden.
			for (int i = 0; i < 6; i++) {
				int band = Math.max(4, height / 24);
				int y2 = height - i * band;
				c.fill(0, y2 - band, width, y2, ColorMath.withAlpha(t.glow, Math.max(0, 22 - i * 4)));
			}
			Redstone.dustH(c, 0, width, height - 2, ColorMath.lerp(t.dustOff, t.dustOn, 0.55f + 0.2f * pulse(time)), 0.4f);
		} else {
			scene(c, width, height, time);
		}
		if (headerH > 0) header(c, width, headerH, inWorld);
		if (footerTop < height) footer(c, width, footerTop, height, inWorld);
		measure(start);
	}

	/** Redstone-Schaltung des Startbildschirms (ruhig, wenn der animierte Hintergrund aus ist). */
	private static void scene(Canvas c, int width, int height, float time) {
		boolean animated = dev.theredstonee.trsclient.core.menus.MenuStyle.animated();
		if (sceneSimple == null || sceneSimple != !animated) {
			// setSimple baut die Schaltung neu – nur beim Umschalten aufrufen.
			sceneSimple = !animated;
			SCENE.setSimple(!animated);
		}
		SCENE.draw(c, width, height, animated ? time : 0f, null);
	}

	private static Boolean sceneSimple;

	private static float pulse(float time) {
		return 0.5f + 0.5f * (float) Math.sin(time * 2.2);
	}

	/** Kopfleiste mit kleinem TRS-Schriftzug links und Staubleitung unten. */
	public static void header(Canvas c, int width, int h, boolean inWorld) {
		Theme t = Theme.get();
		c.fill(0, 0, width, h, inWorld ? 0x8C0C0A0B : 0xD8141113);
		c.fill(0, 0, width, 1, ColorMath.withAlpha(BEVEL_LIGHT, 0x60));
		Redstone.dustH(c, 0, width, h - 2, ColorMath.lerp(t.dustOff, t.dustOn, 0.8f), 0.7f);
		logo(c, 8, (h - PixelFont.HEIGHT * 2) / 2, 2, 1f);
	}

	/** Fußleiste (Knopfbereich unter einer Liste). */
	public static void footer(Canvas c, int width, int top, int height, boolean inWorld) {
		Theme t = Theme.get();
		c.fill(0, top, width, height, inWorld ? 0x8C0C0A0B : 0xD8141113);
		Redstone.dustH(c, 0, width, top, ColorMath.lerp(t.dustOff, t.dustOn, 0.8f), 0.7f);
	}

	/** „TRS“ in der Pixelschrift, glühend; {@code scale} = Pixel je Schriftpunkt. */
	public static void logo(Canvas c, int x, int y, int scale, float alpha) {
		Theme t = Theme.get();
		List<int[]> rects = PixelFont.rects("TRS");
		int glow = ColorMath.withAlpha(t.glow, Math.round(40 * alpha));
		for (int[] r : rects) c.fill(x + r[0] * scale - 1, y + r[1] * scale - 1, x + r[2] * scale + 1, y + r[3] * scale + 1, glow);
		int light = ColorMath.lerp(t.accent, 0xFFFFFFFF, 0.3f);
		for (int[] r : rects) {
			int col = r[1] == 0 ? light : t.accent;
			c.fill(x + r[0] * scale, y + r[1] * scale, x + r[2] * scale, y + r[3] * scale, ColorMath.fade(col, alpha));
		}
	}

	/** Eingelassene Mulde hinter einer Liste (Server, Welten, Optionen). */
	public static void listWell(Canvas c, int x1, int y1, int x2, int y2, boolean inWorld) {
		if (x2 - x1 < 4 || y2 - y1 < 4) return;
		c.fill(x1, y1, x2, y2, inWorld ? 0x70000000 : 0xA8080607);
		c.fill(x1, y1, x2, y1 + 1, 0x80000000);
		c.fill(x1, y2 - 1, x2, y2, ColorMath.withAlpha(BEVEL_LIGHT, 0x50));
	}

	/** Trennlinien einer Liste (oben/unten) als Staubleitungen. */
	public static void listSeparators(Canvas c, int x1, int y1, int x2, int y2) {
		Theme t = Theme.get();
		int dust = ColorMath.lerp(t.dustOff, t.dustOn, 0.6f);
		Redstone.dustH(c, x1, x2, y1 - 2, dust, 0.3f);
		Redstone.dustH(c, x1, x2, y2, dust, 0.3f);
	}

	// --- Knöpfe ---

	/**
	 * Fläche eines Vanilla-Knopfs (die Beschriftung zeichnet Minecraft darüber): Stein mit Lichtkante, beim
	 * Überfahren glimmt eine Staubleitung, gesperrte Knöpfe sind dunkler.
	 */
	public static void button(Canvas c, int x, int y, int w, int h, boolean hover, boolean active) {
		if (w < 3 || h < 3) return;
		Theme t = Theme.get();
		boolean hot = hover && active;
		int fill = !active ? STONE_OFF : (hot ? STONE_HOVER : STONE);
		if (hot) Redstone.glow(c, x, y, w, h, t.glow, 0.55f);
		Redstone.block(c, x, y, w, h, hot ? ColorMath.lerp(EDGE, t.accent, 0.55f) : EDGE);
		if (h < 5) return;
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
		c.fill(x + 1, y + 1, x + w - 1, y + 2, active ? ColorMath.lerp(fill, BEVEL_LIGHT, 0.8f) : ColorMath.lerp(fill, BEVEL_LIGHT, 0.3f));
		c.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, BEVEL_DARK);
		if (hot) {
			Redstone.dustH(c, x + 3, x + w - 3, y + h - 4, t.dustOn, 0f);
		} else if (active && w > 12) {
			// Unbestromter Staub: dunkles Rot, zeigt den Redstone-Stil auch ohne Maus.
			c.fill(x + 3, y + h - 4, x + w - 3, y + h - 3, ColorMath.withAlpha(t.dustOff, 0x90));
		}
	}

	/** Symbol links auf einem Knopf (TRS-Knöpfe im Pausenmenü). */
	public static void buttonIcon(Canvas c, int x, int y, int h, String icon, boolean active) {
		Theme t = Theme.get();
		Icons.draw(c, icon, x + 5, y + (h - 8) / 2, 1, active ? t.dustOn : ColorMath.fade(t.textDim, 0.8f));
	}

	/** Zähler-Abzeichen (z. B. offene Freundschaftsanfragen) oben rechts an einem Knopf. */
	public static void badge(Canvas c, int right, int top, int count) {
		if (count <= 0) return;
		Theme t = Theme.get();
		String s = count > 99 ? "99+" : String.valueOf(count);
		int w = Math.max(9, c.textWidth(s) + 5);
		int x = right - w + 3;
		int y = top - 3;
		Redstone.glow(c, x, y, w, 10, t.lampGlow, 0.6f);
		Redstone.block(c, x, y, w, 10, t.lampOnEdge);
		c.fill(x + 1, y + 1, x + w - 1, y + 9, t.lampOn);
		c.text(s, x + (w - c.textWidth(s)) / 2 + 1, y + 1, t.lampTextLit, false);
	}

	// --- Serverliste ---

	/** Karte hinter einem Server-/Welt-Eintrag (Vanilla-Inhalt wird darüber gezeichnet). */
	public static void card(Canvas c, int x, int y, int w, int h, boolean selected, boolean hover, boolean pinned) {
		Theme t = Theme.get();
		if (w < 4 || h < 4) return;
		if (selected) Redstone.glow(c, x, y, w, h, t.glow, 0.5f);
		int edge = selected ? ColorMath.lerp(EDGE, t.accent, 0.8f) : (hover ? ColorMath.lerp(EDGE, t.accent, 0.35f) : EDGE);
		Redstone.block(c, x, y, w, h, edge);
		int fill = cardFill(selected, hover);
		c.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
		c.fill(x + 1, y + 1, x + w - 1, y + 2, ColorMath.withAlpha(BEVEL_LIGHT, 0x70));
		if (pinned) {
			// Angeheftet: Staubleitung an der linken Kante, bestromt.
			Redstone.dustV(c, x + 1, y + 2, y + h - 2, t.dustOn, 0.8f);
		}
	}

	/** Füllfarbe einer Karte (deckend – die Ping-Anzeige deckt damit die Vanilla-Balken ab). */
	public static int cardFill(boolean selected, boolean hover) {
		return selected ? 0xFF33292B : (hover ? 0xFF2B2628 : 0xFF1F1C1E);
	}

	/** Markierung „angeheftet“: kleine Redstone-Fackel. */
	public static void pin(Canvas c, int x, int y) {
		Redstone.torch(c, x + 4, y + 12, 1f, pulse(seconds() * 1.7f));
	}

	/**
	 * Ping als fünf Staub-Balken (wie die Vanilla-Balken, aber bestromter Redstone); deckt die Vanilla-Anzeige
	 * (10×8 bei x, y) ab. {@code ping} &lt; 0 = keine Antwort, {@code pinging} = läuft noch.
	 */
	public static void pingBars(Canvas c, int x, int y, long ping, boolean pinging, int coverColor) {
		Theme t = Theme.get();
		c.fill(x - 1, y - 1, x + 11, y + 9, coverColor);
		int lit;
		if (pinging) {
			lit = -1;
		} else if (ping < 0) {
			lit = 0;
		} else if (ping < 150) {
			lit = 5;
		} else if (ping < 300) {
			lit = 4;
		} else if (ping < 600) {
			lit = 3;
		} else if (ping < 1000) {
			lit = 2;
		} else {
			lit = 1;
		}
		int running = (int) (seconds() * 8) % 8;
		for (int i = 0; i < 5; i++) {
			int bh = 2 + i * 1 + (i > 2 ? 1 : 0) + 1;
			int bx = x + i * 2;
			int by = y + 8 - bh;
			int color;
			if (lit < 0) color = (i == running || i == running - 1) ? t.dustOn : t.dustOff;
			else if (lit == 0) color = 0xFF3A3A3A;
			else color = i < lit ? t.dust(9 + lit + (i == lit - 1 ? 1 : 0)) : t.dustOff;
			c.fill(bx, by, bx + 1, y + 8, color);
		}
		if (lit == 0) {
			// Kein Signal: kleines rotes Kreuz.
			c.fill(x + 3, y + 2, x + 4, y + 3, t.dustOn);
			c.fill(x + 6, y + 2, x + 7, y + 3, t.dustOn);
			c.fill(x + 4, y + 3, x + 6, y + 5, t.dustOn);
			c.fill(x + 3, y + 5, x + 4, y + 6, t.dustOn);
			c.fill(x + 6, y + 5, x + 7, y + 6, t.dustOn);
		}
	}

	// --- Laden ---

	/**
	 * Vollständiger Ladebildschirm (Welt laden, verbinden, speichern …): Schaltung, TRS-Schriftzug, Titel,
	 * eine Reihe Redstone-Lampen als Fortschritt ({@code progress} 0..1, &lt; 0 = unbestimmt: ein Signal läuft
	 * durch) und eine Zeile darunter.
	 */
	public static void loading(Canvas c, int width, int height, String title, String detail, float progress, boolean inWorld) {
		long start = System.nanoTime();
		Theme t = Theme.get();
		float time = seconds();
		if (inWorld) {
			c.fill(0, 0, width, height, 0xE00A0708);
		} else {
			scene(c, width, height, time);
			c.fill(0, 0, width, height, 0x60000000);
		}
		int cx = width / 2;
		int scale = height >= 300 ? 4 : 3;
		int logoW = PixelFont.width("TRS") * scale;
		int blockH = PixelFont.HEIGHT * scale + 14 + 12 + 22 + 12;
		int top = Math.max(8, (height - blockH) / 2 - 10);
		// Panel hinter dem Inhalt, damit Text auf der Schaltung lesbar bleibt.
		int pw = Math.min(width - 16, Math.max(220, Math.max(c.textWidth(title), c.textWidth(detail == null ? "" : detail)) + 40));
		int ph = blockH + 20;
		Redstone.window(c, cx - pw / 2, top - 10, pw, ph);
		logo(c, cx - logoW / 2, top, scale, 1f);
		int y = top + PixelFont.HEIGHT * scale + 12;
		if (title != null && !title.isEmpty()) {
			String s = c.clip(title, pw - 16);
			c.text(s, cx - c.textWidth(s) / 2, y, t.text, true);
		}
		y += 16;
		lamps(c, cx, y, Math.min(pw - 24, 176), progress, time);
		y += 22;
		if (detail != null && !detail.isEmpty()) {
			String s = c.clip(detail, pw - 16);
			c.text(s, cx - c.textWidth(s) / 2, y, t.textDim, false);
		}
		measure(start);
	}

	/**
	 * Reihe Redstone-Lampen, mittig um {@code cx}. {@code progress} 0..1 = so viele leuchten; &lt; 0 = ein
	 * Signal läuft durch die Reihe (Staub dazwischen leuchtet mit).
	 */
	public static void lamps(Canvas c, int cx, int y, int maxWidth, float progress, float time) {
		Theme t = Theme.get();
		int n = 10;
		int size = 12;
		int gap = Math.max(2, Math.min(6, (maxWidth - n * size) / (n - 1)));
		int total = n * size + (n - 1) * gap;
		int x0 = cx - total / 2;
		float lit = progress < 0 ? -1 : Math.max(0f, Math.min(1f, progress)) * n;
		float head = (time * 6f) % (n + 3) - 1.5f;
		for (int i = 0; i < n; i++) {
			int x = x0 + i * (size + gap);
			float l;
			if (lit < 0) {
				float d = Math.abs(i - head);
				l = d < 1f ? 1f - d * 0.6f : (d < 2f ? 0.4f * (2f - d) : 0f);
			} else {
				l = Math.max(0f, Math.min(1f, lit - i));
			}
			if (i < n - 1) {
				int dust = l > 0.5f ? t.dustOn : t.dustOff;
				c.fill(x + size, y + size / 2 - 1, x + size + gap, y + size / 2 + 1, dust);
			}
			Redstone.lamp(c, x, y, size, size, l, 0f);
		}
	}

	/**
	 * Überblendung beim Laden der Ressourcen (statt Mojang-Logo). Nur Rechtecke, keine Schrift – beim ersten
	 * Start ist die Schrift noch nicht geladen. {@code alpha} = Deckkraft der Überblendung.
	 */
	public static void resourceOverlay(Canvas raw, int width, int height, float progress, float alpha) {
		if (alpha <= 0.004f) return;
		Canvas c = FadeCanvas.of(raw, Math.min(1f, alpha));
		Theme t = Theme.get();
		c.fill(0, 0, width, height, 0xFF120D0E);
		// Gitter aus unbestromtem Staub, sehr dezent.
		int cell = 32;
		for (int x = cell / 2; x < width; x += cell) c.fill(x, 0, x + 1, height, 0x10FF3020);
		for (int y = cell / 2; y < height; y += cell) c.fill(0, y, width, y + 1, 0x10FF3020);
		int scale = Math.max(2, Math.min(6, width / 110));
		String word = "TRS CLIENT";
		int w = PixelFont.width(word) * scale;
		int x = (width - w) / 2;
		int y = height / 2 - PixelFont.HEIGHT * scale - 6;
		List<int[]> rects = PixelFont.rects(word);
		int glow = ColorMath.withAlpha(t.glow, 46);
		for (int[] r : rects) c.fill(x + r[0] * scale - 1, y + r[1] * scale - 1, x + r[2] * scale + 1, y + r[3] * scale + 1, glow);
		int light = ColorMath.lerp(t.accent, 0xFFFFFFFF, 0.3f);
		for (int[] r : rects) c.fill(x + r[0] * scale, y + r[1] * scale, x + r[2] * scale, y + r[3] * scale, r[1] == 0 ? light : t.accent);
		lamps(c, width / 2, height / 2 + 14, Math.min(width - 40, 220), progress, seconds());
	}
}

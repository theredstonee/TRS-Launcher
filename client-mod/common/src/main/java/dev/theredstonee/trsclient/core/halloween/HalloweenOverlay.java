package dev.theredstonee.trsclient.core.halloween;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.intro.IntroGate;
import dev.theredstonee.trsclient.core.module.HalloweenModules;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.halloween.HorrorLayer;
import dev.theredstonee.trsclient.core.ui.halloween.Sprites;

/**
 * Halloween über jedem TRS-Bildschirm ({@code UiScreen}): Filmkorn/Vignette/Blitz ({@link HorrorLayer}), Toasts
 * ({@link HalloweenToast}), beim ersten Mal der Hinweis „Flackern &amp; Rauschen“ (oben links, [Ausschalten] [Behalten])
 * und einmal je Konto-Instanz der Hinweis „Jumpscares möglich“.
 */
public final class HalloweenOverlay {
	private static final int HINT_W = 200;

	private HalloweenOverlay() {
	}

	/** Halloween-Module des laufenden Spiels oder null (Tests ohne Spiel). */
	public static HalloweenModules modules() {
		TrsModules m = IntroGate.modules();
		return m == null ? null : m.halloween;
	}

	/** „Flackern &amp; Rauschen“ an? (ohne Spiel: an) */
	public static boolean flicker() {
		HalloweenModules h = modules();
		return h == null || h.flicker();
	}

	/**
	 * Nach dem Zeichnen eines TRS-Bildschirms. {@code overlay} = Bildschirm liegt über dem Spiel (Emote-Rad, Schnellantwort)
	 * – dann nur Toasts.
	 */
	public static void afterScreen(Canvas c, Hits hits, int width, int height, int mouseX, int mouseY, boolean overlay) {
		if (Halloween.active()) {
			if (!overlay) HorrorLayer.draw(c, width, height, false);
			infoOnce();
			if (!overlay) flickerHint(c, hits, mouseX, mouseY);
		}
		HalloweenToast.draw(c, width, height);
	}

	/** Im HUD (in der Welt): Toasts (Kürbisse findet man in Menüs, der Toast läuft weiter). */
	public static void hud(Canvas c, int width, int height) {
		if (Halloween.active()) infoOnce();
		HalloweenToast.draw(c, width, height);
	}

	/** Beim ersten Halloween-Start: nur ein Hinweis-Toast „Jumpscares möglich“. */
	private static void infoOnce() {
		HalloweenModules h = modules();
		if (h == null || h.local.scareInfoShown()) return;
		h.local.markScareInfoShown();
		IntroGate.save(IntroGate.modules());
		if (h.jumpscares.get()) {
			HalloweenToast.show(HalloweenToast.GHOST, I18n.tr("halloween.scareInfo.title"), I18n.tr("halloween.scareInfo.text"));
		}
	}

	private static void flickerHint(Canvas c, Hits hits, int mouseX, int mouseY) {
		final HalloweenModules h = modules();
		if (h == null || h.local.flickerHintShown() || h.local.flickerChosen()) return;
		String title = I18n.tr("halloween.flickerHint.title");
		String text = I18n.tr("halloween.flickerHint.text");
		int x = 6;
		int y = 6;
		int w = HINT_W;
		java.util.List<String> lines = Paint.wrap(c, text, w - 34);
		int h2 = 24 + lines.size() * 10 + 18;
		c.push();
		c.raise(380f);
		HalloweenToast.panel(c, x, y, w, h2, 1f);
		Sprites.draw(c, Sprites.MOON, x + 7, y + 7, 1, 1f);
		c.text(c.clip(title, w - 32), x + 24, y + 8, 0xFFF3F5FB, false);
		int ty = y + 22;
		for (String line : lines) {
			c.text(line, x + 24, ty, 0xFF9A94B4, false);
			ty += 10;
		}
		String off = I18n.tr("halloween.flickerHint.off");
		String keep = I18n.tr("halloween.flickerHint.keep");
		int bx = x + 24;
		int by = ty + 2;
		bx = hintButton(c, hits, bx, by, off, mouseX, mouseY, new Runnable() {
			@Override
			public void run() {
				h.local.setFlicker(false);
				h.local.markFlickerHintShown();
				IntroGate.save(IntroGate.modules());
			}
		});
		hintButton(c, hits, bx + 4, by, keep, mouseX, mouseY, new Runnable() {
			@Override
			public void run() {
				h.local.markFlickerHintShown();
				IntroGate.save(IntroGate.modules());
			}
		});
		c.pop();
	}

	private static int hintButton(Canvas c, Hits hits, int x, int y, String label, int mx, int my, Runnable action) {
		int w = c.textWidth(label) + 10;
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + 13;
		c.fill(x, y, x + w, y + 13, hover ? 0xE03A1F5C : 0xC0100C1C);
		int edge = hover ? 0xFFF3F5FB : 0xFF5B5280;
		c.fill(x, y, x + w, y + 1, edge);
		c.fill(x, y + 12, x + w, y + 13, edge);
		c.fill(x, y, x + 1, y + 13, edge);
		c.fill(x + w - 1, y, x + w, y + 13, edge);
		c.text(label, x + 5, y + 3, ColorMath.withAlpha(0xFFF3F5FB, 0xFF), false);
		if (hits != null) hits.add(x, y, w, 13, action);
		return x + w;
	}
}

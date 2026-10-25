package dev.theredstonee.trsclient.core.intro;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.module.TrsModules;
import dev.theredstonee.trsclient.core.sync.ClientSync;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;

/**
 * Aussehen im Client wählen (Einführung und TRS-Menü): Farbschema, Akzent und Design (Redstone | Winternacht | Fuchsbau). Die Wahl
 * gilt sofort, wird lokal gemerkt und – Thema, Akzent, Sprache – mit dem TRS-Konto synchronisiert; das Design bleibt
 * auf diesem PC (die Konto-Einstellungen kennen es nicht), der Launcher übergibt beim Start sein eigenes.
 */
public final class LookPicker {
	public static final String[] THEMES = {"dark", "oled", "light"};
	public static final String[] ACCENTS = {"redstone", "lamp", "emerald", "lapis", "amethyst", "ice", "fox"};
	public static final String[] DESIGNS = {Theme.REDSTONE, Theme.WINTER, Theme.FOX};
	/** Breite der Design-Wahl (drei Knöpfe). */
	public static final int DESIGNS_WIDTH = 456;

	private LookPicker() {
	}

	/**
	 * Übernimmt eine Wahl (null = unverändert). Beim Designwechsel folgt der Akzent wie im Launcher (Redstone-Rot,
	 * Eisblau, Fuchsorange), eine selbst gewählte Farbe bleibt.
	 */
	public static void choose(TrsModules modules, String theme, String accent, String language, String design) {
		Theme cur = Theme.get();
		String th = theme != null ? theme : cur.themeName;
		String ac = accent != null ? accent : cur.accentName;
		String la = language != null ? language : I18n.code();
		String de = design != null ? Theme.normalizeDesign(design) : cur.designName;
		if (design != null && accent == null && !de.equals(cur.designName)) ac = Theme.accentForDesign(de, ac);
		long now = System.currentTimeMillis();
		ClientSync sync = ClientSync.get();
		if (sync != null) {
			sync.chooseLook(th, ac, la, de, now);
		} else if (modules != null) {
			modules.clientState.chooseLook(th, ac, la, de, now);
			ClientSync.applyLook(th, ac, la, de);
		} else {
			ClientSync.applyLook(th, ac, la, de);
		}
		if (modules != null) IntroGate.save(modules);
	}

	/** Name eines Designs in der aktuellen Sprache. */
	public static String designName(String design) {
		return I18n.tr("look.design." + Theme.normalizeDesign(design));
	}

	/**
	 * Wahl-Knopf eines Designs: kleines Vorschaubild und Name; gewählt = leuchtende Lampe bzw. Eislaterne.
	 * Liefert nichts – die Klickfläche registriert der Aufrufer.
	 */
	public static void designChip(Canvas c, int x, int y, int w, int h, String design, boolean selected, boolean hover) {
		Theme t = Theme.get();
		if (selected) {
			Redstone.lamp(c, x, y, w, h, 1f, 0f);
		} else {
			Redstone.stone(c, x, y, w, h, hover ? t.surfaceHover : t.surfaceHigh, hover ? ColorMath.lerp(t.border, t.textDim, 0.5f) : t.border);
		}
		int ph = h - 6;
		int pw = Math.min(w / 3, Math.round(ph * 1.5f));
		preview(c, x + 3, y + 3, pw, ph, design);
		String label = c.clip(designName(design), w - pw - 10);
		int color = selected ? Redstone.lampTextColor(1f) : t.text;
		int room = w - pw - 6;
		c.text(label, x + pw + 6 + Math.max(0, (room - 4 - c.textWidth(label)) / 2), y + (h - 8) / 2, color, false);
	}

	/**
	 * Kleines Vorschaubild eines Designs (nur Rechtecke): Schiefer mit Staub und Lampe, Nacht mit Bergen, Schnee und
	 * Hütte, oder Dämmerung mit Sonne, Fichten und Lagerfeuer.
	 */
	public static void preview(Canvas c, int x, int y, int w, int h, String design) {
		if (w < 6 || h < 6) return;
		if (Theme.FOX.equals(Theme.normalizeDesign(design))) {
			// Dämmerung in Bändern, eckige Sonne über rosa Hügeln, Fichten, Wiese, Lagerfeuer mit Fuchs.
			int[] sky = {0xFF3E2044, 0xFF7A3A5A, 0xFFC05E5A, 0xFFF09A64};
			int horizon = y + h * 3 / 5;
			for (int i = 0; i < sky.length; i++) c.fill(x, y + (horizon - y) * i / sky.length, x + w, y + (horizon - y) * (i + 1) / sky.length, sky[i]);
			int sx = x + w * 3 / 5;
			c.fill(sx - 1, horizon - 4, sx + 2, horizon - 1, 0xFFFFF2C0);
			for (int i = 0; i < w; i += 2) {
				int hh = 1 + Math.floorMod(i * 7 + 3, 3);
				c.fill(x + i, horizon - hh, x + i + 2, horizon, 0xFFB4607A);
			}
			c.fill(x, horizon, x + w, y + h, 0xFF5A5634);
			c.fill(x, horizon, x + w, horizon + 1, 0xFF8A7A46);
			c.fill(x, y + h - 2, x + w, y + h, 0xFF6E4630);
			int[] trees = {1, 4, w - 4};
			for (int tx : trees) {
				if (tx < 0 || tx + 3 > w) continue;
				c.fill(x + tx + 1, horizon - 5, x + tx + 2, horizon - 3, 0xFF1F3A2E);
				c.fill(x + tx, horizon - 3, x + tx + 3, horizon + 1, 0xFF1F3A2E);
				c.fill(x + tx + 1, horizon + 1, x + tx + 2, horizon + 2, 0xFF3B2614);
			}
			int fx = x + w / 3;
			c.fill(fx, y + h - 4, fx + 2, y + h - 2, 0xFFFFB340);
			c.fill(fx, y + h - 5, fx + 1, y + h - 4, 0xFFFFF2A8);
			c.fill(fx + 3, y + h - 3, fx + 6, y + h - 2, 0xFFE47C3A);
			c.fill(fx + 3, y + h - 4, fx + 4, y + h - 3, 0xFFE47C3A);
			Redstone.frame(c, x - 1, y - 1, w + 2, h + 2, 0x60FF9A52);
			return;
		}
		if (Theme.WINTER.equals(Theme.normalizeDesign(design))) {
			c.fill(x, y, x + w, y + h, 0xFF0A1224);
			c.fill(x, y + h / 3, x + w, y + h * 2 / 3, 0xFF152442);
			// Polarlicht-Streifen, Mond, Berge, Schnee.
			c.fill(x + w / 3, y + h / 5, x + w - 2, y + h / 5 + 1, 0xC03DFF86);
			c.fill(x + w - 4, y + 1, x + w - 2, y + 3, 0xFFEEF4FF);
			int horizon = y + h * 3 / 5;
			// Zwei Gipfel mit Schneehaube.
			for (int i = 0; i < w; i++) {
				float u = i / (float) w;
				float m1 = 1f - Math.abs(u - 0.3f) / 0.32f;
				float m2 = 1f - Math.abs(u - 0.72f) / 0.26f;
				int top = Math.round(Math.max(Math.max(m1 * 0.42f, m2 * 0.32f), 0f) * h);
				if (top <= 0) continue;
				c.fill(x + i, horizon - top, x + i + 1, horizon, u > 0.3f && u < 0.72f ? 0xFF1A2744 : 0xFF25365A);
				c.fill(x + i, horizon - top, x + i + 1, horizon - top + Math.max(1, top / 3), 0xFFE3EFFF);
			}
			c.fill(x, horizon, x + w, y + h, 0xFFAAC2E4);
			c.fill(x, horizon, x + w, horizon + 1, 0xFFE3EFFF);
			// Hütte mit warmem Fenster.
			int hx = x + w - Math.max(5, w / 3);
			c.fill(hx, y + h - 4, hx + 4, y + h - 1, 0xFF553A30);
			c.fill(hx, y + h - 5, hx + 4, y + h - 4, 0xFFFFFFFF);
			c.fill(hx + 1, y + h - 3, hx + 2, y + h - 2, 0xFFFFC66B);
			c.fill(x + 2, y + 2, x + 3, y + 3, 0xFFF4F9FF);
			c.fill(x + w / 2, y + 4, x + w / 2 + 1, y + 5, 0xFFF4F9FF);
			Redstone.frame(c, x - 1, y - 1, w + 2, h + 2, 0x60B8E7FF);
			return;
		}
		c.fill(x, y, x + w, y + h, 0xFF18181C);
		c.fill(x, y + h / 2, x + w, y + h / 2 + 1, 0xFF121215);
		int mid = y + h / 2 - 1;
		c.fill(x + 1, mid, x + w - 5, mid + 2, 0xFFE0281E);
		c.fill(x + w - 5, y + 2, x + w - 1, y + h - 2, 0xFFF2B454);
		c.fill(x + w - 4, y + 3, x + w - 2, y + h - 3, 0xFFFFE7A6);
		c.fill(x + 1, mid - 3, x + 3, mid, 0xFFFF5040);
		Redstone.frame(c, x - 1, y - 1, w + 2, h + 2, 0x60E0281E);
	}

	/**
	 * Zeichnet die Design-Wahl (drei Knöpfe nebeneinander) und registriert die Klicks.
	 *
	 * @return Höhe des Bereichs
	 */
	public static int drawDesigns(Canvas c, Hits hits, final TrsModules modules, int x, int y, int w, int mx, int my,
			final Runnable click) {
		Theme t = Theme.get();
		int gap = 4;
		int h = 18;
		int n = DESIGNS.length;
		int cw = Math.min(150, (w - gap * (n - 1)) / n);
		for (int i = 0; i < DESIGNS.length; i++) {
			final String d = DESIGNS[i];
			int cx = x + i * (cw + gap);
			boolean hover = mx >= cx && mx < cx + cw && my >= y && my < y + h;
			designChip(c, cx, y, cw, h, d, d.equals(t.designName), hover);
			hits.add(cx, y, cw, h, new Runnable() {
				@Override
				public void run() {
					if (click != null) click.run();
					choose(modules, null, null, null, d);
				}
			});
		}
		return h;
	}
}

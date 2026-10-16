package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.halloween.HalloweenService;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Halloween-Seite: Flügel-Wahl nach allen 13 Kürbissen (Nachtrag „Wings“). Vier Möglichkeiten als Karten mit kleiner
 * Pixel-Flügel-Vorschau; Auswahl → Bestätigung „endgültig“ → {@code POST /v1/me/halloween/wings}. Nach der Wahl nur noch
 * „Deine Flügel: …“ mit dem Hinweis auf die Garderobe. Vor dem Ende der Suche (und ohne {@code wings} im Zustand) nichts.
 */
public final class WingsChoiceCard {
	/** Bekannte Halloween-Flügel (Reihenfolge, falls die API keine Liste liefert). */
	static final List<String> DEFAULT_OPTIONS = Arrays.asList("bat_wings", "demon_wings", "raven_wings", "ghost_wings");

	private String selected;
	private boolean confirming;

	/**
	 * Zeichnet die Karte ab {@code y} und liefert die neue Unterkante (= {@code y}, wenn es nichts zu zeigen gibt).
	 * {@code click} = Klick-Ton.
	 */
	public int draw(Canvas c, Hits hits, int x, int y, int w, int mx, int my, final Runnable click) {
		final HalloweenService s = HalloweenService.get();
		String choice = s.wingsChoice();
		boolean can = s.canChooseWings();
		if (choice == null && !can) return y;
		List<String> options = new ArrayList<String>(s.wingsOptions());
		if (options.isEmpty()) options.addAll(DEFAULT_OPTIONS);
		if (options.size() > 6) options = options.subList(0, 6);

		if (choice != null) {
			List<String> hint = Paint.wrap(c, I18n.tr("halloween.wings.wardrobeHint"), w - 52);
			int h = 12 + 10 + hint.size() * 10 + 10;
			h = Math.max(h, 40);
			HalloweenPage.card(c, x, y, w, h);
			preview(c, choice, x + 8, y + 10, 2, true);
			c.text(c.clip(I18n.tr("halloween.wings.yours", name(choice)), w - 52), x + 44, y + 8, HalloweenPage.SILVER, false);
			int ty = y + 20;
			for (String line : hint) {
				c.text(line, x + 44, ty, HalloweenPage.MUTED, false);
				ty += 10;
			}
			return y + h;
		}

		List<String> intro = Paint.wrap(c, I18n.tr("halloween.wings.intro"), w - 16);
		int cols = Math.min(options.size(), w >= 300 ? 4 : 2);
		int gap = 6;
		int tw = (w - 16 - (cols - 1) * gap) / cols;
		int th = 44;
		int rows = (options.size() + cols - 1) / cols;
		String err = s.wingsError();
		int footer = confirming && selected != null ? 34 : (err != null ? 14 : 4);
		int h = 8 + 10 + 2 + intro.size() * 10 + 4 + rows * (th + gap) - gap + footer + 6;
		HalloweenPage.card(c, x, y, w, h);
		c.text(c.clip(I18n.tr("halloween.wings.title"), w - 16), x + 8, y + 7, HalloweenPage.SILVER, false);
		int ty = y + 20;
		for (String line : intro) {
			c.text(line, x + 8, ty, HalloweenPage.VIOLET, false);
			ty += 10;
		}
		ty += 4;
		boolean busy = s.wingsBusy();
		for (int i = 0; i < options.size(); i++) {
			final String id = options.get(i);
			int ox = x + 8 + (i % cols) * (tw + gap);
			int oy = ty + (i / cols) * (th + gap);
			boolean sel = id.equals(selected);
			boolean hover = mx >= ox && mx < ox + tw && my >= oy && my < oy + th;
			c.fill(ox, oy, ox + tw, oy + th, sel ? 0xE03A1F5C : (hover ? 0xD0241B38 : 0xC0120E1E));
			int edge = sel ? HalloweenPage.SILVER : (hover ? 0xFF8E6CC9 : 0xFF3B3358);
			c.fill(ox, oy, ox + tw, oy + 1, edge);
			c.fill(ox, oy + th - 1, ox + tw, oy + th, edge);
			c.fill(ox, oy, ox + 1, oy + th, edge);
			c.fill(ox + tw - 1, oy, ox + tw, oy + th, edge);
			preview(c, id, ox + (tw - 30) / 2, oy + 5, 2, hover || sel);
			String n = c.clip(name(id), tw - 6);
			c.text(n, ox + (tw - c.textWidth(n)) / 2, oy + th - 12, sel ? HalloweenPage.SILVER : 0xFFC9C2E2, false);
			if (!busy) {
				hits.add(ox, oy, tw, th, new Runnable() {
					@Override
					public void run() {
						if (click != null) click.run();
						selected = id;
						confirming = true;
					}
				});
			}
		}
		int fy = ty + rows * (th + gap) - gap + 4;
		if (confirming && selected != null) {
			final String pick = selected;
			c.text(c.clip(I18n.tr("halloween.wings.confirm", name(pick)), w - 16), x + 8, fy, 0xFFC9424A, false);
			String yes = I18n.tr(busy ? "halloween.wings.choosing" : "halloween.wings.choose");
			String no = I18n.tr("halloween.wings.cancel");
			int bw = c.textWidth(yes) + 20;
			int nw = c.textWidth(no) + 20;
			int by = fy + 12;
			boolean yh = mx >= x + 8 && mx < x + 8 + bw && my >= by && my < by + 17;
			boolean nh = mx >= x + 14 + bw && mx < x + 14 + bw + nw && my >= by && my < by + 17;
			Paint.button(c, x + 8, by, bw, 17, yes, true, yh && !busy);
			Paint.button(c, x + 14 + bw, by, nw, 17, no, false, nh && !busy);
			if (!busy) {
				hits.add(x + 8, by, bw, 17, new Runnable() {
					@Override
					public void run() {
						if (click != null) click.run();
						HalloweenService.get().chooseWings(pick);
						confirming = false;
					}
				});
				hits.add(x + 14 + bw, by, nw, 17, new Runnable() {
					@Override
					public void run() {
						if (click != null) click.run();
						confirming = false;
						selected = null;
					}
				});
			}
		} else if (err != null) {
			c.text(c.clip(I18n.tr(err), w - 16), x + 8, fy, 0xFFC9424A, false);
		}
		return y + h;
	}

	/** Anzeigename ({@code halloween.wings.<id>}) oder die ID. */
	static String name(String id) {
		return I18n.trOr("halloween.wings." + id, id);
	}

	/** Pixel-Flügelpaar (15×10 je Seite gespiegelt) in den Farben der Flügel; {@code px} GUI-Pixel je Punkt. */
	static void preview(Canvas c, String id, int x, int y, int px, boolean lit) {
		int[] col = colours(id);
		float a = lit ? 1f : 0.8f;
		String[] rows = WING;
		int half = rows[0].length();
		for (int r = 0; r < rows.length; r++) {
			for (int i = 0; i < half; i++) {
				char ch = rows[r].charAt(i);
				if (ch == '.') continue;
				int argb = ColorMath.fade(ch == 'e' ? col[1] : ch == 'v' ? col[2] : col[0], a);
				// links gespiegelt, rechts normal, 1 Punkt Abstand für den Rücken
				int lx = x + (half - 1 - i) * px;
				int rx = x + (half + 1 + i) * px;
				c.fill(lx, y + r * px, lx + px, y + (r + 1) * px, argb);
				c.fill(rx, y + r * px, rx + px, y + (r + 1) * px, argb);
			}
		}
	}

	/** Halber Flügel von der Mitte nach außen: w = Fläche, e = Rand/Glut, v = Ader/Schimmer. */
	private static final String[] WING = {
			"..eee", ".wwwee", "wwvwwwe", "wwwvwwe", "wvwwvwe", "wwwwe.e", "wwe.e..", "we....."};

	static {
		int max = 0;
		for (String r : WING) max = Math.max(max, r.length());
		for (int i = 0; i < WING.length; i++) {
			StringBuilder sb = new StringBuilder(WING[i]);
			while (sb.length() < max) sb.append('.');
			WING[i] = sb.toString();
		}
	}

	/** Fläche, Rand, Ader je Flügel (Vertrag: Fledermaus lila/silber, Dämon blutrot/Glut, Rabe schwarz/violett, Geist silberblau). */
	static int[] colours(String id) {
		if ("demon_wings".equals(id)) return new int[]{0xFF2A070C, 0xFFE08A3C, 0xFF7A0F18};
		if ("raven_wings".equals(id)) return new int[]{0xFF0E0B16, 0xFF3B3358, 0xFF8E6CC9};
		if ("ghost_wings".equals(id)) return new int[]{0x909FD6E6, 0xC0CFD5E3, 0xB0F3F5FB};
		return new int[]{0xFF3A1F5C, 0xFF1A1030, 0xFFCFD5E3};
	}
}

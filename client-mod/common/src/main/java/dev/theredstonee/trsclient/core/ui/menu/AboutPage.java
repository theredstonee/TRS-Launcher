package dev.theredstonee.trsclient.core.ui.menu;

import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.legal.AuthorNotice;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Hits;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.util.Links;

import java.util.List;

/**
 * „Info“ im TRS-Menü: Urheberhinweis (immer sichtbar, ohne Schalter), Lizenz und Drittanbieter.
 * Additional terms under GPL-3.0 section 7(b) apply: see the file NOTICE.
 */
public final class AboutPage {
	static final String LICENSE_URL = "https://github.com/theredstonee/TRS-Launcher/blob/main/LICENSE";
	static final String NOTICE_URL = "https://github.com/theredstonee/TRS-Launcher/blob/main/NOTICE";

	/** Fläche der Warnung und ihre Schrift. */
	static final int WARNING_FILL = 0xFFB3261E;
	static final int WARNING_TEXT = 0xFFFFFFFF;

	private static final int LINE = 10;
	private static final int BUTTON_H = 16;

	private final Runnable click;
	private int scroll;
	private int maxScroll;
	/** Der Browser ließ sich nicht öffnen: Adresse zum Abtippen. */
	private String openFailed;

	AboutPage(Runnable click) {
		this.click = click;
	}

	void opened() {
		scroll = 0;
		openFailed = null;
	}

	boolean mouseScrolled(double amount) {
		if (maxScroll <= 0) return false;
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(amount) * 24));
		return true;
	}

	void draw(Canvas c, Hits hits, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		c.scissor(x, y, x + w, y + h);
		hits.clip(x, y, w, h);
		int cy = y - scroll + 2;

		c.text(I18n.tr("about.title"), x + 2, cy, t.text, true);
		cy += 14;

		if (!AuthorNotice.intact()) cy = box(c, AuthorNotice.warning(), x, cy, w, WARNING_FILL, WARNING_TEXT) + 6;
		cy = box(c, AuthorNotice.text(), x, cy, w, t.surfaceHigh, t.text) + 8;

		cy = Paint.paragraph(c, I18n.tr("about.license"), x + 2, cy, w - 6, LINE, t.textDim) + 4;
		cy = Paint.paragraph(c, I18n.tr("about.thirdParty"), x + 2, cy, w - 6, LINE, t.textDim) + 6;

		int gap = 6;
		int buttonW = Math.min(130, (w - gap) / 2);
		button(c, hits, x, cy, buttonW, I18n.tr("about.openLicense"), LICENSE_URL, mx, my);
		button(c, hits, x + buttonW + gap, cy, buttonW, I18n.tr("about.openNotice"), NOTICE_URL, mx, my);
		cy += BUTTON_H + 6;
		if (openFailed != null) cy = Paint.paragraph(c, I18n.tr("bugreport.done.openFailed", openFailed), x + 2, cy, w - 6, LINE, t.text) + 4;

		maxScroll = Math.max(0, cy + scroll - (y + h));
		scroll = Math.min(scroll, maxScroll);
		hits.noClip();
		c.noScissor();
	}

	/** Umbrochener Text auf einer Fläche; liefert das y darunter. */
	private static int box(Canvas c, String text, int x, int y, int w, int fill, int color) {
		List<String> lines = Paint.wrap(c, text, w - 14);
		int boxH = lines.size() * LINE + 10;
		Redstone.block(c, x, y, w, boxH, fill);
		int ty = y + 5;
		for (String line : lines) {
			c.text(line, x + 7, ty, color, false);
			ty += LINE;
		}
		return y + boxH;
	}

	private void button(Canvas c, Hits hits, int x, int y, int w, String label, final String url, int mx, int my) {
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + BUTTON_H;
		Paint.button(c, x, y, w, BUTTON_H, label, false, hover);
		hits.add(x, y, w, BUTTON_H, new Runnable() {
			@Override
			public void run() {
				click.run();
				openFailed = Links.open(url) ? null : url;
			}
		});
	}
}

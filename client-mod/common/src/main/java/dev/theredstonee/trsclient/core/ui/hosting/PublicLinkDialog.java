package dev.theredstonee.trsclient.core.ui.hosting;

import dev.theredstonee.trsclient.core.hosting.PublicLink;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Icons;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.UiKey;
import dev.theredstonee.trsclient.core.ui.social.Dialog;
import dev.theredstonee.trsclient.core.ui.social.Kit;

/**
 * Warn-Dialog vor JEDER Aktivierung des öffentlichen Links: Warnung, Häkchen „Ich habe verstanden“, erst dann ist
 * „Aktivieren“ bedienbar. Jeder Dialog hat seinen eigenen {@link PublicLink.Gate} – nichts wird gemerkt. Enter
 * aktiviert nicht (nur ein bewusster Klick).
 */
public final class PublicLinkDialog extends Dialog {
	private final PublicLink link;
	private final PublicLink.Gate gate = new PublicLink.Gate();

	public PublicLinkDialog(PublicLink link) {
		this.link = link;
	}

	/** Für Tests/Autotest. */
	public PublicLink.Gate gate() {
		return gate;
	}

	@Override
	protected int[] size(int screenW, int screenH) {
		return new int[] { Math.min(290, screenW - 20), Math.min(186, screenH - 20) };
	}

	@Override
	protected String title() {
		return I18n.tr("hosting.link.title");
	}

	@Override
	protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		// Warnung mit Symbol, gut sichtbar.
		int boxH = 46;
		Redstone.stone(c, x, y, w, boxH, ColorMath.withAlpha(t.dustOn, 40), t.dustOn);
		Icons.draw(c, "lock", x + 6, y + 6, 2, t.dustOn);
		Paint.paragraph(c, I18n.tr("hosting.link.warning"), x + 26, y + 6, w - 32, 10, t.text);
		int ty = y + boxH + 5;
		ty += Paint.paragraph(c, I18n.tr("hosting.link.privacy"), x, ty, w, 10, t.textDim) + 4;
		kit.check(c, x, ty, w, I18n.tr("hosting.link.understood"), gate.understood(), true, mx, my, new Runnable() {
			@Override
			public void run() {
				gate.toggle();
			}
		});
		int bw = Math.min(100, (w - 6) / 2);
		int by = y + h - 18;
		kit.button(c, x + w - bw, by, bw, 18, I18n.tr("hosting.link.activate"), true, gate.canActivate(), mx, my,
				new Runnable() {
					@Override
					public void run() {
						if (link.activate(gate)) close();
					}
				});
		kit.button(c, x + w - bw * 2 - 6, by, bw, 18, I18n.tr("social.cancel"), false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				close();
			}
		});
	}

	@Override
	public boolean keyPressed(UiKey key, String paste) {
		if (key == UiKey.ESCAPE) close();
		return true;
	}
}

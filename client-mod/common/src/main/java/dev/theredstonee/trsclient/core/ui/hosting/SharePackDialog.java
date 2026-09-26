package dev.theredstonee.trsclient.core.ui.hosting;

import dev.theredstonee.trsclient.core.hosting.share.ShareModel;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.social.Dialog;
import dev.theredstonee.trsclient.core.ui.social.Kit;

import java.util.List;

/**
 * Host: Resource Pack wählen (ZIP im {@code resourcepacks}-Ordner, höchstens 250 MB). Es geht direkt an die Gäste,
 * nie über TRS; Gäste bekommen die normale Minecraft-Frage.
 */
public final class SharePackDialog extends Dialog {
	static final int ROW_H = 16;
	private final ShareModel model;
	private int scroll;
	private int[] list = new int[4];

	public SharePackDialog(ShareModel model) {
		this.model = model;
	}

	@Override
	protected int[] size(int screenW, int screenH) {
		return new int[] { Math.min(320, screenW - 20), Math.min(230, screenH - 16) };
	}

	@Override
	protected String title() {
		return I18n.tr("hosting.share.packTitle");
	}

	@Override
	protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		model.poll();
		int ty = Paint.paragraph(c, I18n.tr("hosting.share.packText"), x, y, w, 10, t.textDim) + 4;
		int listH = y + h - 24 - ty;
		list = new int[] { x, ty, w, listH };
		Redstone.well(c, x, ty, w, listH, t.border);
		List<ShareModel.PackFile> packs = model.packs();
		if (model.state() != ShareModel.State.READY) {
			Paint.textCentered(c, I18n.tr("hosting.share.scanning"), x + w / 2, ty + listH / 2 - 4, t.textDim, false);
		} else if (packs.isEmpty()) {
			Paint.textCentered(c, I18n.tr("hosting.share.noPacks"), x + w / 2, ty + listH / 2 - 4, t.textDim, false);
		}
		int content = packs.size() * ROW_H;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, content - listH + 4)));
		c.scissor(x + 1, ty + 1, x + w - 1, ty + listH - 1);
		boolean vis = my >= ty && my < ty + listH;
		int ry = ty + 2 - scroll;
		for (final ShareModel.PackFile p : packs) {
			if (ry + ROW_H > ty && ry < ty + listH) {
				String label = p.name() + " · " + (p.tooLarge() ? I18n.tr("hosting.share.packTooLarge") : ShareModsDialog.size(p.size));
				if (p.tooLarge()) {
					Paint.textClipped(c, label, x + 20, ry + 4, w - 26, t.textDim, false);
				} else {
					kit.radio(c, x + 2, ry + 1, w - 8, label, p.file.equals(model.packFile()), vis ? mx : -1, vis ? my : -1,
							new Runnable() {
								@Override
								public void run() {
									model.choosePack(p.file);
								}
							});
				}
			}
			ry += ROW_H;
		}
		c.noScissor();
		Kit.scrollbar(c, x + w - 3, ty + 1, listH - 2, scroll, Math.max(0, content - listH + 4), content);
		kit.button(c, x + w - 100, y + h - 18, 100, 18, I18n.tr("hosting.share.done"), true, true, mx, my, new Runnable() {
			@Override
			public void run() {
				close();
			}
		});
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (Kit.inside(mx, my, list[0], list[1], list[2], list[3])) scroll = Math.max(0, scroll - (int) Math.round(amount * 20));
		return true;
	}
}

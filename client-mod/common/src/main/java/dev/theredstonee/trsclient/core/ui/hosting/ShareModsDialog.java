package dev.theredstonee.trsclient.core.ui.hosting;

import dev.theredstonee.trsclient.core.hosting.share.ShareModel;
import dev.theredstonee.trsclient.core.hosting.share.SharedContent;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.social.Dialog;
import dev.theredstonee.trsclient.core.ui.social.Kit;

import java.util.List;
import java.util.Locale;

/**
 * Host: welche Mods mitgehen. Je Mod ein Häkchen und „Pflicht“/„optional“, dazu Art (Inhalt, nur Client, Grundlage,
 * Abhängigkeit) und Quelle (Modrinth, CurseForge, direkt vom Host, selbst besorgen). Oben „Mods direkt vom Host
 * übertragen“ (ab Werk aus). Änderungen werden je Welt gemerkt; angekündigt wird erst mit „Hosten“ bzw. „Übernehmen“.
 */
public final class ShareModsDialog extends Dialog {
	static final int ROW_H = 22;
	private final ShareModel model;
	private int scroll;
	private int[] list = new int[4];

	public ShareModsDialog(ShareModel model) {
		this.model = model;
	}

	@Override
	protected int[] size(int screenW, int screenH) {
		return new int[] { Math.min(440, screenW - 20), Math.min(270, screenH - 16) };
	}

	@Override
	protected String title() {
		return I18n.tr("hosting.share.modsTitle");
	}

	@Override
	protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		model.poll();
		kit.check(c, x, y, w, I18n.tr("hosting.share.direct"), model.direct(), true, mx, my, new Runnable() {
			@Override
			public void run() {
				model.setDirect(!model.direct());
			}
		});
		int ty = y + 15;
		Paint.textClipped(c, I18n.tr(model.direct() ? "hosting.share.directOn" : "hosting.share.directOff"), x + 16, ty, w - 16,
				model.direct() ? t.dustOn : t.textDim, false);
		ty += 12;
		String status;
		if (model.state() == ShareModel.State.SCANNING || model.state() == ShareModel.State.IDLE) {
			status = I18n.tr("hosting.share.scanning");
		} else if (model.lookupProblem() != null) {
			status = I18n.tr(model.lookupProblem());
		} else {
			status = I18n.tr(model.viaLauncher() ? "hosting.share.viaLauncher" : "hosting.share.viaModrinth", model.rows().size());
		}
		Paint.textClipped(c, status, x, ty, w, t.textDim, false);
		ty += 12;
		int listH = y + h - 24 - ty;
		list = new int[] { x, ty, w, listH };
		Redstone.well(c, x, ty, w, listH, t.border);
		List<ShareModel.Row> rows = model.rows();
		int content = rows.size() * ROW_H;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, content - listH + 4)));
		if (model.state() == ShareModel.State.READY && rows.isEmpty()) {
			Paint.textCentered(c, I18n.tr("hosting.share.noMods"), x + w / 2, ty + listH / 2 - 4, t.textDim, false);
		}
		c.scissor(x + 1, ty + 1, x + w - 1, ty + listH - 1);
		boolean vis = my >= ty && my < ty + listH;
		int vx = vis ? mx : -1;
		int vy = vis ? my : -1;
		int ry = ty + 2 - scroll;
		for (final ShareModel.Row r : rows) {
			if (ry + ROW_H > ty && ry < ty + listH) row(c, kit, r, x + 2, ry, w - 8, vx, vy);
			ry += ROW_H;
		}
		c.noScissor();
		Kit.scrollbar(c, x + w - 3, ty + 1, listH - 2, scroll, Math.max(0, content - listH + 4), content);
		int by = y + h - 18;
		Paint.textClipped(c, I18n.tr("hosting.share.summary", model.selectedCount(), model.requiredCount()), x, by + 5, w - 110,
				t.text, false);
		kit.button(c, x + w - 100, by, 100, 18, I18n.tr("hosting.share.done"), true, true, mx, my, new Runnable() {
			@Override
			public void run() {
				close();
			}
		});
	}

	private void row(Canvas c, Kit kit, final ShareModel.Row r, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		int reqW = 62;
		int srcW = Math.min(130, Math.max(80, w / 4));
		int nameW = w - reqW - srcW - 22;
		String name = r.mod.name + (r.mod.version.isEmpty() ? "" : " " + r.mod.version);
		kit.check(c, x, y + 4, nameW + 16, name, r.on, !r.locked(), mx, my, new Runnable() {
			@Override
			public void run() {
				model.toggle(r);
			}
		});
		String kind = I18n.tr(r.dependency ? "hosting.share.kind.dependency" : "hosting.share.kind." + r.kind.name().toLowerCase(Locale.ROOT))
				+ " · " + size(r.mod.size);
		Paint.textClipped(c, kind, x + 16, y + 14, nameW, t.textDim, false);
		SharedContent.Source src = r.source(model.direct());
		int sx = x + nameW + 18;
		Paint.textClipped(c, sourceLabel(src), sx, y + 7, srcW, src == SharedContent.Source.HOST ? t.dustOn
				: src == SharedContent.Source.MANUAL ? t.textDim : t.lampOn, false);
		if (!r.on) return;
		int rx = x + w - reqW;
		boolean canChange = !r.locked();
		kit.button(c, rx, y + 2, reqW, 16, I18n.tr(r.required ? "hosting.mods.required" : "hosting.mods.optional"), r.required,
				canChange, mx, my, new Runnable() {
					@Override
					public void run() {
						model.setRequired(r, !r.required);
					}
				});
	}

	static String sourceLabel(SharedContent.Source s) {
		return I18n.tr("hosting.mods.source." + s.id);
	}

	/** Größe kurz: „850 KB“ bzw. „12,3 MB“ (Dezimaltrenner der Sprache). */
	static String size(long bytes) {
		if (bytes < 1024L * 1024) return I18n.tr("hosting.size.kb", Math.max(1, (bytes + 1023) / 1024));
		return I18n.tr("hosting.size.mb", String.format(I18n.locale(), "%.1f", bytes / (1024.0 * 1024.0)));
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (Kit.inside(mx, my, list[0], list[1], list[2], list[3])) scroll = Math.max(0, scroll - (int) Math.round(amount * 20));
		return true;
	}
}

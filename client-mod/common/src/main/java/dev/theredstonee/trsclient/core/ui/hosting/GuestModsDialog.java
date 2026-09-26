package dev.theredstonee.trsclient.core.ui.hosting;

import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.Rooms;
import dev.theredstonee.trsclient.core.hosting.share.GuestCheck;
import dev.theredstonee.trsclient.core.hosting.share.SharedContent;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.social.Dialog;
import dev.theredstonee.trsclient.core.ui.social.Kit;

import java.util.ArrayList;
import java.util.List;

/**
 * Gast im Spiel: Der Welt fehlen hier Mods. Zeigt jede fehlende Mod (Pflicht/optional, Quelle, Größe) und bietet
 * „Im Launcher öffnen“ (dort: neue Instanz, vorhandene als Kopie ergänzen oder ohne Mods), „Ohne diese Mods beitreten“
 * (nur ohne fehlende Pflicht-Mods – sonst mit Erklärung, warum nicht) und Abbrechen.
 */
public final class GuestModsDialog extends Dialog {
	static final int ROW_H = 20;
	private final Hosting hosting;
	private final GuestCheck check;
	private final Rooms.Room room;
	private int scroll;
	private int[] list = new int[4];
	private boolean handled;

	public GuestModsDialog(Hosting hosting, GuestCheck check, Rooms.Room room) {
		this.hosting = hosting;
		this.check = check;
		this.room = room;
	}

	public GuestCheck check() {
		return check;
	}

	@Override
	protected int[] size(int screenW, int screenH) {
		return new int[] { Math.min(420, screenW - 20), Math.min(270, screenH - 16) };
	}

	@Override
	protected String title() {
		return I18n.tr("hosting.mods.guestTitle");
	}

	@Override
	protected void body(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		String host = room == null ? "?" : room.hostName;
		String world = room == null ? "?" : room.name;
		int ty = Paint.paragraph(c, I18n.tr("hosting.mods.guestText", host, world, check.missingRequired.size(),
				check.missingOptional.size()), x, y, w, 10, t.text) + 3;
		List<SharedContent.Mod> missing = new ArrayList<SharedContent.Mod>(check.missingRequired);
		missing.addAll(check.missingOptional);
		int foot = check.canJoinWithout() ? 36 : 58;
		int listH = Math.max(30, y + h - foot - ty);
		list = new int[] { x, ty, w, listH };
		Redstone.well(c, x, ty, w, listH, t.border);
		int content = missing.size() * ROW_H;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, content - listH + 4)));
		c.scissor(x + 1, ty + 1, x + w - 1, ty + listH - 1);
		int ry = ty + 2 - scroll;
		for (SharedContent.Mod m : missing) {
			if (ry + ROW_H > ty && ry < ty + listH) {
				String req = I18n.tr(m.required ? "hosting.mods.required" : "hosting.mods.optional");
				int reqW = c.textWidth(req) + 8;
				Redstone.stone(c, x + 4, ry + 2, reqW, 14, m.required ? 0xFF6A1414 : t.surface, m.required ? t.dustOn : t.border);
				c.text(req, x + 8, ry + 5, 0xFFFFFFFF, false);
				int nx = x + reqW + 8;
				int srcW = Math.min(150, w / 3);
				Paint.textClipped(c, m.name + (m.version.isEmpty() ? "" : " " + m.version), nx, ry + 5, w - (nx - x) - srcW - 50, t.text,
						false);
				Paint.textClipped(c, ShareModsDialog.sourceLabel(m.source), x + w - srcW - 50, ry + 5, srcW,
						m.source == SharedContent.Source.HOST ? t.dustOn : m.source == SharedContent.Source.MANUAL ? t.textDim : t.lampOn,
						false);
				Paint.textRight(c, ShareModsDialog.size(m.size), x + w - 8, ry + 5, t.textDim, false);
			}
			ry += ROW_H;
		}
		c.noScissor();
		Kit.scrollbar(c, x + w - 3, ty + 1, listH - 2, scroll, Math.max(0, content - listH + 4), content);
		int fy = ty + listH + 4;
		if (!check.canJoinWithout()) {
			fy = Paint.paragraph(c, I18n.tr("hosting.mods.requiredMissing"), x, fy, w, 10, t.dustOn) + 2;
		}
		boolean launcher = Hosting.launcherCanOpen();
		if (!launcher) Paint.textClipped(c, I18n.tr("hosting.mods.noLauncher"), x, fy, w, t.textDim, false);
		int by = y + h - 18;
		int bw = Math.min(130, (w - 12) / 3);
		kit.button(c, x + w - bw, by, bw, 18, I18n.tr("hosting.mods.openLauncher"), true, launcher, mx, my, new Runnable() {
			@Override
			public void run() {
				handled = true;
				hosting.openInLauncher();
				close();
			}
		});
		kit.button(c, x + w - bw * 2 - 6, by, bw, 18, I18n.tr("hosting.mods.joinWithout"), false, check.canJoinWithout(), mx, my,
				new Runnable() {
					@Override
					public void run() {
						handled = true;
						hosting.joinWithoutMods();
						close();
					}
				});
		kit.button(c, x, by, Math.min(90, bw), 18, I18n.tr("social.cancel"), false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				close();
			}
		});
	}

	@Override
	public void close() {
		if (!handled && hosting.guestState() == Hosting.GuestState.NEEDS_MODS) {
			handled = true;
			hosting.cancelJoin();
		}
		super.close();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (Kit.inside(mx, my, list[0], list[1], list[2], list[3])) scroll = Math.max(0, scroll - (int) Math.round(amount * 20));
		return true;
	}

	/** Selbsttest. */
	public void testOpenInLauncher() {
		handled = true;
		hosting.openInLauncher();
		close();
	}
}

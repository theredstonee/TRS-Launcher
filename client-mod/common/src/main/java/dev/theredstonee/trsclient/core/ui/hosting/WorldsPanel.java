package dev.theredstonee.trsclient.core.ui.hosting;

import dev.theredstonee.trsclient.core.account.FaceCache;
import dev.theredstonee.trsclient.core.hosting.Hosting;
import dev.theredstonee.trsclient.core.hosting.Rooms;
import dev.theredstonee.trsclient.core.i18n.I18n;
import dev.theredstonee.trsclient.core.ui.Canvas;
import dev.theredstonee.trsclient.core.ui.ColorMath;
import dev.theredstonee.trsclient.core.ui.Faces;
import dev.theredstonee.trsclient.core.ui.Paint;
import dev.theredstonee.trsclient.core.ui.Redstone;
import dev.theredstonee.trsclient.core.ui.Theme;
import dev.theredstonee.trsclient.core.ui.social.Kit;

import java.util.List;

/**
 * Liste „Welten von Freunden“: offene Welten der Freunde, Einladungen und eigene Anfragen – mit „Beitreten“
 * (eingeladen/angenommen) bzw. „Anfragen“. Oben der Stand des laufenden Beitritts mit „Abbrechen“. Genutzt im
 * Sozial-Bildschirm (Reiter Welten) und in „Mit Code beitreten“.
 */
public final class WorldsPanel {
	static final int ROW_H = 30;

	private final FaceCache faces;
	private int scroll;
	private int[] area = new int[4];
	private int contentH;

	public WorldsPanel(FaceCache faces) {
		this.faces = faces;
	}

	public void draw(Canvas c, Kit kit, int x, int y, int w, int h, int mx, int my) {
		Theme t = Theme.get();
		final Hosting hosting = Hosting.current();
		area = new int[] { x, y, w, h };
		if (hosting == null || !hosting.signedIn()) {
			Redstone.well(c, x, y, w, h, t.border);
			Paint.textCentered(c, I18n.tr("hosting.worlds.offline"), x + w / 2, y + h / 2 - 4, t.textDim, false);
			return;
		}
		hosting.refreshFriendsRooms(false);
		int top = y;
		int status = drawStatus(c, kit, hosting, x, y, w, mx, my);
		top += status;
		Redstone.well(c, x, top, w, y + h - top, t.border);
		List<Rooms.Room> rooms = hosting.friendsRooms();
		int listY = top + 2;
		int listH = y + h - top - 4;
		contentH = rooms.size() * (ROW_H + 2);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - listH)));
		if (rooms.isEmpty()) {
			int ty = top + (y + h - top) / 2 - 8;
			for (String l : Paint.wrap(c, I18n.tr("hosting.worlds.empty"), w - 20)) {
				Paint.textCentered(c, l, x + w / 2, ty, t.textDim, false);
				ty += 11;
			}
			return;
		}
		c.scissor(x + 1, listY, x + w - 1, listY + listH);
		int ry = listY - scroll;
		for (final Rooms.Room r : rooms) {
			if (ry + ROW_H >= listY && ry <= listY + listH) row(c, kit, hosting, r, x + 3, ry, w - 8, mx, my, listY, listH);
			ry += ROW_H + 2;
		}
		c.noScissor();
		Kit.scrollbar(c, x + w - 3, listY, listH, scroll, Math.max(0, contentH - listH), contentH);
	}

	private int drawStatus(Canvas c, Kit kit, final Hosting hosting, int x, int y, int w, int mx, int my) {
		Theme t = Theme.get();
		Hosting.GuestState st = hosting.guestState();
		if (st == Hosting.GuestState.IDLE || st == Hosting.GuestState.CONNECTED) return 0;
		Rooms.Room r = hosting.guestRoom();
		String host = r == null ? "?" : r.hostName;
		String text = st == Hosting.GuestState.WAITING ? I18n.tr("hosting.join.waiting", host)
				: st == Hosting.GuestState.CONNECTING ? I18n.tr("hosting.join.connecting", host) : I18n.tr("hosting.join.joining");
		Redstone.stone(c, x, y, w, 22, ColorMath.withAlpha(t.accent, 40), t.accent);
		float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 180.0));
		Redstone.pip(c, x + 6, y + 7, 8, pulse);
		Paint.textClipped(c, text, x + 18, y + 7, w - 100, t.text, false);
		kit.button(c, x + w - 76, y + 2, 72, 18, I18n.tr("social.cancel"), false, true, mx, my, new Runnable() {
			@Override
			public void run() {
				hosting.cancelJoin();
			}
		});
		return 26;
	}

	private void row(Canvas c, Kit kit, final Hosting hosting, final Rooms.Room r, int x, int y, int w, int mx, int my,
			int clipY, int clipH) {
		Theme t = Theme.get();
		boolean hov = Kit.inside(mx, my, x, y, w, ROW_H) && my >= clipY && my < clipY + clipH;
		Redstone.stone(c, x, y, w, ROW_H, hov ? t.surfaceHover : t.surface, t.border);
		int[] face = faces.face(r.hostUuid, null);
		Faces.draw(c, face, r.hostUuid, r.hostName, x + 5, y + 7, 2, true);
		int tx = x + 26;
		int bw = 76;
		int right = x + w - bw - 8;
		Paint.textClipped(c, r.name, tx, y + 5, right - tx, t.text, false);
		String state = r.myState == null ? "" : " · " + I18n.tr("hosting.state." + r.myState);
		String info = I18n.tr("hosting.worlds.info", r.hostName, r.mcVersion, Hosting.loaderName(r.loader), r.players,
				r.maxPlayers) + state;
		Paint.textClipped(c, info, tx, y + 17, right - tx, t.textDim, false);
		String compat = hosting.compatibility(r);
		boolean versionOk = !"hosting.error.version".equals(compat);
		boolean busy = hosting.guestState() != Hosting.GuestState.IDLE && hosting.guestState() != Hosting.GuestState.CONNECTED;
		String label;
		boolean enabled;
		if (!versionOk) {
			label = I18n.tr("hosting.worlds.otherVersion");
			enabled = false;
		} else if ("requested".equals(r.myState)) {
			label = I18n.tr("hosting.worlds.requested");
			enabled = false;
		} else if ("invited".equals(r.myState) || "accepted".equals(r.myState)) {
			label = I18n.tr("hosting.worlds.join");
			enabled = !busy;
		} else {
			label = I18n.tr("hosting.worlds.request");
			enabled = !busy && r.open;
		}
		boolean inView = my >= clipY && my < clipY + clipH;
		kit.button(c, x + w - bw - 4, y + 6, bw, 18, label, "invited".equals(r.myState) || "accepted".equals(r.myState),
				enabled && inView, mx, my, new Runnable() {
					@Override
					public void run() {
						hosting.joinRoom(r.id);
					}
				});
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!Kit.inside(mx, my, area[0], area[1], area[2], area[3])) return false;
		scroll = Math.max(0, scroll - (int) Math.round(amount * 20));
		return true;
	}
}
